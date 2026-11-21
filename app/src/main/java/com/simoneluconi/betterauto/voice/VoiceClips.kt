package com.simoneluconi.betterauto.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.SystemClock
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.audio.CarVoice
import com.simoneluconi.betterauto.audio.Pcm
import java.io.ByteArrayOutputStream
import com.simoneluconi.betterauto.map.nav.Maneuver
import com.simoneluconi.betterauto.map.nav.ManeuverKind
import com.simoneluconi.betterauto.settings.AppLocales
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.SpokenVoice
import com.simoneluconi.betterauto.voice.wake.WakeListener

/**
 * Natural voices, recorded once (ElevenLabs "Soraya" and "Nadine", through Higgsfield; Google Gemini's "Autonoe",
 * Arabic only) and shipped in the app: the fixed words of the guidance and of the assistant's replies, in Egyptian
 * Arabic (spelled as Cairo says it, ق as a glottal stop) and English, in `assets/voice/<voice>/<language>/`; the
 * driver picks one, or the phone's voice. A voice with no recording of what is said (a language it doesn't speak,
 * or a build without its folder) hands that sentence to [SpokenVoice.FALLBACK]: never two voices in one sentence.
 * Joined at run time ("In 300 metres" + "turn right"); what changes (street, contact and station names) and any
 * language without recordings stays with the phone's text-to-speech.
 */
object VoiceClips {
    private val Languages = setOf("ar", "en")

    /** The recordings' folders to try, best first, for the chosen voice and the voice language; none: the phone's voice speaks. */
    fun folders(context: Context): List<String> =
        folders(LauncherSettings.get(context).spokenVoice.value, AppLocales.voice(context).language)

    internal fun folders(voice: SpokenVoice, language: String): List<String> {
        if (voice.folder == null || language !in Languages) return emptyList()
        return listOfNotNull(voice.folder, SpokenVoice.FALLBACK.folder).distinct().map { "voice/$it/$language" }
    }

    /**
     * The files to play for [keys] (each as [pick] finds it), all from the first of [folders] that has every one,
     * as [list] says what a folder holds; null when none has them all.
     */
    internal fun resolve(keys: List<String>, folders: List<String>, list: (String) -> Set<String>): List<String>? =
        folders.firstNotNullOfOrNull { folder ->
            val present = list(folder)
            keys.map { key -> pick(key, present)?.let { "$folder/$it" } ?: return@firstNotNullOfOrNull null }
        }

    /** What [folder] holds in the app's assets (empty when the build has no such folder). */
    internal fun listed(context: Context, folder: String): Set<String> =
        runCatching { context.assets.list(folder)?.toSet() }.getOrNull().orEmpty()

    /** "In 300 metres" as recorded: the same rounding as the spoken text ([com.simoneluconi.betterauto.map.nav.NavVoice.aheadPhrase]). */
    internal fun distance(metres: Double): String? {
        if (metres >= 950) {
            val tenths = Math.round(metres / 100)
            return when {
                tenths <= 12 -> "km1"
                tenths <= 17 -> "km1_5"
                else -> Math.round(tenths / 10.0).toInt().takeIf { it in 2..10 }?.let { "km$it" }
            }
        }
        val step = if (metres < 300) 50 else 100
        val m = (Math.round(metres / step) * step).coerceAtLeast(step.toLong()).toInt()
        return "m$m".takeIf { m <= 900 }
    }

    /**
     * The file to play for [key] among those [present]: the plain recording or one of its personal takes
     * (`<key>.p1.ogg`, `<key>.p2.ogg`…, with the driver's name; local only, never in git), these about 70% of the
     * time when there are any. Null when there is neither.
     */
    internal fun pick(key: String, present: Set<String>, random: kotlin.random.Random = kotlin.random.Random): String? {
        val plain = "$key.ogg".takeIf { it in present }
        val personal = present.filter { it.startsWith("$key.p") && PersonalTake.matches(it.removePrefix(key)) }
        return when {
            personal.isEmpty() -> plain
            plain == null || random.nextFloat() < PERSONAL_SHARE -> personal.random(random)
            else -> plain
        }
    }

    private val PersonalTake = Regex("\\.p\\d+\\.ogg")
    private const val PERSONAL_SHARE = 0.7f

