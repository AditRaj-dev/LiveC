package com.livec.app.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "livec_config")

/** Phase 5b: a peer we've trusted via QR pinning or explicit approval. */
data class TrustedPeer(
    val fingerprint: String,
    val deviceName: String,
    val addedAt: Long,
    val quickMode: Boolean,
)

data class AppConfig(
    val deviceId: String,
    val deviceName: String,
    val roomToken: String,
    val relayUrl: String,
    /** Hex-encoded 32-byte Ed25519 public key. */
    val devicePubkey: String = "",
    /** Hex-encoded SHA-256(devicePubkey)[..16]. Stable per install. */
    val fingerprint: String = "",
    /** Phase 5b: peers we've trusted. Empty by default. */
    val trustedPeers: List<TrustedPeer> = emptyList(),
    /**
     * 0 = placeholder random bytes (pre-Phase-5b).
     * 1 = real Ed25519 keypair via BouncyCastle; private key in AndroidKeyStore-wrapped blob.
     */
    val identityVersion: Int = 0,
)

class ConfigStore(private val context: Context) {
    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val ROOM_TOKEN = stringPreferencesKey("room_token")
        val RELAY_URL = stringPreferencesKey("relay_url")
        val DEVICE_PUBKEY = stringPreferencesKey("device_pubkey")
        val FINGERPRINT = stringPreferencesKey("fingerprint")
        val TRUSTED_PEERS = stringPreferencesKey("trusted_peers")  // JSON array
        val IDENTITY_VERSION = intPreferencesKey("identity_version")
        val ENCRYPTED_PRIVKEY = stringPreferencesKey("encrypted_privkey")
    }

    val flow: Flow<AppConfig> = context.dataStore.data.map { prefs ->
        AppConfig(
            deviceId = prefs[Keys.DEVICE_ID] ?: "",
            deviceName = prefs[Keys.DEVICE_NAME] ?: Build.MODEL,
            roomToken = prefs[Keys.ROOM_TOKEN] ?: "",
            relayUrl = prefs[Keys.RELAY_URL] ?: "",
            devicePubkey = prefs[Keys.DEVICE_PUBKEY] ?: "",
            fingerprint = prefs[Keys.FINGERPRINT] ?: "",
            trustedPeers = parseTrustedPeers(prefs[Keys.TRUSTED_PEERS] ?: "[]"),
            identityVersion = prefs[Keys.IDENTITY_VERSION] ?: 0,
        )
    }

    suspend fun get(): AppConfig = flow.first()

    /**
     * Ensure the install has both a deviceId AND a real Ed25519 identity.
     * Migrates from the old opaque-bytes placeholder (identityVersion < 1)
     * or when the private key blob is missing (e.g. after app-data clear).
     * Returns the deviceId. Safe to call on every launch.
     */
    suspend fun ensureDeviceId(): String {
        val current = get()
        var id = current.deviceId
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString()
            context.dataStore.edit { it[Keys.DEVICE_ID] = id }
        }
        val needsIdentity = current.identityVersion < 1
            || current.devicePubkey.isEmpty()
            || loadPrivkey() == null
        if (needsIdentity) {
            val (pubkeyBytes, fingerprintBytes, privkeyBytes) = generateIdentity()
            val pubkeyHex = pubkeyBytes.joinToString("") { "%02x".format(it) }
            val fpHex = fingerprintBytes.joinToString("") { "%02x".format(it) }
            val encryptedPrivkey = SecureStore.encrypt(privkeyBytes)
            context.dataStore.edit {
                it[Keys.DEVICE_PUBKEY] = pubkeyHex
                it[Keys.FINGERPRINT] = fpHex
                it[Keys.ENCRYPTED_PRIVKEY] = encryptedPrivkey
                it[Keys.IDENTITY_VERSION] = 1
            }
        }
        return id
    }

    /**
     * Generate a real Ed25519 keypair via BouncyCastle.
     * Returns Triple(pubkeyBytes[32], fingerprintBytes[16], privkeyBytes[32]).
     * The private key must be stored encrypted — never in plain DataStore.
     */
    private fun generateIdentity(): Triple<ByteArray, ByteArray, ByteArray> {
        val gen = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }
        val kp = gen.generateKeyPair()
        val pubBytes = (kp.public as Ed25519PublicKeyParameters).encoded   // 32 B
        val privBytes = (kp.private as Ed25519PrivateKeyParameters).encoded // 32 B
        val hash = MessageDigest.getInstance("SHA-256").digest(pubBytes)
        val fpBytes = hash.copyOfRange(0, 16)
        return Triple(pubBytes, fpBytes, privBytes)
    }

    /**
     * Load the raw Ed25519 private key bytes (32 B), decrypting from the
     * AndroidKeyStore-wrapped blob stored in DataStore.
     * Returns null if the blob is absent or decryption fails.
     */
    suspend fun loadPrivkey(): ByteArray? {
        val prefs = context.dataStore.data.first()
        val b64 = prefs[Keys.ENCRYPTED_PRIVKEY] ?: return null
        if (b64.isEmpty()) return null
        return SecureStore.decrypt(b64)
    }

    suspend fun setRoom(relayUrl: String, roomToken: String) {
        context.dataStore.edit {
            it[Keys.RELAY_URL] = relayUrl
            it[Keys.ROOM_TOKEN] = roomToken
        }
    }

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { it[Keys.DEVICE_NAME] = name }
    }

    suspend fun setRelayUrl(url: String) {
        context.dataStore.edit { it[Keys.RELAY_URL] = url }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    // ── Trusted peers (Phase 5b) ──────────────────────────────────────────────

    suspend fun addTrustedPeer(fingerprint: String, deviceName: String, quickMode: Boolean = false) {
        if (fingerprint.isEmpty()) return
        val current = get().trustedPeers.toMutableList()
        val idx = current.indexOfFirst { it.fingerprint == fingerprint }
        val peer = TrustedPeer(
            fingerprint = fingerprint,
            deviceName = deviceName,
            addedAt = System.currentTimeMillis(),
            quickMode = quickMode,
        )
        if (idx >= 0) {
            current[idx] = current[idx].copy(deviceName = deviceName, quickMode = quickMode)
        } else {
            current.add(peer)
        }
        context.dataStore.edit { it[Keys.TRUSTED_PEERS] = serializeTrustedPeers(current) }
    }

    suspend fun removeTrustedPeer(fingerprint: String) {
        val updated = get().trustedPeers.filterNot { it.fingerprint == fingerprint }
        context.dataStore.edit { it[Keys.TRUSTED_PEERS] = serializeTrustedPeers(updated) }
    }

    suspend fun setQuickMode(fingerprint: String, enabled: Boolean) {
        val updated = get().trustedPeers.map {
            if (it.fingerprint == fingerprint) it.copy(quickMode = enabled) else it
        }
        context.dataStore.edit { it[Keys.TRUSTED_PEERS] = serializeTrustedPeers(updated) }
    }

    suspend fun isTrusted(fingerprint: String): Boolean {
        if (fingerprint.isEmpty()) return false
        return get().trustedPeers.any { it.fingerprint == fingerprint }
    }

    suspend fun isQuickMode(fingerprint: String): Boolean {
        if (fingerprint.isEmpty()) return false
        return get().trustedPeers.any { it.fingerprint == fingerprint && it.quickMode }
    }
}

private fun parseTrustedPeers(json: String): List<TrustedPeer> = try {
    val arr = JSONArray(json)
    List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        TrustedPeer(
            fingerprint = o.optString("fingerprint"),
            deviceName = o.optString("deviceName"),
            addedAt = o.optLong("addedAt", 0L),
            quickMode = o.optBoolean("quickMode", false),
        )
    }
} catch (_: Exception) {
    emptyList()
}

private fun serializeTrustedPeers(peers: List<TrustedPeer>): String {
    val arr = JSONArray()
    for (p in peers) {
        arr.put(JSONObject().apply {
            put("fingerprint", p.fingerprint)
            put("deviceName", p.deviceName)
            put("addedAt", p.addedAt)
            put("quickMode", p.quickMode)
        })
    }
    return arr.toString()
}
