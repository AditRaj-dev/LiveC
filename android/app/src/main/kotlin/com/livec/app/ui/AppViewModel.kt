package com.livec.app.ui

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.livec.app.LiveCApplication
import com.livec.app.data.AppState
import com.livec.app.data.ConfigStore
import com.livec.app.data.Message
import com.livec.app.data.MessageType
import com.livec.app.data.TransferItem
import com.livec.app.service.LiveCService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store: ConfigStore = (app as LiveCApplication).configStore

    val config = store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val connected = AppState.connected
    val devices = AppState.devices
    val clips = AppState.clips
    val transfers = AppState.transfers

    /** Parses the pairing QR payload and writes it to config.
     *  Phase 5b: if the QR carries a fingerprint, pin it as a trusted (quick-mode) peer.
     */
    fun pairFromQr(rawPayload: String): Result<Unit> = try {
        val o = JSONObject(rawPayload)
        val relayUrl = o.getString("relayUrl")
        val roomToken = o.getString("roomToken")
        val fingerprint = o.optString("fingerprint", "")
        val deviceName = o.optString("deviceName", "Paired device")
        viewModelScope.launch {
            store.setRoom(relayUrl, roomToken)
            if (fingerprint.isNotEmpty()) {
                // QR pinning is an explicit user action → trust + quick-mode.
                store.addTrustedPeer(fingerprint, deviceName, quickMode = true)
            }
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // ── Trusted-peer commands (Phase 5b) ──────────────────────────────────────
    fun trustPeer(fingerprint: String, deviceName: String, quickMode: Boolean = false) =
        viewModelScope.launch { store.addTrustedPeer(fingerprint, deviceName, quickMode) }

    fun untrustPeer(fingerprint: String) =
        viewModelScope.launch { store.removeTrustedPeer(fingerprint) }

    fun setQuickMode(fingerprint: String, enabled: Boolean) =
        viewModelScope.launch { store.setQuickMode(fingerprint, enabled) }

    fun setRelayUrl(url: String) = viewModelScope.launch { store.setRelayUrl(url) }
    fun setDeviceName(name: String) = viewModelScope.launch { store.setDeviceName(name) }
    fun leaveRoom() = viewModelScope.launch {
        store.setRoom("", "")
        AppState.reset()
    }
    /** Clear local transfers AND broadcast the clear to all paired devices. */
    fun clearTransfers() {
        AppState.clearTransfers()
        broadcastClear(MessageType.FILES_CLEAR)
    }

    /** Clear local clipboard AND broadcast the clear to all paired devices. */
    fun clearClips() {
        AppState.clearClips()
        broadcastClear(MessageType.CLIPBOARD_CLEAR)
    }

    private fun broadcastClear(type: String) {
        val cfg = config.value ?: return
        if (cfg.roomToken.isEmpty()) return
        val ctx: Context = getApplication()
        LiveCService.startWithClear(ctx, type)
    }

    fun dismissTransfer(item: TransferItem) {
        AppState.removeTransfer(item.id)
        if (item.downloadUrl.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val conn = URL(item.downloadUrl).openConnection() as HttpURLConnection
                    conn.requestMethod = "DELETE"
                    conn.connectTimeout = 5_000
                    conn.readTimeout   = 5_000
                    conn.connect()
                    conn.responseCode   // sends the request
                    conn.disconnect()
                }
            }
        }
    }

    /** Use the system DownloadManager to fetch a staged file. */
    fun downloadTransfer(item: TransferItem) {
        val ctx: Context = getApplication()
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(item.downloadUrl))
            .setTitle(item.name)
            .setDescription("LiveC file transfer")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, item.name)
        AppState.updateTransfer(item.id) { copy(status = TransferItem.Status.DOWNLOADING) }
        try {
            val downloadId = dm.enqueue(req)
            // DO NOT mark Status.DONE yet — DownloadManager hasn't actually fetched
            // anything. Hand the downloadId to LiveCService, which listens for
            // ACTION_DOWNLOAD_COMPLETE and fires file_done only on success. Sending
            // file_done here would delete the file before DownloadManager could
            // fetch it (the bug we just fixed).
            val offerId = item.offerId
            val sender = item.senderDeviceId
            if (!offerId.isNullOrEmpty() && !sender.isNullOrEmpty()) {
                LiveCService.trackDownloadForCompletion(ctx, downloadId, offerId, item.id, sender)
            }
        } catch (e: Exception) {
            AppState.updateTransfer(item.id) {
                copy(status = TransferItem.Status.ERROR, errorMsg = e.message ?: "Failed")
            }
        }
    }

    /** Accept all files in an incoming offer (called from HomeScreen Accept button). */
    fun acceptOffer(item: TransferItem) {
        val sender = item.senderDeviceId ?: item.from
        val ids = item.offerFileIds?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        if (sender.isEmpty() || ids.isEmpty()) return
        AppState.updateTransfer(item.id) { copy(status = TransferItem.Status.DOWNLOADING) }
        LiveCService.acceptOffer(getApplication(), item.id, ids, sender)
    }

    /** Reject an incoming offer (called from HomeScreen Reject button). */
    fun rejectOffer(item: TransferItem) {
        val sender = item.senderDeviceId ?: item.from
        AppState.removeTransfer(item.id)
        if (sender.isEmpty()) return
        LiveCService.rejectOffer(getApplication(), item.id, sender)
    }

    /** Send text via share sheet — routed through LiveCService so it uses the active WS. */
    fun sendSharedText(text: String) {
        val ctx: Context = getApplication()
        LiveCService.startWithText(ctx, text)
    }

    /** Upload a file shared via the Android share sheet. */
    fun sendSharedFile(uri: Uri) {
        val ctx: Context = getApplication()
        LiveCService.startWithFile(ctx, uri)
    }
}
