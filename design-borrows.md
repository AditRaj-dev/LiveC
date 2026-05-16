# LiveC — Design Doc: Borrows from LocalSend & Blip

**Status:** Proposal
**Last updated:** 2026-05-15
**Author:** —
**Owner:** —

This doc proposes a set of architectural changes to LiveC that adopt the best ideas from two adjacent open-source / commercial products: **LocalSend** (LAN-only, open source, Flutter) and **Blip by Plex** (cloud-relayed, closed source, account-based).

The intent is **not** to copy either app wholesale. LiveC's hybrid LAN + relay architecture is already a strict superset of both on the network axis. What's missing is operational polish around file lifecycle, identity, and large transfers — areas where both reference apps are more mature.

---

## 1. Goals

1. Support **GB-scale file transfers** without OOM, without 90-second TTL races, and with resume across network drops.
2. Replace the implicit "trust whoever has the room token" model with **per-device identity** that survives token rotation.
3. Move file transfer to a **two-phase protocol** so receivers can accept/reject before bytes flow over the wire.
4. Stop the relay from sitting on uploaded files that nobody asked for. Free disk faster, charge less for relay hosting.
5. Lay the groundwork for a future **web client** and **background async delivery** without changing the protocol again.

## 2. Non-goals

- End-to-end encryption (separate proposal — see `design-e2e.md` if/when written).
- Multi-room / device-group support.
- Replacing the LAN path (mDNS + direct WS) — it already works and stays as the fast path.
- Replacing WebSocket as the control transport.
- Adding user accounts.

---

## 3. What we borrow, and from whom

| Feature | Source | Why we want it |
|---|---|---|
| Per-device TLS cert + fingerprint as identity | LocalSend | Stronger than random UUID. Survives room rotation. Enables "trusted device" lists. |
| Two-phase `prepare-upload` → `upload` REST flow | LocalSend | Receiver approves before bytes flow. No orphaned files on relay. |
| Streaming raw-body upload (no multipart) | LocalSend | Simpler server, no multer, smaller dep tree, easier to stream GB-scale. |
| Quick-mode for trusted devices | LocalSend | Skip approval UI for previously-trusted device IDs. |
| HMAC-signed download URLs scoped to recipient | Blip | Only the intended recipient can fetch. Defends against URL leakage. |
| Resumable chunked upload (TUS protocol) | Blip | Survive network drops. Necessary above ~1GB on consumer connections. |
| Days-scale TTL with explicit completion | Blip | Async delivery becomes a feature, not a race. |
| Background / queued sync model | Blip | Offline recipient can still receive when they come online. |
| Web client / signed-URL link sharing | Blip | "Send to someone without the app" — zero-install receiver. |
| Per-file token (not just session token) | LocalSend | Granular cancel/reject per file in a multi-file send. |

Each is broken out in detail below.

---

## 4. Design

### 4.1 Per-device fingerprint identity

**Today.** Each device generates a random `device_id` (UUID v4) on first launch ([`desktop/src-tauri/src/config.rs:44`](desktop/src-tauri/src/config.rs), `android/app/src/main/kotlin/com/livec/app/data/ConfigStore.kt`). The relay trusts whoever connects with a known `roomToken`. If the token leaks, anyone can join.

**Proposed.** On first launch, each device additionally generates a long-lived ED25519 keypair. Public key fingerprint (SHA-256, first 16 bytes hex) becomes the device's stable identity, separate from the per-install `device_id`.

```
AppConfig {
  device_id:        Uuid                   // ephemeral install ID (unchanged)
  device_pubkey:    [u8; 32]               // ED25519 public key (new)
  device_privkey:   [u8; 32]               // stored in OS keychain, never config (new)
  fingerprint:      String                 // sha256(pubkey)[..16] hex (new)
  trusted_peers:    Vec<TrustedPeer>       // (new)
  ...
}

TrustedPeer {
  fingerprint: String       // their public-key fingerprint
  device_name: String       // last-seen friendly name
  added_at:    u64
  quick_mode:  bool         // auto-accept files from this peer
}
```

**Where keys live:**
- Windows: Windows Credential Manager (`windows-rs` already in deps)
- Android: Android Keystore (`KeyStore.getInstance("AndroidKeyStore")`)
- The public key + fingerprint can sit in plain config.

