package com.ismartcoding.plain.chat.peer.transport.aware

import android.Manifest
import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.ismartcoding.plain.chat.peer.PeerCacher
import com.ismartcoding.plain.lib.isRPlus
import com.ismartcoding.plain.lib.logcat.LogCat
import kotlinx.coroutines.CompletableDeferred
import java.net.Inet6Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

@RequiresApi(Build.VERSION_CODES.Q)
@SuppressLint("MissingPermission")
class AwareClient(
    private val session: AwareSession,
    private val peerId: String,
    private val connectivityManager: ConnectivityManager,
    private val httpFactory: AwareHttpClientFactory,
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
    suspend fun connect(): ClientConnection {
        start()
        subscribeStarted.await()
        val handle = handleDeferred.await()
        return openNetwork(handle)
    }

    private suspend fun openNetwork(handle: PeerHandle): ClientConnection {
        val sub = subscribeSession ?: error("subscribe session not ready")
        val pmk = derivePmk(peerId)
        val builder = WifiAwareNetworkSpecifier.Builder(sub, handle)
        if (pmk != null && isRPlus()) {
            builder.setPmk(pmk)
        }
        val specifier = builder.build()

        val ready = CompletableDeferred<ClientConnection>()
        var pendingNetwork: Network? = null
        var pendingIpv6: Inet6Address? = null
        var pendingPort: Int = 0

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                pendingNetwork = network
                tryComplete()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                pendingIpv6 = info.peerIpv6Addr
                pendingPort = info.port
                tryComplete()
            }

            override fun onUnavailable() {
                if (!ready.isCompleted) {
                    ready.completeExceptionally(IllegalStateException("client network unavailable"))
                }
            }

            override fun onLost(network: Network) {
                if (!ready.isCompleted) {
                    ready.completeExceptionally(IllegalStateException("client network lost"))
                }
            }

            fun tryComplete() {
                if (ready.isCompleted) return
                val n = pendingNetwork ?: return
                val ip = pendingIpv6 ?: return
                val p = pendingPort
                if (p <= 0) return
                    ready.complete(
                        ClientConnection(
                            network = n,
                            peerIpv6 = ip,
                            peerPort = p,
                            httpClient = httpFactory.build(peerId, n, ip, p),
                        ),
                    )
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
            LogCat.e("Wi-Fi Aware client requestNetwork error: ${e.message}")
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