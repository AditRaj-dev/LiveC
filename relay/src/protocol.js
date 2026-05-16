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

const LIMITS = Object.freeze({
  MAX_FILE_BYTES: 10 * 1024 * 1024 * 1024,         // 10 GB (was 100 MB)
  MAX_TEXT_BYTES: 1 * 1024 * 1024,
  FILE_TTL_MS: 7 * 24 * 60 * 60 * 1000,            // 7 days (was 90 sec)
  OFFER_TTL_MS: 24 * 60 * 60 * 1000,               // unaccepted offers: 24h
  OFFLINE_QUEUE_TTL_MS: 7 * 24 * 60 * 60 * 1000,   // 7 days (was 1h)
  OFFLINE_QUEUE_MAX_PER_DEVICE: 200,               // was 50
  CHUNK_SIZE: 8 * 1024 * 1024,                     // 8 MB TUS-style PATCH chunk
});

const UPLOAD_FIELD_NAME = 'file';

module.exports = { PATHS, MESSAGE_TYPES, BROADCAST, LIMITS, UPLOAD_FIELD_NAME };
