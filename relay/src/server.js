const http = require('http');
const crypto = require('crypto');
const express = require('express');
const { WebSocketServer } = require('ws');
const cors = require('cors');
const { PATHS, MESSAGE_TYPES, LIMITS } = require('./protocol');

const roomManager = require('./room-manager');
const messageRouter = require('./message-router');
const offlineQueue = require('./offline-queue');
const fileStore = require('./file-store');
const transferManager = require('./transfer-manager');
const { signDownloadUrl } = require('./url-signer');

const PORT = process.env.PORT || 3000;

const app = express();
app.use(cors());
app.use(express.json());

// Health check route
app.get(PATHS.HEALTH, (req, res) => {
  res.json({ status: 'ok', time: new Date().toISOString() });
});

// File routes — legacy single-phase upload
app.post(PATHS.UPLOAD, fileStore.uploadMiddleware, fileStore.handleUpload);
app.get(`${PATHS.DOWNLOAD}/:fileId`, fileStore.handleDownload);
app.delete(`${PATHS.DOWNLOAD}/:fileId`, fileStore.handleDelete);

// ── Two-phase upload: TUS-subset (HEAD + PATCH) ─────────────────────────────
//
// HEAD  /upload/:offerId/:fileId            — returns current Upload-Offset
// PATCH /upload/:offerId/:fileId            — appends chunk at Upload-Offset
//   Headers: Authorization: Bearer <uploadToken>
//            Upload-Offset: <bytesReceived>
//            Content-Type: application/offset+octet-stream
//   Body:    raw chunk bytes (≤ CHUNK_SIZE)
//
// Server validates token + offset, appends to disk, returns new offset.
// On the chunk that pushes bytesReceived == declared size, server finalizes
// SHA-256, signs download URL, and emits file_ready to recipient.

function _authUploadToken(req, res) {
  const { offerId, fileId } = req.params;
  const authHeader = req.headers['authorization'] || '';
  if (!authHeader.startsWith('Bearer ')) {
    res.status(401).json({ error: 'Missing Bearer token' });
    return null;
  }
  const token = authHeader.slice(7);
  if (!transferManager.validateUploadToken(offerId, fileId, token)) {
    res.status(403).json({ error: 'Invalid upload token' });
    return null;
  }
  return { offerId, fileId };
}

app.head('/upload/:offerId/:fileId', (req, res) => {
  console.log(`[Server] HEAD /upload/${req.params.offerId.slice(0,8)}/${req.params.fileId.slice(0,8)}`);
  const auth = _authUploadToken(req, res);
  if (!auth) return;
  const info = transferManager.getUploadOffset(auth.offerId, auth.fileId);
  if (!info) return res.status(404).end();
  res.set('Upload-Offset', String(info.bytesReceived));
  res.set('Upload-Length', String(info.size));
  res.set('Cache-Control', 'no-store');
  res.status(200).end();
});

app.patch('/upload/:offerId/:fileId', async (req, res) => {
  console.log(`[Server] PATCH /upload/${req.params.offerId.slice(0,8)}/${req.params.fileId.slice(0,8)} ` +
    `Upload-Offset=${req.headers['upload-offset']} Content-Length=${req.headers['content-length']} ` +
    `auth=${(req.headers['authorization'] || '').slice(0, 20)}…`);

  const auth = _authUploadToken(req, res);
  if (!auth) { console.warn('[Server] PATCH auth failed'); return; }
  const { offerId, fileId } = auth;

  const offset = parseInt(req.headers['upload-offset'], 10);
  if (Number.isNaN(offset) || offset < 0) {
    console.warn(`[Server] PATCH bad offset: ${req.headers['upload-offset']}`);
    return res.status(400).json({ error: 'Missing or invalid Upload-Offset' });
  }

  // Collect the chunk body. Cap at CHUNK_SIZE + 1KB slack to reject oversized.
  const chunks = [];
  let total = 0;
  const cap = LIMITS.CHUNK_SIZE + 1024;
  try {
    for await (const buf of req) {
      total += buf.length;
      if (total > cap) {
        return res.status(413).json({ error: 'Chunk exceeds CHUNK_SIZE' });
      }
      chunks.push(buf);
    }
  } catch (err) {
    console.warn('[Server] PATCH body read error:', err.message);
    return res.status(400).json({ error: 'Body read failed' });
  }
  const body = Buffer.concat(chunks, total);

  console.log(`[Server] PATCH body=${total}B for ${fileId.slice(0,8)}`);

  const result = await transferManager.appendChunk(offerId, fileId, offset, body);
  if (!result.ok) {
    console.warn(`[Server] PATCH appendChunk failed: ${result.status} ${result.reason}`);
    if (result.status === 409) {
      // Help the client resync by surfacing the authoritative offset.
      const info = transferManager.getUploadOffset(offerId, fileId);
      if (info) res.set('Upload-Offset', String(info.bytesReceived));
    }
    return res.status(result.status).json({ error: result.reason });
  }

  res.set('Upload-Offset', String(result.bytesReceived));

  if (!result.complete) {
    return res.status(204).end();
  }

  // Final chunk: emit file_ready to recipient.
  const info = transferManager.completeUpload(offerId, fileId);
  if (!info) return res.status(500).json({ error: 'Transfer state error' });
  fileStore.registerFile(fileId, info.diskPath);

  const protocol = req.headers['x-forwarded-proto'] || req.protocol;
  const host = req.headers.host;
  const downloadUrl = signDownloadUrl(`${protocol}://${host}`, fileId);

  const fileReadyMsg = {
    type: MESSAGE_TYPES.FILE_READY,
    id: crypto.randomUUID(),
    from: info.senderId,
    to: info.recipientId,
    room: info.roomToken,
    payload: { offerId, fileId, name: info.name, size: info.size, mimeType: info.mimeType, downloadUrl },
  };
  if (info.recipientId === 'broadcast') {
    roomManager.broadcast(info.roomToken, info.senderId, fileReadyMsg);
  } else {
    const recipientWs = roomManager.getDevice(info.roomToken, info.recipientId);
    if (recipientWs && recipientWs.readyState === 1) {
      recipientWs.send(JSON.stringify(fileReadyMsg));
    }
  }

  console.log(`[Server] Two-phase upload complete: ${fileId} (${info.size} B) → ${info.recipientId}`);
  res.status(204).end();
});

