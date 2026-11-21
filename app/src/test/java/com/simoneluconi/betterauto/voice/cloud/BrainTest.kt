package com.simoneluconi.betterauto.voice.cloud

import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.settings.VoiceBrain
import com.simoneluconi.betterauto.voice.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTest {
    @Test
    fun `online only with keys, internet and Android 13`() {
        assertEquals(Brain.Route(true, true, "auto: local first, online for the rest"), Brain.route(VoiceBrain.AUTO, true, true, 36))
        assertEquals(true to true, Brain.route(VoiceBrain.ONLINE, true, true, 36).let { it.cloud to !it.localFirst })
        assertFalse(Brain.route(VoiceBrain.OFFLINE, true, true, 36).cloud)
        assertFalse(Brain.route(VoiceBrain.AUTO, false, true, 36).cloud)
        assertFalse(Brain.route(VoiceBrain.ONLINE, false, true, 36).cloud)
        assertFalse(Brain.route(VoiceBrain.AUTO, true, false, 36).cloud)
        assertFalse(Brain.route(VoiceBrain.ONLINE, true, false, 36).cloud)
        assertFalse(Brain.route(VoiceBrain.AUTO, true, true, 32).cloud)
        assertEquals("offline: no internet", Brain.route(VoiceBrain.AUTO, true, false, 36).why)
        assertEquals("offline: this build has no Gemini/Anthropic keys", Brain.route(VoiceBrain.AUTO, false, false, 36).why)
    }

    @Test
    fun `clear commands take the fast path`() {
        val recent = listOf("Roadhouse Grill", "City Stars Mall")
        listOf(VoiceCommand.Pause, VoiceCommand.Next, VoiceCommand.Play("Amr Diab"), VoiceCommand.Volume("up"),
            VoiceCommand.Open(Section.RADIO), VoiceCommand.AcPower(true), VoiceCommand.AcTemp(value = 22), VoiceCommand.Fan(1),
            VoiceCommand.Tires, VoiceCommand.EndNavigation, VoiceCommand.Cancel,
            VoiceCommand.Call(listOf("Ahmed Ali"), null, exact = true), VoiceCommand.Navigate("roadhouse grill"),
        ).forEach { assertTrue(it.toString(), Brain.fastPath(it, recent)) }
    }

    @Test
    fun `anything doubtful goes online`() {
        val recent = listOf("Roadhouse Grill")
        assertFalse(Brain.fastPath(null, recent))
        assertFalse(Brain.fastPath(VoiceCommand.Call(listOf("Ahmed Ali", "Ahmed Samy"), null), recent))
        assertFalse(Brain.fastPath(VoiceCommand.Call(listOf("Ahmed Ali"), null, exact = false), recent))
        assertFalse(Brain.fastPath(VoiceCommand.Navigate("the nearest pharmacy"), recent))
        assertFalse(Brain.fastPath(VoiceCommand.AddStop("a gas station"), recent))
    }
}
