package com.simoneluconi.betterauto.radio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.media.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/** A radio station from radio-browser.info. */
data class Station(val id: String, val name: String, val url: String, val tags: String)

/**
 * Egypt's internet radio stations, played by the app itself: the list comes from radio-browser.info (the open
 * station directory, every station listed in Egypt), kept on the phone for the next time without a connection.
 * Played with a media session of its own, for the phone's media keys and notification. The music player never shows
 * it (it keeps the song the radio paused); the car's media keys and the voice reach the radio through NowPlaying.
 */
object Radio {
    enum class Status { IDLE, CONNECTING, PLAYING, FAILED }

    private const val LIST_URL = "https://all.api.radio-browser.info/json/stations/bycountrycodeexact/EG?limit=1000"
    private const val REFRESH_MS = 24 * 60 * 60 * 1000L
    private const val PREFS = "radio"
    private const val KEY_LAST = "last"

    private val _stations = MutableStateFlow<List<Station>?>(null)
    /** Null until the list is read (from the phone or the directory); empty when neither has it. */
    val stations: StateFlow<List<Station>?> = _stations

    private val _current = MutableStateFlow<Station?>(null)
    val current: StateFlow<Station?> = _current

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    /** Playing or tuning in: what the car's keys and the voice act on. Any thread. */
    val on: Boolean get() = _status.value == Status.PLAYING || _status.value == Status.CONNECTING

    /** When a station was last tuned in (elapsed realtime). */
    @Volatile
    var tunedAt = 0L
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private lateinit var appContext: Context
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null
    private var loading = false

