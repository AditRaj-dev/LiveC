# LiveC — Investigation Findings: Drop Shelf → Android File Transfer

**Date:** 2026-05-15
**Status:** Root cause **not yet confirmed**. Symptoms isolated to Android's receive/render pipeline. Multiple fixes landed along the way.

---

## 1. Original complaint

> "Files sent from Windows via the drop shelf overlay do not appear on Android. The Windows browse button works."

Same `upload_file` Tauri command in both paths, so the divergence pointed to either (a) overlay-window-specific behavior or (b) the difference between **broadcast** (browse) and **targeted** (drop shelf picker) delivery.

---

## 2. Fixes landed during the session

These were all real bugs found and fixed along the way. None of them turned out to be the root cause of the current symptom — but they unblocked the investigation.

### 2.1 TLS — `native-tls` → `rustls-tls-webpki-roots`
**File:** [desktop/src-tauri/Cargo.toml](desktop/src-tauri/Cargo.toml)
**Why:** `tokio-tungstenite` with `native-tls` was failing to complete the WSS handshake to `*.trycloudflare.com` (Windows SChannel quirks). `reqwest` also switched from `native-tls` → `rustls-tls` for the file-upload path. After this change, `wss://` connections succeed and the only remaining handshake failures are origin-routing issues (404 / 530).

### 2.2 Relay WebSocket upgrade — `path:` → `noServer: true` + manual `upgrade` handler
**File:** [relay/src/server.js](relay/src/server.js)
**Why:** With Express 5 in front of `ws`, the `{ server, path: '/ws' }` form let Cloudflare's WebSocket-upgrade probe slip through to Express, which returned 404. Switched to `noServer: true` + explicit `server.on('upgrade', …)` handler that strips the query string and routes only `/ws` upgrades to `wss.handleUpgrade`. Local `node test.js` still passes (3 sockets connect, broadcast / targeted routing work — the pre-existing `offline_msg` failure is unrelated).

### 2.3 Cloudflare tunnel ingress
**File:** `C:\Users\study\.cloudflared\config.yml`
**Finding:** Existing named tunnel routes `hybridforge.xyz → http://localhost:8090`, with `http_status:404` catch-all. The trycloudflare quick-tunnel URL therefore hit the catch-all → 404. **Two paths forward** documented for the user (subdomain ingress vs. ephemeral quick tunnel). User chose to point the desktop at `wss://hybridforge.xyz/ws`. Also note: the user originally had a typo (`hybridflorge.xyz`) saved in config — fixed.

> ⚠️ The cloudflared config currently routes `hybridforge.xyz` to **port 8090, not 3000**. The fact that WSS and HTTP uploads work through this tunnel implies *something* on port 8090 is forwarding to the relay, OR the config was updated since the last read. **Re-verify this on the next session.**

### 2.4 Drop shelf silently swallowed upload errors
**File:** [desktop/src/OverlayApp.tsx](desktop/src/OverlayApp.tsx)
**Symptom:** Shelf flipped to "Synced" regardless of upload outcome.
**Fix:**
- `uploadFilesToDevice` now returns `Promise<boolean>` and surfaces errors via a new `uploadError` state shown inline in the picker (red text under the device buttons).
- `handleDeviceSelect` only transitions to `accepted` + auto-hides when upload succeeded; on error the picker stays open so the user can retry / see the message.
- `target: targetDeviceId ?? undefined` so missing target gets dropped from the IPC payload (Tauri serdes missing `Option<String>` field as `None`).

### 2.5 `connection::send_raw` failure was being ignored in `upload_file`
**File:** [desktop/src-tauri/src/lib.rs:275](desktop/src-tauri/src/lib.rs)
**Was:** `let _ = connection::send_raw(json);` — file uploaded fine, but if the WS was momentarily disconnected the `file_meta` notification was dropped silently and Android never learned about the file.
**Now:** `connection::send_raw(json).map_err(|e| format!("File uploaded but failed to notify peers: {e}"))?;` — surfaces the failure all the way up to the overlay UI.

