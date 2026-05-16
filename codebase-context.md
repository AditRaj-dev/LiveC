# LiveC — Codebase Context for AI Sessions

**Purpose:** Drop this file into a fresh AI session as context so the assistant doesn't have to re-explore the codebase from scratch.

**Last refreshed:** 2026-05-15 — captures state after the cloudflared drop-shelf fix.

---

## 1. One-paragraph summary

LiveC is a universal clipboard + file sync app between a Windows PC (Tauri/Rust + React) and Android phones (Kotlin/Compose), with two transports: a Node.js WebSocket relay (deployable behind cloudflared/Render) and a direct LAN path (mDNS-discovered WebSocket on port 7777). Both transports carry the same JSON envelope and use ID-based dedup so the same message arriving on both paths is processed once. There are no accounts; pairing is a per-install random `room_token` shared via QR code. File transfer uses HTTP multipart upload to the relay with a 90-second TTL, then a `file_meta` WS message tells the receiver where to download.

---

## 2. Top-level directory layout

```
D:\LiveC\
├── relay/                 # Node.js relay (Express 5 + ws + multer)
│   ├── src/
│   │   ├── server.js          # HTTP + WS bootstrap; routes upload/download/health; WS upgrade handler
│   │   ├── file-store.js      # Multer disk storage, 90s TTL cleanup, in-memory fileTracker Map
│   │   ├── message-router.js  # Reads msg.to, routes via room-manager or offline-queue
│   │   ├── room-manager.js    # Map<roomToken, Map<deviceId, WebSocket>>; join/leave/broadcast
│   │   ├── offline-queue.js   # Per-device message queue, 1h TTL, max 50 items
│   │   └── protocol.js        # PATHS, MESSAGE_TYPES, LIMITS constants (MIRROR FILE)
│   ├── Dockerfile             # node:20-alpine, exposes 3000
│   └── tmp-uploads/           # Multer storage (ephemeral)
│
├── desktop/                # Tauri app (Rust backend + React frontend)
│   ├── src/                   # React/TypeScript frontend
│   │   ├── App.tsx               # 3-panel main window (devices | clipboard | transfers)
│   │   ├── OverlayApp.tsx        # Drop-shelf + device picker overlay
│   │   ├── ScreenshotToastApp.tsx# Screenshot-send toast
│   │   ├── hooks/useLiveC.ts     # useClipboard, useFileTransfers, useRoomState, useConfig
│   │   ├── protocol.ts           # MESSAGE_TYPES, LIMITS, URL helpers (MIRROR FILE)
│   │   └── types.ts              # ClipEntry, FileTransfer, Device, RoomState
│   └── src-tauri/             # Rust backend
│       ├── tauri.conf.json       # 3 windows: main, overlay, screenshot_toast
│       ├── Cargo.toml            # Trimmed deps to avoid LLVM OOM
│       └── src/
│           ├── lib.rs            # Tauri command handlers + setup() + run()
│           ├── protocol.rs       # Message struct, msg/limits/paths mods, MsgDedup (MIRROR FILE)
│           ├── config.rs         # AppConfig, init_config, normalize_relay_url
│           ├── connection.rs     # WS relay client + handle_message + ROOM_DEVICES registry
│           ├── lan.rs            # LAN WS server (port 7777) + mDNS advertisement
│           ├── clipboard/mod.rs  # Win32 clipboard monitor (CF_UNICODETEXT, CF_DIB)
│           ├── screenshot/mod.rs # Polls Pictures\Screenshots folder
│           ├── windows_overlay/  # Drop shelf positioning + global mouse hook for Shift+drag
│           └── tray/mod.rs       # System tray menu
│
├── android/                # Kotlin + Jetpack Compose
│   └── app/src/main/kotlin/com/livec/app/
│       ├── MainActivity.kt
│       ├── LiveCApplication.kt
│       ├── data/
│       │   ├── Protocol.kt       # Paths, MessageType, Limits (MIRROR FILE)
│       │   ├── Message.kt        # Envelope + factory methods, DeviceInfo, ClipItem, TransferItem
│       │   ├── AppState.kt       # In-memory MutableStateFlow store
│       │   └── ConfigStore.kt    # DataStore-backed persistent config
│       ├── network/
│       │   ├── RelayClient.kt    # OkHttp WS client w/ exponential backoff reconnect
│       │   ├── LanClient.kt      # LAN WS client
│       │   └── LanDiscovery.kt   # NsdManager mDNS browser
│       ├── service/
│       │   └── LiveCService.kt   # Foreground service — owns all network + clipboard
│       └── ui/
│           ├── AppViewModel.kt
│           ├── screens/{HomeScreen, PairingScreen, SettingsScreen}.kt
│           └── theme/{Color, Theme}.kt
│
├── PROTOCOL.md             # Authoritative protocol spec (the mirror source-of-truth)
├── handoff.md              # Maintainer's running notes on fixes and known issues
├── livec_design.md         # Original design doc
├── design-borrows.md       # Proposal: borrows from LocalSend & Blip
└── codebase-context.md     # This file
```

