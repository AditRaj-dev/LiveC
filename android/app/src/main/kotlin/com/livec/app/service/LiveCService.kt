package com.livec.app.service

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.livec.app.MainActivity
import com.livec.app.R
import com.livec.app.data.*
import com.livec.app.network.LanClient
import com.livec.app.network.LanDiscovery
import com.livec.app.network.RelayClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "LiveCService"
private const val SERVICE_CHANNEL = "livec.service"
const val FILES_CHANNEL = "livec.files"
private const val NOTIF_ID = 1

private const val EXTRA_SHARE_TEXT = "extra_share_text"
private const val EXTRA_SHARE_FILE = "extra_share_file"
private const val EXTRA_CLEAR_KIND = "extra_clear_kind"
private const val EXTRA_ACCEPT_OFFER = "extra_accept_offer"
private const val EXTRA_REJECT_OFFER = "extra_reject_offer"
private const val EXTRA_ACCEPT_FILE_IDS = "extra_accept_file_ids"
private const val EXTRA_SENDER_DEVICE_ID = "extra_sender_device_id"
private const val EXTRA_FILE_DONE_OFFER = "extra_file_done_offer"
private const val EXTRA_FILE_DONE_FILE  = "extra_file_done_file"

private sealed class OfferAcceptResult {
    data class Accepted(val uploadTokens: Map<String, String>) : OfferAcceptResult()
    object Rejected : OfferAcceptResult()
}

/**
 * Foreground service: owns the WebSocket connection, clipboard listener,
 * and pushes updates into [AppState].
 */
class LiveCService : LifecycleService() {

    private lateinit var configStore: ConfigStore
    private lateinit var client: RelayClient
    private lateinit var lanClient: LanClient
    private var lanDiscovery: LanDiscovery? = null
    private lateinit var clipboard: ClipboardManager

    private var roomToken: String = ""
    private var deviceId: String = ""

    // SELF_WRITE guard — same pattern as Windows client
    private var selfWritePending = false

    // Outbound offers awaiting file_accept/file_reject from the recipient.
    private val pendingOffers = ConcurrentHashMap<String, CompletableDeferred<OfferAcceptResult>>()

    // Cross-transport dedup: same msg.id arriving via relay + LAN is processed once.
    private val seenIds = ArrayDeque<String>()
    private val seenIdsCap = 200

    // Content + time dedup for clipboard text (both directions). Android can fire
    // OnPrimaryClipChangedListener multiple times for one logical clip change
    // (format normalization, IME, system clip-history hand-off). selfWritePending
    // is one-shot so the second event slips through and bounces the same text
    // back to the sender. This catches that within a 5-second window.
    private var lastTextHash: Int = 0
    private var lastTextTimeMs: Long = 0L
    private val textDedupWindowMs = 5_000L

    private fun isDuplicateText(text: String): Boolean {
        val now = System.currentTimeMillis()
        val hash = text.hashCode()
        val dup = hash == lastTextHash && (now - lastTextTimeMs) < textDedupWindowMs
        if (!dup) {
            lastTextHash = hash
            lastTextTimeMs = now
        }
        return dup
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (selfWritePending) {
            selfWritePending = false
            return@OnPrimaryClipChangedListener
        }
        val clip = clipboard.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
        val text = clip.getItemAt(0).coerceToText(this).toString()
        if (text.isBlank()) return@OnPrimaryClipChangedListener
        if (text.toByteArray(Charsets.UTF_8).size > Limits.MAX_TEXT_BYTES) return@OnPrimaryClipChangedListener

        // Drop repeat fires for the same content within the dedup window.
        if (isDuplicateText(text)) {
            Log.d(TAG, "Clip listener dup drop: ${text.take(24)}…")
            return@OnPrimaryClipChangedListener
        }

        AppState.addClip(ClipItem(kind = ClipItem.Kind.TEXT, text = text, source = ClipItem.Source.LOCAL))
        if (roomToken.isNotEmpty()) {
            val msg = Message.clipboardText(deviceId, roomToken, text)
            client.send(msg)
            if (lanClient.isConnected()) lanClient.send(msg)
        }
    }

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(applicationContext)
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        createChannels()
        startForeground(NOTIF_ID, buildNotification("Connecting…"))

