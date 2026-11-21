package com.simoneluconi.betterauto.transport.wireless

import aap_protobuf.aaw.WifiSecurityModeOuterClass.WifiSecurityMode
import java.io.IOException

/** Why joining the head unit's WiFi did not work: what the driver is told, and whether trying again can help. */
enum class JoinFailure {
    /** The phone's WiFi is switched off: Android refuses every request at once, the whole time. */
    WIFI_OFF,

    /** Refused at once with the WiFi on, before any dialog: app not in the foreground, or approval blocked. */
    REFUSED,

    /** Not paired, and Android gave up while its "connect to this device?" dialog was up: cancelled or dismissed. */
    DECLINED,

    /** Paired (no dialog), and the connection itself failed. */
    FAILED,

    /** No answer in time: the dialog left unanswered, or the network not found. */
    TIMED_OUT,
}

/** A join that did not work, and why ([failure]); [paired]: BetterAuto had the car's WiFi paired, no dialog. */
class WifiJoinException(val failure: JoinFailure, val paired: Boolean, message: String) : IOException(message)

/** What BetterAuto asks Android for: the WiFi network specifier's fields, from what the head unit sent. */
data class JoinPlan(
    val ssid: String,
    /** The exact access point, upper case: lets a companion-device pairing skip Android's dialog. Null if unusable. */
    val bssid: String?,
    /** WPA2 passphrase; null for an open network. */
    val passphrase: String?,
    /** Dongles often hide their network: without asking for it by name the phone never finds it. */
    val hidden: Boolean = true,
)

/**
 * The pure rules of the WiFi join, apart from Android so they can be tested: the specifier, how long to wait,
 * how to read Android's answer and when another try makes sense.
 *
 * Android (WifiNetworkFactory) answers a request with a WiFi network specifier at once with "unavailable"
 * when the WiFi is off or the app is not in the foreground; otherwise it scans and, unless the app has a
 * companion-device association with the access point's BSSID (or an earlier approval), shows the driver a
 * dialog. Cancelling or dismissing it ends the request; leaving it ends it at the request's timeout.
 */
object JoinPolicy {
    /** Paired: no dialog, only the scan and the connection. */
    const val PAIRED_TIMEOUT_MS = 30_000L

    /** Not paired: the driver may need a while to see and answer Android's dialog. */
    const val DIALOG_TIMEOUT_MS = 60_000L

    /** An answer sooner than this came before any dialog could be shown, let alone answered. */
    const val INSTANT_MS = 1_500L

    private val MAC = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    fun plan(endpoint: WifiEndpoint): JoinPlan {
        val passphrase = when (endpoint.security) {
            WifiSecurityMode.OPEN -> null
            WifiSecurityMode.WPA_PERSONAL, WifiSecurityMode.WPA2_PERSONAL, WifiSecurityMode.WPA_WPA2_PERSONAL,
            WifiSecurityMode.UNKNOWN_SECURITY_MODE -> endpoint.password
            else -> throw IOException("WiFi security ${endpoint.security} is not supported")
        }
        return JoinPlan(endpoint.ssid, accessPoint(endpoint.bssid), passphrase)
    }

    /** The BSSID as Android wants it, or null when it is missing, malformed, all zeros or broadcast. */
    fun accessPoint(bssid: String?): String? {
        val mac = bssid?.trim()?.uppercase() ?: return null
        if (!MAC.matches(mac) || mac == "00:00:00:00:00:00" || mac == "FF:FF:FF:FF:FF:FF") return null
        return mac
    }

    fun timeoutMs(paired: Boolean) = if (paired) PAIRED_TIMEOUT_MS else DIALOG_TIMEOUT_MS

    /**
     * Reads Android's answer. [wifiOn]: the phone's WiFi after the request; [unavailableAfterMs]: when
     * onUnavailable came, null if it never did.
     */
    fun classify(wifiOn: Boolean, paired: Boolean, unavailableAfterMs: Long?, timeoutMs: Long): JoinFailure = when {
        !wifiOn -> JoinFailure.WIFI_OFF
        // Android's own timeout also ends in onUnavailable, right at the end
        unavailableAfterMs == null || unavailableAfterMs >= timeoutMs - INSTANT_MS -> JoinFailure.TIMED_OUT
        unavailableAfterMs < INSTANT_MS -> JoinFailure.REFUSED
        paired -> JoinFailure.FAILED
        else -> JoinFailure.DECLINED
    }

    /**
     * Whether another try right away can help. Never against a dialog the driver cancelled, dismissed or
     * ignored (each try would bring it up again, and end it), nor while Android refuses outright.
     */
    fun canRetry(failure: JoinFailure, paired: Boolean): Boolean = when (failure) {
        JoinFailure.FAILED -> true
        // Paired: no dialog was ignored, the network was not there yet (a head unit still starting)
        JoinFailure.TIMED_OUT -> paired
        JoinFailure.WIFI_OFF, JoinFailure.REFUSED, JoinFailure.DECLINED -> false
    }

    /**
     * Pairing the car's WiFi once is what the driver can do about it: the dialog was the problem. Not for an
     * instant refusal, which comes before any dialog (app not in the foreground, another app's request).
     */
    fun needsPairing(failure: JoinFailure, paired: Boolean): Boolean = when (failure) {
        JoinFailure.DECLINED -> true
        JoinFailure.TIMED_OUT -> !paired
        JoinFailure.WIFI_OFF, JoinFailure.REFUSED, JoinFailure.FAILED -> false
    }

    /** How long automatic connections wait after a join that must not be retried. */
    const val AUTO_HOLD_MS = 10 * 60_000L

    /**
     * The car keeps announcing itself over Bluetooth, and each time auto-connect would start over: the same
     * refusal, or the same dialog the driver just cancelled. After a join that must not be retried, automatic
     * starts wait until this time (null: no hold). A manual connection or a pairing lifts it.
     */
    fun autoHoldUntil(failure: JoinFailure, paired: Boolean, nowMs: Long): Long? =
        if (canRetry(failure, paired)) null else nowMs + AUTO_HOLD_MS

    fun autoAllowed(heldUntilMs: Long, nowMs: Long): Boolean = nowMs >= heldUntilMs

    /** For the log and the phone app, in plain words. */
    fun describe(failure: JoinFailure, ssid: String, paired: Boolean, timeoutMs: Long): String = when (failure) {
        JoinFailure.WIFI_OFF -> "the phone's WiFi is off: Android refuses to join \"$ssid\". Switch the WiFi on"
        JoinFailure.REFUSED ->
            "Android refused \"$ssid\" at once, before any dialog (BetterAuto not in the foreground, or another " +
                "app's WiFi request): open BetterAuto on the phone and connect from there"
        JoinFailure.DECLINED ->
            "Android refused \"$ssid\" (user rejected or approval blocked): open BetterAuto on the phone to pair the car's WiFi once"
        JoinFailure.FAILED -> "could not join the WiFi \"$ssid\""
        JoinFailure.TIMED_OUT ->
            if (paired) {
                "\"$ssid\" not found in ${timeoutMs / 1000} s"
            } else {
                "no answer to Android's WiFi dialog in ${timeoutMs / 1000} s (or \"$ssid\" not found): " +
                    "open BetterAuto on the phone to pair the car's WiFi once"
            }
    }
}