### 2.6 Relay observability — message-router logging
**File:** [relay/src/message-router.js](relay/src/message-router.js)
Two log lines uncommented / added:
- Every route logs `[MessageRouter] Routing <type> from <8hex> to <to>`
- Targeted routing logs `Targeted <type> → <8hex> SENT` **or** `QUEUED (offline/missing, state=<n>)`

This was the key diagnostic that narrowed the bug down (see §3).

---

## 3. Current state — the actual bug

After the fixes above, the user reproduced and we captured this from the relay:

```
[MessageRouter] Routing file_meta from fbfd77f4 to 89fa931c-2042-42c2-9dfd-63bd11442801
[MessageRouter] Targeted file_meta → 89fa931c SENT
[MessageRouter] Routing ping from fbfd77f4 to broadcast
[RoomManager] broadcast ping → 1 recipient(s) in room 9b1893d1 (room size: 2)
...
```

**Conclusions from these lines:**
1. Windows (`fbfd77f4`) sends `file_meta` targeted at the exact Android UUID (`89fa931c-2042-42c2-9dfd-63bd11442801`).
2. Both devices are in the same room (`9b1893d1`), size 2.
3. The relay **found Android's socket** (state was `OPEN` / 1, otherwise it would have logged `QUEUED`).
4. The relay **wrote `messageStr` to Android's socket** via `targetSocket.send(messageStr)`.
5. **Android does not render the file in the Files tab.**

So the bug is somewhere between *bytes leaving the relay socket* and *the Files tab recomposing*. Code-reading on the Android side has not identified the failure — see §4 for what's been ruled out, and §5 for the next concrete diagnostic step.

---

## 4. What's been ruled out (Android side)

Code paths inspected via the `.code-review-graph/graph.db` Kuzu-style graph DB (extremely useful — use it for the next session too):

| Component | File | Conclusion |
|---|---|---|
| `RelayClient.onMessage` | [android/.../network/RelayClient.kt:103](android/app/src/main/kotlin/com/livec/app/network/RelayClient.kt) | Calls `Message.parse(text)`, skips PING/PONG, forwards to `onMessage(msg)` → `LiveCService.handleMessage`. No filtering. |
| `Message.parse` | [android/.../data/Message.kt:27](android/app/src/main/kotlin/com/livec/app/data/Message.kt) | Wrapped in try/catch returning null. Only fails if `type` field missing — confirmed present in Rust serializer. |
| `LiveCService.handleMessage` filters | [android/.../service/LiveCService.kt:219](android/app/src/main/kotlin/com/livec/app/service/LiveCService.kt) | `msg.from != deviceId` ✓; `msg.to == deviceId` for targeted (`89fa931c-…`) ✓; `markSeen` synchronized, returns true on first delivery. |
| `FILE_META` branch | [android/.../service/LiveCService.kt:306](android/app/src/main/kotlin/com/livec/app/service/LiveCService.kt) | Calls `AppState.addTransfer(...)` then `postFileNotification(...)`. No early returns. |
| `AppState.addTransfer` | [android/.../data/AppState.kt:40](android/app/src/main/kotlin/com/livec/app/data/AppState.kt) | `_transfers.update { (listOf(item) + it).take(50) }` on a `MutableStateFlow`. Thread-safe. |
| `HomeScreen` Files tab | [android/.../ui/screens/HomeScreen.kt:113](android/app/src/main/kotlin/com/livec/app/ui/screens/HomeScreen.kt) | `transfers by vm.transfers.collectAsStateWithLifecycle()` → `FilesTabContent(transfers = transfers, …)`. No source/recipient filtering anywhere — every transfer in state should render. |

**Nothing in the static code path explains the symptom.** The dedup ring (`seenIds`) is the most suspicious dynamic mechanism, but it requires the message ID to have been *already* marked seen, which can only happen if the LAN client delivered the same `id` first.

---

## 5. Next concrete step (the one we didn't get to)

**Run `adb logcat` on the device while reproducing.** Tag filter from [handoff.md §2 Android](handoff.md):

```bash
adb logcat -s LiveCService:* RelayClient:* LanClient:* LanDiscovery:*
```

In `handleMessage` there are already two log lines that will decisively answer the question:

