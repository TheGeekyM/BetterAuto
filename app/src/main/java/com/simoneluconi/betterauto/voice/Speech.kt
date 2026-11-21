package com.simoneluconi.betterauto.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.audio.CarVoice
import com.simoneluconi.betterauto.audio.Pcm
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.voice.wake.WakeListener
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * Spoken replies and the listening chime. While the phone's audio is copied to the car they go into the car's
 * stream through [CarVoice] (the copy never hears our own playback); otherwise they play as media.
 */
object Speech {
    private var tts: TextToSpeech? = null
    private var toCar: TtsToPcm? = null
    private var ready = false
    private var pending: String? = null
    private var pendingDone: (() -> Unit)? = null

    private val Media = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * The replies recorded in [VoiceClips]. One with a name in it ("I can't find “Ahmed”") is said in its recorded
     * form without the name ("I can't find that name"): the screen still shows the whole sentence.
     */
    private val Recorded = mapOf(
        R.string.voice_nothing_heard to "nothing_heard", R.string.voice_no_player to "no_player",
        R.string.voice_quiet_on to "quiet_on", R.string.voice_quiet_off to "quiet_off",
        R.string.voice_no_notifications to "no_notifications", R.string.voice_no_stops to "no_stops",
        R.string.voice_navigation_ended to "nav_ended", R.string.voice_no_navigation to "no_nav",
        R.string.voice_which to "which", R.string.voice_language_missing to "language_missing",
        R.string.voice_not_understood to "not_understood", R.string.voice_contact_not_found to "contact_not_found",
        R.string.voice_unknown_place to "unknown_place", R.string.voice_playing to "playing",
        R.string.voice_track_not_found to "track_not_found", R.string.voice_place_not_found to "place_not_found",
        R.string.voice_navigating to "navigating", R.string.voice_choose_stop to "choose_stop",
        R.string.voice_no_number to "no_number", R.string.voice_calling to "calling",
        R.string.voice_stop_skipped to "stop_skipped", R.string.voice_stop_added to "stop_added",
        R.string.voice_stop_already to "stop_already",
        R.string.voice_ac_on to "ac_on", R.string.voice_ac_off to "ac_off", R.string.voice_ac_auto to "ac_auto",
        R.string.voice_ac_temp to "ac_temp", R.string.voice_ac_temp_plain to "ac_temp", R.string.voice_fan_up to "fan_up",
        R.string.voice_fan_down to "fan_down", R.string.voice_volume_up to "volume_up", R.string.voice_volume_down to "volume_down",
        R.string.voice_mute to "mute", R.string.voice_unmute to "unmute", R.string.voice_tires_ok to "tires_ok",
        R.string.voice_tires_check to "tires_check", R.string.voice_trips to "trips", R.string.voice_car_unreachable to "car_unreachable",
    )
    private var recorded: ClipPlayer? = null

    /**
     * One of the recorded greetings, at random ("hi, what do you need now?"), then [then]; false
     * when the chosen voice has none (the phone's voice, or none recorded): the caller goes on at once.
     */
    fun greet(context: Context, timed: String? = null, then: () -> Unit): Boolean {
        val folders = VoiceClips.folders(context)
        // [timed]: a greeting for the time of day ("morning"), only when recorded; else any of the greetings of the
        // first voice that has some (the player then finds it in that same folder)
        val clip = if (timed != null) timed.takeIf { VoiceClips.resolve(listOf(it), folders) { f -> VoiceClips.listed(context, f) } != null } ?: return false
            else folders.firstNotNullOfOrNull { f ->
                VoiceClips.listed(context, f).filter { it.startsWith("greet") }.map { it.substringBefore('.') }.distinct().randomOrNull()
            } ?: return false
        val player = recorded ?: ClipPlayer(context.applicationContext, AudioAttributes.USAGE_MEDIA).also { recorded = it }
        tts?.stop()
        // Mixed into the car's stream, the end is only known when the stream moves on; should it stall (music
        // paused, nothing captured), the microphone still opens: no greeting is longer than this
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val open = { if (once.compareAndSet(false, true)) then() }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.postDelayed(open, GREET_MAX_MS)
        return player.play(listOf(clip), done = { main.post(open) }).also { if (!it) main.removeCallbacks(open) }
    }

    private const val GREET_MAX_MS = 4_000L

    /**
     * Says [text]; [done] runs once it is over (on any thread; on the phone also when cut), never when there is no
     * speech engine: a caller that waits for it keeps a time limit of its own and checks it is still wanted.
     */
    fun say(context: Context, text: String, done: (() -> Unit)? = null) {
        val res = AppLocales.voiceContext(context).resources
        val templates = Recorded.entries.associate { (id, clip) -> res.getString(id) to clip }
        VoiceClips.recordedFor(text, templates)?.let { clip ->
            val player = recorded ?: ClipPlayer(context.applicationContext, AudioAttributes.USAGE_MEDIA).also { recorded = it }
            if (player.play(listOf(clip), done = done)) {
                tts?.stop()
                return
            }
        }
        val engine = tts
        if (engine != null && ready) {
            // The voice language may have been changed since the engine started
            AppLocales.voice(context).let { if (engine.voice?.locale != it) engine.language = it }
            speak(engine, text, done)
            return
        }
        pending = text
        pendingDone = done
        if (engine != null) return
        val cache = context.applicationContext.cacheDir
        tts = TextToSpeech(context.applicationContext, { status ->
            ready = status == TextToSpeech.SUCCESS
            val t = tts ?: return@TextToSpeech
            if (!ready) {
                EventLog.log("Voice: text-to-speech unavailable ($status)")
                return@TextToSpeech
            }
            t.setAudioAttributes(Media)
            languageOf(t, AppLocales.voice(context), "Voice")
            pending?.let { speak(t, it, pendingDone) }
            pending = null
            pendingDone = null
        }, engine(context)).also { toCar = TtsToPcm(it, cache) }
    }

