package com.simoneluconi.betterauto.assist

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.audio.CarVoice
import com.simoneluconi.betterauto.audio.NavPrompts
import com.simoneluconi.betterauto.audio.Pcm
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.voice.MessageReader
import com.simoneluconi.betterauto.voice.VoiceAssistant
import com.simoneluconi.betterauto.voice.VoiceState
import com.simoneluconi.betterauto.voice.wake.WakeListener
import kotlin.math.PI
import kotlin.math.sin

/**
 * The driving assistant's voice: the assistant's own ([MessageReader.say]: online the Gemini voice with the persona's
 * warm style, the line already carrying the driver's name; else a recorded clip when the line is one, or the phone's
 * speech), into the car's stream through [CarVoice] while the audio is copied. Quiet while the driver talks to the
 * assistant or is on a call: a line then is dropped, not queued (a reminder heard ten minutes late is noise).
 */
object AssistVoice {
    private val main = Handler(Looper.getMainLooper())

    /** The driver is not talking: no voice turn or question, no call. */
    fun talkFree(): Boolean = VoiceAssistant.state.value is VoiceState.Idle && !VoiceAssistant.asking && Calls.current.value == null

    /** Nothing else is heard either: no sound of ours in the car (a message read), no navigation prompt. */
    fun free(context: Context): Boolean = talkFree() && !CarVoice.speaking && !NavPrompts.speaking(context.applicationContext)

    /** Says [text], then [then] (main thread) once heard; false when it was dropped (the driver is busy talking). */
    fun say(context: Context, text: String, then: () -> Unit = {}): Boolean {
        if (!free(context)) {
            EventLog.log("Assist: a line dropped, the driver is busy")
            return false
        }
        MessageReader.say(context.applicationContext, text, then)
        return true
    }

    /**
     * The speed chime: two soft falling tones, quieter than the assistant's own chime, into the car's stream (its
     * own owner there, so it never cuts a spoken line), on the phone when the car doesn't take our sounds.
     */
    fun chime() {
        val rate = 48_000
        val each = rate * 140 / 1000
        val frequencies = listOf(880.0, 660.0)
        val wave = FloatArray(each * frequencies.size)
        frequencies.forEachIndexed { n, f ->
            for (i in 0 until each) {
                // Soft attack, long fade: a bell, not a beep
                val envelope = minOf(1.0, i / 960.0) * (1.0 - i.toDouble() / each)
                wave[n * each + i] = (sin(2 * PI * f * i / rate) * envelope * 0.25).toFloat()
            }
        }
        if (CarVoice.play(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, queued = true, done = null) { listOf(Pcm(wave, rate, 1)) }) return
        val samples = ShortArray(wave.size) { (wave[it] * Short.MAX_VALUE).toInt().toShort() }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            WakeListener.mute(samples.size * 1000L / rate)
            main.postDelayed({ runCatching { track.release() } }, samples.size * 1000L / rate + 200)
        }.onFailure { EventLog.log("Assist: chime failed: $it") }
    }
}
