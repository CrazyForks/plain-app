package com.ismartcoding.plain.chat.peer.transport.aware

import android.Manifest
import android.net.ConnectivityManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.ismartcoding.plain.db.DPeer
import com.ismartcoding.plain.lib.logcat.LogCat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

@RequiresApi(Build.VERSION_CODES.Q)
internal class AwareLinkPool(
    private val session: AwareSession,
    private val connectivityManager: ConnectivityManager,
    private val httpFactory: AwareHttpClientFactory,
) {
    private val links = ConcurrentHashMap<String, AwarePeerLink>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var sweepJob: Job? = null

    fun start() {
        if (sweepJob?.isActive == true) return
        sweepJob = scope.launch {
            while (isActive) {
                delay(IDLE_SWEEP_INTERVAL_MS.milliseconds)
                sweepIdleLinks()
            }
        }
    }

    fun stop() {
        sweepJob?.cancel()
        sweepJob = null
        links.values.toList().forEach { it.close(reason = "stop") }
        links.clear()
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.CHANGE_NETWORK_STATE])
    fun subscribeToPairedPeers(peers: List<DPeer>) {
        if (peers.isEmpty()) return
        session.start()
        scope.launch {
            val ready = runCatching {
                withTimeoutOrNull(SESSION_WAIT_TIMEOUT_MS) { session.awaitReady(); true } == true
            }.getOrDefault(false)
            if (!ready) {
                LogCat.e("Wi-Fi Aware subscribeToPairedPeers: session not ready")
                return@launch
            }
            peers.forEach { peer -> runCatching { linkFor(peer) }.getOrNull()?.prewarm() }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.CHANGE_NETWORK_STATE])
    suspend fun linkFor(peer: DPeer): AwarePeerLink {
        session.start()
        session.awaitReady()
        return links.computeIfAbsent(peer.id) {
            AwarePeerLink.create(
                peer = peer,
                session = session,
                connectivityManager = connectivityManager,
                httpFactory = httpFactory,
                onClose = { peerId: String, reason: String ->
                    if (reason == "unavailable") {
                        session.markStale()
                    }
                    links.remove(peerId)
                },
            )
        }
    }

    private fun sweepIdleLinks() {
        val now = System.currentTimeMillis()
        links.forEach { (peerId, link) ->
            if (link.inFlight.get() > 0) return@forEach
            val last = link.lastActiveAt.get()
            if (last == 0L) return@forEach
            if (now - last > IDLE_TIMEOUT_MS) {
                LogCat.d("Wi-Fi Aware idle timeout for peer $peerId (${now - last}ms), closing link")
                link.close(reason = "idle")
            }
        }
    }

    companion object {
        private const val IDLE_TIMEOUT_MS = 60_000L
        private const val IDLE_SWEEP_INTERVAL_MS = 5_000L
        private const val SESSION_WAIT_TIMEOUT_MS = 5_000L
    }
}
