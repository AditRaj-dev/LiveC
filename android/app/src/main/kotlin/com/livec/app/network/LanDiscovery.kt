package com.livec.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.security.MessageDigest

private const val TAG = "LanDiscovery"
private const val SERVICE_TYPE = "_livec._tcp"

/**
 * Discovers Windows LiveC instances on the LAN via mDNS.
 * Filters by room_hash so only same-room peers are returned.
 * Calls [onPeerFound] with (host, port) when a matching peer resolves.
 */
class LanDiscovery(
    context: Context,
    private val roomToken: String,
    private val deviceId: String,
    private val onPeerFound: (host: String, port: Int) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val expectedHash = roomHash(roomToken)
    private val pendingResolves = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        advertise()
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            Log.d(TAG, "Discovery started, expecting room_hash=$expectedHash")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start discovery: ${e.message}")
        }
    }

    fun stop() {
        try { nsd.unregisterService(registrationListener) } catch (_: Exception) {}
        try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
    }

    private fun advertise() {
        val info = NsdServiceInfo().apply {
            serviceName = deviceId
            serviceType = SERVICE_TYPE
            port = 1 // placeholder — Android has no WS server, port unused
            setAttribute("room_hash", expectedHash)
            setAttribute("device_id", deviceId)
            setAttribute("platform", "android")
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS register failed: ${e.message}")
        }
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.d(TAG, "mDNS registered: ${info.serviceName}")
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
            Log.w(TAG, "mDNS registration failed: $code")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) {
            Log.w(TAG, "Discovery start failed: $code")
        }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}

        override fun onServiceFound(info: NsdServiceInfo) {
            // Skip our own advertisement — Android doesn't run a LAN server.
            if (info.serviceName == deviceId) {
                Log.d(TAG, "Service found: ${info.serviceName} (self, skipping)")
                return
            }
            Log.d(TAG, "Service found: ${info.serviceName}")
            pendingResolves.addLast(info)
            drainResolves()
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            Log.d(TAG, "Service lost: ${info.serviceName}")
        }
    }

    private fun drainResolves() {
        if (resolving || pendingResolves.isEmpty()) return
        val info = pendingResolves.removeFirst()
        resolving = true
        try {
            nsd.resolveService(info, resolveListener)
        } catch (e: Exception) {
            Log.w(TAG, "Resolve error: ${e.message}")
            resolving = false
            drainResolves()
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
            Log.w(TAG, "Resolve failed for ${info.serviceName}: $code")
            resolving = false
            drainResolves()
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            resolving = false
            val peerHash = info.attributes["room_hash"]?.let { String(it) }
            val peerDeviceId = info.attributes["device_id"]?.let { String(it) } ?: info.serviceName
            val peerPlatform = info.attributes["platform"]?.let { String(it) }
            val host = info.host?.hostAddress
            Log.d(TAG, "Resolved ${info.serviceName}: host=$host port=${info.port} room_hash=$peerHash platform=$peerPlatform")
            val isSelf = peerDeviceId == deviceId
            val portUsable = info.port > 1
            if (!isSelf && portUsable && peerHash == expectedHash && host != null) {
                onPeerFound(host, info.port)
            } else if (isSelf) {
                Log.d(TAG, "Skip self resolution")
            } else if (!portUsable) {
                Log.d(TAG, "Skip peer with unusable port ${info.port}")
            }
            drainResolves()
        }
    }

    companion object {
        fun roomHash(roomToken: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(roomToken.toByteArray(Charsets.UTF_8))
            return bytes.take(4).joinToString("") { "%02x".format(it) }
        }
    }
}
