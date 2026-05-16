const fs = require('fs');
const { LIMITS, UPLOAD_FIELD_NAME } = require('./protocol');
const path = require('path');
const { v4: uuidv4 } = require('uuid');
const multer = require('multer');

const FILE_TTL_MS = LIMITS.FILE_TTL_MS;
const CLEANUP_INTERVAL_MS = 30 * 1000;
const MAX_FILE_SIZE = LIMITS.MAX_FILE_BYTES;

// Use a folder next to this file so it survives temp-dir cleanup on Windows.
// Override with UPLOAD_DIR env var in production (e.g. Render ephemeral /tmp).
const UPLOAD_DIR = process.env.UPLOAD_DIR || path.join(__dirname, '..', 'tmp-uploads');

function ensureUploadDir(cb) {
  fs.mkdir(UPLOAD_DIR, { recursive: true }, cb);
}

// Create on startup (best-effort; destination callback also ensures it per-request).
ensureUploadDir((err) => {
  if (err) console.error('[FileStore] Could not create upload dir:', err);
  else console.log('[FileStore] Upload dir ready:', UPLOAD_DIR);
});

// In-memory file tracker: Map<fileId, { path, uploadedAt }>
const fileTracker = new Map();

const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    // Re-create the dir if it was cleaned between startup and this request.
    ensureUploadDir((err) => {
      if (err) return cb(err);
      cb(null, UPLOAD_DIR);
    });
  },
  filename: (req, file, cb) => {
    const ext = path.extname(file.originalname) || '';
    const fileId = uuidv4();
    req.fileId = fileId;
    cb(null, `${fileId}${ext}`);
  },
});

const upload = multer({
  storage,
  limits: { fileSize: MAX_FILE_SIZE },
});

// Cleanup: remove expired files from disk and tracker.
setInterval(() => {
  const now = Date.now();
  for (const [fileId, fileInfo] of fileTracker.entries()) {
    if (now - fileInfo.uploadedAt > FILE_TTL_MS) {
      fileTracker.delete(fileId);
      fs.unlink(fileInfo.path, (err) => {
        if (err && err.code !== 'ENOENT') {
          console.error(`[FileStore] Failed to delete expired file:`, err.message);
        }
      });
    }
  }
}, CLEANUP_INTERVAL_MS);

module.exports = {
  uploadMiddleware: upload.single(UPLOAD_FIELD_NAME),

  handleUpload: (req, res) => {
    if (!req.file) {
      return res.status(400).json({ error: 'No file uploaded' });
    }

    const fileId = req.fileId;
    fileTracker.set(fileId, {
      path: req.file.path,
      uploadedAt: Date.now(),
    });

    const protocol = req.headers['x-forwarded-proto'] || req.protocol;
    const host = req.headers.host;
    const downloadUrl = `${protocol}://${host}/download/${fileId}`;

    console.log(`[FileStore] Uploaded: ${fileId} (${req.file.size} bytes) → ${req.file.originalname}`);

    res.json({ fileId, downloadUrl, size: req.file.size });
  },

  handleDownload: (req, res) => {
    const { fileId } = req.params;
    const fileInfo = fileTracker.get(fileId);

    if (!fileInfo) {
      return res.status(404).json({ error: 'File not found or expired' });
    }

    res.download(fileInfo.path, (err) => {
      if (err) {
        // File disappeared from disk (e.g. external cleanup). Evict from tracker.
        fileTracker.delete(fileId);
        console.warn(`[FileStore] Download failed for ${fileId}:`, err.message);
        if (!res.headersSent) {
          res.status(404).json({ error: 'File no longer available' });
        }
      }
    });
  },

  handleDelete: (req, res) => {
    const { fileId } = req.params;
    const fileInfo = fileTracker.get(fileId);

    if (!fileInfo) {
      return res.status(404).json({ error: 'File not found or expired' });
    }

    fileTracker.delete(fileId);
    fs.unlink(fileInfo.path, (err) => {
      if (err && err.code !== 'ENOENT') {
        console.warn(`[FileStore] Failed to delete ${fileId}:`, err.message);
      }
    });
    console.log(`[FileStore] Deleted: ${fileId}`);
    res.json({ ok: true });
  },
};
