package com.simoneluconi.betterauto.audio

/** Decoded sound of our own (a recording, text-to-speech, a chime): float samples, interleaved. */
class Pcm(val samples: FloatArray, val sampleRate: Int, val channels: Int) {
    val frames: Int get() = samples.size / channels

    /**
     * The same sound in [format]'s rate and channels: mono spread to every channel, more channels averaged down
     * to mono. ponytail: linear interpolation, fine for speech; a real resampler if music ever goes this way.
     */
    fun to(format: PcmFormat): FloatArray {
        val out = format.channels
        if (frames == 0) return FloatArray(0)
        val outFrames = (frames.toLong() * format.sampleRate / sampleRate).toInt().coerceAtLeast(1)
        val step = sampleRate.toDouble() / format.sampleRate
        val result = FloatArray(outFrames * out)
        for (f in 0 until outFrames) {
            val pos = f * step
            val i = pos.toInt().coerceAtMost(frames - 1)
            val next = (i + 1).coerceAtMost(frames - 1)
            val t = (pos - i).toFloat()
            for (c in 0 until out) {
                val value = if (channels == 1 || out == channels) {
                    val ch = if (channels == 1) 0 else c
                    lerp(samples[i * channels + ch], samples[next * channels + ch], t)
                } else if (out == 1) {
                    var sum = 0f
                    for (ch in 0 until channels) sum += lerp(samples[i * channels + ch], samples[next * channels + ch], t)
                    sum / channels
                } else {
                    // Stereo to more channels, or the other way: the first ones only
                    val ch = c.coerceAtMost(channels - 1)
                    lerp(samples[i * channels + ch], samples[next * channels + ch], t)
                }
                result[f * out + c] = value
            }
        }
        return result
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        /** 16-bit little-endian PCM bytes as samples. */
        fun of16(bytes: ByteArray, offset: Int, length: Int, sampleRate: Int, channels: Int): Pcm {
            val samples = FloatArray(length / 2)
            for (i in samples.indices) {
                val at = offset + i * 2
                samples[i] = ((bytes[at].toInt() and 0xff) or (bytes[at + 1].toInt() shl 8)).toShort() / 32768f
            }
            return Pcm(samples, sampleRate, channels)
        }

        /** A WAV file (what text-to-speech writes) with 16-bit PCM in it, or null. */
        fun ofWav(bytes: ByteArray): Pcm? {
            if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var rate = 0
            var channels = 0
            var bits = 0
            var at = 12
            while (at + 8 <= bytes.size) {
                val id = String(bytes, at, 4, Charsets.US_ASCII)
                val size = int32(bytes, at + 4)
                val body = at + 8
                when (id) {
                    "fmt " -> if (body + 16 <= bytes.size) {
                        if (int16(bytes, body) != 1) return null // not plain PCM
                        channels = int16(bytes, body + 2)
                        rate = int32(bytes, body + 4)
                        bits = int16(bytes, body + 14)
                    }
                    "data" -> {
                        if (bits != 16 || channels <= 0 || rate <= 0) return null
                        // An engine still writing may leave the size unset: whatever is there
                        val length = (if (size in 0..(bytes.size - body)) size else bytes.size - body) and 1.inv()
                        return of16(bytes, body, length, rate, channels)
                    }
                }
                if (size < 0) return null
                at = body + size + (size and 1)
            }
            return null
        }

        private fun int16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
        private fun int32(b: ByteArray, at: Int) = int16(b, at) or (int16(b, at + 2) shl 16)
    }
}

/**
 * Our own speech waiting to be laid over the music in the car, already in the car's format: filled from any
 * thread, read by the capture thread, which adds it into each chunk of music. Each part belongs to an [owner]
 * (who speaks: the guidance, the assistant), so one of them can be cut off without silencing the other; parts
 * play one after the other. [onDone] is handed each part's callback once its last sample has been mixed.
 */
class VoiceQueue(private val onDone: (() -> Unit) -> Unit) {
    private class Part(val owner: Int, val samples: FloatArray, val done: (() -> Unit)?) {
        var at = 0
    }

    private val parts = ArrayDeque<Part>()

    /** Something is waiting to be heard. */
    val hasSound: Boolean
        @Synchronized get() = parts.isNotEmpty()

    /** The last chunk delivered held our speech over silent music: its sound is not the players'. */
    @Volatile
    var speechOnly = false
        internal set

    @Synchronized
    fun add(owner: Int, samples: FloatArray, done: (() -> Unit)?) {
        parts.addLast(Part(owner, samples, done))
    }

    /** Drops [owner]'s parts (every part when null); their callbacks only with [runDone]. */
    fun clear(owner: Int?, runDone: Boolean = false) {
        val dropped = synchronized(this) {
            val gone = parts.filter { owner == null || it.owner == owner }
            parts.removeAll(gone)
            gone
        }
        if (runDone) dropped.forEach { it.done?.let(onDone) }
    }

    /** Adds the next [count] samples of speech into [samples]; true when there was any. */
    fun mixInto(samples: FloatArray, count: Int): Boolean {
        val finished = ArrayList<() -> Unit>(1)
        var mixed = false
        synchronized(this) {
            var filled = 0
            while (filled < count) {
                val part = parts.firstOrNull() ?: break
                val n = minOf(count - filled, part.samples.size - part.at)
                for (i in 0 until n) samples[filled + i] += part.samples[part.at + i]
                if (n > 0) mixed = true
                part.at += n
                filled += n
                if (part.at >= part.samples.size) {
                    parts.removeFirst()
                    part.done?.let(finished::add)
                }
            }
        }
        finished.forEach(onDone)
        return mixed
    }
}
