package com.simoneluconi.betterauto.prayer

import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.simoneluconi.betterauto.BuildConfig
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.audio.CarVoice
import com.simoneluconi.betterauto.audio.Pcm
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.VoiceBrain
import com.simoneluconi.betterauto.voice.Speech
import com.simoneluconi.betterauto.voice.TtsToPcm
import com.simoneluconi.betterauto.voice.VoiceAssistant
import com.simoneluconi.betterauto.voice.VoiceState
import com.simoneluconi.betterauto.voice.cloud.CloudSpeech
import com.simoneluconi.betterauto.voice.wake.WakeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The Azan notice while a car screen is up ([watch], held by the launcher for the session): at each prayer's time a
 * short chime and "حان الآن موعد أذان الظهر" in the voice language, the music paused meanwhile and resumed after.
 * The voice is the online assistant's Gemini voice when the build has its key and the phone is online (made a
 * couple of minutes ahead), else the phone's text-to-speech (in English when it has no voice for the language).
 * Into the car's stream through [CarVoice] under an owner of its own, so an assistant reply doesn't cut it;
 * on the phone when the car doesn't take our sounds. No recording of an Azan is shipped.
 */
object Azan {
    /** [CarVoice]'s owner for these sounds: not media (the assistant's replies cut that one). */
    private const val OWNER = AudioAttributes.USAGE_ALARM

    /** A prayer is announced only this soon after its time: never late, on a connection long after. */
    private const val LATE_S = 60L

    /** The online voice is made this long before the time. */
    private const val PREPARE_MS = 3 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var users = 0
    private var job: Job? = null

    /** Prayers already announced ("2026-03-15/DHUHR"), kept across reconnections. */
    private val announced = HashSet<String>()

    private var tts: TextToSpeech? = null
    private var ttsToPcm: TtsToPcm? = null

    /** The online voice made ahead: what it says, and the sound. */
    @Volatile private var made: Pair<String, Pcm>? = null
    private var making: String? = null

    private var resumeMusic = false
    private var playing = 0
    private var phoneTrack: AudioTrack? = null
    private val watchdog = Runnable { finish(playing) }

    /** Follows the prayer times until the returned function is called. Main thread. */
    fun watch(context: Context): () -> Unit {
        val app = context.applicationContext
        users++
        if (job == null) job = scope.launch { loop(app) }
        var released = false
        return {
            if (!released) {
                released = true
                users--
                if (users <= 0) stopAll()
            }
        }
    }

    private suspend fun loop(context: Context) {
        val settings = LauncherSettings.get(context)
        while (true) {
            // Every pass: the car's first fix may come after the connection (a city until then)
            val at = PrayerPlace.of(context, settings)
            val now = ZonedDateTime.now()
            val days = { date: java.time.LocalDate -> prayerDay(date, at, settings) }
            PrayerTimes.last(now, days)?.let { (prayer, time) ->
                val late = Duration.between(time, now).seconds
                if (late < LATE_S && announced.add("${time.toLocalDate()}/$prayer")) {
                    val sound = settings.azanSound.value
                    if (sound != AzanSound.OFF) scope.launch { announce(context, prayer, sound) }
                }
            }
            val next = PrayerTimes.next(now, days)
            val wait = next?.let { Duration.between(now, it.second).toMillis() } ?: 60_000L
            if (next != null && wait < PREPARE_MS && settings.azanSound.value == AzanSound.SPOKEN) prepare(context, next.first)
            delay(wait.coerceIn(500L, 30_000L) + 50)
        }
    }

    private suspend fun announce(context: Context, prayer: Prayer, sound: AzanSound) {
        // Never over a call; the assistant listening or answering is waited for a little
        if (Calls.current.value != null) return EventLog.log("Azan: ${prayer.name} not announced during a call")
        val idle = withTimeoutOrNull(BUSY_WAIT_MS) {
            while (VoiceAssistant.state.value !is VoiceState.Idle || Calls.current.value != null) delay(500)
            true
        }
        if (idle == null) return EventLog.log("Azan: ${prayer.name} not announced, the voice assistant or a call was busy")
        if (job == null) return
        val speech = if (sound == AzanSound.SPOKEN) speech(context, prayer) else null
        if (job == null) return
        val rate = speech?.sampleRate ?: CHIME_RATE
        val chime = azanChime(rate)
        val samples = FloatArray(chime.size + (speech?.let { rate * GAP_MS / 1000 + it.samples.size } ?: 0))
        chime.copyInto(samples)
        speech?.samples?.copyInto(samples, chime.size + rate * GAP_MS / 1000)
        val whole = Pcm(samples, rate, 1)
        EventLog.log("Azan: ${prayer.name}, ${if (speech == null) "chime" else "chime and voice"}")
        resumeMusic = NowPlaying.isPlaying
        if (resumeMusic) NowPlaying.pause()
        play(whole)
    }

    /** The spoken notice as mono sound: the online voice when there is one, else the phone's; null when neither speaks. */
    private suspend fun speech(context: Context, prayer: Prayer): Pcm? {
        val text = spoken(AppLocales.voiceContext(context), prayer)
        made?.takeIf { it.first == text }?.let { return it.second }
        if (online(context)) {
            withTimeoutOrNull(ONLINE_WAIT_MS) { runInterruptible(Dispatchers.IO) { synthesizeOnline(context, text) } }?.let { return it }
        }
        return phoneSpeech(context, prayer)
    }

    /** Makes the coming prayer's online voice ahead, and wakes the phone's engine. */
    private fun prepare(context: Context, prayer: Prayer) {
        val text = spoken(AppLocales.voiceContext(context), prayer)
        if (making == text) return
        making = text
        scope.launch { engine(context) }
        if (!online(context)) return
        scope.launch(Dispatchers.IO) { synthesizeOnline(context, text)?.let { made = text to it } }
    }

