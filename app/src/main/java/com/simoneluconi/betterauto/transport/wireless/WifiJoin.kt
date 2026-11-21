package com.simoneluconi.betterauto.transport.wireless

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import com.simoneluconi.betterauto.EventLog
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Joins the head unit's (or dongle's) WiFi for this app only, without internet and without touching the
 * phone's own WiFi settings. Android asks the driver to approve it unless BetterAuto has the car's WiFi
 * paired ([WifiPairing]): then the exact access point is approved already and no dialog comes up.
 * The request (and so the network) is held until [release], for the whole session; [onLost] is called if
 * the network goes away meanwhile. One request at a time in the whole app: a new one would cancel
 * Android's dialog for the one before. The rules live in [JoinPolicy].
 */
class WifiJoin(private val context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null
    /** Woken by [release] too, so that a cancelled connection does not wait out the request. */
    @Volatile
    private var waiting: CountDownLatch? = null

    @Volatile
    var onLost: (() -> Unit)? = null

    fun join(endpoint: WifiEndpoint): Network {
        val plan = JoinPolicy.plan(endpoint)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        if (wifi?.isWifiEnabled == false) {
            throw WifiJoinException(JoinFailure.WIFI_OFF, false, JoinPolicy.describe(JoinFailure.WIFI_OFF, plan.ssid, false, 0))
        }
        val paired = WifiPairing.isPaired(context, plan.bssid)
        val timeoutMs = JoinPolicy.timeoutMs(paired)
        EventLog.log(
            "Wireless: asking Android for \"${plan.ssid}\" at ${plan.bssid ?: "any access point"}, " +
                (if (paired) "paired with BetterAuto (no dialog)" else "not paired (Android asks the driver), up to ${timeoutMs / 1000} s") +
                ", app ${importance()}",
        )
        val specifier = WifiNetworkSpecifier.Builder().setSsid(plan.ssid).setIsHiddenSsid(plan.hidden).apply {
            // The exact access point: what a companion-device pairing approves
            plan.bssid?.let { setBssid(MacAddress.fromString(it)) }
            plan.passphrase?.let(::setWpa2Passphrase)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val ready = CountDownLatch(1)
        val started = SystemClock.elapsedRealtime()
        val network = AtomicReference<Network?>(null)
        val unavailableAfterMs = AtomicReference<Long?>(null)
        val newCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                network.set(available)
                ready.countDown()
            }

            override fun onUnavailable() {
                unavailableAfterMs.set(SystemClock.elapsedRealtime() - started)
                ready.countDown()
            }

            override fun onLost(lost: Network) {
                if (lost == network.get()) {
                    EventLog.log("Wireless: the head unit's WiFi was lost, the session ends")
                    onLost?.invoke()
                }
            }
        }
        takeOver()
        callback = newCallback
        waiting = ready
        connectivity.requestNetwork(request, newCallback, timeoutMs.toInt())
        ready.await(timeoutMs + 2_000, TimeUnit.MILLISECONDS)
        waiting = null
        if (callback !== newCallback) throw IOException("WiFi request withdrawn")
        network.get()?.let {
            EventLog.log("Wireless: on \"${plan.ssid}\" after ${(SystemClock.elapsedRealtime() - started) / 100 / 10.0} s")
            return it
        }
        release()
        val failure = JoinPolicy.classify(wifi?.isWifiEnabled != false, paired, unavailableAfterMs.get(), timeoutMs)
        unavailableAfterMs.get()?.let { EventLog.log("Wireless: Android answered \"unavailable\" after $it ms ($failure)") }
        throw WifiJoinException(failure, paired, JoinPolicy.describe(failure, plan.ssid, paired, timeoutMs))
    }

    /** Gives the network back (the session ended, or the join failed). */
    fun release() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        waiting?.countDown()
        synchronized(WifiJoin) { if (current === this) current = null }
    }

    /** Becomes the one request of the app, ending one still there (it would cancel its dialog anyway). */
    private fun takeOver() {
        val previous = synchronized(WifiJoin) { current.also { current = this } }
        if (previous != null && previous !== this) {
            EventLog.log("Wireless: an earlier WiFi request was still there, withdrawn")
            previous.release()
        }
    }

    /**
     * Android refuses a WiFi request at once from an app that is neither in the foreground nor running a
     * foreground service: said in the log next to each request.
     */
    private fun importance(): String {
        val info = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        return when {
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "in the foreground"
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "with a foreground service"
            else -> "in the background (importance ${info.importance}): Android may refuse"
        }
    }

    private companion object {
        /** The request in progress or held, in the whole app. */
        var current: WifiJoin? = null
    }
}
