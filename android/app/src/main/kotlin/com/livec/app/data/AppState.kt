package com.livec.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-wide reactive state shared between LiveCService and the UI layer. */
object AppState {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices.asStateFlow()

    private val _clips = MutableStateFlow<List<ClipItem>>(emptyList())
    val clips: StateFlow<List<ClipItem>> = _clips.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    fun setConnected(value: Boolean) { _connected.value = value }

    fun upsertDevice(d: DeviceInfo) {
        _devices.update { list ->
            val existing = list.find { it.id == d.id }
            if (existing == null) {
                list + d
            } else if (existing.fingerprint.isEmpty() && d.fingerprint.isNotEmpty()) {
                // Backfill fingerprint when a later device_join carries one (LAN-vs-relay race).
                list.map { if (it.id == d.id) it.copy(fingerprint = d.fingerprint) else it }
            } else {
                list
            }
        }
    }

    fun removeDevice(id: String) {
        _devices.update { list -> list.filterNot { it.id == id } }
    }

    fun clearDevices() { _devices.value = emptyList() }

    fun addClip(item: ClipItem) {
        _clips.update { (listOf(item) + it).take(100) }
    }

    fun clearClips() { _clips.value = emptyList() }

    fun addTransfer(item: TransferItem) {
        _transfers.update { (listOf(item) + it).take(50) }
    }

    fun updateTransfer(id: String, patch: TransferItem.() -> TransferItem) {
        _transfers.update { list -> list.map { if (it.id == id) it.patch() else it } }
    }

    fun removeTransfer(id: String) {
        _transfers.update { list -> list.filterNot { it.id == id } }
    }

    fun clearTransfers() { _transfers.value = emptyList() }

    fun reset() {
        _connected.value = false
        _devices.value = emptyList()
        _clips.value = emptyList()
        _transfers.value = emptyList()
    }
}
