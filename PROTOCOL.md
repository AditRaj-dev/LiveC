# LiveC Protocol — Source of Truth

All three components (relay, desktop, android) MUST match this spec.
When you change anything here, update the four mirror files listed at the bottom.

## HTTP endpoints

Base URL: derived from `relayUrl` by converting `ws[s]://…/ws` → `http[s]://…`.

| Method | Path                  | Purpose                                     |
|--------|-----------------------|---------------------------------------------|
| GET    | `/health`             | Liveness check. Returns `{ status, time }`. |
| POST   | `/upload`             | Multipart upload. Field name MUST be `file`. Optional text fields: `roomToken`, `deviceId`. Returns `{ fileId, downloadUrl, size }`. Max 100 MB. |
| GET    | `/download/:fileId`   | Download a staged file. 90-second TTL. Returns binary or 404. |

## WebSocket

Path: `/ws`. Frame format: text JSON.

### Envelope (every message)

```json
{
  "id": "uuid-v4",
  "type": "<see types below>",
  "from": "<sender device_id>",
  "to":   "<recipient device_id | 'broadcast'>",
  "room": "<room_token>",
  "timestamp": 1715534400000,
  "payload": { ... }
}
```

Rules:
- `to: "broadcast"` → relay fans out to every device in the room **except the sender**.
- `to: "<device_id>"` → relay sends only to that device. Falls through to offline queue if offline.
- Relay never inspects `payload` — routes by `to` only.
- Senders never receive their own broadcasts. If you need local state on send, update it locally.

### Message types

| `type`            | Direction         | `payload` shape |
|-------------------|-------------------|-----------------|
| `device_join`     | client → relay    | `{ deviceId, deviceName, platform: "windows"\|"android", roomToken }` |
| `device_join`     | relay → clients   | Same shape — relay re-broadcasts. |
| `device_leave`    | client → relay    | `{}` (relay reads `from` field) |
| `device_leave`    | relay → clients   | `{ deviceId }` |
| `clipboard_text`  | both ways         | `{ text: string }` (skip if > 1 MB) |
| `clipboard_image` | both ways         | `{ fileId, downloadUrl }` — image was uploaded via `/upload` first |
| `file_meta`       | both ways         | `{ fileId, name, size, downloadUrl }` |
| `file_expired`    | relay → uploader  | `{ fileId }` — sent when 90s TTL hits before download |
| `ping`            | both ways         | `{}` (heartbeat, 25-30s interval) |
| `pong`            | both ways         | `{}` |
| `ack`             | both ways         | `{ ackId }` (reserved) |

### Pairing QR code

QR encodes a JSON string:
```json
{ "relayUrl": "wss://example.com/ws", "roomToken": "abc12345" }
```

mDNS service type: `_livec._tcp.local` (LAN discovery, V2).

## Mirror these files when this spec changes

1. `relay/src/protocol.js`
2. `desktop/src-tauri/src/protocol.rs`
3. `desktop/src/protocol.ts` *(new — frontend mirror)*
4. `android/app/src/main/kotlin/com/livec/app/data/Protocol.kt`
