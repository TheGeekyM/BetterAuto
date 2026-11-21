package com.simoneluconi.betterauto.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class VadTest {
    private val rate = 16_000
    private val random = Random(1)

    /** [ms] of a 200 Hz tone at [amplitude] over noise at [noise], as 16-bit little-endian PCM. */
    private fun sound(ms: Int, amplitude: Double, noise: Double = 80.0): ByteArray {
        val n = rate * ms / 1000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (amplitude * sin(2 * PI * 200 * i / rate) + (random.nextDouble() * 2 - 1) * noise).toInt().coerceIn(-32768, 32767)
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    @Test
    fun aPauseMidSentenceDoesNotEndIt() {
        val vad = Vad(rate, 1, silenceMs = 1_300)
        val events = vad.feed(sound(500, 0.0)) + vad.feed(sound(800, 4000.0)) + vad.feed(sound(900, 0.0)) +
            vad.feed(sound(700, 4000.0)) + vad.feed(sound(1_000, 0.0))
        assertEquals(listOf(Vad.Event.SPEECH), events)
        assertEquals(listOf(Vad.Event.END), vad.feed(sound(400, 0.0)))
    }

    @Test
    fun roadNoiseAloneIsNotSpeech() {
        val vad = Vad(rate, 1, silenceMs = 1_300)
        // Loud, steady noise: the floor rises with it
        assertTrue(vad.feed(sound(4_000, 0.0, noise = 2_000.0)).isEmpty())
        // A voice over that noise still counts
        assertEquals(listOf(Vad.Event.SPEECH), vad.feed(sound(600, 9_000.0, noise = 2_000.0)))
    }

    @Test
    fun stereoFramesWork() {
        val vad = Vad(48_000, 2, silenceMs = 500)
        // Mono samples at 16 kHz read as stereo at 48 kHz: the same 20 ms frames by count, loud is loud
        assertEquals(listOf(Vad.Event.SPEECH), vad.feed(sound(1_000, 0.0)) + vad.feed(sound(1_000, 5000.0)))
    }
}