        client = RelayClient(
            scope = lifecycleScope,
            onConnected = {
                AppState.setConnected(true)
                updateNotification("Connected")
            },
            onDisconnected = {
                AppState.setConnected(false)
                updateNotification("Reconnecting…")
            },
            onMessage = ::handleMessage,
        )

        lanClient = LanClient(
            scope = lifecycleScope,
            onMessage = ::handleMessage,
        )

        clipboard.addPrimaryClipChangedListener(clipListener)

        lifecycleScope.launch {
            configStore.ensureDeviceId()
            configStore.flow.distinctUntilChanged().collect { cfg ->
                deviceId = cfg.deviceId
                roomToken = cfg.roomToken
                if (cfg.relayUrl.isNotBlank() && cfg.roomToken.isNotBlank()) {
                    client.start(cfg.relayUrl, cfg.deviceId, cfg.deviceName, cfg.roomToken, cfg.fingerprint)
                    startLanDiscovery(cfg.roomToken, cfg.deviceId, cfg.deviceName, cfg.fingerprint)
                } else {
                    client.stop()
                    lanClient.disconnect()
                    lanDiscovery?.stop()
                    lanDiscovery = null
                    AppState.setConnected(false)
                    updateNotification("Not paired")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle share-sheet extras delivered after the service is already running
        intent?.getStringExtra(EXTRA_SHARE_TEXT)?.let { text ->
            if (roomToken.isNotEmpty() && text.isNotBlank()) {
                client.send(Message.clipboardText(deviceId, roomToken, text))
            }
        }
        @Suppress("DEPRECATION")
        val uri: Uri? = intent?.getParcelableExtra(EXTRA_SHARE_FILE)
        uri?.let {
            if (roomToken.isNotEmpty()) {
                lifecycleScope.launch { uploadAndBroadcast(it) }
            }
        }
        // Broadcast a clipboard/files clear to all paired devices
        intent?.getStringExtra(EXTRA_CLEAR_KIND)?.let { kind ->
            if (roomToken.isNotEmpty()) {
                val msg = Message(
                    type = kind,
                    from = deviceId,
                    to = BROADCAST,
                    room = roomToken,
                    payload = org.json.JSONObject(),
                )
                client.send(msg)
                if (lanClient.isConnected()) lanClient.send(msg)
            }
        }
        // User accepted an incoming file offer (from notification action)
        intent?.getStringExtra(EXTRA_ACCEPT_OFFER)?.let { offerId ->
            val fileIds = intent.getStringArrayExtra(EXTRA_ACCEPT_FILE_IDS)?.toList() ?: emptyList()
            val senderDeviceId = intent.getStringExtra(EXTRA_SENDER_DEVICE_ID) ?: ""
            if (roomToken.isNotEmpty() && senderDeviceId.isNotEmpty()) {
                client.send(Message.fileAccept(deviceId, roomToken, offerId, fileIds, senderDeviceId))
                AppState.updateTransfer(offerId) { copy(status = TransferItem.Status.DOWNLOADING) }
                cancelOfferNotification(offerId)
            }
        }
        // User rejected an incoming file offer (from notification action)
        intent?.getStringExtra(EXTRA_REJECT_OFFER)?.let { offerId ->
            val senderDeviceId = intent.getStringExtra(EXTRA_SENDER_DEVICE_ID) ?: ""
            if (roomToken.isNotEmpty() && senderDeviceId.isNotEmpty()) {
                client.send(Message.fileReject(deviceId, roomToken, offerId, senderDeviceId))
            }
            AppState.removeTransfer(offerId)
            cancelOfferNotification(offerId)
        }
        // Download complete — let the relay drop the file immediately.
        intent?.getStringExtra(EXTRA_FILE_DONE_OFFER)?.let { offerId ->
            val fileId = intent.getStringExtra(EXTRA_FILE_DONE_FILE) ?: return@let
            val senderDeviceId = intent.getStringExtra(EXTRA_SENDER_DEVICE_ID) ?: ""
            if (roomToken.isNotEmpty() && senderDeviceId.isNotEmpty()) {
                client.send(Message.fileDone(deviceId, roomToken, offerId, fileId, senderDeviceId))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        client.stop()
        lanClient.disconnect()
        lanDiscovery?.stop()
        super.onDestroy()
    }

    private fun startLanDiscovery(roomToken: String, deviceId: String, deviceName: String, fingerprint: String) {
        lanDiscovery?.stop()
        lanDiscovery = LanDiscovery(
            context = applicationContext,
            roomToken = roomToken,
            deviceId = deviceId,
            onPeerFound = { host, port ->
                Log.d("LiveCService", "LAN peer found: $host:$port")
                lanClient.connect(host, port, deviceId, deviceName, roomToken, fingerprint)
            },
        ).also { it.start() }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ── Inbound messages ──────────────────────────────────────────────────────

    private fun handleMessage(msg: Message) {
        // Drop our own echoes — relay shouldn't send these but LAN re-fans them.
        if (msg.from == deviceId) return
        // Only handle messages addressed to us or broadcast.
        if (msg.to != BROADCAST && msg.to != deviceId) return
        // Dedup across relay + LAN by envelope id.
        if (!markSeen(msg.id)) {
            Log.d(TAG, "Dedup drop ${msg.type} id=${msg.id.take(8)}")
            return
        }
        Log.d(TAG, "Handle ${msg.type} id=${msg.id.take(8)} from=${msg.from.take(8)}")

        when (msg.type) {
            MessageType.DEVICE_JOIN -> {
                val p = msg.payload
                AppState.upsertDevice(
                    DeviceInfo(
                        id = p.optString("deviceId"),
                        name = p.optString("deviceName", "Device"),
                        platform = p.optString("platform", "unknown"),
                        fingerprint = p.optString("fingerprint", ""),
                    )
                )
            }
            MessageType.DEVICE_LEAVE -> {
                AppState.removeDevice(msg.payload.optString("deviceId", msg.from))
            }
            MessageType.CLIPBOARD_TEXT -> {
                val text = msg.payload.optString("text")
                if (text.isNotEmpty()) {
                    AppState.addClip(
                        ClipItem(
                            kind = ClipItem.Kind.TEXT,
                            text = text,
                            source = ClipItem.Source.REMOTE,
                            from = msg.from,
                        )
                    )
                    // Pre-seed content+time dedup so the clip listener event triggered
                    // by our own setPrimaryClip below — and any repeat fires Android
                    // throws in for format normalization — gets dropped instead of
                    // bouncing the text back to the sender.
                    isDuplicateText(text)
                    // Write to local clipboard with self-write guard
                    selfWritePending = true
                    clipboard.setPrimaryClip(ClipData.newPlainText("LiveC", text))
                }
            }
            MessageType.CLIPBOARD_IMAGE -> {
                val inlineData = msg.payload.optString("data")
                val downloadUrl = msg.payload.optString("downloadUrl")
                when {
                    inlineData.isNotEmpty() -> {
                        // LAN path: base64-encoded image inline
                        try {
                            val bytes = android.util.Base64.decode(inlineData, android.util.Base64.DEFAULT)
                            val mime = msg.payload.optString("mimeType", "image/png")
                            val uri = saveImageToMediaStore(bytes, mime)
                            if (uri != null) {
                                writeImageToClipboard(uri, mime)
                                AppState.addClip(
                                    ClipItem(
                                        kind = ClipItem.Kind.IMAGE,
                                        downloadUrl = uri.toString(),
                                        source = ClipItem.Source.REMOTE,
                                        from = msg.from,
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to handle inline image: ${e.message}")
                        }
                    }
                    downloadUrl.isNotEmpty() -> {
                        // Relay path: download then write to clipboard
                        AppState.addClip(
                            ClipItem(
                                kind = ClipItem.Kind.IMAGE,
                                downloadUrl = downloadUrl,
                                source = ClipItem.Source.REMOTE,
                                from = msg.from,
                            )
                        )
                        lifecycleScope.launch { downloadAndWriteImageToClipboard(downloadUrl) }
                    }
                }
            }
            MessageType.FILE_META -> {
                val fileId = msg.payload.optString("fileId")
                val name = msg.payload.optString("name", "file")
                val size = msg.payload.optLong("size", 0)
                val url = msg.payload.optString("downloadUrl")
                AppState.addTransfer(
                    TransferItem(
                        id = fileId.ifEmpty { msg.id },
                        name = name, size = size, downloadUrl = url,
                        from = msg.from,
                        status = TransferItem.Status.PENDING,
                        direction = TransferItem.Direction.INCOMING,
                    )
                )
                postFileNotification(name, msg.from)
            }
            MessageType.FILE_EXPIRED -> {
                val fileId = msg.payload.optString("fileId")
                AppState.updateTransfer(fileId) {
                    copy(status = TransferItem.Status.ERROR, errorMsg = "Expired")
                }
            }
            MessageType.CLIPBOARD_CLEAR -> {
                Log.d(TAG, "Remote clipboard clear from ${msg.from.take(8)}")
                AppState.clearClips()
            }
            MessageType.FILES_CLEAR -> {
                Log.d(TAG, "Remote files clear from ${msg.from.take(8)}")
                AppState.clearTransfers()
            }
            // ── Two-phase transfer messages ──────────────────────────────────
            MessageType.FILE_OFFER -> {
                val offerId = msg.payload.optString("offerId")
                val filesArr = msg.payload.optJSONArray("files")
                val fileCount = filesArr?.length() ?: 0
                val firstName = filesArr?.optJSONObject(0)?.optString("name", "file") ?: "file"
                val totalSize = (0 until fileCount).sumOf {
                    filesArr?.optJSONObject(it)?.optLong("size", 0L) ?: 0L
                }
                val displayName = if (fileCount == 1) firstName else "$fileCount files"
                val fileIds = (0 until fileCount).mapNotNull {
                    filesArr?.optJSONObject(it)?.optString("fileId")
                }

                // Phase 5b: auto-accept from trusted quick-mode peers.
                val senderFp = AppState.devices.value.find { it.id == msg.from }?.fingerprint ?: ""
                lifecycleScope.launch {
                    val isQuick = senderFp.isNotEmpty() && configStore.isQuickMode(senderFp)
                    if (isQuick) {
                        AppState.addTransfer(
                            TransferItem(
                                id = offerId,
                                name = displayName,
                                size = totalSize,
                                downloadUrl = "",
                                from = msg.from,
                                status = TransferItem.Status.DOWNLOADING,
                                direction = TransferItem.Direction.INCOMING,
                                senderDeviceId = msg.from,
                                offerFileIds = fileIds.joinToString(","),
                                offerId = offerId,
                            )
                        )
                        client.send(Message.fileAccept(deviceId, roomToken, offerId, fileIds, msg.from))
                        Log.d(TAG, "Auto-accepted offer $offerId from quick-mode peer ${senderFp.take(8)}")
                    } else {
                        AppState.addTransfer(
                            TransferItem(
                                id = offerId,
                                name = displayName,
                                size = totalSize,
                                downloadUrl = "",
                                from = msg.from,
                                status = TransferItem.Status.OFFER_PENDING,
                                direction = TransferItem.Direction.INCOMING,
                                senderDeviceId = msg.from,
                                offerFileIds = fileIds.joinToString(","),
                                offerId = offerId,
                            )
                        )
                        postOfferNotification(offerId, displayName, msg.from, fileIds, msg.from)
                    }
                }
            }
            MessageType.FILE_ACCEPT -> {
                // Our outbound offer was accepted by the recipient.
                val offerId = msg.payload.optString("offerId")
                val tokensObj = msg.payload.optJSONObject("uploadTokens")
                val uploadTokens = tokensObj?.let { obj ->
                    obj.keys().asSequence().associateWith { obj.optString(it) }
                } ?: emptyMap()
                pendingOffers.remove(offerId)
                    ?.complete(OfferAcceptResult.Accepted(uploadTokens))
                AppState.updateTransfer(offerId) { copy(status = TransferItem.Status.UPLOADING) }
            }
            MessageType.FILE_REJECT -> {
                val offerId = msg.payload.optString("offerId")
                pendingOffers.remove(offerId)
                    ?.complete(OfferAcceptResult.Rejected)
                AppState.updateTransfer(offerId) {
                    copy(status = TransferItem.Status.ERROR, errorMsg = "Rejected")
                }
            }
            MessageType.FILE_READY -> {
                // File is uploaded and ready for download (we're the recipient).
                val offerId = msg.payload.optString("offerId")
                val fileId = msg.payload.optString("fileId")
                val name = msg.payload.optString("name", "file")
                val size = msg.payload.optLong("size", 0L)
                val downloadUrl = msg.payload.optString("downloadUrl")
                // Replace the offer_pending entry with a downloadable transfer.
                AppState.removeTransfer(offerId)
                AppState.addTransfer(
                    TransferItem(
                        id = fileId,
                        name = name,
                        size = size,
                        downloadUrl = downloadUrl,
                        from = msg.from,
                        status = TransferItem.Status.PENDING,
                        direction = TransferItem.Direction.INCOMING,
                        senderDeviceId = msg.from,
                        offerId = offerId,
                    )
                )
                postFileNotification(name, msg.from)
            }
        }
    }

    private fun writeImageToClipboard(uri: Uri, mime: String) {
        selfWritePending = true
        val clip = ClipData.newUri(contentResolver, "LiveC Image", uri)
        clipboard.setPrimaryClip(clip)
    }

    private suspend fun downloadAndWriteImageToClipboard(downloadUrl: String) = withContext(Dispatchers.IO) {
        try {
            val bytes = OkHttpClient().newCall(Request.Builder().url(downloadUrl).build())
                .execute().body?.bytes() ?: return@withContext
            val mime = if (downloadUrl.contains("jpeg") || downloadUrl.contains("jpg")) "image/jpeg" else "image/png"
            val uri = saveImageToMediaStore(bytes, mime) ?: return@withContext
            withContext(Dispatchers.Main) { writeImageToClipboard(uri, mime) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download image for clipboard: ${e.message}")
        }
    }

    /**
     * Write an image to MediaStore (Pictures/LiveC) so the returned content:// URI is
     * globally readable by other apps' paste targets. FileProvider URIs only work for
     * apps we explicitly grant — useless for the clipboard since we can't predict the
     * paste target.
     */
    private fun saveImageToMediaStore(bytes: ByteArray, mime: String): Uri? {
        val ext = if (mime.contains("jpeg")) "jpg" else "png"
        val name = "LiveC_${System.currentTimeMillis()}.$ext"
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LiveC")
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: run { resolver.delete(uri, null, null); null }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "saveImageToMediaStore failed: ${e.message}")
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            null
        }
    }

    @Synchronized
    private fun markSeen(id: String): Boolean {
        if (id.isEmpty()) return true
        if (seenIds.contains(id)) return false
        if (seenIds.size >= seenIdsCap) seenIds.removeFirst()
        seenIds.addLast(id)
        return true
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.service_channel_description)
                setShowBadge(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                FILES_CHANNEL,
                getString(R.string.files_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    private fun buildNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle("LiveC")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setColor(Color.parseColor("#FBBF24"))
            .setContentIntent(intent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun postFileNotification(name: String, from: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, FILES_CHANNEL)
            .setContentTitle("New file from ${from.take(8)}")
            .setContentText(name)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setColor(Color.parseColor("#FBBF24"))
            .build()
        nm.notify(name.hashCode(), notif)
    }

    // ── File upload (two-phase + chunked TUS-subset) ──────────────────────────

    private suspend fun uploadAndBroadcast(uri: Uri, target: String = BROADCAST) =
        withContext(Dispatchers.IO) {
            val cfg = configStore.get()
            val httpBase = relayToHttpBase(cfg.relayUrl)
            val cr = contentResolver

            val name = cr.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            } ?: uri.lastPathSegment ?: "file"
            val mime = cr.getType(uri) ?: "application/octet-stream"

            // ContentResolver streams aren't seekable — copy once to cache so we can
            // hash AND chunk-upload without re-asking the provider.
            val cacheFile = File(cacheDir, "upload_${System.currentTimeMillis()}_$name")
            try {
                cr.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    Log.e(TAG, "Cannot read URI: $uri")
                    return@withContext
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stage upload", e)
                cacheFile.delete()
                return@withContext
            }
            val size = cacheFile.length()

            val sha256Hex = try {
                val md = MessageDigest.getInstance("SHA-256")
                cacheFile.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                cacheFile.delete()
                Log.e(TAG, "SHA-256 failed", e)
                return@withContext
            }

            val offerId = UUID.randomUUID().toString()
            val fileId  = UUID.randomUUID().toString()

            val placeholder = TransferItem(
                id = offerId,
                name = name,
                size = size,
                downloadUrl = "",
                from = cfg.deviceId,
                status = TransferItem.Status.UPLOADING,
                direction = TransferItem.Direction.OUTGOING,
            )
            AppState.addTransfer(placeholder)

            val deferred = CompletableDeferred<OfferAcceptResult>()
            pendingOffers[offerId] = deferred

            val offerMsg = Message.fileOffer(
                deviceId = cfg.deviceId,
                roomToken = cfg.roomToken,
                offerId = offerId,
                files = listOf(
                    Message.OfferFile(
                        fileId = fileId, name = name, size = size,
                        sha256 = sha256Hex, mimeType = mime,
                    )
                ),
                target = target,
            )
            client.send(offerMsg)
            // LAN fallback — defense against stale relay socket entries.
            if (lanClient.isConnected()) lanClient.send(offerMsg)

            val result = try {
                withTimeout(300_000L) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                pendingOffers.remove(offerId)
                cacheFile.delete()
                Log.w(TAG, "Offer $offerId timed out")
                AppState.updateTransfer(offerId) {
                    copy(status = TransferItem.Status.ERROR, errorMsg = "Offer timed out")
                }
                return@withContext
            }

            when (result) {
                is OfferAcceptResult.Rejected -> {
                    cacheFile.delete()
                    AppState.updateTransfer(offerId) {
                        copy(status = TransferItem.Status.ERROR, errorMsg = "Rejected")
                    }
                    return@withContext
                }
                is OfferAcceptResult.Accepted -> {
                    val token = result.uploadTokens[fileId] ?: run {
                        cacheFile.delete()
                        AppState.updateTransfer(offerId) {
                            copy(status = TransferItem.Status.ERROR, errorMsg = "No upload token")
                        }
                        return@withContext
                    }

                    val patchUrl = "$httpBase/upload/$offerId/$fileId"
                    try {
                        chunkedUpload(offerId, cacheFile, patchUrl, token, size)
                        AppState.updateTransfer(offerId) { copy(status = TransferItem.Status.DONE, progress = 1f) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Chunked upload failed", e)
                        AppState.updateTransfer(offerId) {
                            copy(status = TransferItem.Status.ERROR, errorMsg = e.message ?: "Upload failed")
                        }
                    } finally {
                        cacheFile.delete()
                    }
                }
            }
        }

    /** Stream `file` to `patchUrl` in CHUNK_SIZE pieces with HEAD-based resume.
     *  Updates AppState.transfer.progress after each accepted chunk so HomeScreen
     *  can render a progress bar. */
    private fun chunkedUpload(transferId: String, file: File, patchUrl: String, token: String, totalSize: Long) {
        val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val chunkSize = Limits.CHUNK_SIZE
        val maxAttempts = 5
        val chunkBuf = ByteArray(chunkSize)
        val patchMedia = "application/offset+octet-stream".toMediaType()
        var offset = 0L
        var attempts = 0

        RandomAccessFile(file, "r").use { raf ->
            while (offset < totalSize) {
                raf.seek(offset)
                val toRead = minOf(chunkSize.toLong(), totalSize - offset).toInt()
                raf.readFully(chunkBuf, 0, toRead)
                val body = chunkBuf.copyOf(toRead).toRequestBody(patchMedia)
                val req = Request.Builder()
                    .url(patchUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Upload-Offset", offset.toString())
                    .patch(body)
                    .build()

                try {
                    http.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            offset += toRead
                            attempts = 0
                            val ratio = if (totalSize > 0) offset.toFloat() / totalSize.toFloat() else 0f
                            AppState.updateTransfer(transferId) { copy(progress = ratio.coerceIn(0f, 1f)) }
                        } else {
                            resp.header("Upload-Offset")?.toLongOrNull()?.let { offset = it }
                            attempts++
                            if (attempts >= maxAttempts) {
                                throw IOException("PATCH failed after $attempts attempts: HTTP ${resp.code}")
                            }
                            Thread.sleep(500L * attempts)
                        }
                    }
                } catch (e: IOException) {
                    attempts++
                    if (attempts >= maxAttempts) throw e
                    // Ask server where it actually is, then retry from there.
                    try {
                        val headReq = Request.Builder()
                            .url(patchUrl)
                            .addHeader("Authorization", "Bearer $token")
                            .head()
                            .build()
                        http.newCall(headReq).execute().use { r ->
                            r.header("Upload-Offset")?.toLongOrNull()?.let { offset = it }
                        }
                    } catch (_: IOException) {
                        // ignore; will retry at current offset
                    }
                    Thread.sleep(500L * attempts)
                }
            }
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun postOfferNotification(
        offerId: String,
        displayName: String,
        from: String,
        fileIds: List<String>,
        senderDeviceId: String,
    ) {
        val nm = getSystemService(NotificationManager::class.java)

        val acceptIntent = PendingIntent.getService(
            this, offerId.hashCode(),
            Intent(this, LiveCService::class.java).apply {
                putExtra(EXTRA_ACCEPT_OFFER, offerId)
                putExtra(EXTRA_ACCEPT_FILE_IDS, fileIds.toTypedArray())
                putExtra(EXTRA_SENDER_DEVICE_ID, senderDeviceId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val rejectIntent = PendingIntent.getService(
            this, offerId.hashCode() + 1,
            Intent(this, LiveCService::class.java).apply {
                putExtra(EXTRA_REJECT_OFFER, offerId)
                putExtra(EXTRA_SENDER_DEVICE_ID, senderDeviceId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(this, FILES_CHANNEL)
            .setContentTitle("File from ${from.take(8)}")
            .setContentText(displayName)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setAutoCancel(false)
            .setColor(Color.parseColor("#FBBF24"))
            .addAction(0, "Accept", acceptIntent)
            .addAction(0, "Reject", rejectIntent)
            .build()
        nm.notify(offerId.hashCode(), notif)
    }

    private fun cancelOfferNotification(offerId: String) {
        getSystemService(NotificationManager::class.java).cancel(offerId.hashCode())
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, LiveCService::class.java))
        }

        fun startWithText(ctx: Context, text: String) {
            val intent = Intent(ctx, LiveCService::class.java)
                .putExtra(EXTRA_SHARE_TEXT, text)
            ctx.startForegroundService(intent)
        }

        fun startWithFile(ctx: Context, uri: Uri) {
            val intent = Intent(ctx, LiveCService::class.java)
                .putExtra(EXTRA_SHARE_FILE, uri)
            ctx.startForegroundService(intent)
        }

        fun startWithClear(ctx: Context, kind: String) {
            val intent = Intent(ctx, LiveCService::class.java)
                .putExtra(EXTRA_CLEAR_KIND, kind)
            ctx.startForegroundService(intent)
        }

        fun acceptOffer(ctx: Context, offerId: String, fileIds: List<String>, senderDeviceId: String) {
            ctx.startForegroundService(
                Intent(ctx, LiveCService::class.java)
                    .putExtra(EXTRA_ACCEPT_OFFER, offerId)
                    .putExtra(EXTRA_ACCEPT_FILE_IDS, fileIds.toTypedArray())
                    .putExtra(EXTRA_SENDER_DEVICE_ID, senderDeviceId)
            )
        }

        fun rejectOffer(ctx: Context, offerId: String, senderDeviceId: String) {
            ctx.startForegroundService(
                Intent(ctx, LiveCService::class.java)
                    .putExtra(EXTRA_REJECT_OFFER, offerId)
                    .putExtra(EXTRA_SENDER_DEVICE_ID, senderDeviceId)
            )
        }

        /** Tell the relay a downloaded file can be deleted immediately. */
        fun markFileDone(ctx: Context, offerId: String, fileId: String, senderDeviceId: String) {
            ctx.startForegroundService(
                Intent(ctx, LiveCService::class.java)
                    .putExtra(EXTRA_FILE_DONE_OFFER, offerId)
                    .putExtra(EXTRA_FILE_DONE_FILE, fileId)
                    .putExtra(EXTRA_SENDER_DEVICE_ID, senderDeviceId)
            )
        }
    }
}
