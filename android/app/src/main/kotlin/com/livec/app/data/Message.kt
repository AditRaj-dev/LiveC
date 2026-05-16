package com.livec.app.data

import org.json.JSONObject
import java.util.UUID

/** Protocol envelope. JSON-based to avoid extra serialization deps. */
data class Message(
    val type: String,
    val from: String,
    val to: String,
    val room: String,
    val payload: JSONObject,
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("from", from)
        put("to", to)
        put("room", room)
        put("timestamp", timestamp)
        put("payload", payload)
    }.toString()

    companion object {
        fun parse(text: String): Message? = try {
            val o = JSONObject(text)
            Message(
                id = o.optString("id", UUID.randomUUID().toString()),
                type = o.getString("type"),
                from = o.optString("from"),
                to = o.optString("to"),
                room = o.optString("room"),
                timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                payload = o.optJSONObject("payload") ?: JSONObject(),
            )
        } catch (e: Exception) {
            null
        }

        fun deviceJoin(deviceId: String, deviceName: String, roomToken: String): Message =
            Message(
                type = MessageType.DEVICE_JOIN,
                from = deviceId,
                to = BROADCAST,
                room = roomToken,
                payload = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("deviceName", deviceName)
                    put("platform", PLATFORM)
                    put("roomToken", roomToken)
                },
            )

        fun deviceLeave(deviceId: String, roomToken: String): Message =
            Message(
                type = MessageType.DEVICE_LEAVE,
                from = deviceId,
                to = BROADCAST,
                room = roomToken,
                payload = JSONObject().apply { put("deviceId", deviceId) },
            )

        fun clipboardText(deviceId: String, roomToken: String, text: String, target: String = BROADCAST): Message =
            Message(
                type = MessageType.CLIPBOARD_TEXT,
                from = deviceId,
                to = target,
                room = roomToken,
                payload = JSONObject().put("text", text),
            )

        fun ping(deviceId: String, roomToken: String): Message =
            Message(
                type = MessageType.PING, from = deviceId, to = BROADCAST,
                room = roomToken, payload = JSONObject(),
            )
    }
}

data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String,
    val lastSeen: Long = System.currentTimeMillis(),
)

data class ClipItem(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
    val text: String? = null,
    val downloadUrl: String? = null,
    val source: Source,
    val from: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Kind { TEXT, IMAGE }
    enum class Source { LOCAL, REMOTE }
}

data class TransferItem(
    val id: String,
    val name: String,
    val size: Long,
    val downloadUrl: String,
    val from: String,
    val status: Status,
    val direction: Direction,
    val savedPath: String? = null,
    val errorMsg: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Status { PENDING, DOWNLOADING, UPLOADING, DONE, ERROR }
    enum class Direction { INCOMING, OUTGOING }
}
