package com.livec.app.network

import android.util.Log
import com.livec.app.data.Message
import com.livec.app.data.MessageType
import com.livec.app.data.Paths
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlin.math.min

private const val TAG = "RelayClient"

/**
 * WebSocket client with exponential-backoff reconnect (1s → 30s max).
 * Single instance owned by LiveCService.
 */
class RelayClient(
    private val scope: CoroutineScope,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onMessage: (Message) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var loopJob: Job? = null
    private val outbound = Channel<String>(Channel.UNLIMITED)

    // Current connection params (set on start)
    private var relayUrl: String = ""
    private var joinMessage: Message? = null

    fun start(relayUrl: String, deviceId: String, deviceName: String, roomToken: String) {
        if (relayUrl.isBlank() || roomToken.isBlank()) {
            Log.w(TAG, "Skip start: relayUrl or roomToken empty")
            return
        }
        stop()
        // Convert http(s) base URL → ws(s) URL with /ws path
        val wsUrl = relayUrl
            .trimEnd('/')
            .let { u ->
                when {
                    u.startsWith("https://") -> u.replaceFirst("https://", "wss://")
                    u.startsWith("http://")  -> u.replaceFirst("http://",  "ws://")
                    else -> u  // already ws:// or wss://
                }
            }
            .let { u -> if (u.endsWith(Paths.WS)) u else u + Paths.WS }
        this.relayUrl = wsUrl
        Log.d(TAG, "Relay WS URL: $wsUrl")
        this.joinMessage = Message.deviceJoin(deviceId, deviceName, roomToken)

        loopJob = scope.launch { connectLoop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        socket?.close(1000, "stopped")
        socket = null
    }

    fun send(message: Message) {
        outbound.trySend(message.toJson())
    }

    fun isConnected(): Boolean = socket != null

    private suspend fun connectLoop() {
        var backoff = 1L
        while (currentCoroutineContext().isActive) {
            try {
                runConnection()
                // clean disconnect — reset backoff
                backoff = 1L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connect failed: ${e.message}")
            }
            Log.d(TAG, "Retrying in ${backoff}s")
            delay(backoff * 1000L)
            backoff = min(backoff * 2, 30L)
        }
    }

    private suspend fun runConnection() = coroutineScope {
        val request = Request.Builder().url(relayUrl).build()
        val ready = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Throwable?>()

        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Relay onOpen ${response.code} $relayUrl")
                socket = webSocket
                ready.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = Message.parse(text) ?: run {
                    Log.w(TAG, "RelayClient parse fail len=${text.length} preview=${text.take(80)}")
                    return
                }
                if (msg.type == MessageType.PONG || msg.type == MessageType.PING) return
                onMessage(msg)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Relay onClosing $code $reason")
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Relay onClosed $code $reason")
                if (!finished.isCompleted) finished.complete(null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Relay onFailure ${response?.code ?: "n/a"}: ${t.message}")
                if (!ready.isCompleted) ready.completeExceptionally(t)
                if (!finished.isCompleted) finished.complete(t)
            }
        })

        try {
            ready.await()
            socket = ws
            Log.d(TAG, "Relay connected, sending device_join")
            onConnected()

            // Send join
            joinMessage?.let { ws.send(it.toJson()) }

            // Outbound pump
            val pump = launch {
                for (line in outbound) {
                    if (!ws.send(line)) break
                }
            }

            // Heartbeat
            val joinMsg = joinMessage
            val hb = launch {
                while (isActive) {
                    delay(25_000)
                    if (joinMsg != null) {
                        ws.send(Message.ping(joinMsg.from, joinMsg.room).toJson())
                    }
                }
            }

            // Wait for connection to end
            val err = finished.await()
            pump.cancel()
            hb.cancel()
            socket = null
            onDisconnected()
            if (err != null) throw err
        } finally {
            socket = null
        }
    }

}
