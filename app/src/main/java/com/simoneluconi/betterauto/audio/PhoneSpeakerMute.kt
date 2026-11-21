package com.simoneluconi.betterauto.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.simoneluconi.betterauto.EventLog

/**
 * Playback capture copies the phone's audio instead of moving it, so while music goes to the car we
 * turn the phone's media volume down to its lowest step and keep it there (the capture is taken before
 * the volume, so the car still gets full level). Not to zero: YouTube 21.45 mutes its own player half a
 * second after the media volume reaches zero, and the capture, taken after each app's own volume, then
 * heard silence (seen as "muted by streamVolume clientVolume" in `dumpsys audio`). The lowest of the
 * phone's steps (1 of 150 on HyperOS) is not heard over the car. The previous volume is persisted and
 * put back when car audio stops, or on the next start if the app died in between. A navigation prompt,
 * which capture cannot take, lifts it back for a moment ([lift]) so the driver still hears it from the phone.
 */
object PhoneSpeakerMute {
    private const val PREFS = "phone_speaker_mute"
    private const val KEY_SAVED = "saved_media_volume"
    private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    private var receiver: BroadcastReceiver? = null

    /** The saved volume is back for a navigation prompt: volume changes are not undone meanwhile. */
    @Volatile
    private var lifted = false

    /** The level the phone is held at: its lowest step above zero; the stream's own minimum if that is higher. */
    @Volatile
    private var quiet = 1

    fun engage(context: Context) {
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SAVED)) prefs.edit { putInt(KEY_SAVED, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
        quiet = quietLevel(audio.getStreamMinVolume(AudioManager.STREAM_MUSIC))
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, quiet, 0)
        // A ROM may round the step (even to zero): hold whatever it settled on, or the receiver would fight it forever
        val held = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        quiet = held

        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC || lifted) return
                    // Up would be heard, zero would mute the players that mute themselves: back to the quiet step
                    if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != quiet) {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, quiet, 0)
                    }
                }
            }
            ContextCompat.registerReceiver(app, r, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            receiver = r
        }
        val db = runCatching {
            audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, held, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        }.getOrNull()
        val level = db?.let { "%.0f dB".format(java.util.Locale.ROOT, it) } ?: "level unknown"
        EventLog.log("Phone speaker muted while audio goes to the car (media volume $held of ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, $level)")
    }

    /** The quiet step for a stream whose lowest index is [min]: never zero. */
    internal fun quietLevel(min: Int): Int = maxOf(min, 1)

    /** While muted: the saved volume back for a navigation prompt (the music is heard on the phone too, briefly). */
    fun lift(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SAVED) || lifted) return
        lifted = true
        app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, prefs.getInt(KEY_SAVED, 0), 0)
    }

    /** The prompt is over: quiet again, if still muted. */
    fun lower(context: Context) {
        if (!lifted) return
        lifted = false
        val app = context.applicationContext
        if (!isMuted(app)) return
        app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, quiet, 0)
    }

    /** True when the phone is (or was left) muted by [engage] and not yet restored. */
    fun isMuted(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_SAVED)

    /** Puts back the volume saved by [engage]; safe to call when nothing was saved. */
    fun release(context: Context) {
        val app = context.applicationContext
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        lifted = false
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SAVED)) return
        val saved = prefs.getInt(KEY_SAVED, 0)
        prefs.edit { remove(KEY_SAVED) }
        val audio = app.getSystemService(AudioManager::class.java)
        // Still at our quiet step (or zero, as earlier versions left it): ours to put back. Otherwise the user
        // already set a volume they want, after the app died
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) > quiet) return
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
        EventLog.log("Phone media volume restored to $saved")
    }
}
