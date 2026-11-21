package com.simoneluconi.betterauto.voice.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.audio.CarVoice
import com.simoneluconi.betterauto.audio.NavPrompts
import com.simoneluconi.betterauto.phone.Calls
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.voice.MessageReader
import com.simoneluconi.betterauto.voice.VoiceAssistant
import com.simoneluconi.betterauto.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.concurrent.thread

/**
 * "hi batata", hands-free, while a car screen is up and the setting is on: the phone's microphone (recorded by us, as
 * the foreground service) through Vosk's small English model with [WakeMatcher.GRAMMAR], 100 ms of 16 kHz mono at a
 * time, every partial and final result through a [WakeDetector]. On a match the microphone is let go and the
 * assistant starts as the mic button starts it, greeting included.
 *
 * Light on the CPU: a grammar of a few look-alike phrases keeps Vosk's search tiny, and the microphone is closed
 * whenever [WakeGate] says so (the assistant, a call, a message being read), the thread then only checking 5 times a
 * second. The model is unpacked from the APK once (to `files/vosk-model`), loaded for the session and freed after.
 * What is logged is only ever the grammar's words, never what the driver says.
 *
 * Start and stop on the main thread; the listening runs on a thread of its own and only posts to the main one.
 */
object WakeListener {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var prompts: NavPrompts? = null
    private lateinit var appContext: Context

    /** The listening thread of now; an older one sees it is no longer this and ends. */
    @Volatile private var worker: Thread? = null
    @Volatile private var navPrompt = false
    /** One of our sounds played on the phone (its end is not followed) lasts until then. */
    @Volatile private var mutedUntil = 0L
    /** The assistant opens a microphone: ours stays closed until then (its state says "busy" meanwhile). */
    @Volatile private var yieldUntil = 0L
    private val lock = Object()
    @Volatile private var micOpen = false

    /** The car screen is up: listening whenever the setting is on, until [stop]. */
    fun start(context: Context) {
        stop()
        appContext = context.applicationContext
        // The driver's recordings of the phrase from an earlier build: no longer needed, and personal
        File(appContext.filesDir, "wake").takeIf { it.exists() }?.deleteRecursively()
        prompts = NavPrompts(appContext) { navPrompt = it }.also { it.start() }
        job = scope.launch {
            LauncherSettings.get(appContext).wakePhrase.collect { on -> if (on) startWorker() else stopWorker() }
        }
    }

    /** The car screen is gone: the microphone is let go, the model freed. */
    fun stop() {
        job?.cancel()
        job = null
        prompts?.stop()
        prompts = null
        navPrompt = false
        stopWorker()
    }