    /** Into the car when it takes our speech, else on the phone. */
    private fun speak(engine: TextToSpeech, text: String, done: (() -> Unit)? = null) {
        engine.stop()
        val car = toCar
        if (car != null && CarVoice.play(AudioAttributes.USAGE_MEDIA, queued = false, done = done) { car.pcm(text) }) return
        val id = "reply-${replies.incrementAndGet()}"
        if (done != null) car?.whenSpoken(id, done)
        // Its end is not followed: about as long as the phone's voice takes for it
        WakeListener.mute(1_000L + text.length * 70L)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    private val replies = AtomicInteger()

    /**
     * Google's speech engine when the phone has it: the default may be one that doesn't start for other apps
     * (Xiaomi's on China ROMs). Null: the phone's default.
     */
    internal fun engine(context: Context): String? =
        GOOGLE_TTS.takeIf { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }

    /** Sets [locale]; logs when the engine lacks its voice (it would then speak another language, or nothing). */
    internal fun languageOf(engine: TextToSpeech, locale: Locale, tag: String) {
        val result = engine.setLanguage(locale)
        if (result < TextToSpeech.LANG_AVAILABLE) EventLog.log("$tag: text-to-speech has no ${locale.toLanguageTag()} voice ($result)")
    }

    private const val GOOGLE_TTS = "com.google.android.tts"

    fun stop() {
        CarVoice.stop(AudioAttributes.USAGE_MEDIA)
        recorded?.stop()
        tts?.stop()
    }

    /** The car is gone: the speech engine is let go (its own process stays up while bound) and comes back on the next reply. */
    fun release() {
        tts?.shutdown()
        tts = null
        toCar = null
        ready = false
        pending = null
        pendingDone = null
    }

    /** Two short rising tones: listening starts. */
    fun chime() = tones(listOf(660.0, 990.0))

    /** One short low tone: nothing understood, or cancelled. */
    fun cancelTone() = tones(listOf(440.0))

    private fun tones(frequencies: List<Double>) {
        val rate = 48_000
        val each = rate * 90 / 1000
        val wave = FloatArray(each * frequencies.size)
        frequencies.forEachIndexed { n, f ->
            for (i in 0 until each) {
                // Short fade in and out, no clicks
                val envelope = minOf(1.0, i / 480.0, (each - i) / 480.0)
                wave[n * each + i] = (sin(2 * PI * f * i / rate) * envelope * 0.35).toFloat()
            }
        }
        if (CarVoice.play(AudioAttributes.USAGE_MEDIA, queued = true, done = null) { listOf(Pcm(wave, rate, 1)) }) return
        val samples = ShortArray(wave.size) { (wave[it] * Short.MAX_VALUE).toInt().toShort() }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size)
            WakeListener.mute(samples.size * 1000L / rate)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        }.onFailure { EventLog.log("Voice: chime failed: $it") }
    }
}

/**
 * Text-to-speech as PCM for [CarVoice]: [engine] synthesizes to a file (it has no other way to hand the sound
 * over whole), read back once it is done. Takes the engine's progress listener.
 */
internal class TtsToPcm(private val engine: TextToSpeech, private val dir: File) {
    private val waiting = ConcurrentHashMap<String, CountDownLatch>()
    private val ids = AtomicInteger()

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
    }

    private val spoken = ConcurrentHashMap<String, () -> Unit>()

    private fun finished(id: String?) {
        id?.let { waiting.remove(it)?.countDown() }
        id?.let { spoken.remove(it)?.invoke() }
    }

    /** [done] once the engine has said utterance [id] aloud (on the phone, not into a file). */
    fun whenSpoken(id: String, done: () -> Unit) {
        spoken[id] = done
    }

    /** [text] spoken, waiting for the engine (a worker thread, never the main one); empty when it failed. */
    fun pcm(text: String): List<Pcm> {
        val id = "car-${ids.incrementAndGet()}"
        val file = File(dir, "$id.wav")
        val latch = CountDownLatch(1).also { waiting[id] = it }
        try {
            // Always queued after what the engine is making: callers stop it first to cut that
            if (engine.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) {
                EventLog.log("Car voice: text-to-speech refused to synthesize")
                return emptyList()
            }
            if (!latch.await(SYNTHESIS_TIMEOUT_S, TimeUnit.SECONDS)) {
                EventLog.log("Car voice: text-to-speech gave nothing in $SYNTHESIS_TIMEOUT_S s")
                return emptyList()
            }
            val pcm = Pcm.ofWav(file.readBytes())
            if (pcm == null) EventLog.log("Car voice: text-to-speech wrote no usable sound (${file.length()} bytes)")
            return listOfNotNull(pcm)
        } finally {
            waiting.remove(id)
            file.delete()
        }
    }

    private companion object {
        const val SYNTHESIS_TIMEOUT_S = 10L
    }
}
