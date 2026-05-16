# LiveC — Universal Clipboard & File Sync

## Understanding Summary

- **What:** A universal clipboard and file sync app that connects a Windows PC with multiple Android devices, enabling seamless sharing of text, screenshots, and files across all paired devices
- **Why:** No Windows + Android equivalent of Apple's Universal Clipboard / Handoff. Unreliable hostel WiFi (frequently using phone hotspot) demands a cloud relay as the primary communication path
- **Who:** Personal use — 1 PC + 2-3 Android devices
- **Topology:** Peer mesh — any paired device can send text, screenshots, or files to any other (PC ↔ Android, Android ↔ Android)

---

## Tech Stack

| Component | Technology |
|---|---|
| Windows Client | Tauri (Rust backend + React/TypeScript frontend) |
| Android Client | Kotlin + Jetpack Compose |
| Relay Server | Node.js (Express + ws), Docker on Render |
| Transport | WSS (TLS-encrypted WebSockets) |
| LAN Discovery | mDNS (`_livec._tcp`) |
| Security | Token-based pairing, WSS encryption, no user accounts |

---

## Architecture

### Four Components

1. **Render Relay** (Node.js Docker) — always-on WSS message broker
   - Room-based routing (stateless — rooms exist in memory only while devices are connected)
   - File staging with 90-second TTL and auto-cleanup
   - Offline message queue with 1-hour expiry (max 50 items per device)
   - HTTPS endpoints for file upload/download
   - No database, no user accounts, no persistent storage

2. **Windows Client** (Tauri)
   - 3-panel UI: device list | clipboard history | file transfers + drop zone
   - System tray with background operation
   - Win32 clipboard monitor (text changes, debounced 300ms)
   - Screenshot folder watcher (configurable path)
   - Floating popup at cursor for screenshot send confirmation
   - Shift+drag global drop zone (bottom-right) for quick file sends
   - mDNS responder/browser for LAN discovery
   - WSS connections to relay + LAN peers

3. **Android Client** (Kotlin + Compose)
   - Screens: Home (devices + clipboard history), File Transfer, Settings, Pairing (QR scanner)
   - Foreground service for persistent WebSocket
   - Clipboard monitoring via ClipboardManager + optional Accessibility Service
   - Share sheet integration (outgoing: send from any app; incoming: forward received files)
   - File receiver with notification progress bars
   - NsdManager for mDNS LAN discovery

4. **Shared Protocol** — unified JSON message envelope

---

## Message Protocol

Every message uses this envelope:

```json
{
  "id": "uuid-v4",
  "type": "clipboard_text | clipboard_image | file_meta | file_chunk | device_join | device_leave | ping | pong",
  "from": "device-id",
  "to": "device-id | broadcast",
  "room": "room-token",
  "timestamp": 1715534400000,
  "payload": { ... }
}
```

### Message Types

| Type | Payload | Size | Route |
|---|---|---|---|
| `clipboard_text` | `{ "text": "..." }` | < 1MB | LAN-preferred |
| `clipboard_image` | `{ "data": "base64", "width": N, "height": N }` | < 10MB | LAN-preferred |
| `file_meta` | `{ "name": "...", "size": N, "downloadUrl": "..." }` | Tiny | Relay only |
| `file_chunk` | Binary frame (chunked upload) | ≤ 1MB/chunk | Relay only |
| `device_join` | `{ "name": "...", "type": "android\|windows", "isPrimary": false }` | Tiny | Relay |
| `device_leave` | `{}` | Tiny | Relay |
| `ping` / `pong` | `{}` | Tiny | Both |

### Rules
- `to: "broadcast"` → fan out to all room members
- `to: "specific-device-id"` → targeted send
- Relay never inspects payloads — routes by `to` field only
- Binary frames prefixed with message UUID for reassembly

---

## Smart Routing & LAN Detection

### How It Works

1. **Discovery:** On app start + network change, each device broadcasts via mDNS:
   - Service: `_livec._tcp.local`
   - TXT record: `room_hash=<sha256(room_token)[:8]>`, `device_id=<id>`, `wss_port=<port>`

2. **Matching:** Discovered peers with matching `room_hash` → establish direct WSS connection on local IP

3. **Routing decision per message type:**
   - `clipboard_text` / `clipboard_image` → LAN-direct if available, relay fallback
   - `file_meta` / `file_chunk` → always relay (file staging needs the server)
   - `device_join` / `device_leave` → always relay (room state)

