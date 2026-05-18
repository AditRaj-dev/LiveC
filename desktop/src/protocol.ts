// Mirror of D:\LiveC\PROTOCOL.md. Update both together.

export const PATHS = {
  WS: "/ws",
  UPLOAD: "/upload",
  DOWNLOAD: "/download", // append /:fileId
  HEALTH: "/health",
} as const;

export const MESSAGE_TYPES = {
  DEVICE_JOIN: "device_join",
  DEVICE_LEAVE: "device_leave",
  CLIPBOARD_TEXT: "clipboard_text",
  CLIPBOARD_IMAGE: "clipboard_image",
  FILE_META: "file_meta",
  FILE_EXPIRED: "file_expired",
  FILE_OFFER: "file_offer",
  FILE_ACCEPT: "file_accept",
  FILE_REJECT: "file_reject",
  FILE_READY: "file_ready",
  FILE_DONE: "file_done",
  PING: "ping",
  PONG: "pong",
  ACK: "ack",
} as const;

export const BROADCAST = "broadcast";

// Sized for Render free tier (512 MB RAM, ephemeral disk, idle-sleeps).
export const LIMITS = {
  MAX_FILE_BYTES: 100 * 1024 * 1024,              // 100 MB
  MAX_TEXT_BYTES: 1 * 1024 * 1024,
  FILE_TTL_MS: 60 * 60 * 1000,                    // 1 hour
  OFFER_TTL_MS: 30 * 60 * 1000,                   // 30 min
  OFFLINE_QUEUE_TTL_MS: 60 * 60 * 1000,           // 1 hour
  OFFLINE_QUEUE_MAX_PER_DEVICE: 100,
  CHUNK_SIZE: 1 * 1024 * 1024,                    // 1 MB
} as const;

/** Convert the configured relay URL (ws://…/ws or wss://…/ws) to an HTTP base. */
export function relayToHttpBase(relayUrl: string): string {
  let u = relayUrl;
  if (u.startsWith("wss://")) u = u.replace("wss://", "https://");
  else if (u.startsWith("ws://")) u = u.replace("ws://", "http://");
  if (u.endsWith(PATHS.WS)) u = u.slice(0, -PATHS.WS.length);
  return u;
}

export function downloadUrl(httpBase: string, fileId: string): string {
  return `${httpBase}${PATHS.DOWNLOAD}/${fileId}`;
}
