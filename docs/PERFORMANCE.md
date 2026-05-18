# LiveC — Performance Roadmap

Optimizations queued for the free-Render-tier deployment. Ordered by user-visible impact.

## Baseline (today)

- Sequential 1 MB PATCH chunks
- Pre-hash the full file before the first chunk goes out
- HTTP/1.1, no keep-alive (fresh TCP per chunk — was needed to dodge Cloudflare HTTP/2 stalls)
- Relay buffers each PATCH body fully in RAM, then `transfer-manager.appendChunk` writes synchronously
- Render's free web service idle-sleeps after 15 min → next request waits ~30 s for cold start

For a 60 MB APK over a typical residential link + Render free tunnel, that's roughly:
- ~60 round-trips × (one TCP handshake + 1 MB body)
- 1-1.5 s wall time per chunk on a 10 Mbps up link
- Total: **60–90 s** for the upload + 30 s cold start if idle.

---

## 1. Keep-alive ping → eliminate Render cold-start

**Impact:** removes the perceived 30 s wait on first send after idle.

**Why:** Render free tier sleeps after 15 minutes of no inbound HTTP traffic. WebSocket pings don't count against the inactivity timer because Render measures *HTTP* traffic for free web services. First action after sleep blocks ~30 s while the container boots.

**Approach:** add a tiny HTTP GET to `/health` every ~10 min from the app (desktop background task or Android `LiveCService` heartbeat). Use a 5 s timeout so a sleeping service doesn't block the heartbeat task — the request being made is what wakes the dyno.

**Sketch (Rust, in `connection.rs` reconnect loop):**
```rust
tokio::spawn(async move {
    let mut tick = tokio::time::interval(Duration::from_secs(10 * 60));
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(5))
        .build().unwrap();
    let health = format!("{}/health", relay_to_http_base(&relay_url));
    loop {
        tick.tick().await;
        let _ = client.get(&health).send().await;
    }
});
```

**Sketch (Kotlin, in `LiveCService.onCreate`):**
```kotlin
lifecycleScope.launch(Dispatchers.IO) {
    while (isActive) {
        try { OkHttpClient().newCall(Request.Builder().url("$httpBase/health").build()).execute().close() } catch (_: Exception) {}
        delay(10 * 60 * 1000L)
    }
}
```

**Risk:** none. `/health` is already exposed, returns 200 with a tiny JSON body.

**Effort:** ~15 minutes.

---

## 2. Bump `CHUNK_SIZE` back up

**Impact:** ~1.5× fewer round-trips → noticeable on slow uplinks where per-request RTT dominates.

**Why:** we dropped to 1 MB to dodge stalls that turned out to be caused by HTTP/2 + keep-alive (now fixed). 1 MB is overly conservative now.

**Recommended:** **4 MB**. Sits well under the 100 MB Cloudflare body limit, fits comfortably in the 512 MB Render RAM (with 4 concurrent PATCHes = 16 MB RAM), and cuts a 60 MB upload from 60 chunks → 15.

**Where:** the four protocol mirrors (`relay/src/protocol.js`, `desktop/src-tauri/src/protocol.rs`, `desktop/src/protocol.ts`, `android/.../Protocol.kt`) plus the matching `CHUNK_SIZE` constant in each.

**Risk:** if Cloudflare stalls reappear at 4 MB, drop back to 2 MB. The `http1_only` + `pool_max_idle_per_host(0)` fix should have removed the underlying cause.

**Effort:** 5 minutes.

---

## 3. Concurrent PATCH chunks → biggest theoretical speedup

**Impact:** 3-4× on the upload phase itself, assuming the link has bandwidth headroom (most home uplinks do — sequential single-chunk uploads usually leave bandwidth on the table because of TCP slow-start + HTTP request setup per chunk).

**Why:** today every chunk is one-at-a-time. Pipelining 4 chunks fills the network pipe much better, especially over Render's slightly higher RTT.

