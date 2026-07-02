package com.ismartcoding.plain.chat.peer.transport

import android.Manifest
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.ismartcoding.plain.chat.peer.GraphQLResponse
import com.ismartcoding.plain.chat.peer.transport.aware.AwareHttpClientFactory
import com.ismartcoding.plain.chat.peer.transport.aware.AwareLinkPool
import com.ismartcoding.plain.chat.peer.transport.aware.AwareSession
import com.ismartcoding.plain.connectivityManager
import com.ismartcoding.plain.db.DPeer
import com.ismartcoding.plain.db.getAwareFileUrl
import com.ismartcoding.plain.lib.logcat.LogCat

@Suppress("DEPRECATION")
@RequiresApi(Build.VERSION_CODES.Q)
object WifiAwareTransport : PeerTransport {
    override val id: String = "aware"

    private val session = AwareSession()
    private val httpFactory = AwareHttpClientFactory()
    private val pool = AwareLinkPool(session, connectivityManager, httpFactory)
    @RequiresPermission(allOf = [Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.ACCESS_WIFI_STATE])
    fun isAvailable(): Boolean = session.isAvailable()

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun start() {
        session.start()
        pool.start()
    }

    fun stop() {
        pool.stop()
        session.stop()
    }

    fun shutdown() {
        pool.shutdown()
        session.stop()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.CHANGE_NETWORK_STATE])
    fun subscribeToPairedPeers(peers: List<DPeer>) {
        pool.subscribeToPairedPeers(peers)
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.CHANGE_NETWORK_STATE])
    override suspend fun downloadFile(peer: DPeer, fileId: String): DownloadedResponse {
        val link = pool.linkFor(peer)
        val connection = link.build(peer)
        link.inFlight.incrementAndGet()
        return try {
            link.touch()
            val client = httpFactory.buildFileDownload(connection.network, connection.peerIpv6)
            val url = peer.getAwareFileUrl(fileId, connection.peerPort)
            val response = executeDownloadRequest(id, peer.id, client, url)
            if (!response.isSuccessful) {
                response.close()
                LogCat.e("Aware transport file download failed: ${response.code}")
                throw TransportUnavailable(id, peer.id, null)
            }
            link.touch()
            DownloadedResponse(client, response)
        } finally {
            link.inFlight.decrementAndGet()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.CHANGE_NETWORK_STATE])
    override suspend fun send(peer: DPeer, request: SignedRequest, keyBytes: ByteArray): GraphQLResponse {
        val link = pool.linkFor(peer)
        link.inFlight.incrementAndGet()
        return try {
            val connection = link.build(peer)
            link.touch()
            val url = "https://${AwareHttpClientFactory.AWARE_HOST}:${connection.peerPort}/peer_graphql"
            val resp = executeGraphQLRequest(
                transportId = id,
                peerId = peer.id,
                client = connection.httpClient,
                url = url,
                body = request.body,
                channelId = request.channelId,
            )
            link.touch()
            resp
        } catch (e: TransportUnavailable) {
            link.close(reason = "send_failed")
            throw e
        } finally {
            link.inFlight.decrementAndGet()
        }
    }
}