4. **Health check:** LAN peers ping every 5s. 3 missed pings → LAN marked dead → fallback to relay. Auto-resumes when LAN recovers.

5. **Deduplication:** UUID seen-set (ring buffer of 200 entries). Duplicates arriving via both paths are dropped silently.

---

## Pairing & Discovery

### First-Time Setup

1. PC generates `device_id` (UUID) + `room_token` (12-char alphanumeric), stores locally
2. User clicks "Add Device" → QR code displayed containing `{ relay_url, room_token }`
3. Android scans QR → saves token to SharedPreferences → connects to relay → joins room
4. Additional devices: repeat scan from the same QR

### Persistence
- Room token saved client-side (Tauri app data / Android SharedPreferences)
- Relay is stateless — rooms are created in-memory when first device connects
- No re-pairing needed on app restart

### Unpairing
- Settings → device list → remove device → sends `device_leave` to relay

---

## File Transfer Flow

```
Sender                    Render Relay              Receiver
  │                           │                        │
  ├── POST /upload ──────────>│                        │
  │   (multipart, ≤100MB)     │── stores (UUID name)   │
  │                           │── starts 90s TTL       │
  ├── WS: file_meta ─────────>│── routes ─────────────>│
  │                           │<── GET /download/:id ──┤
  │                           │── streams file ───────>│
  │                           │── deletes after TTL    │
```

- **Upload:** Chunked multipart POST over HTTPS. Progress tracked on sender.
- **Download:** Receiver gets notification with Accept/Reject. Downloads via HTTPS.
- **TTL:** 90 seconds from upload. If not downloaded → deleted → sender notified with "Resend?" option.
- **Cap:** 100MB per file (Render free tier constraint).

---

## Windows Client UI

### Main Window (3-Panel Layout)

| Area | Content |
|---|---|
| **Left Sidebar** | Paired devices with online/offline badges, connection type (LAN/Cloud), primary star icon, "Add Device" button |
| **Center Panel** | Clipboard history feed — chronological text snippets + screenshot thumbnails, click to copy, click device to resend |
| **Right Panel** | Active file transfers with progress bars, drag-and-drop zone + "Browse" button, device picker on drop |
| **Top Bar** | App name, connection status indicators, settings gear |

### Special Windows

- **Floating Screenshot Popup:** Borderless, always-on-top at cursor position. Thumbnail + device picker + Send/Dismiss buttons. Auto-dismiss after 10s.
- **Global Drop Zone:** Borderless semi-transparent overlay at bottom-right. Appears when Shift held during file drag. Shows device icons for targeted drop.
- **System Tray:** App minimizes to tray on close. Right-click: Open, Settings, Quit. Icon shows connection status.

---

## Android Client UI

### Screens

| Screen | Content |
|---|---|
| **Home** | Device list with status, clipboard history feed, quick-copy buttons |
| **File Transfer** | Active/recent transfers with progress, file picker |
| **Settings** | Device name, primary toggle, room token, unpair, notifications |
| **Pairing** | QR camera scanner + manual entry |

### Background Services
- **Foreground Service:** Persistent notification, keeps WSS alive
- **Clipboard Monitor:** `OnPrimaryClipChangedListener` + optional Accessibility Service for background
- **Share Sheet:** Registered as share target — send any content from any app via LiveC

---

## Error Handling & Edge Cases

| Scenario | Behavior |
|---|---|
| Render relay asleep (free tier) | Exponential backoff reconnect (1s→30s). LAN-direct unaffected. ~30s cold start. |
| Clipboard loop | "Self-write" flag prevents re-sending content that was just received |
| Duplicate messages | UUID seen-set (200 entries). Silently dropped. |
| File upload failure | Error toast. Manual retry. No partial resume in V1. |
| File TTL expires | Receiver gets `file_expired`. Sender sees "Resend?" button. |
| Network switch (WiFi↔hotspot) | ConnectivityManager callback triggers mDNS re-probe + relay reconnect. ~2-3s interruption. |
| Rapid clipboard copies | 300ms debounce window. Only last value sent. |
| Oversized clipboard (>1MB) | Skip sync, show warning. |
| Android app killed | Foreground service prevents most kills. Offline queue catches missed items. |

---

## Assumptions

