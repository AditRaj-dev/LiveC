package com.livec.app.network

import android.util.Log
import com.livec.app.data.Message
import com.livec.app.data.MessageType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlin.math.min

private const val TAG = "LanClient"

/**
 * Direct WebSocket connection to the Windows LAN server (port 7777).
 * Mirrors RelayClient but connects to ws://<host>:<port>/ws instead of the cloud relay.
 * Clipboard text sent here bypasses the relay entirely on the same WiFi.
 */
class LanClient(
    private val scope: CoroutineScope,
    private val onMessage: (Message) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val outbound = Channel<String>(Channel.UNLIMITED)
    private var socket: WebSocket? = null
    private var loopJob: Job? = null
    private var currentUrl: String = ""

    fun connect(host: String, port: Int, deviceId: String, deviceName: String, roomToken: String) {
        val url = "ws://$host:$port/ws"
        if (url == currentUrl && loopJob?.isActive == true) return
        Log.d(TAG, "Connecting to $url")
        disconnect()
        currentUrl = url
        val joinMsg = Message.deviceJoin(deviceId, deviceName, roomToken)
        loopJob = scope.launch { connectLoop(url, joinMsg) }
    }

    fun disconnect() {
        loopJob?.cancel()
        loopJob = null
        socket?.close(1000, "stopped")
        socket = null
        currentUrl = ""
    }

    fun send(message: Message) {
        outbound.trySend(message.toJson())
    }

    fun isConnected(): Boolean = socket != null

    private suspend fun connectLoop(url: String, joinMsg: Message) {
        var backoff = 1L
        while (currentCoroutineContext().isActive) {
            try {
                runConnection(url, joinMsg)
                backoff = 1L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "LAN connect failed: ${e.message}")
            }
            Log.d(TAG, "LAN retry in ${backoff}s")
            delay(backoff * 1000L)
            backoff = min(backoff * 2, 30L)
        }
    }

    private suspend fun runConnection(url: String, joinMsg: Message) = coroutineScope {
        val req = Request.Builder().url(url).build()
        val ready = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Throwable?>()

        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "LAN onOpen ${response.code} $url")
                socket = webSocket
                ready.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = Message.parse(text) ?: return
                if (msg.type == MessageType.PING || msg.type == MessageType.PONG) return
                onMessage(msg)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "LAN onClosing $code $reason")
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "LAN onClosed $code $reason")
                if (!finished.isCompleted) finished.complete(null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "LAN onFailure ${response?.code ?: "n/a"}: ${t.message}")
                if (!ready.isCompleted) ready.completeExceptionally(t)
                if (!finished.isCompleted) finished.complete(t)
            }
        })

        try {
            ready.await()
            socket = ws
            onConnected()
            ws.send(joinMsg.toJson())

            val pump = launch {
                for (line in outbound) { if (!ws.send(line)) break }
            }
            val err = finished.await()
            pump.cancel()
            socket = null
            onDisconnected()
            if (err != null) throw err
        } finally {
            socket = null
        }
    }
}