---

## 3. The four protocol mirror files

**Always update all four together.** This is the most common source of drift.

1. [`relay/src/protocol.js`](relay/src/protocol.js) — JS constants
2. [`desktop/src-tauri/src/protocol.rs`](desktop/src-tauri/src/protocol.rs) — Rust constants + `Message` struct
3. [`desktop/src/protocol.ts`](desktop/src/protocol.ts) — TS constants
4. [`android/app/src/main/kotlin/com/livec/app/data/Protocol.kt`](android/app/src/main/kotlin/com/livec/app/data/Protocol.kt) — Kotlin constants

The authoritative spec lives at [`PROTOCOL.md`](PROTOCOL.md) — always edit it first.

---

## 4. Message envelope

```json
{
  "id": "uuid-v4",
  "type": "clipboard_text | file_meta | device_join | ...",
  "from": "<sender device_id>",
  "to":   "<recipient device_id | 'broadcast'>",
  "room": "<room_token>",
  "timestamp": 1715534400000,
  "payload": { /* type-specific */ }
}
```

**Routing rules:**
- `to: "broadcast"` → relay fans out to every device in the room **except** the sender.
- `to: "<device_id>"` → targeted send; falls through to offline queue if recipient is offline.
- Relay **never inspects payload** — routes by `to` only.
- Senders never receive their own broadcasts; the Rust client (`connection.rs:177`) and Android (`LiveCService.kt:221`) both explicitly drop `msg.from == self.deviceId` as defense in depth.

**Note:** The Rust `Message` struct doesn't currently include a `timestamp` field, but Android does. Relay doesn't enforce it. Cross-platform messages work because all consumers `optLong("timestamp", ...)` with a fallback.

### Message types (current)

| Type | Direction | Payload |
|---|---|---|
| `device_join` | both | `{deviceId, deviceName, platform, roomToken}` |
| `device_leave` | both | `{deviceId}` |
| `clipboard_text` | both | `{text}` (skip if >1MB) |
| `clipboard_image` | both | LAN: `{data: base64, mimeType}` · Relay: `{fileId, downloadUrl}` |
| `clipboard_clear` | broadcast | `{}` |
| `file_meta` | both | `{fileId, name, size, downloadUrl}` |
| `file_expired` | relay → uploader | `{fileId}` (90s TTL hit) |
| `files_clear` | broadcast | `{}` |
| `ping` / `pong` | both | `{}` (25s heartbeat) |
| `ack` | reserved | unused |

---

## 5. Transport architecture