    private fun synthesizeOnline(context: Context, text: String): Pcm? {
        val settings = LauncherSettings.get(context)
        val arabic = AppLocales.voice(context).language == "ar"
        val (samples, rate) = CloudSpeech.synthesize(BuildConfig.GEMINI_KEY, settings.geminiVoice.value.apiName, text, arabic) ?: return null
        return Pcm(FloatArray(samples.size) { samples[it] / 32768f }, rate, 1)
    }

    private fun online(context: Context): Boolean {
        if (BuildConfig.GEMINI_KEY.isBlank() || LauncherSettings.get(context).voiceBrain.value == VoiceBrain.OFFLINE) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** The phone's voice in the voice language; English when the engine has no voice for it. */
    private suspend fun phoneSpeech(context: Context, prayer: Prayer): Pcm? {
        val engine = engine(context) ?: return null
        val toPcm = ttsToPcm ?: return null
        val wanted = AppLocales.voice(context)
        val texts = if (engine.setLanguage(wanted) >= TextToSpeech.LANG_AVAILABLE) AppLocales.voiceContext(context)
        else {
            EventLog.log("Azan: text-to-speech has no ${wanted.toLanguageTag()} voice, English instead")
            engine.setLanguage(Locale.ENGLISH)
            context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        }
        val text = spoken(texts, prayer)
        val pcm = runInterruptible(Dispatchers.IO) { toPcm.pcm(text) }.firstOrNull() ?: return null
        return if (pcm.channels == 1) pcm else Pcm(pcm.to(com.simoneluconi.betterauto.audio.PcmFormat(pcm.sampleRate, 1, 16)), pcm.sampleRate, 1)
    }

    /** Our own engine (a second [TtsToPcm] on [Speech]'s would take its progress listener); null when it doesn't start. */
    private suspend fun engine(context: Context): TextToSpeech? {
        tts?.let { return if (ttsToPcm != null) it else null }
        var made: TextToSpeech? = null
        val ok = withTimeoutOrNull(ENGINE_WAIT_MS) {
            suspendCancellableCoroutine { cont ->
                made = TextToSpeech(context, { status -> main.post { if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS) } }, Speech.engine(context))
            }
        }
        val engine = made ?: return null
        if (tts != null) {
            // Another call started one meanwhile
            engine.shutdown()
            return tts?.takeIf { ttsToPcm != null }
        }
        tts = engine
        if (ok != true) {
            EventLog.log("Azan: text-to-speech unavailable")
            return null
        }
        ttsToPcm = TtsToPcm(engine, context.cacheDir)
        return engine
    }

    private fun spoken(texts: Context, prayer: Prayer): String =
        texts.getString(R.string.azan_spoken, texts.getString(prayer.label))

    private fun play(sound: Pcm) {
        val id = ++playing
        val ms = sound.frames * 1000L / sound.sampleRate
        main.removeCallbacks(watchdog)
        // Should the car's stream stall or cut it, the music still comes back
        main.postDelayed(watchdog, ms + WATCHDOG_MARGIN_MS)
        if (CarVoice.play(OWNER, queued = false, done = { finish(id) }) { listOf(sound) }) return
        // The car doesn't take our sounds now: on the phone
        val samples = ShortArray(sound.samples.size) { (sound.samples[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sound.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            phoneTrack = track
            track.play()
            WakeListener.mute(ms)
            main.postDelayed({ finish(id) }, ms + 200)
        }.onFailure {
            EventLog.log("Azan: not played on the phone ($it)")
            finish(id)
        }
    }

    /** The sound [id] is over: the music comes back, if it was playing. */
    private fun finish(id: Int) {
        if (id != playing) return
        playing++
        main.removeCallbacks(watchdog)
        phoneTrack?.runCatching { stop(); release() }
        phoneTrack = null
        if (resumeMusic) main.postDelayed({ NowPlaying.resume() }, RESUME_DELAY_MS)
        resumeMusic = false
    }

    /** The car is gone: nothing more is said, the engine is let go; the music is left as the disconnect leaves it. */
    private fun stopAll() {
        job?.cancel()
        job = null
        users = 0
        CarVoice.stop(OWNER)
        resumeMusic = false
        finish(playing)
        tts?.shutdown()
        tts = null
        ttsToPcm = null
        making = null
        made = null
    }

    private const val CHIME_RATE = 48_000
    private const val GAP_MS = 250
    private const val BUSY_WAIT_MS = 30_000L
    private const val ONLINE_WAIT_MS = 4_000L
    private const val ENGINE_WAIT_MS = 5_000L
    private const val WATCHDOG_MARGIN_MS = 4_000L
    private const val RESUME_DELAY_MS = 400L
}

/**
 * Three soft bell-like notes, falling, at [rate] (mono): heard over lowered or paused music, never startling.
 */
internal fun azanChime(rate: Int): FloatArray {
    val notes = doubleArrayOf(880.0, 740.0, 587.33)
    val each = rate * 420 / 1000
    val wave = FloatArray(each * notes.size)
    notes.forEachIndexed { n, f ->
        for (i in 0 until each) {
            val t = i.toDouble() / rate
            // A quick attack, then a bell's decay faded out at the end (no click); the octave above for some shine
            val envelope = minOf(1.0, i / (rate * 0.008), (each - i) / (rate * 0.012)) * exp(-t * 5.0)
            val tone = sin(2 * PI * f * t) + 0.3 * sin(4 * PI * f * t)
            wave[n * each + i] = (tone * envelope * 0.28).toFloat()
        }
    }
    return wave
}
