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
  PING: "ping",
  PONG: "pong",
  ACK: "ack",
} as const;

export const BROADCAST = "broadcast";

export const LIMITS = {
  MAX_FILE_BYTES: 100 * 1024 * 1024,
  MAX_TEXT_BYTES: 1 * 1024 * 1024,
  FILE_TTL_MS: 90 * 1000,
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
