package com.simoneluconi.betterauto.transport.wireless

import aap_protobuf.aaw.WifiSecurityModeOuterClass.WifiSecurityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The WiFi join's rules: the specifier from what the head unit sent, how Android's answer is read, retries. */
class JoinPolicyTest {
    private fun endpoint(
        bssid: String = "02:00:00:aa:bb:cc",
        security: WifiSecurityMode = WifiSecurityMode.WPA2_PERSONAL,
    ) = WifiEndpoint("DIRECT-xy-Example", "secret123", bssid, security, "192.0.2.1", 5288)

    @Test
    fun `the specifier names the exact access point, upper case, with the passphrase`() {
        val plan = JoinPolicy.plan(endpoint())
        assertEquals("DIRECT-xy-Example", plan.ssid)
        assertEquals("02:00:00:AA:BB:CC", plan.bssid)
        assertEquals("secret123", plan.passphrase)
        assertTrue(plan.hidden)
    }

    @Test
    fun `an open network has no passphrase`() {
        assertNull(JoinPolicy.plan(endpoint(security = WifiSecurityMode.OPEN)).passphrase)
    }

    @Test
    fun `WPA, WPA2, mixed and unknown modes use the WPA2 passphrase`() {
        for (mode in listOf(
            WifiSecurityMode.WPA_PERSONAL, WifiSecurityMode.WPA2_PERSONAL,
            WifiSecurityMode.WPA_WPA2_PERSONAL, WifiSecurityMode.UNKNOWN_SECURITY_MODE,
        )) {
            assertEquals(mode.name, "secret123", JoinPolicy.plan(endpoint(security = mode)).passphrase)
        }
    }

    @Test(expected = IOException::class)
    fun `an enterprise network is refused`() {
        JoinPolicy.plan(endpoint(security = WifiSecurityMode.WPA2_ENTERPRISE))
    }