```kotlin
Log.d(TAG, "Dedup drop ${msg.type} id=${msg.id.take(8)}")   // dedup ring dropped it
Log.d(TAG, "Handle ${msg.type} id=${msg.id.take(8)} from=${msg.from.take(8)}")  // accepted, processing
```

**Three possible outcomes:**

1. **`Handle file_meta …` appears, but the file still doesn't render.**
   → Bug is in the Compose collection / recomposition. Suspect: viewmodel scope, lifecycle pausing, or `selectedTab` not on the Files tab when the user looks. Add a transient counter overlay (`"${transfers.size} transfers"` in `LiveCTopBar`) as a quick sanity probe.

2. **`Dedup drop file_meta …` appears for the relay copy.**
   → LAN client *did* deliver the same ID first. Then check whether the LAN-delivered copy actually made it into `AppState.transfers` — if not, we have an exception swallowed somewhere in the LAN path's `handleMessage` (FILE_META block) that marks the ID as seen but fails before `addTransfer`. Wrap the `FILE_META` branch in try/catch with logging.

3. **Neither log line appears at all.**
   → The bytes the relay sent never reached `RelayClient.onMessage`. Either `Message.parse` returned null (corrupt JSON, header-related), or the OkHttp socket isn't the active one (stale connection). Have `RelayClient.onMessage` log the raw `text` length and first 80 chars regardless of parse outcome.

---

## 6. Useful artifacts to re-use next session

### The graph DB
**`D:\LiveC\.code-review-graph\graph.db`** — SQLite, ~210 MB. Tables: `nodes`, `edges`, `flows`, `communities`, `nodes_fts*`, `flow_memberships`, `metadata`.

Cheat-sheet queries:

```sql
-- Find a symbol fast
SELECT qualified_name, file_path, line_start, line_end
FROM nodes WHERE name LIKE '%SomeName%'
  AND file_path NOT LIKE '%node_modules%'
ORDER BY file_path, line_start;

-- Trace edges in/out of a function
SELECT source_qualified, target_qualified, kind, line
FROM edges
WHERE source_qualified LIKE '%handleMessage%'
   OR target_qualified LIKE '%handleMessage%';

-- All nodes in a file
SELECT qualified_name, line_start, line_end
FROM nodes WHERE file_path LIKE '%HomeScreen.kt'
ORDER BY line_start;
```

This saved many full-file reads. **Use it instead of grepping for symbols.**

### Relay logging
Currently very verbose (every routed message logs). Quiet it again with:
```js
// console.log(`[MessageRouter] Routing ${type} from ${from.slice(0,8)} to ${to}`);
```
in `relay/src/message-router.js` once debugging is done. Keep the targeted SENT/QUEUED log — it's load-bearing for any future "Android isn't getting messages" investigation.

### Background relay process
The dev relay is currently running as a backgrounded `node src/server.js` from PowerShell `Start-Process`. PID drift was observed (the netstat-reported PIDs didn't match the spawned PID). To kill before next session:
```powershell
Get-Process node -ErrorAction SilentlyContinue | Stop-Process -Force
```

---

## 7. Open follow-ups (not blockers)

- **Offline-queue test failure** in `relay/test.js` (`Error: Failed offline queue`). The first three local WS connections all work; the fourth assertion about queued delivery to a late-joining device fails. Not related to the current bug. Pre-existing.
- **Cloudflared config port mismatch.** The named tunnel routes `hybridforge.xyz → :8090` but the relay is on `:3000`. Verify on next session whether the user updated the config, or whether something on `:8090` is acting as a forwarder. If neither, the working relay path is a mystery.
- **Drop shelf "All devices" vs specific device.** The picker offers both. Browse button is always broadcast. If §5's diagnosis shows that targeted delivery is somehow broken end-to-end while broadcast works, the simplest workaround is to make the drop shelf picker also broadcast (treat device buttons as cosmetic) until the targeted path is fixed.
- **Cargo.toml rustls change is dev-built only.** Confirm it's still right for the production bundle; `rustls-tls-webpki-roots` adds ~1.5 MB to the binary vs `native-tls`. Worth it for cross-platform reliability.