    /** The maneuver's words; [soon] for the announcement ahead (an arrival is "you'll reach" then, "you have arrived" after). */
    internal fun maneuver(m: Maneuver, soon: Boolean): String = when (m.kind) {
        ManeuverKind.STRAIGHT -> "straight"
        ManeuverKind.RIGHT -> "right"
        ManeuverKind.LEFT -> "left"
        ManeuverKind.SLIGHT_RIGHT -> "slight_right"
        ManeuverKind.SLIGHT_LEFT -> "slight_left"
        ManeuverKind.SHARP_RIGHT -> "sharp_right"
        ManeuverKind.SHARP_LEFT -> "sharp_left"
        ManeuverKind.U_TURN -> "u_turn"
        ManeuverKind.ROUNDABOUT -> m.roundaboutExit?.takeIf { it in 1..6 }?.let { "rb$it" } ?: "roundabout"
        ManeuverKind.ARRIVE -> (if (m.stop != null) "stop_" else "arrive_") + if (soon) "soon" else "now"
    }

    /** The recording [text] is a form of: the string itself, or its template with anything in place of %1$s. */
    internal fun recordedFor(text: String, templates: Map<String, String>): String? =
        templates.entries.firstOrNull { (template, _) -> matches(template, text) }?.value

    private fun matches(template: String, text: String): Boolean {
        val parts = template.split(Placeholder)
        if (parts.size == 1) return template == text
        return Regex(parts.joinToString(".+") { Regex.escape(it) }, RegexOption.DOT_MATCHES_ALL).matches(text)
    }

    private val Placeholder = Regex("%\\d\\$[sd]")

    /** "In 300 metres, turn right", when both parts are recorded. */
    internal fun ahead(metres: Double, m: Maneuver): List<String>? = distance(metres)?.let { listOf(it, maneuver(m, soon = true)) }
}

/**
 * Plays recordings one after the other with [usage] (navigation guidance, or media for replies). [play] says no
 * when one is missing, so the caller speaks the text instead. Main thread or not: MediaPlayer calls back on the
 * thread that made it, if it has a looper, else the main one. While the phone's audio is copied to the car the
 * clips are decoded and go to [CarVoice] instead (the copy never hears our own playback).
 */
internal class ClipPlayer(private val context: Context, private val usage: Int) {
    private val lock = Any()
    private var player: MediaPlayer? = null
    private val queue = ArrayDeque<String>()
    private var onDone: (() -> Unit)? = null

    /** [done] runs (on the player's thread, or the main one) when the last clip has been heard; not when stopped before. */
    fun play(clips: List<String>, queued: Boolean = false, done: (() -> Unit)? = null): Boolean {
        // Each clip, or one of its personal takes when the build has them, all in one voice
        val files = VoiceClips.resolve(clips, VoiceClips.folders(context)) { VoiceClips.listed(context, it) } ?: return false
        synchronized(lock) {
            if (!queued) stopLocked()
            if (CarVoice.play(usage, queued, done) { files.mapNotNull(::decode) }) return true
            onDone = done
            queue.addAll(files)
            if (player == null) nextLocked()
        }
        return true
    }

    fun stop() {
        CarVoice.stop(usage)
        synchronized(lock) { stopLocked() }
    }

    /** One recording as PCM (Opus in Ogg: the platform's extractor and decoder), or null. */
    private fun decode(file: String): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            context.assets.openFd(file).use { extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(input, null, null, 0)
            decoder.start()
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(CODEC_WAIT_US)
                    if (i >= 0) {
                        val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, CODEC_WAIT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    // 16-bit little-endian, the decoders' default output
                    val buffer = decoder.getOutputBuffer(o)!!
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    pcm.write(bytes)
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val bytes = pcm.toByteArray()
            Pcm.of16(bytes, 0, bytes.size and 1.inv(), rate, channels)
        } catch (e: Exception) {
            EventLog.log("Voice clips: $file not decoded ($e)")
            null
        } finally {
            codec?.runCatching { stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun stopLocked() {
        onDone = null
        queue.clear()
        player?.runCatching { stop(); release() }
        player = null
    }

    private fun nextLocked() {
        val file = queue.removeFirstOrNull() ?: run {
            onDone?.invoke()
            onDone = null
            return
        }
        val mp = MediaPlayer()
        player = mp
        runCatching {
            context.assets.openFd(file).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            mp.setOnCompletionListener {
                synchronized(lock) {
                    if (player !== it) return@setOnCompletionListener
                    it.release()
                    player = null
                    nextLocked()
                }
            }
            mp.prepare()
            mp.start()
            WakeListener.mute(mp.duration.toLong())
        }.onFailure {
            EventLog.log("Voice clips: $file not played ($it)")
            mp.release()
            player = null
            nextLocked()
        }
    }

    private companion object {
        const val CODEC_WAIT_US = 10_000L
        /** A clip lasts seconds and decodes in milliseconds: this only guards against a decoder that never ends. */
        const val DECODE_TIMEOUT_MS = 5_000L
    }
}