```
┌─────────────────┐        wss://relay/ws (cloudflared/Render)       ┌─────────────────┐
│  Windows app    │ ◄─────────────────────────────────────────────► │  Android app    │
│  (Tauri/Rust)   │                                                  │  (Kotlin)       │
│                 │        ws://LAN_IP:7777/ws (mDNS-discovered)     │                 │
│  connection.rs ─┼──────── (relay path)                             │ RelayClient.kt  │
│  lan.rs ────────┼──────── (LAN path, both server + client roles)   │ LanClient.kt    │
└─────────────────┘                                                  └─────────────────┘
```

### Dedup model (critical)

Each device maintains a single ring buffer of seen message IDs (`MsgDedup` in [`protocol.rs:107`](desktop/src-tauri/src/protocol.rs), `seenIds` in [`LiveCService.kt:64`](android/app/src/main/kotlin/com/livec/app/service/LiveCService.kt)). Both relay and LAN paths feed into the **same** dedup buffer. First arrival wins; second copy is dropped silently.

Without this, a `clipboard_text` sent over both relay and LAN would be applied twice on the receiver. With it, the faster path wins.

### Outbound fan-out

When the desktop or Android sends a message that should propagate, it goes out on **both** transports simultaneously:

```rust
// Desktop pattern (lib.rs)
lan::send_lan(&json);           // to all connected LAN peers (no-op if none)
connection::send_raw(json);     // to relay
```

```kotlin
// Android pattern (LiveCService.kt)
client.send(msg)                // to relay
if (lanClient.isConnected()) lanClient.send(msg)   // to LAN
```

Receivers dedup, so duplicates are harmless. **Recently fixed:** Android's `uploadAndBroadcast` was relay-only; now also sends `file_meta` via LAN.

### LAN discovery

- mDNS service type: `_livec._tcp.local`
- Port: 7777
- TXT records: `room_hash` (sha256(roomToken)[..4] hex), `device_id`, `platform`
- Windows advertises and listens. Android browses with `NsdManager`.
- Peers verify `room_hash` matches before connecting.

---

## 6. File transfer flow (the current, post-fix path)

```
Desktop drop shelf:
1. User Shift+drags from Explorer → global mouse hook (windows_overlay/mod.rs) shows overlay
2. User drops files → Tauri DragDrop::Drop event → "shelf:drop" emitted to OverlayApp.tsx
3. OverlayApp transitions to 'picking' state, shows device picker
4. User selects target → invoke('upload_file', { path, target })
5. lib.rs::upload_file:
   a. fs::read(&path)  ← entire file into RAM
   b. POST {http_base}/upload (multipart)
   c. Receive { fileId, downloadUrl, size }
   d. Construct file_meta envelope
   e. lan::send_lan(&json)
   f. connection::send_raw(json)  ← errors here NO LONGER abort (was a bug)
6. Relay routes file_meta to recipient(s)
7. Recipient sees notification, calls invoke('download_file', { url, filename })
8. Saves to ~/Downloads with conflict-suffix renaming

Android share-sheet upload (LiveCService.uploadAndBroadcast):
1. User shares a file → MainActivity calls LiveCService.startWithFile(uri)
2. ContentResolver opens stream, reads bytes  ← entire file into RAM
3. OkHttp multipart POST to /upload
4. Send file_meta via relay AND LAN (recent fix)
```

### Drop-shelf state machine ([`OverlayApp.tsx`](desktop/src/OverlayApp.tsx))

```
hidden ──shelf:drag_start──► bloomed ──shelf:drag_enter──► ready
                                ▲                            │
                                └──shelf:drag_leave──────────┘
                                                             │
                                                       shelf:drop
                                                             │
                                                             ▼
                                                          picking
                                                             │
                                              user picks device│
                                                             │
                                                             ▼
                                                         accepted ──1.2s──► hidden
```

The global mouse hook in [`windows_overlay/mod.rs:86`](desktop/src-tauri/src/windows_overlay/mod.rs) only fires `shelf:drag_start` when the drag originates from an Explorer window **and** Shift is held when the drag threshold is crossed.

---

## 7. Tauri command handlers (the Rust↔frontend contract)

