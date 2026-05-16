package com.livec.app.data

// Mirror of D:\LiveC\PROTOCOL.md. Update both together.

object Paths {
    const val WS = "/ws"
    const val UPLOAD = "/upload"
    const val DOWNLOAD = "/download" // append /:fileId
    const val HEALTH = "/health"
}

object MessageType {
    const val DEVICE_JOIN = "device_join"
    const val DEVICE_LEAVE = "device_leave"
    const val CLIPBOARD_TEXT = "clipboard_text"
    const val CLIPBOARD_IMAGE = "clipboard_image"
    const val CLIPBOARD_CLEAR = "clipboard_clear"
    const val FILE_META = "file_meta"
    const val FILE_EXPIRED = "file_expired"
    const val FILES_CLEAR = "files_clear"
    const val FILE_OFFER = "file_offer"
    const val FILE_ACCEPT = "file_accept"
    const val FILE_REJECT = "file_reject"
    const val FILE_READY = "file_ready"
    const val FILE_DONE = "file_done"
    const val PING = "ping"
    const val PONG = "pong"
    const val ACK = "ack"
}

const val BROADCAST = "broadcast"

object Limits {
    const val MAX_FILE_BYTES = 10L * 1024L * 1024L * 1024L              // 10 GB
    const val MAX_TEXT_BYTES = 1L * 1024L * 1024L
    const val FILE_TTL_MS = 7L * 24L * 60L * 60L * 1000L                // 7 days
    const val OFFER_TTL_MS = 24L * 60L * 60L * 1000L                    // 24h
    const val OFFLINE_QUEUE_TTL_MS = 7L * 24L * 60L * 60L * 1000L       // 7 days
    const val OFFLINE_QUEUE_MAX_PER_DEVICE = 200
    const val CHUNK_SIZE = 8 * 1024 * 1024                              // 8 MB
}

const val UPLOAD_FIELD_NAME = "file"
const val PLATFORM = "android"

/** Convert ws[s]://…/ws → http[s]://… */
fun relayToHttpBase(relayUrl: String): String {
    var u = relayUrl
    when {
        u.startsWith("wss://") -> u = u.replaceFirst("wss://", "https://")
        u.startsWith("ws://")  -> u = u.replaceFirst("ws://", "http://")
    }
    if (u.endsWith(Paths.WS)) u = u.dropLast(Paths.WS.length)
    return u
}

fun downloadUrl(httpBase: String, fileId: String): String =
    "$httpBase${Paths.DOWNLOAD}/$fileId"