1. Screenshot folder path is configurable (defaults to Windows screenshot directory)
2. Text clipboard sync is automatic — no confirmation popup
3. No user accounts or login — pairing tokens are the only identity
4. "Primary device" setting stored locally on each device
5. Designed for Render free tier (512MB RAM, ephemeral disk)
6. Files are ephemeral on relay — never stored long-term
7. Android clipboard monitoring requires Accessibility Service for background access (Android 10+)

---

## Decision Log

| # | Decision | Alternatives | Rationale |
|---|---|---|---|
| 1 | Two-way sync | One-way PC→Android | Full parity — all devices are equal peers |
| 2 | mDNS + QR fallback | Manual IP, QR-only | Auto-discovery for convenience; QR for initial pairing |
| 3 | Tauri (Rust + Web) | Electron, native, Python | Lightweight, performant, existing experience |
| 4 | Kotlin + Jetpack Compose | Flutter, XML views | Full Android API access, modern toolkit |
| 5 | Peer mesh topology | Hub-and-spoke | Android↔Android sync required |
| 6 | Render relay as primary | LAN-only | Unreliable hostel WiFi, phone hotspot scenarios |
| 7 | Smart routing (LAN + relay) | Always-relay, P2P-first | Speed on LAN, reliability via relay |
| 8 | Node.js for relay | Rust, Go, Python | Fast to build, familiar, good WS support |
| 9 | 100MB cap, 90s file TTL | Unlimited | Render free tier constraints |
| 10 | Primary device auto-receive | Broadcast, always-targeted | Best daily UX with manual override |
| 11 | WSS encryption | Plaintext, E2E | Shared network protection, low complexity |
| 12 | Floating popup at cursor | Toast, tray menu | Contextual, visual, non-intrusive |
| 13 | Shift+drag drop zone | In-app only | Quick sends without opening app |
| 14 | 3-panel UI + drop zone | Tray-only, dashboard | Full visibility and control |
| 15 | Client-side token persistence | Server DB | Relay stays stateless, pairing is one-time |
| 16 | Share sheet integration | Clipboard-only | Reliable fallback for Android 10+ restrictions |
| 17 | Guest feature dropped | PIN/QR temp access | YAGNI — architecture supports adding later |

---

## Project Structure (Proposed)

```
d:\LiveC\
├── relay/                    # Node.js relay server
│   ├── Dockerfile
│   ├── package.json
│   └── src/
│       ├── server.js         # Express + ws setup
│       ├── room-manager.js   # Room state, device tracking
│       ├── message-router.js # Route by 'to' field
│       ├── file-store.js     # Upload/download + TTL cleanup
│       └── offline-queue.js  # Per-device message queue
│
├── desktop/                  # Tauri Windows app
│   ├── src-tauri/
│   │   └── src/
│   │       ├── main.rs
│   │       ├── clipboard.rs    # Win32 clipboard monitor
│   │       ├── screenshot.rs   # Folder watcher
│   │       ├── connection.rs   # WSS client (relay + LAN)
│   │       ├── mdns.rs         # mDNS discovery
│   │       ├── router.rs       # LAN vs relay decision
│   │       ├── config.rs       # Local config store
│   │       └── drop_zone.rs    # Global Shift+drag handler
│   └── src/                  # React/TS frontend
│       ├── App.tsx
│       ├── components/
│       │   ├── DeviceList.tsx
│       │   ├── ClipboardHistory.tsx
│       │   ├── FileTransfers.tsx
│       │   ├── DropZone.tsx
│       │   ├── ScreenshotPopup.tsx
│       │   ├── QRDisplay.tsx
│       │   └── Settings.tsx
│       └── hooks/
│           ├── useDevices.ts
│           ├── useClipboard.ts
│           └── useTransfers.ts
│
└── android/                  # Kotlin + Compose app
    └── app/src/main/
        ├── kotlin/.../livec/
        │   ├── MainActivity.kt
        │   ├── ui/
        │   │   ├── HomeScreen.kt
        │   │   ├── FileTransferScreen.kt
        │   │   ├── SettingsScreen.kt
        │   │   └── PairingScreen.kt
        │   ├── service/
        │   │   ├── WebSocketService.kt
        │   │   ├── ClipboardMonitor.kt
        │   │   └── FileReceiver.kt
        │   ├── network/
        │   │   ├── RelayClient.kt
        │   │   ├── LanDiscovery.kt
        │   │   └── SmartRouter.kt
        │   └── data/
        │       ├── ConfigStore.kt
        │       ├── ClipboardHistory.kt
        │       └── DeviceRegistry.kt
        └── AndroidManifest.xml
```
