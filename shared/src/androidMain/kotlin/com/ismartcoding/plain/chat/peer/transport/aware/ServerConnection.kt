package com.ismartcoding.plain.chat.peer.transport.aware

import android.net.Network

data class ServerConnection(
    val network: Network,
    val port: Int,
)