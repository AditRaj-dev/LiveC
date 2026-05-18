// Two-phase + chunked file transfer state machine. Phase 4 (§4.4).
//
// Lifecycle:
//   file_offer received  → registerOffer()  → PENDING
//   file_accept received → acceptFile()     → ACCEPTED  (relay injects uploadToken)
//   PATCH chunks         → appendChunk()    → UPLOADING (offset advances per chunk)
//   final chunk byte ==  → completeUpload() → AVAILABLE (relay sends file_ready)
//     expected size
//   file_done received   → markDone()       → DELETED
//   file_reject received → rejectOffer()    → cleanup
//
// Resume model: HEAD returns the current bytesReceived. Sender seeks to that
// offset and resumes with PATCH. Hash and write stream are kept open across
// PATCH calls so partial uploads survive client reconnects.

'use strict';
const crypto = require('crypto');
const { randomUUID } = require('crypto');
const fs = require('fs');
const path = require('path');
const { LIMITS } = require('./protocol');

const UPLOAD_DIR = process.env.UPLOAD_DIR || path.join(__dirname, '..', 'tmp-uploads');

const OFFER_TTL_MS    = LIMITS.OFFER_TTL_MS;
const ACCEPTED_TTL_MS = LIMITS.FILE_TTL_MS;
const CLEANUP_INTERVAL_MS = 60 * 1000;

const FILE_STATE = {
  PENDING: 'pending', ACCEPTED: 'accepted', UPLOADING: 'uploading',
  AVAILABLE: 'available', DONE: 'done', REJECTED: 'rejected',
};

// offers: Map<offerId, OfferEntry>
// OfferEntry { senderId, recipientId, roomToken, createdAt, accepted, files, uploadTokens }
// FileEntry  { name, size, sha256, mimeType, state, diskPath,
//              bytesReceived, hasher, writeStream }
const offers = new Map();

function registerOffer(offerId, senderId, recipientId, files, roomToken) {
  if (offers.has(offerId)) return;
  const fileMap = new Map();
  for (const f of (files || [])) {
    fileMap.set(f.fileId, {
      name: f.name || 'file',
      size: f.size || 0,
      sha256: f.sha256 || null,
      mimeType: f.mimeType || 'application/octet-stream',
      state: FILE_STATE.PENDING,
      diskPath: null,
      bytesReceived: 0,
      hasher: null,
      writeStream: null,
    });
  }
  offers.set(offerId, {
    senderId, recipientId, roomToken,
    createdAt: Date.now(),
    accepted: false,
    files: fileMap,
    uploadTokens: new Map(),
  });
  console.log(`[TransferManager] Offer registered: ${offerId} (${fileMap.size} file(s))`);
}

function acceptFile(offerId, fileId, recipientId) {
  const offer = offers.get(offerId);
  if (!offer) return null;
  if (offer.recipientId !== 'broadcast' && offer.recipientId !== recipientId) return null;
  const file = offer.files.get(fileId);
  if (!file || file.state !== FILE_STATE.PENDING) return null;

  const token = randomUUID();
  offer.uploadTokens.set(fileId, token);
  file.state = FILE_STATE.ACCEPTED;
  offer.accepted = true;
  return { uploadToken: token };
}

function validateUploadToken(offerId, fileId, token) {
  const offer = offers.get(offerId);
  if (!offer) return false;
  return offer.uploadTokens.get(fileId) === token;
}

function getOfferFile(offerId, fileId) {
  const offer = offers.get(offerId);
  if (!offer) return null;
  const file = offer.files.get(fileId);
  if (!file) return null;
  return {
    name: file.name, size: file.size, sha256: file.sha256, mimeType: file.mimeType,
    state: file.state, bytesReceived: file.bytesReceived,
    recipientId: offer.recipientId, senderId: offer.senderId, roomToken: offer.roomToken,
  };
}

/**
 * Return all in-flight offers awaiting `file_accept`/`file_reject` from
 * `recipientId` (including broadcast offers). Called on device reconnect to
 * re-deliver offers whose original send was lost when the WS was killed.
 */
function getPendingOffersForRecipient(recipientId) {
  const out = [];
  for (const [offerId, offer] of offers.entries()) {
    if (offer.accepted) continue; // sender side already moved on
    if (offer.recipientId !== recipientId && offer.recipientId !== 'broadcast') continue;
    const files = [];
    for (const [fileId, f] of offer.files.entries()) {
      if (f.state === FILE_STATE.PENDING) {
        files.push({
          fileId,
          name: f.name,
          size: f.size,
          sha256: f.sha256,
          mimeType: f.mimeType,
        });
      }
    }
    if (files.length === 0) continue;
    out.push({ offerId, senderId: offer.senderId, roomToken: offer.roomToken, files });
  }
  return out;
}

/** Get current upload offset for HEAD response. */
function getUploadOffset(offerId, fileId) {
  const offer = offers.get(offerId);
  if (!offer) return null;
  const file = offer.files.get(fileId);
  if (!file) return null;
  return { bytesReceived: file.bytesReceived, size: file.size };
}

/**
 * Lazily open the write stream + hasher for a file on its first PATCH.
 * Returns the FileEntry (with .writeStream and .hasher initialized) or null.
 */
