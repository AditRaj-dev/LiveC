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
    const val PING = "ping"
    const val PONG = "pong"
    const val ACK = "ack"
}

const val BROADCAST = "broadcast"

object Limits {
    const val MAX_FILE_BYTES = 100L * 1024L * 1024L
    const val MAX_TEXT_BYTES = 1L * 1024L * 1024L
    const val FILE_TTL_MS = 90_000L
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
