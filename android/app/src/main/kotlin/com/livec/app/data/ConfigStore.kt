package com.livec.app.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "livec_config")

data class AppConfig(
    val deviceId: String,
    val deviceName: String,
    val roomToken: String,
    val relayUrl: String,
)

class ConfigStore(private val context: Context) {
    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val ROOM_TOKEN = stringPreferencesKey("room_token")
        val RELAY_URL = stringPreferencesKey("relay_url")
    }

    val flow: Flow<AppConfig> = context.dataStore.data.map { prefs ->
        AppConfig(
            deviceId = prefs[Keys.DEVICE_ID] ?: "",
            deviceName = prefs[Keys.DEVICE_NAME] ?: Build.MODEL,
            roomToken = prefs[Keys.ROOM_TOKEN] ?: "",
            relayUrl = prefs[Keys.RELAY_URL] ?: "",
        )
    }

    suspend fun get(): AppConfig = flow.first()

    suspend fun ensureDeviceId(): String {
        val current = get().deviceId
        if (current.isNotEmpty()) return current
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.DEVICE_ID] = id }
        return id
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
}
