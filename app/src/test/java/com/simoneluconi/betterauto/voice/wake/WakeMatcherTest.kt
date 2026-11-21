package com.simoneluconi.betterauto.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeMatcherTest {
    private val t = WakeMatcher.threshold(0.5f)
    private val eager = WakeMatcher.threshold(0.75f)

    @Test
    fun `what the grammar recognizer hears for the phrase wakes it`() {
        // The outputs Vosk gave for synthetic "hi batata" on the laptop evaluation
        listOf("hi bata ta", "hey bata ta", "hi but a ta", "hi bata tata", "[unk] hi bata ta", "hi potato",
            "high but a ta", "hey but tata").forEach {
            assertTrue("$it should wake", WakeMatcher.matches(it, t))
        }
        // A syllable lost: only when eager
        listOf("hi tata", "hi bata", "hey bata").forEach {
            assertFalse("$it at the default", WakeMatcher.matches(it, t))
            assertTrue("$it when eager", WakeMatcher.matches(it, eager))
        }
    }

    @Test
    fun `no greeting no wake`() {
        listOf("bata ta", "potato", "tata", "but a ta", "[unk] bata ta").forEach {
            assertEquals("$it", 0f, WakeMatcher.score(it), 0f)
        }
    }

    @Test
    fun `other speech does not wake it`() {
        listOf("", "[unk]", "hi", "hi ta", "hey [unk]", "hi there how are you", "turn the volume up", "hey siri",
            "ta ta ta", "hi a", "hello batata").forEach {
            assertFalse("$it should not wake", WakeMatcher.matches(it, t))
        }
    }

    @Test
    fun `sensitivity moves the threshold`() {
        assertEquals(0.9f, WakeMatcher.threshold(0f), 1e-6f)
        assertEquals(0.5f, WakeMatcher.threshold(1f), 1e-6f)
        assertEquals(0.7f, WakeMatcher.threshold(0.5f), 1e-6f)
        assertTrue(WakeMatcher.threshold(0.6f) < 2f / 3)
        assertTrue(WakeMatcher.matches("hi bata ta", WakeMatcher.threshold(0f)))
        assertFalse(WakeMatcher.matches("hi tata", WakeMatcher.threshold(0f)))
    }

    @Test
    fun `phonetic key folds look-alikes`() {
        assertEquals("batata", WakeMatcher.key("potato"))
        assertEquals("batata", WakeMatcher.key("butata"))
        assertEquals("batata", WakeMatcher.key("batta-ta"))
        assertEquals(1f, WakeMatcher.similarity("batata", "batata"), 0f)
        assertEquals(2f / 3, WakeMatcher.similarity("tata", "batata"), 1e-6f)
    }

    @Test
    fun `a partial must hold, a final counts at once`() {
        val d = WakeDetector(stable = 2)
        assertFalse(d.feed("hi bata ta", final = false, threshold = t))
        assertTrue(d.feed("hi bata ta", final = false, threshold = t))
        assertFalse(WakeDetector().feed("hi tata", final = true, threshold = t))
        d.reset()
        assertFalse(d.feed("hi bata ta", final = false, threshold = t))
        assertFalse(d.feed("hi bat", final = false, threshold = t)) // flickered away
        assertFalse(d.feed("hi bata ta", final = false, threshold = t))
        assertTrue(WakeDetector().feed("hi bata ta", final = true, threshold = t))
        assertFalse(WakeDetector().feed("", final = true, threshold = t))
    }
}
