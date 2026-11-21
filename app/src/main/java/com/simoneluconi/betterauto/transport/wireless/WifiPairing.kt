package com.simoneluconi.betterauto.transport.wireless

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.WifiDeviceFilter
import android.content.Context
import android.content.IntentSender
import android.net.MacAddress
import android.os.Build
import androidx.core.content.edit
import com.simoneluconi.betterauto.EventLog

/**
 * The car's WiFi paired with BetterAuto through the companion device manager, so that Android joins it
 * without its "connect to this device?" dialog every time. Android skips that dialog for a request that
 * names the exact access point (BSSID) when the requesting app has a companion association with that MAC
 * address (WifiNetworkFactory, isDeviceAssociatedForWifiConnection). The association is made once, from
 * the phone app (Android's own dialog, which needs an activity, the phone's WiFi on and the car's WiFi in
 * range); the Bluetooth association of auto-connect does not count, its address is another one.
 *
 * The access point is learnt from the head unit's bootstrap ([remember]): a head unit that comes up with a
 * new address needs pairing again, which the phone app then offers.
 */
object WifiPairing {
    private const val KEY_SSID = "wifi_ssid"
    private const val KEY_BSSID = "wifi_bssid"

    /** The car's WiFi as the head unit last described it. */
    data class AccessPoint(val ssid: String, val bssid: String)

    fun remember(context: Context, endpoint: WifiEndpoint) {
        val bssid = JoinPolicy.accessPoint(endpoint.bssid) ?: return
        val prefs = context.wirelessPrefs()
        if (prefs.getString(KEY_BSSID, null) == bssid && prefs.getString(KEY_SSID, null) == endpoint.ssid) return
        prefs.edit {
            putString(KEY_SSID, endpoint.ssid)
            putString(KEY_BSSID, bssid)
        }
    }

    fun known(context: Context): AccessPoint? {
        val prefs = context.wirelessPrefs()
        val ssid = prefs.getString(KEY_SSID, null) ?: return null
        val bssid = prefs.getString(KEY_BSSID, null) ?: return null
        return AccessPoint(ssid, bssid)
    }

    /** BetterAuto has a companion association with the access point [bssid]. */
    fun isPaired(context: Context, bssid: String?): Boolean {
        if (bssid == null) return false
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return runCatching { associationId(manager, bssid) != null || legacyAssociated(manager, bssid) }.getOrDefault(false)
    }

    /**
     * Pairs the last known access point. Android's dialog is needed: [onDialog] gets it to launch, [onDone]
     * says whether the phone is paired in the end (the dialog's result goes through [finished]).
     */
    fun pair(context: Context, onDialog: (IntentSender) -> Unit, onDone: (Boolean) -> Unit) {
        val point = known(context) ?: return onDone(false)
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return onDone(false)
        if (isPaired(context, point.bssid)) return onDone(true)
        val request = AssociationRequest.Builder()
            // By address only: a dongle may hide its name, and the address is what Android checks
            .addDeviceFilter(WifiDeviceFilter.Builder().setBssid(MacAddress.fromString(point.bssid)).build())
            .setSingleDevice(true)
            .build()
        EventLog.log("Wireless: pairing the car's WiFi \"${point.ssid}\" (Android looks for it)")
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onDialog(intentSender)

            @Deprecated("Android 12 calls this one instead of onAssociationPending")
            override fun onDeviceFound(intentSender: IntentSender) = onDialog(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                EventLog.log("Wireless: the car's WiFi is paired, Android joins it without asking")
                WirelessConnector.holdAuto(context, null)
                onDone(true)
            }

            override fun onFailure(error: CharSequence?) {
                EventLog.log("Wireless: the car's WiFi is not paired: $error")
                onDone(false)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.associate(request, context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                manager.associate(request, callback, null)
            }
        }.onFailure {
            EventLog.log("Wireless: cannot pair the car's WiFi: $it")
            onDone(false)
        }
    }

    /** After Android's dialog: whether it paired (Android 12 and older report it only through the result). */
    fun finished(context: Context, accepted: Boolean): Boolean {
        val paired = isPaired(context, known(context)?.bssid)
        if (paired) WirelessConnector.holdAuto(context, null)
        if (!paired) EventLog.log("Wireless: pairing the car's WiFi " + if (accepted) "did not complete" else "declined")
        return paired
    }

    /** Takes the pairing back: Android asks again at the next join. */
    fun unpair(context: Context) {
        val bssid = known(context)?.bssid ?: return
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                associationId(manager, bssid)?.let(manager::disassociate)
            } else {
                @Suppress("DEPRECATION")
                manager.disassociate(bssid)
            }
        }.onFailure { EventLog.log("Wireless: cannot unpair the car's WiFi: $it") }
        EventLog.log("Wireless: the car's WiFi unpaired")
    }

    private fun associationId(manager: CompanionDeviceManager, bssid: String): Int? =
        if (Build.VERSION.SDK_INT >= 33) {
            manager.myAssociations.firstOrNull { it.deviceMacAddress?.toString().equals(bssid, ignoreCase = true) }?.id
        } else {
            null
        }

    @Suppress("DEPRECATION")
    private fun legacyAssociated(manager: CompanionDeviceManager, bssid: String): Boolean =
        Build.VERSION.SDK_INT < 33 && manager.associations.any { it.equals(bssid, ignoreCase = true) }
}
