package com.simoneluconi.betterauto.voice.wake

import com.simoneluconi.betterauto.settings.WakeSensitivity
import com.simoneluconi.betterauto.voice.wake.WakeGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeGateTest {
    private fun inputs(
        enabled: Boolean = true,
        call: Boolean = false,
        assistant: Boolean = false,
        yielding: Boolean = false,
        reading: Boolean = false,
        ownSound: Boolean = false,
        navPrompt: Boolean = false,
    ) = WakeGate.Inputs(enabled, call, assistant, yielding, reading, ownSound, navPrompt)

    @Test
    fun `quiet and idle listens`() {
        assertEquals(Decision.Listening, WakeGate().decide(inputs(), 0))
    }

    @Test
    fun `anything else holding the microphone releases it`() {
        val gate = WakeGate()
        assertEquals(Decision.Released("off"), gate.decide(inputs(enabled = false), 0))
        assertEquals(Decision.Released("call"), gate.decide(inputs(call = true), 0))
        assertEquals(Decision.Released("assistant"), gate.decide(inputs(assistant = true), 0))
        assertEquals(Decision.Released("assistant"), gate.decide(inputs(yielding = true), 0))
        assertEquals(Decision.Released("message being read"), gate.decide(inputs(reading = true), 0))
    }

    @Test
    fun `a call wins over everything, and releasing wins over muting`() {
        val gate = WakeGate()
        assertEquals(Decision.Released("call"), gate.decide(inputs(call = true, assistant = true, reading = true, ownSound = true), 0))
        assertEquals(Decision.Released("assistant"), gate.decide(inputs(assistant = true, ownSound = true, navPrompt = true), 0))
    }

    @Test
    fun `our sounds and navigation prompts mute, and for a while after`() {
        val gate = WakeGate(tailMs = 2_000)
        assertEquals(Decision.Muted("our sound"), gate.decide(inputs(ownSound = true), 1_000))
        assertEquals(Decision.Muted("navigation prompt"), gate.decide(inputs(navPrompt = true), 1_500))
        // The prompt ended at 1.5 s: still muted until 3.5 s
        assertEquals(Decision.Muted("after a sound"), gate.decide(inputs(), 3_499))
        assertEquals(Decision.Listening, gate.decide(inputs(), 3_500))
    }

    @Test
    fun `back from a pause the assistant's last words are not heard`() {
        val gate = WakeGate(tailMs = 2_000)
        gate.decide(inputs(assistant = true), 10_000)
        assertTrue(gate.decide(inputs(), 10_100) is Decision.Muted)
        assertEquals(Decision.Listening, gate.decide(inputs(), 12_000))
    }

    @Test
    fun `level-triggered - a turn that never started does not hold it off`() {
        // A wake whose assistant refused to start (voice commands off a moment): only the yield, then listening again
        val gate = WakeGate(tailMs = 2_000)
        assertEquals(Decision.Released("assistant"), gate.decide(inputs(yielding = true), 0))
        assertEquals(Decision.Listening, gate.decide(inputs(), 2_000))
    }

    @Test
    fun `sensitivity choices map onto the matcher`() {
        assertEquals(0.7f, WakeMatcher.threshold(WakeSensitivity.NORMAL.value), 1e-6f)
        assertTrue(WakeMatcher.threshold(WakeSensitivity.STRICT.value) > WakeMatcher.threshold(WakeSensitivity.NORMAL.value))
        // Eager takes a lost syllable, the default does not
        assertTrue(WakeMatcher.matches("hi tata", WakeMatcher.threshold(WakeSensitivity.EAGER.value)))
        assertTrue(!WakeMatcher.matches("hi tata", WakeMatcher.threshold(WakeSensitivity.NORMAL.value)))
        assertTrue(WakeMatcher.matches("hi bata ta", WakeMatcher.threshold(WakeSensitivity.STRICT.value)))
    }
}
