package com.simoneluconi.betterauto.voice.cloud

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.audio.CarVoice
import com.simoneluconi.betterauto.audio.Pcm
import com.simoneluconi.betterauto.voice.wake.WakeListener

/**
 * The online assistant's own answers spoken by a Gemini voice, sentence by sentence and piece by piece as the
 * stream brings them, so the first words play while the rest is still being made. Into the car's stream through
 * [CarVoice] like every sound of ours; on the phone when the car doesn't take them. Blocking: a worker thread.
 */
object CloudSpeech {
    private val gemini = Gemini()
    @Volatile private var generation = 0
    private var track: AudioTrack? = null

    /** Speaks [text]; [firstAudio] runs once when the first piece is played. False when nothing could be made. */
    fun speak(key: String, voice: String, text: String, arabic: Boolean, firstAudio: () -> Unit): Boolean {
        val gen = ++generation
        var any = false
        for (sentence in CloudTools.sentences(text)) {
            if (gen != generation) break
            val made = runCatching {
                gemini.speak(key, voice, sentence, arabic) { pcm, rate ->
                    if (gen == generation) {
                        if (!any) {
                            any = true
                            firstAudio()
                        }
                        play(pcm, rate)
                    }
                }
            }.onFailure { EventLog.log("Voice online: Gemini voice failed: ${it.message}") }.getOrNull()
            // Nothing for the first sentence: the caller says it another way; later failures just end it
            if (made == null || made == 0) break
        }
        return any
    }

    /** [text] made whole (a short greeting, kept for next time); null when Gemini gave nothing. */
    fun synthesize(key: String, voice: String, text: String, arabic: Boolean): Pair<ShortArray, Int>? {
        val pieces = ArrayList<ShortArray>()
        var rate = 24_000
        runCatching { gemini.speak(key, voice, text, arabic) { pcm, r -> pieces += pcm; rate = r } }
            .onFailure { EventLog.log("Voice online: greeting not made: ${it.message}") }
        if (pieces.isEmpty()) return null
        val all = ShortArray(pieces.sumOf { it.size })
        var at = 0
        pieces.forEach { it.copyInto(all, at); at += it.size }
        return all to rate
    }

    /** Plays a made greeting at once, then [done] (on the main thread, also when the car cuts it). */
    fun playGreeting(samples: ShortArray, rate: Int, done: () -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val pcm = Pcm(FloatArray(samples.size) { samples[it] / 32768f }, rate, 1)
        if (CarVoice.play(AudioAttributes.USAGE_MEDIA, queued = false, done = done) { listOf(pcm) }) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        t.write(samples, 0, samples.size)
        t.play()
        val ms = samples.size * 1000L / rate
        WakeListener.mute(ms)
        main.postDelayed({ runCatching { t.release() }; done() }, ms + 100)
    }

    fun stop() {
        generation++
        synchronized(this) {
            track?.let { runCatching { it.pause(); it.flush() } }
        }
    }

    private fun play(samples: ShortArray, rate: Int) {
        val pcm = Pcm(FloatArray(samples.size) { samples[it] / 32768f }, rate, 1)
        if (CarVoice.play(AudioAttributes.USAGE_MEDIA, queued = true, done = null) { listOf(pcm) }) return
        // No car stream of ours: the phone plays it, in order (the write waits for room)
        val t = synchronized(this) {
            track?.takeIf { it.sampleRate == rate } ?: newTrack(rate).also { old -> track?.release(); track = old }
        }
        t.play()
        // Written as it plays (the write waits for room): what is left of this piece plus what the track holds
        WakeListener.mute(samples.size * 1000L / rate + 1_000L)
        t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
    }

    private fun newTrack(rate: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate))
        .build()
}
