package com.simoneluconi.betterauto.audio

import android.os.Handler
import android.os.Looper
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.aap.AudioSinkChannel
import java.util.concurrent.Executors

/**
 * BetterAuto's own sounds (spoken directions, the assistant's replies, its chimes) straight into the car's media
 * stream while the phone's audio is copied. The copy never hears them: playback capture takes no navigation
 * audio at all, and in a car it did not hear BetterAuto's own media playback either ("the copy hears nothing"
 * with our radio playing). Played on the muted phone they were silent; with the speaker lifted for them, the music
 * came out of the phone too. So they are decoded here and mixed into the copied music, which is lowered meanwhile.
 *
 * Only while our media stream holds the car's audio focus: otherwise the car is on a source of its own (its radio),
 * and the first word would take its speakers for good. [play] then says no and the caller plays on the phone as
 * before (a prompt lifts the phone's speaker through [NavPrompts]).
 * ponytail: the car's guidance stream would take them with the car's own ducking and a passing focus, but no car has
 * been heard playing it yet; the media stream is the one known to sound.
 */
object CarVoice {
    private val main = Handler(Looper.getMainLooper())

    /** Read by the capture thread ([AudioCapture]). */
    val queue = VoiceQueue { main.post(it) }

    @Volatile
    private var media: AudioSinkChannel? = null

    /** Decodes and synthesizes in order, off the callers' threads. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "car-voice") }

    /** Per owner: bumped by each flush, so audio still being made for a flushed request is thrown away. */
    private val generation = HashMap<Int, Int>()

    /**
     * [CarAudio]: the media stream our sounds are mixed into, while the audio is copied in float; null otherwise.
     * Speech cut by a change still runs its callbacks: a greeting's caller waits for its end to open the microphone.
     */
    @Synchronized
    fun attach(sink: AudioSinkChannel?) {
        if (sink === media) return
        media = sink
        generation.replaceAll { _, gen -> gen + 1 }
        queue.clear(null, runDone = true)
    }

    /** Our speech is heard in the car now: the music under it is lowered. */
    val speaking: Boolean get() = queue.hasSound

    /**
     * Plays what [source] makes (called on a worker thread; an empty list is nothing to say) in the car, after
     * what is waiting unless [queued] is false: then [owner]'s earlier sounds are cut. [done] runs on the main
     * thread once the last sample is in the car's stream, not when cut. False when the car does not take our
     * sounds now: the caller plays them on the phone.
     */
    fun play(owner: Int, queued: Boolean, done: (() -> Unit)?, source: () -> List<Pcm>): Boolean {
        val sink = media?.takeIf { it.holdsFocus } ?: return false
        val gen = synchronized(this) {
            if (!queued) flush(owner)
            generation[owner] ?: 0
        }
        worker.execute {
            val sounds = runCatching(source).onFailure { EventLog.log("Car voice: not made ($it)") }.getOrNull().orEmpty()
            val format = sink.format
            val converted = sounds.map { it.to(format) }
            synchronized(this) {
                if ((generation[owner] ?: 0) != gen) return@execute
                // Gone with the car stream, or nothing to say: whoever waits for the end goes on
                if (media !== sink || converted.isEmpty()) {
                    done?.let { main.post(it) }
                    return@execute
                }
                converted.forEachIndexed { i, samples -> queue.add(owner, samples, done.takeIf { i == converted.lastIndex }) }
            }
            EventLog.log("Car voice: %.1f s of speech mixed into the car's stream".format(java.util.Locale.ROOT, converted.sumOf { it.size } / (format.sampleRate * format.channels).toDouble()))
        }
        return true
    }

    /** Cuts [owner]'s sounds, waiting or being made. */
    @Synchronized
    fun stop(owner: Int) = flush(owner)

    private fun flush(owner: Int) {
        generation[owner] = (generation[owner] ?: 0) + 1
        queue.clear(owner)
    }
}