**How pairing changes.** Today the QR encodes `{ relayUrl, roomToken }`. Proposed:
```json
{
  "relayUrl": "wss://example.com/ws",
  "roomToken": "abc12345",
  "fingerprint": "8f4a3c..."   // initial host's fingerprint, pinned by joiner
}
```
The joining device pins this fingerprint as trusted on first contact. Subsequent device_join messages from peers carry their own fingerprint, and the user approves once per new peer (toast / in-app prompt).

**What this unlocks.** Even if the room token leaks, untrusted devices can join the room but can't send/receive without explicit approval. Trusted device lists survive token rotation. Trust is portable across app reinstalls (export/import key on power-user request).

**Files touched:**
- [`desktop/src-tauri/src/config.rs`](desktop/src-tauri/src/config.rs) — add fields, key generation
- [`desktop/src-tauri/src/protocol.rs`](desktop/src-tauri/src/protocol.rs) — add `fingerprint` to `device_join` payload
- [`android/app/src/main/kotlin/com/livec/app/data/ConfigStore.kt`](android/app/src/main/kotlin/com/livec/app/data/ConfigStore.kt) — Keystore wiring
- [`android/app/src/main/kotlin/com/livec/app/data/Message.kt`](android/app/src/main/kotlin/com/livec/app/data/Message.kt) — `Message.deviceJoin` payload
- [`relay/src/room-manager.js`](relay/src/room-manager.js) — pass fingerprint through

---

### 4.2 Two-phase upload (prepare → upload)

**Today.** Single-shot: client POSTs the file to `/upload`, relay stores it, client broadcasts `file_meta` via WS. Receiver pulls from `downloadUrl`. Bytes hit the relay before the receiver has consented.

**Proposed.** Borrow LocalSend's two-phase shape:

```
1. Sender → Relay (WS):
   {
     type: "file_offer",
     from: <sender_fingerprint>,
     to:   <recipient_fingerprint or "broadcast">,
     payload: {
       offerId: "uuid",
       files: [
         { fileId: "uuid", name: "...", size: 4123456789,
           sha256: "...", mimeType: "video/mp4" }
       ]
     }
   }

2. Relay → Recipient (WS):
   Same envelope, routed by 'to' (already supported).

3. Recipient UI prompts user (or auto-accepts if quick_mode):
   Accept ▸ ▸ ▸  Reject

4. Recipient → Relay (WS):
   {
     type: "file_accept" | "file_reject",
     payload: {
       offerId: "uuid",
       fileIds: ["uuid", ...]   // subset, recipient can cherry-pick
     }
   }

5. Relay → Sender (WS):
   Forwarded. On accept, payload includes the signed upload endpoint:
   {
     type: "file_accept",
     payload: {
       offerId, fileIds,
       uploadEndpoint: "https://relay/upload/<offerId>",
       uploadToken: "<jwt-or-hmac>"
     }
   }

6. Sender uploads each accepted file via PUT with Bearer token (see §4.3).

7. Relay → Recipient (WS):
   {
     type: "file_ready",
     payload: { fileId, downloadUrl: "<signed url scoped to recipient>" }
   }

8. Recipient downloads, then:
   Recipient → Relay (WS):
   {
     type: "file_done",
     payload: { fileId }
   }

9. Relay deletes the file immediately (no TTL race).
```

**State machine on the relay** (per offer):
```
PENDING  -- file_accept --> ACCEPTED -- upload started --> UPLOADING
                       \                                       |
                        \-- file_reject --> REJECTED          | upload complete
                                              (drop offer)    v
                                                          AVAILABLE
                                                              |
                                                              | file_done OR ttl
                                                              v
                                                           DELETED
```

**Benefits over today's flow:**
- No bytes on disk until accepted.
- Receiver sees the offer **immediately** (not after upload finishes).
- Multi-file offers can be partially accepted.
- Cancel-mid-upload is a real concept (relay drops the partial file when offer is canceled).
- Relay disk usage scales with accepted-but-undelivered transfers, not "everything anyone tried to send."

**Backwards compatibility.** Keep the old `/upload` + `file_meta` flow for one release as a fallback path. Tag new clients via WS handshake; relay enforces two-phase for v2 clients.

