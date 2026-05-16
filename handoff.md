# LiveC — Handoff

**Last updated:** 2026-05-15

Universal clipboard + file sync across Windows (Tauri/Rust + React) and Android (Kotlin + Compose), with an optional cloud relay (Node.js, deployable on Cloudflare/VPS via WSS) and LAN direct path (mDNS discovery + WS over local network).

---

## 1. Architecture at a glance

```
┌──────────────────┐      wss://relay/ws       ┌──────────────────┐
│  Windows (Tauri) │ ◄───────────────────────► │ Relay (Node.js)  │ ◄──► other clients
│                  │                            └──────────────────┘
│  ┌────────────┐  │
│  │ React UI   │  │      ws://LAN_IP:7777
│  └─────┬──────┘  │ ◄────────────────────────────────┐
│  ┌─────▼──────┐  │                                  │
│  │ Rust core  │  │                                  │
│  │ (lib.rs,   │  │                                  ▼
│  │  clipboard,│  │                            ┌──────────────────┐
│  │  lan,      │  │                            │ Android (Kotlin) │
│  │  conn)     │  │                            │  LiveCService    │
│  └────────────┘  │                            │  + Compose UI    │
└──────────────────┘                            └──────────────────┘
```

- **Relay path** (`wss://`): always-on, works across networks. WebSocket fan-out by room.
- **LAN path** (`ws://`): mDNS-discovered direct connection. Lower latency, no internet needed.
- **Both transports** carry the same JSON envelope. Cross-transport dedup by message UUID.

### Message envelope

```json
{
  "id": "uuid",
  "type": "clipboard_text|clipboard_image|file_meta|...",
  "from": "deviceId",
  "to": "deviceId|broadcast",
  "room": "roomToken",
  "timestamp": 1234567890,
  "payload": { /* type-specific */ }
}
```

Protocol mirror lives in three places — **update all three in lockstep** when adding message types:
- `desktop/src-tauri/src/protocol.rs`
- `android/app/src/main/kotlin/com/livec/app/data/Protocol.kt`
- `desktop/src/protocol.ts`

---

## 2. Build & run

### Windows desktop

```powershell
cd D:\LiveC\desktop
npm install
npm run tauri dev          # dev with HMR
npm run tauri build        # prod exe at src-tauri/target/release/
```

Rust-only iteration: `cd src-tauri && cargo check`.

**Pagefile note:** prod builds OOM on low-RAM hosts. `Cargo.toml` has `debug=0`, `codegen-units=16`, and trimmed `tokio` / `windows` / `image` feature flags specifically to keep LLVM peak memory down. Don't re-add `features = ["full"]` on `tokio` or include unused `windows` features without re-verifying the build still fits.

### Android

```bash
cd android
./gradlew installDebug      # requires connected device or emulator
./gradlew assembleRelease   # unsigned apk
```

ADB logs (key tags during debugging):
```bash
adb logcat -s LiveCService:* RelayClient:* LanClient:* LanDiscovery:*
```

### Relay server

Lives at `D:\LiveC\relay\` (Node.js). `npm start` runs locally on `:3000`. Cloudflare tunnels and VPS Docker deployments both work — LiveC supports `ws://` and `wss://` transparently (URL parsing in `RelayClient.kt` and `connection.rs` rewrites the scheme based on `http(s)`/`ws(s)` prefix).

---

## 3. Recent fixes (most recent first)

### Clipboard text duplication — multi-layer defense
**Symptom:** copying text on Windows resulted in two entries (one local, one ghost-remote) and Android sometimes echoed the same text back as a fresh message.

**Root causes (all fixed):**

1. **Windows had no own-echo filter.** Some relay implementations echo broadcasts back to the sender. Windows processed its own message, wrote to clipboard, emitted `relay:clipboard_text`, frontend added a remote duplicate. Android already had this filter.
   - Fix: `if m.from == device_id { continue; }` in both `connection.rs` inbound loop and `lan.rs` `handle_client`.

2. **`SELF_WRITE_PENDING` is one-shot.** When we write a remote clip to the OS clipboard, the OS sometimes fires `WM_CLIPBOARDUPDATE` / `OnPrimaryClipChangedListener` **twice** (format normalization, clipboard-history hand-off). The first event consumes the flag; the second slips through and broadcasts the text back as a fresh message with a new UUID — defeating the id-based dedup.
   - Fix on Windows: `write_text` pre-seeds `LAST_TEXT_HASH` with `fnv1a(text)` + timestamp before `SetClipboardData`. The existing 5-second content/time dedup in `wnd_proc` then catches the second fire.
   - Fix on Android: added `isDuplicateText(text)` (same hash+timestamp pattern, 5s window). `clipListener` calls it before broadcasting; `handleMessage` calls it before `setPrimaryClip` to pre-seed.