**Blocker:** the current `transfer-manager.appendChunk` rejects any PATCH whose `Upload-Offset` doesn't equal the server's `bytesReceived`. That's enforced for byte-level integrity but kills parallelism.

**Approach:** replace the offset-equality check with a "write at offset" model.

### Relay changes (`transfer-manager.js`)
- Open the file with `fs.open(path, 'w')` on the first chunk and keep the fd.
- For each PATCH, do `fs.write(fd, chunk, 0, chunk.length, expectedOffset)` — `pwrite` semantics, writes at a specific position.
- Track a `Set<offset>` of completed chunks. Mark `complete = true` when `Σ chunk sizes == declared size`.
- Drop the incremental SHA-256 hasher; instead, after `complete`, schedule a one-shot post-upload verification: stream the file through `crypto.createHash('sha256')` and compare to the offer's declared hash. On mismatch, delete + send `file_expired` to sender so they can retry.

```js
async function appendChunk(offerId, fileId, expectedOffset, chunk) {
  const offer = offers.get(offerId);
  // ... existing validation, drop the bytesReceived == expectedOffset check ...
  if (!file.fd) {
    file.fd = await fs.promises.open(file.diskPath, 'w').then(h => h.fd);
    file.received = new Set();
  }
  await new Promise((resolve, reject) => {
    fs.write(file.fd, chunk, 0, chunk.length, expectedOffset, (err) => err ? reject(err) : resolve());
  });
  file.received.add(`${expectedOffset}:${chunk.length}`);
  file.bytesReceived += chunk.length;
  const complete = file.bytesReceived >= file.size;
  if (complete) {
    await fs.promises.close(file.fd);
    file.fd = null;
    // Post-upload SHA-256 verification (background, then move to AVAILABLE)
    const actual = await streamSha256(file.diskPath);
    if (file.sha256 && actual !== file.sha256) { /* reject */ }
  }
  return { ok: true, bytesReceived: file.bytesReceived, complete };
}
```

### Sender changes (`lib.rs chunked_upload`)
Replace the sequential for-loop with `futures::stream::iter().for_each_concurrent(4, …)`. Each task:
- Reads its assigned chunk from a `tokio::fs::File` via a single shared `Arc<Mutex<File>>` (with seek + read_exact) or — better — use `pread` via `file.read_at()` semantics so the locks aren't needed.
- Sends its PATCH with the assigned offset.
- Logs failure into a shared `errors: Mutex<Vec<String>>`.
After the stream completes, if `errors` is non-empty → retry loop, else done.

```rust
use futures::stream::{self, StreamExt};
let concurrency = 4;
let results = stream::iter(offsets)
    .map(|offset| async move {
        let chunk = read_at(&path, offset, chunk_size).await?;
        let res = client.patch(&patch_url)
            .header("Upload-Offset", offset.to_string())
            .body(chunk)
            .send()
            .await?;
        Ok::<_, String>(res.status())
    })
    .buffer_unordered(concurrency)
    .collect::<Vec<_>>()
    .await;
```

### Android changes (`LiveCService.chunkedUpload`)
Same idea: launch `concurrency` coroutines via `async { }` and `awaitAll()`. Each opens its own `RandomAccessFile` handle (not a shared one) and seeks to its offset before reading.

**Risks:**
- Out-of-order writes mean the file is "complete" only after the last chunk lands. If sender or relay restarts mid-upload, the partial file has holes. Recovery: keep the `received: Set<chunkSpec>` persisted (e.g. write it next to the file on disk) and let HEAD return both `Upload-Offset` AND the set of completed ranges so the sender skips already-done chunks.
- SHA-256 verification can no longer be incremental; the post-upload pass is one extra full read of the file. For 60 MB at SSD speeds that's <100 ms.
- Concurrency 4 was conservative — could push to 8 if bandwidth supports.

**Effort:** ~1 hour relay-side, ~30 min per client.

---

