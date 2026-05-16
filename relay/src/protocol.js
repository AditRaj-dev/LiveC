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
  PING: 'ping',
  PONG: 'pong',
  ACK: 'ack',
});

const BROADCAST = 'broadcast';

const LIMITS = Object.freeze({
  MAX_FILE_BYTES: 100 * 1024 * 1024,
  MAX_TEXT_BYTES: 1 * 1024 * 1024,
  FILE_TTL_MS: 90 * 1000,
  OFFLINE_QUEUE_TTL_MS: 60 * 60 * 1000,
  OFFLINE_QUEUE_MAX_PER_DEVICE: 50,
});

const UPLOAD_FIELD_NAME = 'file';

module.exports = { PATHS, MESSAGE_TYPES, BROADCAST, LIMITS, UPLOAD_FIELD_NAME };