**Files touched:**
- [`relay/src/server.js`](relay/src/server.js) — new endpoints, route for `PUT /upload/:offerId/:fileId`
- New file: `relay/src/transfer-manager.js` — owns the state machine
- [`relay/src/file-store.js`](relay/src/file-store.js) — replace multer-based handler with streaming PUT
- [`relay/src/message-router.js`](relay/src/message-router.js) — route `file_offer` / `file_accept` / `file_reject` / `file_ready` / `file_done`
- [`relay/src/protocol.js`](relay/src/protocol.js), [`desktop/src-tauri/src/protocol.rs`](desktop/src-tauri/src/protocol.rs), [`desktop/src/protocol.ts`](desktop/src/protocol.ts), [`android/app/src/main/kotlin/com/livec/app/data/Protocol.kt`](android/app/src/main/kotlin/com/livec/app/data/Protocol.kt) — all four mirrors get the new message types
- [`desktop/src-tauri/src/lib.rs`](desktop/src-tauri/src/lib.rs) — `upload_file` becomes a state-machine driver, not a one-shot
- [`desktop/src/hooks/useLiveC.ts`](desktop/src/hooks/useLiveC.ts) — `useFileTransfers` adds "incoming offer" state with accept/reject buttons
- [`android/app/src/main/kotlin/com/livec/app/service/LiveCService.kt`](android/app/src/main/kotlin/com/livec/app/service/LiveCService.kt) — incoming offer notification with action buttons

---

### 4.3 Streaming PUT upload (no multipart)

**Today.** Sender reads the full file into RAM ([`desktop/src-tauri/src/lib.rs:235`](desktop/src-tauri/src/lib.rs) `std::fs::read(&path)`), wraps in multer multipart form, POSTs to `/upload`. Relay uses multer with disk storage. Practical ceiling ~500MB before RAM pressure on the sender.

**Proposed.** Plain HTTPS PUT, body is the raw file stream, headers carry metadata. LocalSend uses exactly this shape.

```
PUT /upload/<offerId>/<fileId>?token=<jwt>
Authorization: Bearer <upload_token>
Content-Type: <mimeType>
Content-Length: <size>
Content-Range: bytes 0-<size-1>/<size>     # optional, for resumable (see §4.4)
X-LiveC-SHA256: <hex>

<file bytes streamed>
```

**Sender side (Rust):**
```rust
let file = tokio::fs::File::open(&path).await?;
let size = file.metadata().await?.len();
let stream = tokio_util::io::ReaderStream::new(file);
let body = reqwest::Body::wrap_stream(stream);

client.put(&upload_url)
    .header("Authorization", format!("Bearer {token}"))
    .header("Content-Type", &mime)
    .header("Content-Length", size)
    .header("X-LiveC-SHA256", &sha256_hex)
    .body(body)
    .send().await?;
```

**Sender side (Android/OkHttp):**
```kotlin
val body = file.asRequestBody(mime.toMediaType())  // streams, doesn't load
client.newCall(Request.Builder()
    .url(uploadUrl)
    .header("Authorization", "Bearer $token")
    .header("X-LiveC-SHA256", sha256Hex)
    .put(body)
    .build()).execute()
```

**Relay side (Node, replacing multer):**
```js
app.put('/upload/:offerId/:fileId', authUploadToken, async (req, res) => {
    const expected = req.headers['x-livec-sha256'];
    const size = parseInt(req.headers['content-length']);
    const writeStream = fs.createWriteStream(targetPath);
    const hash = crypto.createHash('sha256');
    let received = 0;

    req.on('data', (chunk) => { hash.update(chunk); received += chunk.length; });
    req.pipe(writeStream);

    await once(writeStream, 'finish');
    if (hash.digest('hex') !== expected) {
        fs.unlink(targetPath, () => {});
        return res.status(400).json({ error: 'sha256 mismatch' });
    }
    transferManager.markUploaded(req.params.offerId, req.params.fileId);
    res.json({ ok: true });
});
```

**What gets deleted from the codebase:**
- `multer` dependency in [`relay/package.json`](relay/package.json)
- Multer-specific error handling in [`relay/src/server.js`](relay/src/server.js)
- Multipart `Form` construction in `upload_file` / `upload_screenshot` ([`desktop/src-tauri/src/lib.rs`](desktop/src-tauri/src/lib.rs))
- Multipart `MultipartBody.Builder` in `uploadAndBroadcast` ([`android/.../LiveCService.kt`](android/app/src/main/kotlin/com/livec/app/service/LiveCService.kt))

**What we gain:**
- Streaming sender → streaming server → streaming receiver. No file ever fully in RAM.
- Smaller relay binary, fewer deps to audit.
- SHA-256 verification built in (today: nothing).
- Cleaner code on all three clients.

---

### 4.4 Resumable uploads (TUS-style)

For files >1GB, network drops are inevitable. We add resume on top of §4.3 using a subset of the TUS protocol.

