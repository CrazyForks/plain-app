package com.ismartcoding.plain.chat.peer.transport.aware

import android.net.Network
import com.ismartcoding.plain.api.OkHttpClientFactory
import com.ismartcoding.plain.api.applyDownloadConfig
import com.ismartcoding.plain.chat.peer.PeerCacher
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet6Address
import java.net.InetAddress

class AwareHttpClientFactory {
    fun build(
        peerId: String,
        network: Network,
        peerIpv6: Inet6Address,
        peerPort: Int,
    ): OkHttpClient {
        val keyBytes = requireNotNull(PeerCacher.getKeyBytes(peerId)) {
            "PeerCacher has no key bytes for peer $peerId"
        }
        return OkHttpClientFactory.createCryptoHttpClient(
            keyBytes = keyBytes,
            timeout = 30,
            socketFactory = network.socketFactory,
            dns = awareDns(peerIpv6),
            connectTimeoutMs = 5_000L,
        ).newBuilder()
            .retryOnConnectionFailure(true)
            .build()
    }

    fun buildFileDownload(
        network: Network,
        peerIpv6: Inet6Address,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .dns(awareDns(peerIpv6))
            .applyDownloadConfig()
            .build()
    }

    companion object {
        const val AWARE_HOST = "plain-aware-peer"

        private fun awareDns(peerIpv6: Inet6Address): Dns = Dns { hostname ->
            if (hostname == AWARE_HOST) listOf<InetAddress>(peerIpv6) else Dns.SYSTEM.lookup(hostname)
        }
    }
}
