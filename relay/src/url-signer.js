// HMAC-signed download URL helpers. Phase 3 (§4.5) of design-borrows.md.
//
// URL shape:
//   https://relay/download/<fileId>?exp=<unix_ms>&sig=<hex>
//   sig = HMAC_SHA256(secret, `${fileId}|${exp}`)
//
// Stateless: relay can verify without remembering the per-file token.
// The `to=<recipient>` scoping from the design lands in Phase 5 once we have
// device fingerprints — for now we just sign (fileId, exp).
//
// Secret source:
//   process.env.LIVEC_SIGNING_SECRET   ← production
//   random in-memory (warned on startup) ← dev fallback
//
// Rotating the env-var secret invalidates all in-flight signed URLs. Restart
// the relay and clients will pick up new URLs on the next file_offer round.

'use strict';
const crypto = require('crypto');

let SECRET = process.env.LIVEC_SIGNING_SECRET;
if (!SECRET) {
  SECRET = crypto.randomBytes(32).toString('hex');
  console.warn(
    '[url-signer] LIVEC_SIGNING_SECRET not set — using random in-memory secret. ' +
    'Signed URLs will not survive a relay restart.'
  );
}

function _hmac(input) {
  return crypto.createHmac('sha256', SECRET).update(input).digest('hex');
}

/**
 * Sign a download URL. `ttlMs` defaults to 7 days.
 * Returns the full URL string ready to embed in file_ready/file_meta.
 */
function signDownloadUrl(httpBase, fileId, ttlMs = 7 * 24 * 60 * 60 * 1000) {
  const exp = Date.now() + ttlMs;
  const sig = _hmac(`${fileId}|${exp}`);
  return `${httpBase}/download/${fileId}?exp=${exp}&sig=${sig}`;
}

/**
 * Verify a download request. Returns { ok: true } on success or
 * { ok: false, reason: string, status: number } on failure.
 */
function verifyDownloadRequest(fileId, query) {
  const exp = parseInt(query.exp, 10);
  const sig = query.sig;
  if (!exp || !sig) {
    return { ok: false, reason: 'Missing signature', status: 401 };
  }
  if (Date.now() > exp) {
    return { ok: false, reason: 'Signature expired', status: 403 };
  }
  const expected = _hmac(`${fileId}|${exp}`);
  // crypto.timingSafeEqual requires equal-length buffers.
  const a = Buffer.from(sig, 'hex');
  const b = Buffer.from(expected, 'hex');
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
    return { ok: false, reason: 'Invalid signature', status: 403 };
  }
  return { ok: true };
}

module.exports = { signDownloadUrl, verifyDownloadRequest };