**New endpoint:**
```
HEAD /upload/<offerId>/<fileId>
Authorization: Bearer <upload_token>
→ Upload-Offset: <bytes-received-so-far>
  Upload-Length: <total-size>
```

**Sender flow on retry:**
```
1. HEAD to discover received offset
2. PATCH /upload/<offerId>/<fileId> with:
   Upload-Offset: <offset>
   Content-Type: application/offset+octet-stream
   <remaining bytes starting at offset>
3. Server appends to existing file, recomputes hash incrementally
```

**Chunking strategy:**
- Default chunk: 8MB
- Sender pipelines chunks but tracks last-acknowledged offset
- On disconnect: HEAD → resume from acknowledged offset
- On chunk failure: retry only that chunk

**Cloudflare interaction.** Each PATCH is one HTTP request bounded by Cloudflare's 100-second timeout. At 5 Mbps sustained, 8MB takes ~13 seconds — well within budget. At 100 Mbps, the whole upload happens in seconds anyway.

**Use the `tus-node-server` library** rather than rolling this. Drop-in Express middleware.

**Files touched:**
- New: `relay/src/tus-handler.js` (thin wrapper around tus-node-server)
- [`relay/package.json`](relay/package.json) — add `@tus/server`
- Sender clients: chunked PUT loop with HEAD-on-retry

---

### 4.5 HMAC-signed download URLs scoped to recipient

**Today.** `downloadUrl` is `https://relay/download/<fileId>`. Anyone with the URL gets the file for 90 seconds. The fileId is a UUID so the URL space is large, but URLs are routinely logged by proxies, CDNs, share-sheets, etc.

**Proposed.** When a file becomes available (§4.2 step 7), the relay generates a URL signed for the intended recipient:

```
https://relay/download/<fileId>?
  exp=<unix>&
  to=<recipient_fingerprint>&
  sig=<HMAC_SHA256(secret, "<fileId>|<exp>|<to>")>
```

**Server (relay) holds the HMAC secret.** Verifies signature + expiry + that the requesting WS connection (or attached cookie/header) is from the device matching the `to` fingerprint.

**Auth for download.** Three options, pick one:
- **a) URL params only** — simple, but anyone with the URL still works. Use only if `to` check is loose.
- **b) Bearer token header** — recipient gets the download token via WS, sends as `Authorization: Bearer`. Strongest. Requires headers, so plain `<a href>` won't work in a browser.
- **c) Short-lived cookie** — relay sets a cookie at WS-connect time scoped to the recipient. Browser-friendly. Mobile clients ignore it. Pick (b) for native, (c) for web.

**Recommendation:** Bearer token header for native clients (Tauri/Android both use reqwest/OkHttp, easy). Add (c) when the web client lands.

**Why this matters.** If a future feature ever logs URLs (debug logging, error reporting, etc.) the leak doesn't expose files to the open internet. Defense in depth.

**Files touched:**
- New: `relay/src/url-signer.js` — HMAC sign/verify helpers, env var `LIVEC_SIGNING_SECRET`
- [`relay/src/file-store.js`](relay/src/file-store.js) — `handleDownload` calls `verifySignedUrl` first
- All three clients — `download_file` / `downloadFile` adds bearer header

---

### 4.6 Days-scale TTL with explicit completion

**Today.** Hard TTL of 90 seconds in [`relay/src/protocol.js`](relay/src/protocol.js). Cleanup runs every 30 seconds. Files vanish whether anyone got them or not.

**Proposed.** TTL becomes a safety net, not the primary lifecycle event.

| Event | Action |
|---|---|
| Offer accepted by recipient | Start tracking |
| Upload complete | File becomes downloadable, set `expires_at = now + 7 days` |
| First successful download | If single-recipient offer: delete immediately on `file_done`. If broadcast offer: mark recipient as fetched. |
| All recipients fetched | Delete immediately |
| `expires_at` reached without all fetches | Delete, emit `file_expired` to sender for any unfetched recipients |
| Offer rejected | Delete (if uploaded) and drop offer |
| Sender cancels | Delete and drop offer |

**Constants (in `protocol.js` and mirrors):**
```js
const LIMITS = {
  MAX_FILE_BYTES: 10 * 1024 * 1024 * 1024,   // 10 GB (was 100 MB)
  FILE_TTL_MS:    7 * 24 * 60 * 60 * 1000,   // 7 days (was 90 sec)
  OFFER_TTL_MS:   24 * 60 * 60 * 1000,       // unaccepted offers: 24h
  OFFLINE_QUEUE_TTL_MS:           7 * 24 * 60 * 60 * 1000,  // also 7d
  OFFLINE_QUEUE_MAX_PER_DEVICE:   200,
};
```

