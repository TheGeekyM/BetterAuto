package com.simoneluconi.betterauto.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickSessionTest {
    private data class Session(val packageName: String, val playing: Boolean)

    private fun pick(vararg sessions: Session) = pickSession(sessions.toList(), OWN, { it.packageName }) { it.playing }

    @Test
    fun ourRadioIsNeverFollowed() {
        // The radio plays and paused the song: the player keeps the song
        val song = Session("com.spotify.music", playing = false)
        assertEquals(song, pick(Session(OWN, playing = true), song))
        assertNull(pick(Session(OWN, playing = true)))
    }

    @Test
    fun thePlayingOneFirstElseTheFirst() {
        val first = Session("a", playing = false)
        val playing = Session("b", playing = true)
        assertEquals(playing, pick(first, playing))
        assertEquals(first, pick(first, Session("c", playing = false)))
        assertNull(pick())
    }

    @Test
    fun aCallIsNeverFollowed() {
        // The telecom service's session comes first while a call rings: the player keeps the song
        val song = Session("com.google.android.youtube", playing = false)
        assertEquals(song, pick(Session("com.android.server.telecom", playing = true), song))
        assertNull(pick(Session("com.android.server.telecom", playing = false)))
        assertNull(pick(Session("com.microsoft.teams", playing = false)))
    }

    @Test
    fun aPlayerStartingStopsTheRadio() {
        assertTrue(radioYields(radioOn = true, wasPlaying = false, playing = true, sinceTunedMs = 10_000))
    }

    @Test
    fun theRadioStaysOtherwise() {
        // Already playing when the radio tuned in (ignoring the pause): no start, the radio stays
        assertFalse(radioYields(radioOn = true, wasPlaying = true, playing = true, sinceTunedMs = 10_000))
        // Its last report before the pause lands, right after the tuning in
        assertFalse(radioYields(radioOn = true, wasPlaying = false, playing = true, sinceTunedMs = 500))
        assertFalse(radioYields(radioOn = false, wasPlaying = false, playing = true, sinceTunedMs = 10_000))
        assertFalse(radioYields(radioOn = true, wasPlaying = false, playing = false, sinceTunedMs = 10_000))
    }

    private companion object {
        const val OWN = "com.simoneluconi.betterauto"
    }
}
