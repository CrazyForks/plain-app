package com.ismartcoding.plain.chat.peer.transport.aware

import android.Manifest
import android.net.ConnectivityManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.ismartcoding.plain.db.DPeer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

@RequiresApi(Build.VERSION_CODES.Q)
class AwarePeerLink(
    val peerId: String,
    private val client: AwareClient,
    private val server: AwareServer?,
    private val onClose: (peerId: String, reason: String) -> Unit,
) {
    private val connection = AtomicReference<ClientConnection?>(null)
    private val buildLock = Mutex()
    private val closed = AtomicBoolean(false)

    val lastActiveAt = AtomicLong(System.currentTimeMillis())
    val inFlight = AtomicInteger(0)

    fun touch() {
        lastActiveAt.set(System.currentTimeMillis())
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE])
    fun prewarm() {
        client.start()
        server?.start()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.CHANGE_NETWORK_STATE])
    suspend fun build(peer: DPeer): ClientConnection {
        connection.get()?.let { return it }
        return buildLock.withLock {
            connection.get() ?: doBuild(peer).also { connection.set(it) }
        }
    }

    private suspend fun doBuild(peer: DPeer): ClientConnection = withTimeout(BUILD_TIMEOUT_MS.milliseconds) {
        client.connect()
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        connection.getAndSet(null)?.httpClient?.let { runCatching { it.connectionPool.evictAll() } }
        onClose(peerId, reason)
    }

    companion object {
        private const val BUILD_TIMEOUT_MS = 10_000L

        @RequiresApi(Build.VERSION_CODES.Q)
        @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.CHANGE_NETWORK_STATE])
        fun create(
            peer: DPeer,
            session: AwareSession,
            connectivityManager: ConnectivityManager,
            httpFactory: AwareHttpClientFactory,
            onClose: (peerId: String, reason: String) -> Unit,
        ): AwarePeerLink = AwarePeerLink(
            peerId = peer.id,
            client = AwareClient(session, peer.id, connectivityManager, httpFactory),
            server = null,
            onClose = onClose,
        )
    }
}