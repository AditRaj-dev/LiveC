const http = require('http');
const express = require('express');
const { WebSocketServer } = require('ws');
const cors = require('cors');
const { PATHS, MESSAGE_TYPES } = require('./protocol');

const roomManager = require('./room-manager');
const messageRouter = require('./message-router');
const offlineQueue = require('./offline-queue');
const fileStore = require('./file-store');

const PORT = process.env.PORT || 3000;

const app = express();
app.use(cors());
app.use(express.json());

// Health check route
app.get(PATHS.HEALTH, (req, res) => {
  res.json({ status: 'ok', time: new Date().toISOString() });
});

// File routes
app.post(PATHS.UPLOAD, fileStore.uploadMiddleware, fileStore.handleUpload);
app.get(`${PATHS.DOWNLOAD}/:fileId`, fileStore.handleDownload);
app.delete(`${PATHS.DOWNLOAD}/:fileId`, fileStore.handleDelete);

// Multer and general error handler — must be defined after routes.
// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  console.error('[Server] Request error:', err.message || err);
  if (err.code === 'LIMIT_FILE_SIZE') {
    return res.status(413).json({ error: 'File too large. Maximum is 100 MB.' });
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
      const { roomToken, deviceId, deviceName, platform } = messageObj.payload;
      if (!roomToken || !deviceId) {
        console.warn('[Server] Invalid device_join message');
        ws.close(1008, 'Invalid join params');
        return;
      }

      roomManager.join(ws, roomToken, deviceId, deviceName, platform);
      
      // Flush any queued messages for this device
      offlineQueue.flush(deviceId, ws);
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