function _ensureUploadOpen(offer, file, fileId) {
  if (file.writeStream && file.hasher) return file;
  fs.mkdirSync(UPLOAD_DIR, { recursive: true });
  const ext = path.extname(file.name) || '';
  const diskPath = path.join(UPLOAD_DIR, `${fileId}${ext}`);
  file.diskPath = diskPath;
  // Append mode so subsequent PATCH calls extend rather than truncate.
  file.writeStream = fs.createWriteStream(diskPath, { flags: 'a' });
  file.hasher = crypto.createHash('sha256');
  file.state = FILE_STATE.UPLOADING;
  return file;
}

/**
 * Append a PATCH chunk. Returns:
 *   { ok: false, status, reason }
 *   { ok: true, bytesReceived, complete: bool }
 * On final chunk, completes the stream and verifies SHA-256 if provided.
 */
async function appendChunk(offerId, fileId, expectedOffset, chunk) {
  const offer = offers.get(offerId);
  if (!offer) return { ok: false, status: 404, reason: 'Offer not found' };
  const file = offer.files.get(fileId);
  if (!file) return { ok: false, status: 404, reason: 'File not found' };

  if (file.state === FILE_STATE.AVAILABLE || file.state === FILE_STATE.DONE) {
    return { ok: false, status: 409, reason: 'Already uploaded' };
  }
  if (expectedOffset !== file.bytesReceived) {
    return {
      ok: false, status: 409,
      reason: `Offset mismatch: client=${expectedOffset} server=${file.bytesReceived}`,
    };
  }
  if (file.size && file.bytesReceived + chunk.length > file.size) {
    return { ok: false, status: 413, reason: 'Chunk exceeds declared size' };
  }

  _ensureUploadOpen(offer, file, fileId);
  file.hasher.update(chunk);

  // Write the chunk and await drain to keep memory bounded.
  await new Promise((resolve, reject) => {
    const ok = file.writeStream.write(chunk, (err) => err ? reject(err) : resolve());
    if (!ok) file.writeStream.once('drain', resolve);
  });
  file.bytesReceived += chunk.length;

  const complete = (file.size > 0 && file.bytesReceived >= file.size);
  if (complete) {
    await new Promise((resolve, reject) => {
      file.writeStream.end((err) => err ? reject(err) : resolve());
    });
    if (file.sha256) {
      const actual = file.hasher.digest('hex');
      if (actual !== file.sha256) {
        fs.unlink(file.diskPath, () => {});
        file.state = FILE_STATE.REJECTED;
        return { ok: false, status: 400, reason: 'SHA-256 mismatch' };
      }
    }
    file.state = FILE_STATE.AVAILABLE;
    file.writeStream = null;
    file.hasher = null;
  }

  return { ok: true, bytesReceived: file.bytesReceived, complete };
}

/** Called after successful completion. Returns routing info for file_ready. */
function completeUpload(offerId, fileId) {
  const offer = offers.get(offerId);
  if (!offer) return null;
  const file = offer.files.get(fileId);
  if (!file) return null;
  return {
    recipientId: offer.recipientId, senderId: offer.senderId, roomToken: offer.roomToken,
    name: file.name, size: file.size, mimeType: file.mimeType, diskPath: file.diskPath,
  };
}

function markDone(offerId, fileId) {
  const offer = offers.get(offerId);
  if (!offer) return;
  const file = offer.files.get(fileId);
  if (!file) return;
  _closeStream(file);
  _deleteFile(file);
  file.state = FILE_STATE.DONE;
  _pruneOffer(offerId, offer);
}

function rejectOffer(offerId) {
  const offer = offers.get(offerId);
  if (!offer) return;
  for (const file of offer.files.values()) {
    _closeStream(file);
    _deleteFile(file);
    file.state = FILE_STATE.REJECTED;
  }
  offers.delete(offerId);
  console.log(`[TransferManager] Offer rejected/cleaned: ${offerId}`);
}

function _closeStream(file) {
  if (file.writeStream) {
    try { file.writeStream.destroy(); } catch (_) {}
    file.writeStream = null;
    file.hasher = null;
  }
}

function _deleteFile(file) {
  if (file.diskPath) {
    fs.unlink(file.diskPath, (err) => {
      if (err && err.code !== 'ENOENT') {
        console.warn(`[TransferManager] unlink failed: ${file.diskPath}:`, err.message);
      }
    });
    file.diskPath = null;
  }
}

function _pruneOffer(offerId, offer) {
  const done = [...offer.files.values()].every(
    (f) => f.state === FILE_STATE.DONE || f.state === FILE_STATE.REJECTED
  );
  if (done) offers.delete(offerId);
}

// TTL cleanup
setInterval(() => {
  const now = Date.now();
  for (const [offerId, offer] of offers.entries()) {
    const ttl = offer.accepted ? ACCEPTED_TTL_MS : OFFER_TTL_MS;
    if (now - offer.createdAt > ttl) {
      console.log(`[TransferManager] TTL expired: ${offerId}`);
      rejectOffer(offerId);
    }
  }
}, CLEANUP_INTERVAL_MS).unref();

module.exports = {
  registerOffer, acceptFile, validateUploadToken, getOfferFile,
  getUploadOffset, appendChunk, completeUpload, markDone, rejectOffer,
  getPendingOffersForRecipient,
};
