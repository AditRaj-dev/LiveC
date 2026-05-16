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

    /** Parses the pairing QR payload and writes it to config. */
    fun pairFromQr(rawPayload: String): Result<Unit> = try {
        val o = JSONObject(rawPayload)
        val relayUrl = o.getString("relayUrl")
        val roomToken = o.getString("roomToken")
        viewModelScope.launch { store.setRoom(relayUrl, roomToken) }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

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
            dm.enqueue(req)
            AppState.updateTransfer(item.id) { copy(status = TransferItem.Status.DONE) }
        } catch (e: Exception) {
            AppState.updateTransfer(item.id) {
                copy(status = TransferItem.Status.ERROR, errorMsg = e.message ?: "Failed")
            }
        }
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
