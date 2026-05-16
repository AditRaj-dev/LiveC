package com.livec.app.service

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.IBinder
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

private const val TAG = "LiveCService"
private const val SERVICE_CHANNEL = "livec.service"
const val FILES_CHANNEL = "livec.files"
private const val NOTIF_ID = 1

private const val EXTRA_SHARE_TEXT = "extra_share_text"
private const val EXTRA_SHARE_FILE = "extra_share_file"
private const val EXTRA_CLEAR_KIND = "extra_clear_kind"

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
                    client.start(cfg.relayUrl, cfg.deviceId, cfg.deviceName, cfg.roomToken)
                    startLanDiscovery(cfg.roomToken, cfg.deviceId, cfg.deviceName)
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
        return START_STICKY
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        client.stop()
        lanClient.disconnect()
        lanDiscovery?.stop()
        super.onDestroy()
    }

    private fun startLanDiscovery(roomToken: String, deviceId: String, deviceName: String) {
        lanDiscovery?.stop()
        lanDiscovery = LanDiscovery(
            context = applicationContext,
            roomToken = roomToken,
            deviceId = deviceId,
            onPeerFound = { host, port ->
                Log.d("LiveCService", "LAN peer found: $host:$port")
                lanClient.connect(host, port, deviceId, deviceName, roomToken)
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
                            val ext = if (mime.contains("jpeg")) "jpg" else "png"
                            val file = File(cacheDir, "livec_img_${System.currentTimeMillis()}.$ext")
                            file.writeBytes(bytes)
                            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                            writeImageToClipboard(uri, mime)
                            AppState.addClip(
                                ClipItem(
                                    kind = ClipItem.Kind.IMAGE,
                                    downloadUrl = file.toURI().toString(),
                                    source = ClipItem.Source.REMOTE,
                                    from = msg.from,
                                )
                            )
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
            val ext = if (downloadUrl.contains("jpeg") || downloadUrl.contains("jpg")) "jpg" else "png"
            val file = File(cacheDir, "livec_img_${System.currentTimeMillis()}.$ext")
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(this@LiveCService, "$packageName.fileprovider", file)
            withContext(Dispatchers.Main) { writeImageToClipboard(uri, "image/png") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download image for clipboard: ${e.message}")
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

        /** Broadcast a clear message (clipboard_clear / files_clear) to all paired devices. */
        fun startWithClear(ctx: Context, kind: String) {
            val intent = Intent(ctx, LiveCService::class.java)
                .putExtra(EXTRA_CLEAR_KIND, kind)
            ctx.startForegroundService(intent)
        }
    }

    // ── File upload ───────────────────────────────────────────────────────────

    private suspend fun uploadAndBroadcast(uri: Uri) = withContext(Dispatchers.IO) {
        val cfg = configStore.get()
        val httpBase = relayToHttpBase(cfg.relayUrl)
        val cr = contentResolver

        // Resolve display name
        val name = cr.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        } ?: uri.lastPathSegment ?: "file"

        val mime = cr.getType(uri) ?: "application/octet-stream"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: run {
            Log.e(TAG, "Cannot read URI: $uri")
            return@withContext
        }

        // Add placeholder transfer
        val placeholder = TransferItem(
            id = "upload_${System.currentTimeMillis()}",
            name = name,
            size = bytes.size.toLong(),
            downloadUrl = "",
            from = cfg.deviceId,
            status = TransferItem.Status.UPLOADING,
            direction = TransferItem.Direction.OUTGOING,
        )
        AppState.addTransfer(placeholder)

        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(UPLOAD_FIELD_NAME, name, bytes.toRequestBody(mime.toMediaType()))
                .addFormDataPart("roomToken", cfg.roomToken)
                .addFormDataPart("deviceId", cfg.deviceId)
                .build()

            val req = Request.Builder().url("$httpBase${Paths.UPLOAD}").post(body).build()
            val res = OkHttpClient().newCall(req).execute()

            if (!res.isSuccessful) {
                AppState.updateTransfer(placeholder.id) {
                    copy(status = TransferItem.Status.ERROR, errorMsg = "HTTP ${res.code}")
                }
                return@withContext
            }

            val json = JSONObject(res.body!!.string())
            val fileId = json.getString("fileId")
            val downloadUrl = json.getString("downloadUrl")

            // Broadcast file_meta to room via relay + LAN
            val fileMeta = Message(
                type = MessageType.FILE_META,
                from = cfg.deviceId,
                to = BROADCAST,
                room = cfg.roomToken,
                payload = JSONObject().apply {
                    put("fileId", fileId)
                    put("name", name)
                    put("size", bytes.size.toLong())
                    put("downloadUrl", downloadUrl)
                },
            )
            client.send(fileMeta)
            if (lanClient.isConnected()) lanClient.send(fileMeta)

            AppState.updateTransfer(placeholder.id) {
                copy(status = TransferItem.Status.DONE, downloadUrl = downloadUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            AppState.updateTransfer(placeholder.id) {
                copy(status = TransferItem.Status.ERROR, errorMsg = e.message ?: "Upload failed")
            }
        }
    }
}