3. **Rapid copies spammed peers.** Rich-text copies fire multiple `clipboard:change` events with slightly different content as the OS normalizes formats. Each one was being sent.
   - Fix: 350ms debounce on the send side in `useLiveC.ts` (`pendingSendRef` with `setTimeout` reset on each new copy). Local feed still gets every entry; only the final one is pushed to peers.

### Clear history not working
**Symptom:** clicking the brush icon on either panel didn't visibly remove entries.

**Root causes:**

1. **State race with React batching.** `setEntries([])` was scheduled, then a `clipboard:change` or `relay:*` event fired in the same tick, ran `setEntries(prev => [...new, ...prev])` with `prev=[]`, re-populating one item.
   - Fix: `clearedAtRef` tombstone in `useClipboard` and `useFileTransfers`. Any `addEntry` call within 400ms of a clear returns early.

2. **Sync to peers wasn't implemented.** Clear was local-only.
   - Fix: new protocol messages `clipboard_clear` and `files_clear`. Rust command `broadcast_clear(kind)` sends via both `lan::send_lan` and `connection::send_raw`. Receivers emit `relay:clipboard_clear` / `relay:files_clear`. Android handles in `LiveCService.handleMessage` → `AppState.clearClips()` / `clearTransfers()`.

3. **Ctrl+R wiped UI state.** React state was in-memory only; reload reset everything.
   - Fix: `localStorage` persistence in `useClipboard` and `useFileTransfers`. Keys: `livec.clipboard.entries`, `livec.transfers`. `clearEntries` / `clearTransfers` also write `[]` directly to localStorage (not relying on `useEffect` timing).

### Files dropped on the shelf didn't reach Android
**Symptom:** drop-shelf upload succeeded (file appeared in transfers list on Windows) but Android never got the `file_meta` notification.

**Root cause:** `upload_file` in `lib.rs` only sent `file_meta` via relay (`connection::send_raw`), not LAN. If Android was primarily on LAN, the message was missed.

**Fix:** `upload_file` now sends `file_meta` through both transports. `connection::send_raw` result is now ignored (no `?` propagation) so a flaky relay doesn't fail the upload after the file already landed.

### Device picker showed empty even with peers connected
**Symptom:** drop-shelf picker showed only "All devices" — actual peers missing.

**Root cause:** `OverlayApp.tsx` only listened to `relay:device_join` events, so it missed devices that had joined *before* the overlay window registered its listener.

**Fix:** added `invoke('get_room_devices')` on mount to seed the device list from the Rust-side `ROOM_DEVICES` registry.

### Drop shelf disappeared before user could pick a device
**Symptom:** dropped a file, picker briefly appeared then vanished without input.

**Root cause:** `shelf:drag_end` had a 120ms timeout that auto-hid the shelf if state wasn't `picking` yet — losing the race against `shelf:drop` arrival.

**Fix:** `dropReceivedRef` flag set inside `shelf:drop`. `shelf:drag_end` timeout now bails out immediately if a drop was received. Timeout raised from 120ms → 500ms for safety. Picker stays open until user picks or presses Esc. Also: auto-broadcast on empty device list is gone — picker always shows.

### Screenshot file path was sent as clipboard text
**Symptom:** taking a screenshot with Win+PrtSc pushed the PNG file path (e.g., `C:\Users\...\screenshot.png`) to Android as text instead of the image.

**Root cause:** screenshot tools write the path as `CF_UNICODETEXT` *and* save the PNG to the watched folder. The clipboard monitor saw the text first.

**Fix:** `wnd_proc` checks if clipboard text is a path to an existing `.png` / `.jpg` / `.jpeg` file. If yes, suppress the `clipboard:change` event — the screenshot file watcher handles it via the toast flow (`upload_screenshot` sends the actual image bytes).

### Android image-clip handling
**Symptom:** received screenshots were inserted into Android's clipboard as the `file://` URI string instead of an actual image.

**Fix:** new `writeImageToClipboard(uri, mime)` and `downloadAndWriteImageToClipboard(downloadUrl)` helpers in `LiveCService.kt`. LAN path decodes inline base64, saves to cache file, calls `FileProvider.getUriForFile`, then `ClipData.newUri(...)`. Relay path downloads from the URL on `Dispatchers.IO` then same flow. Required new `<provider>` declaration in `AndroidManifest.xml` and `res/xml/file_paths.xml`.

