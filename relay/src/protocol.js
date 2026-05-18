// Mirror of D:\LiveC\PROTOCOL.md. Update both together.

const PATHS = Object.freeze({
  WS: '/ws',
  UPLOAD: '/upload',
  DOWNLOAD: '/download', // append /:fileId
  HEALTH: '/health',
});

const MESSAGE_TYPES = Object.freeze({
  DEVICE_JOIN: 'device_join',
  DEVICE_LEAVE: 'device_leave',
  CLIPBOARD_TEXT: 'clipboard_text',
  CLIPBOARD_IMAGE: 'clipboard_image',
  FILE_META: 'file_meta',
  FILE_EXPIRED: 'file_expired',
  FILE_OFFER: 'file_offer',
  FILE_ACCEPT: 'file_accept',
  FILE_REJECT: 'file_reject',
  FILE_READY: 'file_ready',
  FILE_DONE: 'file_done',
  FILES_CLEAR: 'files_clear',
  CLIPBOARD_CLEAR: 'clipboard_clear',
  PING: 'ping',
  PONG: 'pong',
  ACK: 'ack',
});

const BROADCAST = 'broadcast';

// Sized for Render free tier (512 MB RAM, ephemeral disk, sleeps after 15 min idle).
// Each in-flight PATCH buffers CHUNK_SIZE bytes in RAM, so chunk × concurrent
// uploads must stay well under the RAM ceiling. File TTLs are short because the
// container's filesystem evaporates on every cold start anyway.
const LIMITS = Object.freeze({
  MAX_FILE_BYTES: 100 * 1024 * 1024,               // 100 MB
  MAX_TEXT_BYTES: 1 * 1024 * 1024,
  FILE_TTL_MS: 60 * 60 * 1000,                     // 1 hour
  OFFER_TTL_MS: 30 * 60 * 1000,                    // 30 min unaccepted
  OFFLINE_QUEUE_TTL_MS: 60 * 60 * 1000,            // 1 hour
  OFFLINE_QUEUE_MAX_PER_DEVICE: 100,
  CHUNK_SIZE: 1 * 1024 * 1024,                     // 1 MB PATCH chunk
});

const UPLOAD_FIELD_NAME = 'file';

module.exports = { PATHS, MESSAGE_TYPES, BROADCAST, LIMITS, UPLOAD_FIELD_NAME };