**Disk pressure.** Real risk. Mitigations:
- Per-room storage cap (e.g. 50GB per active room)
- Per-sender daily quota
- Lazy upload (§4.2 already prevents unauthorized files)
- Operators can tune via env vars

**Files touched:** [`relay/src/protocol.js`](relay/src/protocol.js) and three mirrors, plus the new `transfer-manager.js`.

---

### 4.7 Background async delivery

**Today.** Android's `LiveCService` is foreground-only. Recipient must be online when sender uploads, or the upload is wasted (file expires in 90s; receiver's WS reconnect re-delivers `file_meta` but the URL 404s).

**Proposed.** With §4.6's 7-day TTL and the offline queue's same TTL, the relay holds onto:
- The accepted file
- The `file_ready` notification

When recipient reconnects, queue flush delivers `file_ready`, recipient downloads.

**Sender UX:** "Sent to Mike's Pixel — will deliver when device is online."

**Receiver UX (Android):** Push-style notification when WS reconnects and `file_ready` arrives.

This is mostly an emergent property of doing §4.2 + §4.6 correctly. Code-side: no changes to the queue, just trust the TTLs.

One gap: when recipient is offline, the `file_offer` queues fine, but it's purely informational. The sender's actual upload should be deferred until accept — which means the sender keeps `file_offer` "pending" in their UI for up to OFFER_TTL_MS, then expires it. Sender's client needs persistent state for pending offers (already exists via `livec.transfers` localStorage on desktop; needs equivalent on Android).

---

### 4.8 Per-file token within a multi-file offer

**Today.** A multi-file send is just N independent `/upload` calls. Canceling means manually invoking `delete_relay_file` for each.

**Proposed.** Already implicit in §4.2 — each file in an offer has its own `fileId`. Each upload PUT uses a token that encodes `(offerId, fileId)`. Canceling one cancels just that file. Receiver can accept files 1, 3, 5 and reject 2, 4.

UI change: the device picker in [`OverlayApp.tsx`](desktop/src/OverlayApp.tsx) becomes a two-step flow when multi-drop:
1. Pick recipient → send offer with all files
2. Wait for accept message → show per-file progress

The current single-recipient + auto-broadcast UX still works — those are degenerate cases of the multi-file flow.

---

### 4.9 Web client (future)

Out of scope for this round but worth designing for.

**Sketch:**
- Static SPA served from the relay at `/` (separate route from `/upload`, `/download`, `/ws`)
- Generates a device keypair in browser `crypto.subtle.generateKey`, stores in IndexedDB
- Joins via QR scan (camera) or pasted link
- Receives via WS like any other client
- Downloads via signed URLs (with cookie auth, §4.5 option c)
- Sends via streaming PUT (browser supports `fetch` with `ReadableStream` bodies for upload)

**Why this works only after §4.1–§4.6:**
- Fingerprint identity = web client doesn't need an account
- Streaming PUT = browser can upload large files
- Two-phase = receiver can approve from the web UI
- Signed URLs + cookies = browser auth without sketchy URL-only access

**Probable stack:** Vite + React + the same `protocol.ts` mirror this codebase already has. Lots of code reuse.

---

## 5. Phasing

Implementing all of §4 in one PR is reckless. Suggested order:

### Phase 0 — Streaming, no protocol change (1 day)
Just §4.3 sender-side. Switch `upload_file` / `uploadAndBroadcast` / `upload_screenshot` to streaming bodies. Keeps multer on the server. Eliminates client-side RAM pressure. Bump `MAX_FILE_BYTES` to 1GB.

**Risk:** very low. No protocol change. Drop-in.

### Phase 1 — Server streaming + TTL bump (2 days)
Drop multer. Switch relay to busboy or raw PUT. Bump `FILE_TTL_MS` to 1 hour, file size cap to 5GB. Still single-phase upload + WS notify.

**Risk:** low. Backwards compatible with phase-0 clients via dual endpoints.

### Phase 2 — Two-phase protocol + per-file tokens (1 week)
§4.2 + §4.8. Add `file_offer` / `file_accept` / `file_reject` / `file_ready` / `file_done`. Implement state machine. All three clients learn the new flow. Old flow kept behind a feature flag.

**Risk:** medium. UI work on both clients. State machine bugs likely.

