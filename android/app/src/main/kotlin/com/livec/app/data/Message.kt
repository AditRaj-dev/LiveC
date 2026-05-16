package com.livec.app.data

import org.json.JSONArray
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

    data class OfferFile(
        val fileId: String,
        val name: String,
        val size: Long,
        val sha256: String,
        val mimeType: String,
    )

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

        fun deviceJoin(
            deviceId: String,
            deviceName: String,
            roomToken: String,
            fingerprint: String,
        ): Message = Message(
            type = MessageType.DEVICE_JOIN,
            from = deviceId,
            to = BROADCAST,
            room = roomToken,
            payload = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("platform", PLATFORM)
                put("roomToken", roomToken)
                put("fingerprint", fingerprint)
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

        fun fileOffer(
            deviceId: String,
            roomToken: String,
            offerId: String,
            files: List<OfferFile>,
            target: String = BROADCAST,
        ): Message = Message(
            type = MessageType.FILE_OFFER,
            from = deviceId,
            to = target,
            room = roomToken,
            payload = JSONObject().apply {
                put("offerId", offerId)
                put("files", JSONArray().also { arr ->
                    files.forEach { f ->
                        arr.put(JSONObject().apply {
                            put("fileId", f.fileId)
                            put("name", f.name)
                            put("size", f.size)
                            put("sha256", f.sha256)
                            put("mimeType", f.mimeType)
                        })
                    }
                })
            },
        )

        fun fileAccept(
            deviceId: String,
            roomToken: String,
            offerId: String,
            fileIds: List<String>,
            senderDeviceId: String,
        ): Message = Message(
            type = MessageType.FILE_ACCEPT,
            from = deviceId,
            to = senderDeviceId,
            room = roomToken,
            payload = JSONObject().apply {
                put("offerId", offerId)
                put("fileIds", JSONArray(fileIds))
            },
        )

        fun fileReject(
            deviceId: String,
            roomToken: String,
            offerId: String,
            senderDeviceId: String,
        ): Message = Message(
            type = MessageType.FILE_REJECT,
            from = deviceId,
            to = senderDeviceId,
            room = roomToken,
            payload = JSONObject().apply { put("offerId", offerId) },
        )

        fun fileDone(
            deviceId: String,
            roomToken: String,
            offerId: String,
            fileId: String,
            senderDeviceId: String,
        ): Message = Message(
            type = MessageType.FILE_DONE,
            from = deviceId,
            to = senderDeviceId,
            room = roomToken,
            payload = JSONObject().apply {
                put("offerId", offerId)
                put("fileId", fileId)
            },
        )
    }
}

data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String,
    /** Hex-encoded SHA-256-based fingerprint. Empty for v0 peers. */
    val fingerprint: String = "",
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
    /** For incoming offers: deviceId of the sender, needed to route accept/reject. */
    val senderDeviceId: String? = null,
    /** For incoming offers: fileId list (serialised as comma-separated) needed for accept. */
    val offerFileIds: String? = null,
    /** The offerId — kept on both OFFER_PENDING and post-file_ready entries so we
     *  can send file_done back after a successful download. */
    val offerId: String? = null,
) {
    enum class Status { OFFER_PENDING, PENDING, DOWNLOADING, UPLOADING, DONE, ERROR, REJECTED }
    enum class Direction { INCOMING, OUTGOING }
}
