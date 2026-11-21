package com.simoneluconi.betterauto.voice

import com.simoneluconi.betterauto.map.nav.Maneuver
import com.simoneluconi.betterauto.map.nav.ManeuverKind
import com.simoneluconi.betterauto.settings.SpokenVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceClipsTest {
    private fun m(kind: ManeuverKind, exit: Int? = null, stop: Int? = null) = Maneuver(kind, "", null, "", "", 0, exit, stop)

    @Test
    fun distancesRoundAsTheSpokenText() {
        assertEquals("m50", VoiceClips.distance(20.0))
        assertEquals("m250", VoiceClips.distance(260.0))
        assertEquals("m300", VoiceClips.distance(310.0))
        assertEquals("m900", VoiceClips.distance(940.0))
        assertEquals("km1", VoiceClips.distance(1180.0))
        assertEquals("km1_5", VoiceClips.distance(1520.0))
        assertEquals("km3", VoiceClips.distance(2600.0))
        // Beyond what was recorded: the phone's voice says it
        assertNull(VoiceClips.distance(14_000.0))
    }

    @Test
    fun maneuversAndArrivals() {
        assertEquals(listOf("m300", "right"), VoiceClips.ahead(300.0, m(ManeuverKind.RIGHT)))
        assertEquals("rb2", VoiceClips.maneuver(m(ManeuverKind.ROUNDABOUT, exit = 2), soon = false))
        assertEquals("roundabout", VoiceClips.maneuver(m(ManeuverKind.ROUNDABOUT, exit = 9), soon = false))
        assertEquals("arrive_soon", VoiceClips.maneuver(m(ManeuverKind.ARRIVE), soon = true))
        assertEquals("stop_now", VoiceClips.maneuver(m(ManeuverKind.ARRIVE, stop = 0), soon = false))
    }
}

class RecordedRepliesTest {
    private val templates = mapOf("I didn't get “%1\$s”" to "not_understood", "Calling %1\$s, %2\$s" to "calling", "Paused" to "paused")

    @Test
    fun repliesWithNamesFindTheirRecording() {
        assertEquals("not_understood", VoiceClips.recordedFor("I didn't get “open the radio please”", templates))
        assertEquals("calling", VoiceClips.recordedFor("Calling Ahmed Ali, mobile", templates))
        assertEquals("paused", VoiceClips.recordedFor("Paused", templates))
        assertNull(VoiceClips.recordedFor("Playing Amr Diab", templates))
    }
}

class VoiceFallbackTest {
    private val soraya = setOf("right.ogg", "m300.ogg", "greet1.ogg")
    private fun list(assets: Map<String, Set<String>>): (String) -> Set<String> = { assets[it].orEmpty() }

    @Test
    fun autonoeIsTheDefaultAndSorayaSpeaksForIt() {
        assertEquals(SpokenVoice.AUTONOE, SpokenVoice.DEFAULT)
        assertEquals(listOf("voice/autonoe/ar", "voice/soraya/ar"), VoiceClips.folders(SpokenVoice.DEFAULT, "ar"))
    }

    @Test
    fun englishWithAutonoeIsSaidBySoraya() {
        // Autonoe has no English recordings: Soraya's, not the phone's voice
        val assets = list(mapOf("voice/autonoe/ar" to soraya, "voice/soraya/en" to soraya))
        val folders = VoiceClips.folders(SpokenVoice.AUTONOE, "en")
        assertEquals(listOf("voice/soraya/en/m300.ogg", "voice/soraya/en/right.ogg"), VoiceClips.resolve(listOf("m300", "right"), folders, assets))
    }

    @Test
    fun aBuildWithoutAutonoeFallsBackToSoraya() {
        val folders = VoiceClips.folders(SpokenVoice.AUTONOE, "ar")
        assertEquals(listOf("voice/soraya/ar/right.ogg"), VoiceClips.resolve(listOf("right"), folders, list(mapOf("voice/soraya/ar" to soraya))))
        // Present: Autonoe speaks
        val both = list(mapOf("voice/autonoe/ar" to soraya, "voice/soraya/ar" to soraya))
        assertEquals(listOf("voice/autonoe/ar/right.ogg"), VoiceClips.resolve(listOf("right"), folders, both))
        // A sentence Autonoe lacks a part of is said whole by Soraya, never two voices in one sentence
        val partial = list(mapOf("voice/autonoe/ar" to setOf("right.ogg"), "voice/soraya/ar" to soraya))
        assertEquals(listOf("voice/soraya/ar/m300.ogg", "voice/soraya/ar/right.ogg"), VoiceClips.resolve(listOf("m300", "right"), folders, partial))
        assertNull(VoiceClips.resolve(listOf("u_turn"), folders, partial))
    }

    @Test
    fun otherChoicesStayAsTheyWere() {
        assertEquals(listOf("voice/nadine/en", "voice/soraya/en"), VoiceClips.folders(SpokenVoice.NADINE, "en"))
        assertEquals(listOf("voice/soraya/ar"), VoiceClips.folders(SpokenVoice.SORAYA, "ar"))
        assertTrue(VoiceClips.folders(SpokenVoice.PHONE, "ar").isEmpty())
        assertTrue(VoiceClips.folders(SpokenVoice.AUTONOE, "it").isEmpty())
    }
}
