package com.simoneluconi.betterauto.voice.cloud

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The recorded command as a small WAV for Gemini: 16 kHz mono 16-bit, whatever the microphone gave (the car's may
 * be 48 kHz stereo). Channels averaged, then linear interpolation: fine for speech.
 */
object WavEncoder {
    const val RATE = 16_000

    fun encode(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        val frames = pcm.size / 2 / channels
        val mono = ShortArray(frames)
        val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += src.get(f * channels + c)
            mono[f] = (sum / channels).toShort()
        }
        val out = if (rate == RATE) mono else resample(mono, rate)
        return wav(out)
    }

    private fun resample(input: ShortArray, rate: Int): ShortArray {
        if (input.isEmpty()) return input
        val n = (input.size.toLong() * RATE / rate).toInt().coerceAtLeast(1)
        val step = rate.toDouble() / RATE
        return ShortArray(n) { i ->
            val pos = i * step
            val a = pos.toInt().coerceAtMost(input.lastIndex)
            val b = (a + 1).coerceAtMost(input.lastIndex)
            val t = pos - a
            (input[a] * (1 - t) + input[b] * t).toInt().toShort()
        }
    }

    fun wav(samples: ShortArray): ByteArray {
        val data = samples.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (s in samples) b.putShort(s)
        return b.array()
    }
}
