package com.simoneluconi.betterauto.voice

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Voice activity detection on the microphone's 16-bit PCM, so that we (not the recognizer, which ends at the first
 * short pause) decide when the driver has finished: speech starts after [START_FRAMES] loud frames in a row, and
 * ends after [silenceMs] without speech. "Loud" is measured against the noise floor, learned from the frames that
 * are not speech, so road noise and a fan raise the bar instead of counting as a voice.
 *
 * ponytail: energy against an adaptive floor, no spectral model; loud music in the cabin can read as speech (it
 * only makes the listening last longer, up to the caller's limit). A WebRTC/Silero VAD if that matters.
 */
class Vad(sampleRate: Int, channels: Int, private val silenceMs: Long) {
    enum class Event { SPEECH, END }

    private val frameSamples = sampleRate * channels * FRAME_MS / 1000
    private val pending = ShortArray(frameSamples)
    private var filled = 0
    private var frames = 0
    private var floor = -1.0
    private var loudRun = 0
    private var quietMs = 0L
    var speaking = false
        private set
    var ended = false
        private set
    /** How loud the last frame was against the floor, 0 to 1, for the ring on screen. */
    var level = 0f
        private set

    /** Little-endian 16-bit samples; the events this chunk caused, in order (SPEECH once, then END once). */
    fun feed(pcm: ByteArray, length: Int = pcm.size): List<Event> {
        val out = ArrayList<Event>(1)
        var i = 0
        while (i + 1 < length) {
            pending[filled++] = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
            i += 2
            if (filled == frameSamples) {
                filled = 0
                frame()?.let(out::add)
            }
        }
        return out
    }

    private fun frame(): Event? {
        var sum = 0.0
        for (s in pending) sum += s.toDouble() * s
        val rms = sqrt(sum / frameSamples)
        frames++
        if (floor < 0) floor = max(rms, MIN_FLOOR)
        val loud = rms > max(floor * LOUD_OVER_FLOOR, MIN_SPEECH) && frames > WARMUP_FRAMES
        level = ((rms / max(floor * LOUD_OVER_FLOOR * 3, 1.0)).toFloat()).coerceIn(0f, 1f)
        // The floor follows the quiet frames quickly, the loud ones barely (a long sentence must not become "noise")
        floor += (rms - floor) * if (loud) 0.002 else 0.05
        floor = max(floor, MIN_FLOOR)
        if (ended) return null
        if (loud) {
            loudRun++
            quietMs = 0
            if (!speaking && loudRun >= START_FRAMES) {
                speaking = true
                return Event.SPEECH
            }
        } else {
            loudRun = 0
            if (speaking) {
                quietMs += FRAME_MS
                if (quietMs >= silenceMs) {
                    ended = true
                    return Event.END
                }
            }
        }
        return null
    }

    private companion object {
        const val FRAME_MS = 20
        const val START_FRAMES = 4          // 80 ms of voice: a click or a bump is not speech
        const val WARMUP_FRAMES = 10        // the first 200 ms only learn the floor
        const val LOUD_OVER_FLOOR = 3.2     // about 10 dB over the noise
        const val MIN_FLOOR = 30.0
        const val MIN_SPEECH = 300.0        // whispering at the dashboard is not a command
    }
}
