package com.ismartcoding.plain.chat.peer.transport.aware

import android.Manifest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.ismartcoding.plain.TempData
import com.ismartcoding.plain.appContext
import com.ismartcoding.plain.lib.extensions.hasPermission
import com.ismartcoding.plain.lib.isTPlus
import com.ismartcoding.plain.lib.logcat.LogCat
import com.ismartcoding.plain.wifiAwareManager
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

@RequiresApi(Build.VERSION_CODES.Q)
class AwareSession {

    @Volatile var session: WifiAwareSession? = null
        private set
    @Volatile var publish: PublishDiscoverySession? = null
        private set

    val isAttached: Boolean
        get() = session != null

    val isPublished: Boolean
        get() = publish != null

    private var ready = CompletableDeferred<WifiAwareSession>()
    private val attaching = AtomicBoolean(false)

    suspend fun awaitReady(): WifiAwareSession = ready.await()

    @Synchronized
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE, "android.permission.NEARBY_WIFI_DEVICES"])
    fun start() {
        if (attaching.get() || session != null) return
        ready = newReady()
        attaching.set(true)
        try {
            wifiAwareManager.attach(attachCallback, null)
        } catch (e: Throwable) {
            attaching.set(false)
            if (!ready.isCompleted) ready.completeExceptionally(e)
            LogCat.e("Wi-Fi Aware start error: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        runCatching { publish?.close() }
        publish = null
        runCatching { session?.close() }
        session = null
        attaching.set(false)
        if (!ready.isCompleted) {
            ready.cancel()
        }
        ready = newReady()
    }

    fun markStale() {
        stop()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE])
    fun isAvailable(): Boolean {
        return try {
            wifiAwareManager.isAvailable
        } catch (_: Throwable) {
            false
        }
    }

    private fun newReady(): CompletableDeferred<WifiAwareSession> = CompletableDeferred()

    private val attachCallback = object : AttachCallback() {
        override fun onAttached(s: WifiAwareSession) {
            if (session != null) {
                runCatching { s.close() }
                return
            }
            LogCat.d("Wi-Fi Aware session attached")
            session = s
            attaching.set(false)
            if (!ready.isCompleted) ready.complete(s)
            publishOwnService(s)
        }

        override fun onAttachFailed() {
            LogCat.e("Wi-Fi Aware attach failed")
            attaching.set(false)
            if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("attach failed"))
        }

        override fun onAwareSessionTerminated() {
            LogCat.e("Wi-Fi Aware session terminated")
            publish = null
            session = null
            if (!ready.isCompleted) {
                ready.completeExceptionally(IllegalStateException("session terminated"))
            }
        }
    }

    private fun publishOwnService(s: WifiAwareSession) {
        try {
            s.publish(
                PublishConfig.Builder()
                    .setServiceName(SERVICE_NAME)
                    .setServiceSpecificInfo(TempData.clientId.toByteArray(Charsets.UTF_8))
                    .build(),
                publishCallback,
                null,
            )
        } catch (e: Throwable) {
            LogCat.e("Wi-Fi Aware publish error: ${e.message}")
        }
    }

    private val publishCallback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(s: PublishDiscoverySession) {
            publish = s
            LogCat.d("Wi-Fi Aware publish started")
        }

        override fun onSessionConfigFailed() {
            LogCat.e("Wi-Fi Aware publish config failed")
            publish = null
        }

        override fun onSessionTerminated() {
            LogCat.e("Wi-Fi Aware publish terminated")
            publish = null
        }
    }

    companion object {
        const val SERVICE_NAME = "plain-peer"
    }
}