## 4. Stream PATCH body straight to disk

**Impact:** lower relay RAM ceiling → enables higher chunk size or higher concurrency.

**Why:** today the relay accumulates the entire PATCH body into a `Buffer.concat(chunks)` before passing to `appendChunk`. With 4 MB chunks × 4 concurrent uploads = 16 MB RAM during the upload phase. On Render's 512 MB tier with Node's own overhead, that's still fine — but it gets uncomfortable if multiple users hit the relay at once.

**Approach:** replace `for await (const buf of req)` + `Buffer.concat` with a direct stream into the file. `req.pipe(writeStream)` works for sequential chunks; with parallel chunks (item #3), use `fs.write(fd, chunk, 0, len, offset)` per req-data event.

```js
app.patch('/upload/:offerId/:fileId', async (req, res) => {
  // ... auth + offset validation ...
  let written = 0;
  const hash = crypto.createHash('sha256');
  for await (const buf of req) {
    if (file.bytesReceived + written + buf.length > LIMITS.MAX_FILE_BYTES) {
      return res.status(413).end();
    }
    hash.update(buf);
    await new Promise((ok, fail) => {
      fs.write(file.fd, buf, 0, buf.length,
               expectedOffset + written, (err) => err ? fail(err) : ok());
    });
    written += buf.length;
  }
  file.bytesReceived += written;
  // ... mark complete + emit file_ready as before ...
});
```

**Risk:** marginal — adds error handling for partial writes (truncate on error).

**Effort:** 30 minutes.

---

## 5. Hash + upload in parallel

**Impact:** saves the SHA-256 pre-pass wall time. On 60 MB that's ~1 s, on 100 MB ~1.5 s. Trivial on its own but adds up combined with concurrent chunks.

**Why:** today `hash_file()` reads the entire file once to compute the hash *before* the first PATCH goes out. The file is sitting on disk; the relay doesn't actually need the hash until `file_offer` is sent — and even then only because we currently bake it into the offer payload. If we ship the offer without a hash and let the relay compute it post-upload (already in plan as part of #3), we save the pre-pass.

**Approach:**
- Send `file_offer` with `sha256: ""` or `sha256: "pending"`.
- Start `chunked_upload` immediately.
- In parallel, run `hash_file` to compute the hash.
- When chunked_upload completes, send a `file_hash` WS message with the computed hash; the relay compares against its own post-upload computed hash.
- If they disagree, relay rejects (corrupted upload) — sender gets `file_expired` event and retries.

**Risk:** offer arrives at recipient without a hash → recipient can't pre-check (currently we don't expose the hash to the recipient anyway, so this is a no-op user-side).

**Effort:** 30 minutes.

---

## Suggested ship order

1. **#1 keep-alive** (15 min, no risk, huge perceived win)
2. **#2 bigger CHUNK_SIZE** (5 min, low risk, ~1.5× speedup)
3. **#3 concurrent chunks** (1-2 h, medium risk, 3-4× speedup)
4. **#4 streamed disk write** (30 min, low risk, enables higher concurrency in the future)
5. **#5 hash-during-upload** (30 min, low risk, marginal win on top of above)

After all five, a 60 MB APK over Render free should land in **~8-15 seconds** end-to-end vs ~60-90 s today.

---

## Things explicitly NOT on this list

- **HTTP/2 with reqwest** — already tried, caused stalls on the Cloudflare path. Keeping `http1_only()`.
- **TUS reference implementation** — Express middleware exists but adds complexity (and another dep). The TUS-subset we have hits 95 % of the value with 100 lines of code.
- **WebRTC data channels** — would bypass the relay's disk for LAN-paired peers, but we already have LAN-direct WS for that case. Not worth the WebRTC dep.
- **Resumable uploads across app restarts** — the current HEAD-based resume only works within a single `upload_file` invocation. Persisting per-offer state to disk on the sender (so a Tauri restart can resume) is a separate, larger feature.