    // Lazy: the list's parsing is tested off Android
    private val attributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    /** Reads the list: the phone's copy at once, the directory's when the copy is a day old or missing. */
    fun load(context: Context) {
        appContext = context.applicationContext
        if (loading) return
        loading = true
        scope.launch {
            val cache = File(appContext.filesDir, "radio_eg.json")
            if (_stations.value == null) {
                _stations.value = runCatching { parse(cache.readText()) }.getOrNull()
                val last = appContext.getSharedPreferences(PREFS, 0).getString(KEY_LAST, null)
                if (_current.value == null) _current.value = _stations.value?.firstOrNull { it.id == last }
            }
            if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > REFRESH_MS || _stations.value.isNullOrEmpty()) {
                runCatching {
                    val request = Request.Builder().url(LIST_URL).header("User-Agent", "BetterAuto").build()
                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        val body = response.body!!.string()
                        val list = parse(body)
                        check(list.isNotEmpty()) { "no stations" }
                        cache.writeText(body)
                        _stations.value = list
                        EventLog.log("Radio: ${list.size} Egyptian stations from radio-browser.info")
                    }
                }.onFailure { EventLog.log("Radio: station list not reached ($it)") }
            }
            if (_stations.value == null) _stations.value = emptyList()
            loading = false
        }
    }

    /**
     * The directory's answer as stations: one per name (several entries of a station are the same one), the
     * working and most voted first, as the directory ranks them.
     */
    internal fun parse(json: String): List<Station> {
        val array = JSONArray(json)
        val entries = (0 until array.length()).map { array.getJSONObject(it) }
            .filter { it.optString("url_resolved").isNotBlank() || it.optString("url").isNotBlank() }
            .sortedWith(compareByDescending<org.json.JSONObject> { it.optInt("lastcheckok") }.thenByDescending { it.optInt("votes") })
        return entries
            .distinctBy { it.optString("name").trim().lowercase().replace(Regex("\\s+"), " ") }
            .map {
                Station(
                    id = it.optString("stationuuid"),
                    name = it.optString("name").replace(Regex("\\s+"), " ").trim(),
                    url = it.optString("url_resolved").ifBlank { it.optString("url") },
                    tags = it.optString("tags").split(',').filter { tag -> tag.isNotBlank() }.take(3).joinToString(" · "),
                )
            }
            .filter { it.name.isNotBlank() }
    }

    fun play(context: Context, station: Station) {
        appContext = context.applicationContext
        release()
        _current.value = station
        _status.value = Status.CONNECTING
        appContext.getSharedPreferences(PREFS, 0).edit().putString(KEY_LAST, station.id).apply()
        if (!requestFocus()) {
            EventLog.log("Radio: no audio focus")
            _status.value = Status.FAILED
            return
        }
        EventLog.log("Radio: tuning in to ${station.name}")
        tunedAt = SystemClock.elapsedRealtime()
        // The focus is ours, but some players keep playing after losing it: the song is paused by hand too
        NowPlaying.pauseForRadio()
        player = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setOnPreparedListener {
                it.start()
                _status.value = Status.PLAYING
                publish()
            }
            setOnErrorListener { _, what, extra ->
                EventLog.log("Radio: ${station.name} failed ($what, $extra)")
                _status.value = Status.FAILED
                publish()
                true
            }
            // A live stream that ends has dropped: tune in again
            setOnCompletionListener { _current.value?.let { s -> play(appContext, s) } }
            runCatching {
                setDataSource(station.url)
                prepareAsync()
            }.onFailure {
                EventLog.log("Radio: ${station.name} not playable ($it)")
                _status.value = Status.FAILED
            }
        }
        publish()
    }

    /** Plays the last station (or the first) when stopped, stops when playing. */
    fun toggle(context: Context) {
        if (on) return stop()
        val station = _current.value ?: _stations.value?.firstOrNull() ?: return
        play(context, station)
    }

    fun next(context: Context) = step(context, 1)

    fun previous(context: Context) = step(context, -1)

    private fun step(context: Context, by: Int) {
        val list = _stations.value.orEmpty()
        if (list.isEmpty()) return
        val at = list.indexOfFirst { it.id == _current.value?.id }
        play(context, list[Math.floorMod(at + by, list.size)])
    }

    fun stop() {
        if (player == null && _status.value == Status.IDLE) return
        EventLog.log("Radio: stopped")
        release()
        _status.value = Status.IDLE
        publish()
        session?.isActive = false
    }

    private fun release() {
        player?.runCatching { reset(); release() }
        player = null
        focus?.let { appContext.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    // Another player started: the radio gives way for good
                    AudioManager.AUDIOFOCUS_LOSS -> stop()
                    // A prompt or the voice for a moment: silent, still tuned in (a live stream has nothing to pause)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.setVolume(0f, 0f)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.3f, 0.3f)
                    AudioManager.AUDIOFOCUS_GAIN -> player?.setVolume(1f, 1f)
                }
            }
            .build()
        val granted = appContext.getSystemService(AudioManager::class.java).requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focus = request
        return granted
    }

    /** The radio's own picture, for its media session (stations have no covers here). */
    private val cover: Bitmap by lazy {
        val full = BitmapFactory.decodeResource(appContext.resources, R.drawable.radio_default)
        Bitmap.createScaledBitmap(full, 512, 512, true)
    }

    /** The station on the phone's media session (the phone's media keys, the notification); the music player ignores it. */
    private fun publish() {
        val s = session ?: MediaSession(appContext, "BetterAuto radio").also {
            it.setCallback(object : MediaSession.Callback() {
                // Play means play (the car sends it on its own too): never a stop
                override fun onPlay() {
                    if (!on) toggle(appContext)
                }
                override fun onPause() = stop()
                override fun onStop() = stop()
                override fun onSkipToNext() = next(appContext)
                override fun onSkipToPrevious() = previous(appContext)
            })
            session = it
        }
        val station = _current.value ?: return
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, station.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, station.tags.ifBlank { "Radio" })
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Radio")
                .putBitmap(MediaMetadata.METADATA_KEY_ART, cover)
                .build(),
        )
        val state = when (_status.value) {
            Status.PLAYING -> PlaybackState.STATE_PLAYING
            Status.CONNECTING -> PlaybackState.STATE_BUFFERING
            Status.FAILED -> PlaybackState.STATE_ERROR
            Status.IDLE -> PlaybackState.STATE_STOPPED
        }
        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .build(),
        )
        if (_status.value != Status.IDLE) s.isActive = true
    }
}
