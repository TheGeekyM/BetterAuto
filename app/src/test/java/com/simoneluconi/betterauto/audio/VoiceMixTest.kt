package com.simoneluconi.betterauto.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMixTest {
    @Test
    fun monoIsSpreadToStereoAndUpsampled() {
        val out = Pcm(floatArrayOf(0f, 1f), 24_000, 1).to(PcmFormat(48_000, 2, 16))
        // Two frames become four, each on both channels, the new ones halfway
        assertArrayEquals(floatArrayOf(0f, 0f, 0.5f, 0.5f, 1f, 1f, 1f, 1f), out, 1e-6f)
    }

    @Test
    fun stereoIsAveragedToMonoAndDownsampled() {
        val stereo = Pcm(floatArrayOf(0.2f, 0.4f, 0.2f, 0.4f, 0.2f, 0.4f, 0.2f, 0.4f, 0.2f, 0.4f, 0.2f, 0.4f), 48_000, 2)
        val out = stereo.to(PcmFormat(16_000, 1, 16))
        assertEquals(2, out.size)
        out.forEach { assertEquals(0.3f, it, 1e-6f) }
    }

    @Test
    fun sameFormatIsUnchanged() {
        val samples = floatArrayOf(0.1f, -0.2f, 0.3f, -0.4f)
        assertArrayEquals(samples, Pcm(samples, 48_000, 2).to(PcmFormat(48_000, 2, 16)), 0f)
    }

    @Test
    fun speechIsAddedOverTheMusicAcrossChunks() {
        val done = mutableListOf<String>()
        val queue = VoiceQueue { it() }
        queue.add(1, floatArrayOf(0.1f, 0.1f, 0.1f), { done += "first" })
        queue.add(2, floatArrayOf(0.2f, 0.2f), { done += "second" })

        val chunk = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        assertTrue(queue.mixInto(chunk, chunk.size))
        assertArrayEquals(floatArrayOf(0.6f, 0.6f, 0.6f, 0.7f), chunk, 1e-6f)
        assertEquals(listOf("first"), done)

        val next = floatArrayOf(0f, 0f, 0f, 0f)
        assertTrue(queue.mixInto(next, next.size))
        assertArrayEquals(floatArrayOf(0.2f, 0f, 0f, 0f), next, 1e-6f)
        assertEquals(listOf("first", "second"), done)
        assertFalse(queue.hasSound)

        assertFalse(queue.mixInto(next, next.size))
    }

    @Test
    fun clearingOneOwnerKeepsTheOther() {
        val done = mutableListOf<Int>()
        val queue = VoiceQueue { it() }
        queue.add(1, floatArrayOf(0.1f), { done += 1 })
        queue.add(2, floatArrayOf(0.2f), { done += 2 })
        queue.clear(1)
        val chunk = FloatArray(2)
        queue.mixInto(chunk, 2)
        assertArrayEquals(floatArrayOf(0.2f, 0f), chunk, 1e-6f)
        assertEquals(listOf(2), done)

        // Dropped by a change of route: callers waiting for the end still hear of it
        queue.add(1, floatArrayOf(0.1f), { done += 3 })
        queue.clear(null, runDone = true)
        assertEquals(listOf(2, 3), done)
    }

    @Test
    fun speechStartsTheStreamOverSilentMusic() {
        val sink = object : AudioSink {
            override val format = PcmFormat(48_000, 1, 16)
            override val wantsSilence = false
            val silent = mutableListOf<Boolean>()
            override fun onPcm(data: ByteArray, timestampUs: Long, silent: Boolean) {
                this.silent += silent
            }
        }
        val queue = VoiceQueue { it() }
        queue.add(1, FloatArray(4) { 0.5f }, null)
        PcmQuantizer(sink.format).deliver(sink, FloatArray(4), 4, 0, queue)
        assertEquals(listOf(false), sink.silent)
        // Only our speech in it: not taken for the players being heard
        assertTrue(queue.speechOnly)

        // Over music that sounds, the chunk is the players' too
        queue.add(1, FloatArray(4) { 0.1f }, null)
        PcmQuantizer(sink.format).deliver(sink, FloatArray(4) { 0.2f }, 4, 0, queue)
        assertFalse(queue.speechOnly)
    }

    @Test
    fun wavIsRead() {
        val data = byteArrayOf(0, 0x40, 0, 0xC0.toByte()) // 0.5, -0.5
        val wav = header(rate = 22_050, channels = 1, dataSize = data.size) + data
        val pcm = Pcm.ofWav(wav)
        assertNotNull(pcm)
        assertEquals(22_050, pcm!!.sampleRate)
        assertEquals(1, pcm.channels)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), pcm.samples, 1e-6f)
        assertNull(Pcm.ofWav(ByteArray(10)))
    }

    private fun header(rate: Int, channels: Int, dataSize: Int): ByteArray {
        fun le(v: Int, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
        return "RIFF".toByteArray() + le(36 + dataSize, 4) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + le(16, 4) + le(1, 2) + le(channels, 2) + le(rate, 4) +
            le(rate * channels * 2, 4) + le(channels * 2, 2) + le(16, 2) +
            "data".toByteArray() + le(dataSize, 4)
    }
}
