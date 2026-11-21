package com.simoneluconi.betterauto.audio

import com.simoneluconi.betterauto.audio.CopyWatch.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyWatchTest {
    private val watch = CopyWatch(silentMs = 8_000, checkMs = 1_000)

    /** Checks once a second from [from] to [to] (inclusive), returning the actions taken with their time. */
    private fun run(from: Long, to: Long, player: String?, lastSound: (Long) -> Long): List<Pair<Long, Action>> =
        (from..to step 1_000).map { it to watch.check(it, player, lastSound(it)) }.filter { it.second != Action.NONE }

    @Test
    fun aCopiedPlayerNeverGetsTheSpeaker() {
        assertEquals(emptyList<Pair<Long, Action>>(), run(1_000, 60_000, YT) { it - 20 })
    }

    @Test
    fun aPlayerThatRefusesTheCopyGetsTheSpeakerForGood() {
        val actions = run(1_000, 60_000, SPOTIFY) { 0L }
        assertEquals(listOf(9_000L to Action.SPEAKER_BACK), actions)
        assertFalse(watch.mutesItself)
        assertTrue(watch.speakerBack)
    }

    @Test
    fun anotherPlayerHeardMutesThePhoneAgain() {
        run(1_000, 9_000, SPOTIFY) { 0L }
        assertEquals(listOf(10_000L to Action.MUTE_AGAIN), run(10_000, 10_000, SQUARE) { 9_900 })
        assertFalse(watch.speakerBack)
    }

    @Test
    fun aPlayerThatMutesItselfKeepsTheSpeakerAfterOneRound() {
        // The drive of 2026-11-20: heard only for the second after the phone's volume came back
        var heardAt = 0L
        val actions = mutableListOf<Pair<Long, Action>>()
        for (t in 1_000L..60_000L step 1_000) {
            val action = watch.check(t, YT, heardAt)
            if (action == Action.SPEAKER_BACK) heardAt = t + 500 // the player unmutes itself
            if (action != Action.NONE) actions += t to action
        }
        // Back, muted again, quiet again: then it stays on the phone, never cut again
        assertEquals(listOf(Action.SPEAKER_BACK, Action.MUTE_AGAIN, Action.SPEAKER_BACK), actions.map { it.second })
        assertTrue(watch.mutesItself)
        assertTrue(watch.speakerBack)
    }

    @Test
    fun theDrivesTimingIsCaughtToo() {
        // In the car: muted again at 10:33:01.0, given back again at 10:33:11.1 (10 s, not 8)
        assertEquals(listOf(9_000L to Action.SPEAKER_BACK), run(1_000, 9_000, YT) { 0L })
        assertEquals(Action.MUTE_AGAIN, watch.check(9_700, YT, 9_400))
        // Still heard for a moment after the muting, then quiet
        val actions = run(10_700, 30_700, YT) { minOf(it, 11_500L) }
        assertEquals(listOf(19_700L to Action.SPEAKER_BACK), actions)
        assertTrue(watch.mutesItself)
    }

    @Test
    fun nothingPlayingIsNeverAProblem() {
        assertEquals(emptyList<Pair<Long, Action>>(), run(1_000, 60_000, null) { 0L })
    }

    @Test
    fun theQuietStepIsNeverZero() {
        assertEquals(1, PhoneSpeakerMute.quietLevel(0))
        assertEquals(3, PhoneSpeakerMute.quietLevel(3))
    }

    private companion object {
        const val YT = "com.google.android.youtube"
        const val SPOTIFY = "com.spotify.music"
        const val SQUARE = "dev.lelonio.square"
    }
}