    /**
     * Lets the microphone go before the assistant (or the recognizer it starts) opens one, waiting the one read that
     * takes (100 ms), never longer than [RELEASE_WAIT_MS]. Main thread.
     */
    fun releaseMic() {
        yieldUntil = SystemClock.elapsedRealtime() + YIELD_MS
        if (!micOpen) return
        synchronized(lock) {
            val end = SystemClock.elapsedRealtime() + RELEASE_WAIT_MS
            while (micOpen) {
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) break
                lock.wait(left)
            }
        }
        if (micOpen) EventLog.log("Wake: the microphone was not let go in time")
    }

    /** One of our sounds plays on the phone for about [ms]: nothing heard counts until it is over (and a moment). */
    fun mute(ms: Long) {
        mutedUntil = maxOf(mutedUntil, SystemClock.elapsedRealtime() + ms)
    }

    private fun startWorker() {
        if (worker != null) return
        // Known as the current worker before it runs: its loop checks that first
        worker = thread(start = false, name = "wake") { loop() }.also { it.start() }
    }

    private fun stopWorker() {
        // Not joined: the thread sees it and lets the microphone go within one read
        worker = null
    }

    private fun current() = worker === Thread.currentThread()

    private fun inputs(now: Long): WakeGate.Inputs {
        val settings = LauncherSettings.get(appContext)
        return WakeGate.Inputs(
            enabled = settings.wakePhrase.value && settings.voiceEnabled.value,
            call = Calls.current.value != null,
            assistant = VoiceAssistant.state.value !is VoiceState.Idle || VoiceAssistant.asking,
            yielding = now < yieldUntil,
            reading = MessageReader.busy,
            ownSound = CarVoice.speaking || now < mutedUntil,
            navPrompt = navPrompt,
        )
    }

    private fun threshold() = WakeMatcher.threshold(LauncherSettings.get(appContext).wakeSensitivity.value.value)

    private fun loop() {
        val gate = WakeGate()
        val detector = WakeDetector()
        var model: Model? = null
        var rec: AudioRecord? = null
        var recognizer: Recognizer? = null
        var paused: String? = null
        var wasMuted = false
        /** Since when the audio is dropped, and whether that was logged as long: a stalled sound would mute for good. */
        var mutedSince = 0L
        var longMuteLogged = false
        var failing = false
        val chunk = ShortArray(RATE / 10)

        fun close() {
            rec?.let {
                runCatching { it.stop() }
                it.release()
            }
            rec = null
            recognizer?.close()
            recognizer = null
            synchronized(lock) {
                micOpen = false
                lock.notifyAll()
            }
        }

        try {
            while (current()) {
                val now = SystemClock.elapsedRealtime()
                val decision = gate.decide(inputs(now), now)
                if (decision is WakeGate.Decision.Released) {
                    if (rec != null) close()
                    if (paused != decision.why) EventLog.log("Wake: paused (${decision.why})")
                    paused = decision.why
                    Thread.sleep(PAUSED_POLL_MS)
                    continue
                }
                if (paused != null) {
                    EventLog.log("Wake: resumed")
                    paused = null
                }
                if (model == null) model = load() ?: return
                if (rec == null) {
                    rec = open()
                    if (rec == null) {
                        if (!failing) EventLog.log("Wake: microphone unavailable, trying again")
                        failing = true
                        Thread.sleep(RETRY_MS)
                        continue
                    }
                    failing = false
                    recognizer = Recognizer(model, RATE.toFloat(), WakeMatcher.GRAMMAR)
                    detector.reset()
                    wasMuted = false
                    EventLog.log("Wake: listening (threshold %.2f)".format(java.util.Locale.ROOT, threshold()))
                }
                val r = rec!!
                val n = r.read(chunk, 0, chunk.size)
                if (n <= 0) {
                    EventLog.log("Wake: microphone read failed ($n)")
                    close()
                    Thread.sleep(RETRY_MS)
                    continue
                }
                // Muted: the audio is still read (nothing queues up), but dropped
                if (decision is WakeGate.Decision.Muted) {
                    if (!wasMuted) {
                        recognizer?.reset()
                        detector.reset()
                        mutedSince = now
                    }
                    wasMuted = true
                    if (!longMuteLogged && now - mutedSince > LONG_MUTE_MS) {
                        EventLog.log("Wake: muted for over ${LONG_MUTE_MS / 1000} s (${decision.why})")
                        longMuteLogged = true
                    }
                    continue
                }
                if (longMuteLogged) EventLog.log("Wake: unmuted after ${(now - mutedSince) / 1000} s")
                longMuteLogged = false
                wasMuted = false
                val vosk = recognizer ?: continue
                val final = vosk.acceptWaveForm(chunk, n)
                val text = JSONObject(if (final) vosk.result else vosk.partialResult).optString(if (final) "text" else "partial")
                val t = threshold()
                if (detector.feed(text, final, t)) {
                    EventLog.log("Wake: heard “$text” score %.2f".format(java.util.Locale.ROOT, WakeMatcher.score(text)))
                    // Closed before the assistant opens its own; not reopened before it is under way
                    yieldUntil = SystemClock.elapsedRealtime() + YIELD_MS
                    close()
                    main.post(::wake)
                    continue
                }
                if (final && text.isNotEmpty()) {
                    val score = WakeMatcher.score(text)
                    if (score > NEAR_MISS) EventLog.log("Wake: near miss “$text” score %.2f".format(java.util.Locale.ROOT, score))
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            EventLog.log("Wake: stopped ($e)")
        } finally {
            close()
            model?.close()
            if (current()) worker = null
            if (model != null) EventLog.log("Wake: stopped listening, model freed")
        }
    }

    /** Main thread: as the mic button does, unless something else started meanwhile. */
    private fun wake() {
        if (VoiceAssistant.state.value !is VoiceState.Idle || VoiceAssistant.asking || Calls.current.value != null) return
        VoiceAssistant.start(appContext)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is asked for at setup; without it the record does not initialize
    private fun open(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            return null
        }
        if (runCatching { rec.startRecording() }.isFailure || rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            return null
        }
        micOpen = true
        return rec
    }

    /**
     * The model, unpacked from the APK's assets on the first use after each install or update (about 70 MB, a few
     * seconds), then loaded; null (logged) when the build has none or it fails.
     * ponytail: the model is stored twice on the phone (in the APK and unpacked); a download on first use would halve it.
     * Synchronized: a listener of a new session may start while the last one still unpacks.
     */
    @Synchronized
    private fun load(): Model? {
        val started = SystemClock.elapsedRealtime()
        return try {
            if (appContext.assets.list(ASSET_DIR).isNullOrEmpty()) {
                EventLog.log("Wake: no speech model in this build")
                return null
            }
            val version = appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime.toString()
            val dir = File(appContext.filesDir, ASSET_DIR)
            val marker = File(dir, ".unpacked")
            if (marker.takeIf { it.exists() }?.readText() != version) {
                EventLog.log("Wake: unpacking the speech model…")
                dir.deleteRecursively()
                copyAssets(ASSET_DIR, dir)
                marker.writeText(version)
            }
            Model(dir.absolutePath).also {
                EventLog.log("Wake: model ready in ${SystemClock.elapsedRealtime() - started} ms (Vosk small English, a grammar of look-alikes, 16 kHz in 100 ms reads)")
            }
        } catch (e: Throwable) {
            EventLog.log("Wake: model failed to load ($e)")
            null
        }
    }

    private fun copyAssets(path: String, to: File) {
        val children = appContext.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            appContext.assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
            return
        }
        to.mkdirs()
        for (c in children) copyAssets("$path/$c", File(to, c))
    }

    private const val RATE = 16_000
    private const val ASSET_DIR = "vosk-model"
    private const val PAUSED_POLL_MS = 200L
    private const val RETRY_MS = 2_000L
    /** Long enough for the assistant to show it is busy, which then holds the microphone off by itself. */
    private const val YIELD_MS = 1_500L
    private const val RELEASE_WAIT_MS = 400L
    private const val LONG_MUTE_MS = 15_000L
    /** A final result this close is logged, to tune the sensitivity on real drives. */
    private const val NEAR_MISS = 0.4f
}