### Reject file (Windows) — new feature
Hover any transfer row in the right panel → `X` button appears top-right. Clicking it:
- Removes the transfer from the local list
- Sends `HTTP DELETE` to the relay's download URL via new Rust command `delete_relay_file(url)` — frees the staged file immediately instead of waiting for the 90s TTL

Disabled while a transfer is actively `downloading` or `uploading` (would race the in-flight request).
Save button has `mt-5` so it sits below the X without overlap.

### Cross-platform deletion sync
Clear button on either panel now broadcasts to all paired devices. Android receives `clipboard_clear` / `files_clear` and applies the same wipe.

---

## 4. Pending / known issues

- **Relay server source not in this snapshot.** The deployed relay accepts the protocol but the source for tuning (e.g., disabling echo-back to sender) lives elsewhere. The Windows own-echo filter makes Windows tolerant of either relay behavior; Android already had it.
- **Cargo prod builds need pagefile headroom.** If a fresh checkout OOMs, kill the dev server and any other processes before `cargo tauri build`. Dev (`cargo tauri dev`) is fine.
- **Android UI is still the simple single-scroll list.** The HTML mockup (`mockup-android.html`) shows the target redesign with three bottom-nav tabs (Devices / Clipboard / Files), QR display sheet, send-file device picker, and rich card styling. Implementation **not** done yet — only `Protocol.kt` and `LiveCService.kt` have the message-handling pieces in place for the new flows. The ZXing dependency (`com.google.zxing:core:3.5.3`) is already added in `build.gradle.kts` for QR generation when ready.
- **`read_image_bytes` and friends in `clipboard/mod.rs` are dead code.** Kept as stubs for a future "paste image directly from clipboard" feature. Three dead-code warnings on every build — harmless.
- **No integration tests yet.** The clipboard dup logic in particular would benefit from a smoke test that fires a synthetic `clipboard_text` event and asserts only one entry shows up in each peer's feed.

---

## 5. File map (the important ones)

### Windows
```
desktop/
├── src/
│   ├── App.tsx                    # main window (LeftPanel, ClipboardPanel, RightPanel, Settings)
│   ├── OverlayApp.tsx             # drop shelf + device picker
│   ├── ScreenshotToastApp.tsx     # screenshot share toast
│   ├── hooks/useLiveC.ts          # all React hooks: useClipboard, useFileTransfers, useRoomState, useConfig
│   └── protocol.ts                # message-type constants (mirror)
└── src-tauri/
    ├── Cargo.toml                 # trimmed deps — see pagefile note
    ├── tauri.conf.json            # 3 windows: main, overlay, screenshot_toast
    └── src/
        ├── lib.rs                 # Tauri commands + setup + run()
        ├── protocol.rs            # Message struct, msg/limits/paths constants, MsgDedup
        ├── connection.rs          # relay WS client + handle_message + ROOM_DEVICES registry
        ├── lan.rs                 # LAN WS server (port 7777) + mDNS advertisement
        ├── clipboard/mod.rs       # Win32 clipboard monitor + read/write + dedup
        ├── screenshot/mod.rs      # poll Pictures\Screenshots folder
        ├── windows_overlay/mod.rs # overlay positioning + global mouse hook for Shift+drag
        ├── tray/mod.rs            # system tray
        └── config.rs              # persistent config (device_id, room_token, etc.)
```

### Android
```
android/app/src/main/
├── AndroidManifest.xml            # cleartext traffic, FileProvider, service decl
├── res/xml/file_paths.xml         # FileProvider cache-path mapping
└── kotlin/com/livec/app/
    ├── MainActivity.kt
    ├── LiveCApplication.kt
    ├── data/
    │   ├── Protocol.kt            # msg constants (mirror), Paths, Limits
    │   ├── Message.kt             # envelope + factory helpers, DeviceInfo, ClipItem, TransferItem
    │   ├── AppState.kt            # in-memory MutableStateFlow store
    │   └── ConfigStore.kt         # DataStore-backed persistent config
    ├── network/
    │   ├── RelayClient.kt         # WS client with exponential backoff reconnect
    │   ├── LanClient.kt           # LAN WS client
    │   └── LanDiscovery.kt        # mDNS browser (NsdManager)
    ├── service/
    │   └── LiveCService.kt        # foreground service — owns connections, clipboard listener
    └── ui/
        ├── AppViewModel.kt
        ├── theme/                 # Color.kt (palette mirrors Windows), Theme.kt
        └── screens/
            ├── HomeScreen.kt      # single-scroll list (Devices, Clips, Files sections)
            ├── PairingScreen.kt   # QR scanner (CameraX + ML Kit)
            └── SettingsScreen.kt
```