// Multer and general error handler — must be defined after routes.
// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  console.error('[Server] Request error:', err.message || err);
  if (err.code === 'LIMIT_FILE_SIZE') {
    const maxGb = Math.round(LIMITS.MAX_FILE_BYTES / (1024 * 1024 * 1024));
    return res.status(413).json({ error: `File too large. Maximum is ${maxGb} GB.` });
  }
  res.status(500).json({ error: err.message || 'Internal server error' });
});

const server = http.createServer(app);

// WebSocket server — noServer mode so we control upgrade handling explicitly.
// This prevents Cloudflare (and other proxies) from getting a 404 when they
// forward a WS upgrade request that Express would otherwise handle first.
const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const pathname = req.url.split('?')[0];
  if (pathname === PATHS.WS) {
    wss.handleUpgrade(req, socket, head, (ws) => {
      wss.emit('connection', ws, req);
    });
  } else {
    socket.destroy();
  }
});

wss.on('connection', (ws, req) => {
  console.log(`[Server] New WebSocket connection from ${req.socket.remoteAddress}`);

  ws.on('message', (messageStr) => {
    let messageObj;
    try {
      messageObj = JSON.parse(messageStr);
    } catch (e) {
      console.warn('[Server] Received malformed JSON');
      return;
    }

    // Handle initial join message
    if (messageObj.type === MESSAGE_TYPES.DEVICE_JOIN) {
      const { roomToken, deviceId, deviceName, platform, fingerprint } = messageObj.payload;
      if (!roomToken || !deviceId) {
        console.warn('[Server] Invalid device_join message');
        ws.close(1008, 'Invalid join params');
        return;
      }

      roomManager.join(ws, roomToken, deviceId, deviceName, platform, fingerprint || '');

      // Flush any queued messages for this device
      offlineQueue.flush(deviceId, ws);

      // Re-deliver any in-flight file_offers whose original send was lost
      // (e.g. recipient WS got killed by an OEM background freezer between
      // the relay's socket.send() and the actual TCP transmit). Sender's
      // upload_file is still blocked on the oneshot waiting for accept/reject.
      const pending = transferManager.getPendingOffersForRecipient(deviceId);
      for (const p of pending) {
        try {
          ws.send(JSON.stringify({
            type:    MESSAGE_TYPES.FILE_OFFER,
            id:      crypto.randomUUID(),
            from:    p.senderId,
            to:      deviceId,
            room:    p.roomToken,
            payload: { offerId: p.offerId, files: p.files },
          }));
          console.log(`[Server] Replayed pending file_offer ${p.offerId.slice(0,8)} → ${deviceId.slice(0,8)}`);
        } catch (e) {
          console.warn('[Server] Failed to replay file_offer:', e.message);
        }
      }
      return;
    }

    // For all other messages, route them
    messageRouter.handleMessage(ws, messageStr);
  });

  ws.on('close', () => {
    roomManager.leave(ws);
  });

  ws.on('error', (err) => {
    console.error('[Server] WebSocket error:', err);
    roomManager.leave(ws);
  });
});

server.listen(PORT, () => {
  console.log(`[Server] Relay server listening on port ${PORT}`);
});
