package com.ismartcoding.plain.chat.peer.transport.aware

import android.Manifest
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.ismartcoding.plain.chat.peer.PeerCacher
import com.ismartcoding.plain.lib.isRPlus
import com.ismartcoding.plain.lib.logcat.LogCat
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

@RequiresApi(Build.VERSION_CODES.Q)
class AwareServer(
    private val session: AwareSession,
    private val peerId: String,
    private val port: Int,
    private val connectivityManager: ConnectivityManager,
) {
    @Volatile private var subscribeSession: SubscribeDiscoverySession? = null
    private val started = AtomicBoolean(false)
    private val subscribeStarted = CompletableDeferred<Unit>()
    private val handleDeferred = CompletableDeferred<PeerHandle>()

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE])
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val wifiSession = session.session ?: run {
            val ex = IllegalStateException("AwareSession not attached")
            if (!subscribeStarted.isCompleted) subscribeStarted.completeExceptionally(ex)
            return
        }
        try {
            wifiSession.subscribe(
                SubscribeConfig.Builder().setServiceName(AwareSession.SERVICE_NAME).build(),
                discoveryCallback,
                null,
            )
        } catch (e: Exception) {
            LogCat.e("Wi-Fi Aware subscribe error: ${e.message}")
            if (!subscribeStarted.isCompleted) subscribeStarted.completeExceptionally(e)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.CHANGE_NETWORK_STATE])
    suspend fun connect(): ServerConnection {
        start()
        subscribeStarted.await()
        val handle = handleDeferred.await()
        return openNetwork(handle)
    }

    private suspend fun openNetwork(handle: PeerHandle): ServerConnection {
        val publish = session.publish ?: error("publish session not ready")
        val pmk = derivePmk(peerId)
        val builder = WifiAwareNetworkSpecifier.Builder(publish, handle).setPort(port)
        if (pmk != null && isRPlus()) {
            builder.setPmk(pmk)
        }
        val specifier = builder.build()

        val ready = CompletableDeferred<ServerConnection>()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!ready.isCompleted) {
                    ready.complete(ServerConnection(network = network, port = port))
                }
            }

            override fun onUnavailable() {
                if (!ready.isCompleted) {
                    ready.completeExceptionally(IllegalStateException("server network unavailable"))
                }
            }

            override fun onLost(network: Network) {
                if (!ready.isCompleted) {
                    ready.completeExceptionally(IllegalStateException("server network lost"))
                }
            }
        }

        try {
            connectivityManager.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(specifier)
                    .build(),
                callback,
                REQUEST_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            LogCat.e("Wi-Fi Aware server requestNetwork error: ${e.message}")
            if (!ready.isCompleted) ready.completeExceptionally(e)
        }
        return ready.await()
    }

    private fun derivePmk(peerId: String): ByteArray? = try {
        val raw = PeerCacher.getKeyBytes(peerId)
        when {
            raw == null || raw.isEmpty() -> {
                LogCat.w("Wi-Fi Aware no shared key for $peerId; using open link")
                null
            }
            raw.size == 32 -> raw
            else -> ByteArray(32).also { System.arraycopy(raw, 0, it, 0, min(raw.size, 32)) }
        }
    } catch (e: Exception) {
        LogCat.e("Wi-Fi Aware derive PMK failed: ${e.message}")
        null
    }

    private val discoveryCallback = object : DiscoverySessionCallback() {
        override fun onSubscribeStarted(s: SubscribeDiscoverySession) {
            subscribeSession = s
            if (!subscribeStarted.isCompleted) subscribeStarted.complete(Unit)
        }

        override fun onServiceDiscovered(
            peerHandle: PeerHandle,
            serviceSpecificInfo: ByteArray?,
            matchFilter: MutableList<ByteArray>,
        ) {
            val fromCid = serviceSpecificInfo?.toString(Charsets.UTF_8)
            if (fromCid == peerId && !handleDeferred.isCompleted) {
                handleDeferred.complete(peerHandle)
            }
        }

        override fun onSessionConfigFailed() {
            if (!subscribeStarted.isCompleted) {
                subscribeStarted.completeExceptionally(IllegalStateException("subscribe config failed"))
            }
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 5_000
    }
}