    @Test
    fun `an unusable BSSID leaves the access point open`() {
        for (bad in listOf("", "  ", "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "02:00:00:AA:BB", "02-00-00-AA-BB-CC", "zz:00:00:aa:bb:cc")) {
            assertNull("'$bad'", JoinPolicy.plan(endpoint(bssid = bad)).bssid)
        }
        assertEquals("02:00:00:AA:BB:CC", JoinPolicy.accessPoint(" 02:00:00:aa:bb:cc "))
        assertNull(JoinPolicy.accessPoint(null))
    }

    @Test
    fun `waits longer when Android's dialog may come up`() {
        assertTrue(JoinPolicy.timeoutMs(paired = false) >= 30_000)
        assertTrue(JoinPolicy.timeoutMs(paired = false) > JoinPolicy.timeoutMs(paired = true))
    }

    @Test
    fun `WiFi off wins over everything`() {
        assertEquals(JoinFailure.WIFI_OFF, JoinPolicy.classify(wifiOn = false, paired = true, unavailableAfterMs = 20, timeoutMs = 30_000))
        assertEquals(JoinFailure.WIFI_OFF, JoinPolicy.classify(wifiOn = false, paired = false, unavailableAfterMs = null, timeoutMs = 60_000))
    }

    @Test
    fun `an answer within moments is a refusal`() {
        // As on the phone: "unavailable" some 20-30 ms after the request
        assertEquals(JoinFailure.REFUSED, JoinPolicy.classify(wifiOn = true, paired = false, unavailableAfterMs = 25, timeoutMs = 60_000))
        assertEquals(JoinFailure.REFUSED, JoinPolicy.classify(wifiOn = true, paired = true, unavailableAfterMs = 25, timeoutMs = 30_000))
    }

    @Test
    fun `an answer later is the dialog cancelled, or a failed connection when paired`() {
        assertEquals(JoinFailure.DECLINED, JoinPolicy.classify(wifiOn = true, paired = false, unavailableAfterMs = 8_000, timeoutMs = 60_000))
        assertEquals(JoinFailure.FAILED, JoinPolicy.classify(wifiOn = true, paired = true, unavailableAfterMs = 8_000, timeoutMs = 30_000))
    }

    @Test
    fun `Android's timeout and no answer at all are a timeout`() {
        assertEquals(JoinFailure.TIMED_OUT, JoinPolicy.classify(wifiOn = true, paired = false, unavailableAfterMs = 60_003, timeoutMs = 60_000))
        assertEquals(JoinFailure.TIMED_OUT, JoinPolicy.classify(wifiOn = true, paired = false, unavailableAfterMs = 59_200, timeoutMs = 60_000))
        assertEquals(JoinFailure.TIMED_OUT, JoinPolicy.classify(wifiOn = true, paired = true, unavailableAfterMs = null, timeoutMs = 30_000))
    }

    @Test
    fun `never tries again against Android's refusal or the driver's answer`() {
        for (paired in listOf(true, false)) {
            assertFalse(JoinPolicy.canRetry(JoinFailure.WIFI_OFF, paired))
            assertFalse(JoinPolicy.canRetry(JoinFailure.REFUSED, paired))
            assertFalse(JoinPolicy.canRetry(JoinFailure.DECLINED, paired))
            assertTrue(JoinPolicy.canRetry(JoinFailure.FAILED, paired))
        }
        // An ignored dialog is not asked again; a paired network not there yet is
        assertFalse(JoinPolicy.canRetry(JoinFailure.TIMED_OUT, paired = false))
        assertTrue(JoinPolicy.canRetry(JoinFailure.TIMED_OUT, paired = true))
    }

    @Test
    fun `pairing is offered when the dialog is the problem`() {
        assertTrue(JoinPolicy.needsPairing(JoinFailure.DECLINED, paired = false))
        // An instant refusal comes before any dialog: pairing would not help
        assertFalse(JoinPolicy.needsPairing(JoinFailure.REFUSED, paired = false))
        assertTrue(JoinPolicy.needsPairing(JoinFailure.TIMED_OUT, paired = false))
        assertFalse(JoinPolicy.needsPairing(JoinFailure.TIMED_OUT, paired = true))
        assertFalse(JoinPolicy.needsPairing(JoinFailure.WIFI_OFF, paired = false))
        assertFalse(JoinPolicy.needsPairing(JoinFailure.FAILED, paired = true))
    }

    @Test
    fun `auto-connect waits after a join that must not be retried`() {
        val now = 1_000_000L
        val held = JoinPolicy.autoHoldUntil(JoinFailure.DECLINED, paired = false, nowMs = now)!!
        assertTrue(held > now)
        assertFalse(JoinPolicy.autoAllowed(held, now + 1_000))
        assertTrue(JoinPolicy.autoAllowed(held, held))
        assertEquals(held, JoinPolicy.autoHoldUntil(JoinFailure.WIFI_OFF, paired = true, nowMs = now))
        assertEquals(held, JoinPolicy.autoHoldUntil(JoinFailure.TIMED_OUT, paired = false, nowMs = now))
        // Worth another try: no hold
        assertNull(JoinPolicy.autoHoldUntil(JoinFailure.FAILED, paired = true, nowMs = now))
        assertNull(JoinPolicy.autoHoldUntil(JoinFailure.TIMED_OUT, paired = true, nowMs = now))
        // Nothing stored yet
        assertTrue(JoinPolicy.autoAllowed(0, now))
    }

    @Test
    fun `the log says what to do`() {
        assertTrue(JoinPolicy.describe(JoinFailure.WIFI_OFF, "X", false, 0).contains("WiFi is off"))
        val refused = JoinPolicy.describe(JoinFailure.DECLINED, "X", false, 60_000)
        assertTrue(refused.contains("user rejected or approval blocked"))
        assertTrue(refused.contains("pair the car's WiFi once"))
        val instant = JoinPolicy.describe(JoinFailure.REFUSED, "X", false, 60_000)
        assertTrue(instant.contains("before any dialog"))
        assertFalse(instant.contains("pair"))
    }
}
