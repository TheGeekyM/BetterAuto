package com.simoneluconi.betterauto.voice.cloud

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavEncoderTest {
    private fun pcm(vararg s: Short) = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { s.forEach { putShort(it) } }.array()

    @Test
    fun `16 kHz mono stays as it is`() {
        val wav = WavEncoder.encode(pcm(1, -2, 300), 16_000, 1)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(1.toShort(), b.getShort(22))
        assertEquals(16_000, b.getInt(24))
        assertEquals(6, b.getInt(40))
        assertEquals(300.toShort(), b.getShort(48))
    }

    @Test
    fun `48 kHz stereo becomes 16 kHz mono`() {
        // 30 frames of stereo (left 100, right 300) → 10 mono samples of 200
        val stereo = ShortArray(60) { if (it % 2 == 0) 100 else 300 }
        val wav = WavEncoder.encode(pcm(*stereo), 48_000, 2)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(20, b.getInt(40))
        assertEquals(200.toShort(), b.getShort(44))
    }
}