### Phase 3 — Signed URLs + 7-day TTL (3 days)
§4.5 + §4.6. Add HMAC signing, bearer auth on downloads. Move TTL to background safety net.

**Risk:** low once Phase 2 lands. Mostly server-side.

### Phase 4 — Resumable uploads (1 week)
§4.4. Adopt tus-node-server. Update clients to chunk + HEAD-on-retry.

**Risk:** medium. New dep. Testing on flaky networks.

### Phase 5 — Fingerprint identity (1 week)
§4.1. Generate keys, plumb fingerprint through `device_join`, update QR. Trusted peer UI. Quick-mode toggle per peer.

**Risk:** medium. Key storage is OS-specific. Migration of existing installs needs care.

### Phase 6 — Web client (open-ended)
§4.9. Greenfield. No risk to existing flows.

---

## 6. Trade-offs and risks

### Things this design intentionally does **not** solve

- **End-to-end encryption.** The relay sees plaintext file bytes. Adding E2E means uploading ciphertext + sharing keys out-of-band via the WS channel. Worth doing — separate doc.
- **Multi-recipient broadcasts of large files.** Today's broadcast = N×upload bandwidth. Real fix is a single-upload + N-download fan-out, which today's flow approximates. Doesn't get worse here.
- **Cross-device device-list sync.** If you trust device A on your PC, your phone still has to trust it independently. Acceptable for now.
- **Account recovery.** No accounts = no recovery. If you lose all paired devices, you start over. Same as today.

### New risks introduced

- **Disk pressure on the relay.** 7-day TTL × 10 active rooms × 10GB each = 700GB. Mitigate with per-room quotas.
- **HMAC secret rotation.** Need an env var + restart strategy. Active downloads must complete or fail gracefully when secret rotates.
- **Key loss on the device side.** Lose the Keystore entry, lose your identity. Recovery = pair again, re-trust on all peers. Acceptable.
- **Protocol versioning.** Mixing v1 and v2 clients during phase 2 is painful. Recommend a hard cutover via the `device_join` payload carrying a `protocolVersion` field. Older clients see "please update" toast.

### Compatibility matrix during rollout

| Sender | Receiver | Behavior |
|---|---|---|
| v1 (old) | v1 (old) | Works as today (legacy path) |
| v2 (new) | v1 (old) | v2 sender falls back to legacy `/upload` + `file_meta` |
| v1 (old) | v2 (new) | v2 receiver handles `file_meta` as before; no offer UI |
| v2 | v2 | Full two-phase flow |

The fallback path is the legacy code; keep it for one full release after v2 ships, then remove.

---

## 7. Open questions

1. **Bearer-token auth for downloads on Android — how to inject the header into a system DownloadManager?** May need to pre-fetch the bytes in `LiveCService` and write to the user-visible location ourselves, instead of relying on system download UX.

2. **Multi-file drag-and-drop on the desktop — is the UX still "pick recipient once"?** Or one picker per file? Lean toward once per drag, accepted/rejected per file on the receiver side.

3. **Should the relay keep file SHA-256 hashes for dedup?** If a user sends the same 4GB file twice, can the server short-circuit the second upload? Probably yes, but it's optional and adds DB.

4. **TUS or roll-our-own resumable?** TUS is the standard, has client libs in JS/Go/Java/Rust. Custom is simpler but more bugs. Recommendation: TUS.

5. **What happens if the sender goes offline mid-upload of an accepted offer?** The relay holds the partial bytes for OFFER_TTL_MS (24h). Sender resumes on reconnect via TUS HEAD. After 24h, partial is dropped.

6. **Quick-mode trust scope — per-peer or per-peer-per-file-type?** LocalSend does per-peer. Probably enough.

---

## 8. Out-of-scope follow-ups

- Streaming clipboard images instead of base64 over LAN (improves big-image sync)
- Mesh routing when one device is on LAN and another is on relay (e.g., PC bridges phone-A LAN to phone-B over relay)
- Server-sent push notifications via APNs/FCM for the web client and background mobile delivery
- iOS client (entire app, separate effort)

---

## 9. References

- LocalSend: https://github.com/localsend/localsend — see `protocol/v2/` for the prepare-upload pattern
- TUS spec: https://tus.io/protocols/resumable-upload
- tus-node-server: https://github.com/tus/tus-node-server
- LiveC current protocol: [`PROTOCOL.md`](PROTOCOL.md)
- LiveC current handoff: [`handoff.md`](handoff.md)