### Mockups (HTML)
```
mockup-device-picker.html          # Windows drop-shelf picker variants
mockup-android.html                # full Android redesign — 3-tab layout, QR sheet, send picker
```

---

## 6. Protocol message types — full list

| Type              | Direction        | Payload                                                        |
|-------------------|------------------|----------------------------------------------------------------|
| `device_join`     | sender → room    | `{deviceId, deviceName, platform, roomToken}`                  |
| `device_leave`    | sender → room    | `{deviceId}`                                                   |
| `clipboard_text`  | any              | `{text}`                                                       |
| `clipboard_image` | any              | LAN: `{data: base64, mimeType}` · Relay: `{fileId, downloadUrl}` |
| `clipboard_clear` | broadcast        | `{}` (new)                                                     |
| `file_meta`       | any              | `{fileId, name, size, downloadUrl}`                            |
| `file_expired`    | server → client  | `{fileId}`                                                     |
| `files_clear`     | broadcast        | `{}` (new)                                                     |
| `ping` / `pong`   | client ↔ server  | `{}`                                                           |
| `ack`             | reserved         | unused so far                                                  |

**All inbound message handlers must:**
1. Reject if `msg.from == self.deviceId` (own-echo)
2. Reject if `msg.to != BROADCAST && msg.to != self.deviceId` (not for us)
3. Reject if `msg.id` is in the dedup ring (cross-transport)

Forget any of these and you'll see loops or duplicates.

---

## 7. Tauri commands (the contract with the frontend)

```rust
write_clipboard_text(text)             // Write text to local OS clipboard
send_clipboard_text(text)              // Broadcast clipboard_text via relay + LAN
broadcast_clear(kind)                  // kind: "clipboard" | "files"
delete_relay_file(url)                 // HTTP DELETE on relay download URL
upload_screenshot(path, target?)       // Inline via LAN if peer count > 0, else relay
upload_file(path, target?)             // Relay HTTP + file_meta via LAN + relay
download_file(url, filename)           // Save to ~/Downloads
open_file_dialog() / open_folder_dialog()
reveal_in_explorer(path)
get_pending_screenshot()
screenshot_toast_show / screenshot_toast_dismiss
overlay_hide
get_connection_status / get_room_devices / leave_room_cmd
config::get_config / update_device_name / update_relay_url / update_screenshot_folder
```

Events emitted to the frontend:
```
connection:status         { connected, relayUrl }
clipboard:change          { kind, text?, sizeBytes? }
relay:clipboard_text      { text, from }
relay:clipboard_image     { fileId, downloadUrl, from }
relay:clipboard_clear     { from }
relay:files_clear         { from }
relay:file_meta           { fileId, name, size, downloadUrl, from }
relay:device_join         { deviceId, deviceName, platform }
relay:device_leave        { deviceId }
relay:message             { ...envelope }       # fallback for unhandled types
shelf:drag_start | drag_enter | drag_leave | drop | drag_end
screenshot:detected       { source, path }
main:file_drop            { files: string[] }
overlay:file_uploaded     { name, downloadUrl }
tray:leave_room
```

---

## 8. Rebuild reminder

Both apps must be rebuilt after any of the changes above. A common debugging trap is testing against a stale binary:

- **Windows**: `npm run tauri dev` in `desktop/` (HMR for React, auto-rebuild for Rust). `console.error` lines surface in DevTools (right-click → Inspect on the main window).
- **Android**: `./gradlew installDebug` and `adb logcat -s LiveCService:*`.
- **localStorage carries over reloads.** If something looks like stale clipboard history after a fix, clear `livec.clipboard.entries` and `livec.transfers` in DevTools → Application → Local Storage.

---

## 9. What's next (suggested order)

1. **Implement the Android redesign from `mockup-android.html`** — three bottom-nav tabs, QR display sheet (ZXing dep already added), device picker bottom sheet for the Send-File flow.
2. **Wire Android's "Send file" CTA** — use the existing `LiveCService.startWithFile(ctx, uri)` path; add a device-picker bottom sheet before invoking it.
3. **Expose Android's `clearClips()` and the new broadcast through a brush button** in the Clipboard tab to match Windows parity.
4. **Verify the relay's echo-back behavior** by reading the deployed relay source. If it doesn't echo back to senders, the `from == deviceId` filter is defense-in-depth; if it does, the filter is load-bearing.
5. **Add a small integration test** that broadcasts a `clipboard_text` and asserts no duplicate ends up in either client's feed within the dedup window. Would catch regressions in the most fragile area of the codebase.