Registered in [`lib.rs::run()`](desktop/src-tauri/src/lib.rs):

| Command | Purpose |
|---|---|
| `write_clipboard_text` | Write text to local OS clipboard |
| `send_clipboard_text` | Broadcast clipboard_text via relay + LAN |
| `broadcast_clear(kind)` | kind: "clipboard" or "files" — broadcast clear msg |
| `delete_relay_file(url)` | HTTP DELETE on relay download URL (reject file) |
| `upload_screenshot(path, target?)` | LAN inline base64 if peers connected; else relay upload |
| `upload_file(path, target?)` | Relay HTTP upload, then file_meta via LAN + relay |
| `download_file(url, filename)` | Save to ~/Downloads |
| `open_file_dialog` / `open_folder_dialog` | Native picker |
| `reveal_in_explorer(path)` | Open Explorer with file selected |
| `get_pending_screenshot` | For toast app to read the staged screenshot path |
| `screenshot_toast_show / dismiss` | Show/hide toast window |
| `overlay_hide` | Hide drop shelf |
| `get_connection_status` / `get_room_devices` | Read current state |
| `leave_room_cmd` | Send device_leave + clear local state |
| `get_config` / `update_device_name` / `update_relay_url` / `update_screenshot_folder` | Config CRUD |

### Frontend event subscriptions (`listen(...)`)

| Event | Source |
|---|---|
| `connection:status` | connection.rs on connect/disconnect |
| `clipboard:change` | clipboard/mod.rs WM_CLIPBOARDUPDATE |
| `relay:clipboard_text` | connection.rs::handle_message |
| `relay:clipboard_image` | connection.rs::handle_message |
| `relay:clipboard_clear` / `relay:files_clear` | connection.rs |
| `relay:file_meta` | connection.rs |
| `relay:file_expired` | connection.rs (recently added) |
| `relay:device_join` / `relay:device_leave` | connection.rs |
| `shelf:drag_start` | windows_overlay drag_hook |
| `shelf:drag_enter` / `drag_leave` / `drop` / `drag_end` | Tauri DragDrop events |
| `main:file_drop` | Main window file drop handler in lib.rs::setup() |
| `screenshot:detected` | screenshot/mod.rs file watcher |
| `overlay:file_uploaded` | Emitted by OverlayApp after successful upload |
| `tray:leave_room` | tray/mod.rs menu click |

---

## 8. Key state ownership

| State | Where it lives | Notes |
|---|---|---|
| Persistent config | [`config.rs`](desktop/src-tauri/src/config.rs) — `livec_config.json` in app_data_dir | device_id, room_token, relay_url, screenshot_folder, device_name |
| Connection status | `static CONNECTED: AtomicBool` in [`connection.rs:32`](desktop/src-tauri/src/connection.rs) | Single source of truth |
| Outbound WS sender | `static TX: Mutex<Option<UnboundedSender>>` in [`connection.rs:86`](desktop/src-tauri/src/connection.rs) | `send_raw` errors if None |
| Room device registry | `ROOM_DEVICES: Mutex<Vec<DeviceInfo>>` in [`connection.rs:44`](desktop/src-tauri/src/connection.rs) | Polled by overlay & toast windows |
| Dedup ring | `DEDUP: Mutex<MsgDedup>` in [`connection.rs:46`](desktop/src-tauri/src/connection.rs), cap 500 | Used by both relay and LAN |
| Pending screenshot | `PENDING_SCREENSHOT: Mutex<Option<String>>` in [`lib.rs:46`](desktop/src-tauri/src/lib.rs) | Toast reads via command |
| LAN clients | `LAN_CLIENTS: Mutex<HashMap<peer_key, tx>>` in [`lan.rs:33`](desktop/src-tauri/src/lan.rs) | Each connected LAN peer |
| Clipboard entries (UI) | `useClipboard` hook + localStorage `livec.clipboard.entries` | Cap 100 |
| File transfers (UI) | `useFileTransfers` hook + localStorage `livec.transfers` | Cap 50 |

Android equivalents:
- `AppState` — `MutableStateFlow`-based global UI state
- `ConfigStore` — Jetpack DataStore-backed persistent config
- `seenIds: ArrayDeque` in `LiveCService` for dedup
- `lastTextHash` + `lastTextTimeMs` for clipboard self-write content dedup (5s window)

---

## 9. Clipboard duplication defense (multi-layer)

The Windows + Android OS clipboard fires `WM_CLIPBOARDUPDATE` / `OnPrimaryClipChangedListener` multiple times for one logical clip (format normalization, clipboard history hand-off). Several layers prevent bouncing the same text back to the sender:

1. **Own-echo filter** — drop `msg.from == self.deviceId` in both [`connection.rs:177`](desktop/src-tauri/src/connection.rs) and [`lan.rs:227`](desktop/src-tauri/src/lan.rs)
2. **ID-based dedup ring** — across relay + LAN
3. **Content+time dedup** — 5s window, pre-seeded when writing remote clip locally (clipboard/mod.rs `LAST_TEXT_HASH`, LiveCService.kt `isDuplicateText`)
4. **`SELF_WRITE_PENDING` flag** — one-shot, suppresses the very next clipboard event (but the second OS-fired event slips through, hence #3)
5. **350ms send debounce** in [`useLiveC.ts:84`](desktop/src/hooks/useLiveC.ts) — collapses rapid-fire copies

Forget any of these and you'll see duplicate clips. The 5s content/time dedup is the load-bearing one.

---

## 10. Known issues / gotchas

| Issue | Where | Severity |
|---|---|---|
| `MAX_FILE_BYTES = 100MB`, `FILE_TTL_MS = 90s` | [`protocol.js:25`](relay/src/protocol.js) | High — files >100MB rejected, slow downloads expire |
| Sender reads entire file into RAM | `lib.rs::upload_file`, `LiveCService.uploadAndBroadcast` | High for >500MB files |
| `fileTracker` is in-memory only | [`file-store.js:26`](relay/src/file-store.js) | Files on disk are orphaned if relay restarts |
| `pagefile`-sensitive Rust build | [`Cargo.toml`](desktop/src-tauri/Cargo.toml) | Prod builds OOM on low-RAM hosts; don't re-add `features = ["full"]` on tokio |
| Dead-code warnings on `read_image_bytes` etc. | [`clipboard/mod.rs`](desktop/src-tauri/src/clipboard/mod.rs) | Harmless — stubs for future feature |
| No integration tests | n/a | Clipboard dup logic is fragile, would benefit |
| Android UI is simple single-scroll list | [`HomeScreen.kt`](android/app/src/main/kotlin/com/livec/app/ui/screens/HomeScreen.kt) | Redesign mockup exists at `mockup-android.html` |
| `localStorage` persists across reloads | `useLiveC.ts` | If stale entries appear, manually clear `livec.clipboard.entries` and `livec.transfers` in DevTools |
| Tauri prod builds need pagefile headroom | see Cargo.toml comments | Kill dev server before `cargo tauri build` |

---

## 11. Recent fixes (latest first)

### Drop-shelf upload fails over cloudflared (THIS SESSION)
**Symptom:** Files uploaded via drop shelf failed with "File uploaded but failed to notify peers" when relay was behind cloudflared, even though the file landed on disk.

**Root cause:** The `?` operator on `connection::send_raw(json)` in [`lib.rs::upload_file`](desktop/src-tauri/src/lib.rs) propagated WS-disconnect errors back to the frontend. Cloudflared occasionally cycles its tunnel, causing brief WS gaps. If `send_raw` was called during one, the whole upload command returned an error despite the file already being staged. The handoff doc claimed this was fixed, but the `?` was still there.

**Fix:** Removed `?` propagation in both `upload_file` and `upload_screenshot`; failures are now logged but don't abort. Also added `file_expired` handler in [`connection.rs`](desktop/src-tauri/src/connection.rs) and a `relay:file_expired` listener in [`useLiveC.ts`](desktop/src/hooks/useLiveC.ts). Android's `uploadAndBroadcast` now also sends `file_meta` via LAN (was relay-only).

### Clipboard text duplication
Multi-layer fix — see §9 above. Documented thoroughly in [`handoff.md`](handoff.md).

### Clear-history broadcast + persistence
- Added `clipboard_clear` / `files_clear` protocol messages
- `clearedAtRef` tombstones prevent state-race re-population
- localStorage persistence survives Ctrl+R

### Files dropped on shelf didn't reach Android (pre-this-session)
- `upload_file` was relay-only; fixed to send via LAN too
- Note: the `?` propagation bug above was the "second half" of this fix that didn't actually land

### Device picker empty even with peers
- `OverlayApp` only listened to `relay:device_join` (missed pre-mount joins)
- Fix: `invoke('get_room_devices')` on mount

### Drop shelf vanished before user picked
- `shelf:drag_end` had a 120ms hide timeout racing `shelf:drop`
- Fix: `dropReceivedRef` flag + 500ms timeout + bail-if-drop-received

### Screenshot file path was sent as text
- Win+PrtSc puts the path in clipboard as text AND saves the PNG
- Fix: detect clipboard text that's a path to a real PNG/JPG, suppress

### Android image-clip handling
- Inline base64 images saved to cache, registered with FileProvider, ClipData.newUri
- Required AndroidManifest provider declaration + res/xml/file_paths.xml

### Reject-file feature (X button)
- Hover transfer row → X appears → HTTP DELETE on the staged file via `delete_relay_file` Rust command

---

## 12. Build & run

### Desktop (Tauri)
```powershell
cd D:\LiveC\desktop
npm install
npm run tauri dev          # HMR for React, auto-rebuild for Rust
npm run tauri build        # Prod at src-tauri/target/release/
```
Rust-only iteration: `cd src-tauri && cargo check`

DevTools: right-click main window → Inspect.

### Android
```powershell
cd D:\LiveC\android
.\gradlew.bat installDebug          # Requires adb device connected
.\gradlew.bat assembleRelease       # Unsigned APK
adb shell am start -n com.livec.app/.MainActivity     # Launch after install
adb logcat -s LiveCService:* RelayClient:* LanClient:* LanDiscovery:*
```

### Relay
```powershell
cd D:\LiveC\relay
npm install
npm start                  # Listens on PORT=3000 by default
npm run dev                # Auto-reload with nodemon
```

Cloudflared tunnel:
```powershell
cloudflared tunnel run <tunnel-name>
```
The tunnel config maps `https://yourdomain.com` → `http://localhost:3000`. The desktop config (`relay_url`) should be `wss://yourdomain.com/ws` (normalize_relay_url handles `https://...` input).

### Verify connectivity
- Relay health: `https://yourdomain.com/health` → `{"status":"ok","time":"..."}`
- WS connect: desktop title bar shows green "Connected" pill once paired
- LAN: phone and PC on same WiFi, mDNS should populate device list within ~5s

---

## 13. Useful invariants for debugging

1. **Every WS message must have `id`, `type`, `from`, `to`, `room`** — relay drops anything missing these.
2. **`device_join` MUST be the first message after WS open** — sets `ws.roomToken` and `ws.deviceId` on the relay side; subsequent messages fail "unauthenticated socket" otherwise.
3. **Both clients dedup by `msg.id`** — if you reuse an ID for a different message, the second is silently dropped.
4. **`from == self.deviceId`** is dropped on every inbound path. Don't bother trying to send to yourself.
5. **Heartbeat:** desktop sends `ping` every 25s, OkHttp on Android sends WS pings every 25s. If your connection's idle timeout is shorter than this, change the interval, not the protocol.
6. **Multer file size limit returns 413** — caught in the error middleware ([`server.js:32`](relay/src/server.js)).
7. **`relay_to_http_base`** strips `/ws` and converts ws→http. If the URL doesn't end in `/ws`, the function returns it unchanged minus scheme conversion.
8. **`normalize_relay_url`** is permissive — `https://x`, `wss://x/ws`, `x.com` all become `wss://x.com/ws` after `update_relay_url`.

---

## 14. Where to look first when…

| Symptom | Start here |
|---|---|
| Files don't reach the other device | [`lib.rs::upload_file`](desktop/src-tauri/src/lib.rs), [`LiveCService.uploadAndBroadcast`](android/app/src/main/kotlin/com/livec/app/service/LiveCService.kt), [`file-store.js`](relay/src/file-store.js) |
| Clipboard not syncing | [`clipboard/mod.rs`](desktop/src-tauri/src/clipboard/mod.rs) for Win→event, [`useLiveC.ts::useClipboard`](desktop/src/hooks/useLiveC.ts) for event→send, [`LiveCService.clipListener`](android/app/src/main/kotlin/com/livec/app/service/LiveCService.kt) for Android |
| Duplicate clips appearing | The 5-layer defense in §9 — check all five |
| Drop shelf doesn't appear | [`windows_overlay/mod.rs::drag_hook`](desktop/src-tauri/src/windows_overlay/mod.rs) — confirm Explorer window detection + Shift held |
| Drop shelf appears but disappears too fast | `shelf:drag_end` timeout in [`OverlayApp.tsx`](desktop/src/OverlayApp.tsx) |
| WS disconnects constantly | Check heartbeat interval vs proxy idle timeout; check cloudflared logs |
| Files download as wrong name / 404 | The download URL is constructed at upload time. Check [`file-store.js::handleUpload`](relay/src/file-store.js) and [`lib.rs::upload_file`](desktop/src-tauri/src/lib.rs) for mismatch |
| Device list empty | [`connection.rs::ROOM_DEVICES`](desktop/src-tauri/src/connection.rs), check `device_join` is being received |

---

## 15. Companion docs

- [`PROTOCOL.md`](PROTOCOL.md) — authoritative protocol spec (always edit before the four mirrors)
- [`handoff.md`](handoff.md) — running maintainer notes; recent fixes in detail
- [`livec_design.md`](livec_design.md) — original design doc, somewhat dated
- [`design-borrows.md`](design-borrows.md) — proposal for borrowing LocalSend + Blip patterns (two-phase upload, fingerprint identity, signed URLs, resumable, 7-day TTL, etc.)
- `handoff-android.md` / `handoff-findings.md` — older debugging logs

---

## 16. Things NOT in this codebase that you might expect

- ❌ End-to-end encryption (relay sees plaintext)
- ❌ User accounts / authentication
- ❌ Database (everything is in-memory or localStorage)
- ❌ iOS or macOS client
- ❌ Web client
- ❌ Push notifications (FCM/APNs) — Android only gets notifications when the foreground service is running and WS is open
- ❌ Resumable uploads
- ❌ Multi-recipient stream optimization
- ❌ Integration tests (only unit-level absence — the test scripts in relay/test.js and relay/fake-device.js are smoke scripts)
- ❌ Cross-subnet LAN discovery (mDNS is link-local)

---

## 17. Quick mental model for someone landing cold

> "Three apps share a JSON message format over WebSockets. They find each other on LAN via mDNS and over the internet via a Node.js relay you control (likely tunneled through cloudflared). Both transports are used simultaneously and deduped by message ID. Files are uploaded to the relay via multipart HTTP, then a `file_meta` message tells the other side where to download. Clipboard is fire-and-forget JSON. There are no accounts, no DB, no encryption. The drop shelf on Windows is a Win32 mouse-hook-driven overlay that catches Shift+drag from Explorer."

Everything else is detail.
