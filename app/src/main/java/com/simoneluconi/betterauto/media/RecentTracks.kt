package com.simoneluconi.betterauto.media

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A song heard earlier, for the music home when the player shares no queue: played again by a search in its app. */
data class RecentTrack(val packageName: String, val title: String, val artist: String, val art: ImageBitmap?) {
    fun same(other: RecentTrack) = packageName == other.packageName && title == other.title && artist == other.artist
}

/**
 * The songs heard while the app runs, newest first, in memory only (covers are not kept on disk). Follows
 * [NowPlaying] from the first [start] (the music card or page on screen) to the end of the process. Main thread only.
 */
object RecentTracks {
    private const val MAX = 12

    private val _list = MutableStateFlow<List<RecentTrack>>(emptyList())
    val list: StateFlow<List<RecentTrack>> = _list

    private var started = false

    fun start() {
        if (started) return
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            NowPlaying.track.collect { note(it) }
        }
    }

    private fun note(track: Track?) {
        if (track == null || track.title.isBlank()) return
        val entry = RecentTrack(track.packageName, track.title, track.artist, track.art)
        val old = _list.value
        val first = old.firstOrNull()
        // The same song again (every position update): only its cover may arrive late
        if (first != null && first.same(entry)) {
            if (first.art == null && entry.art != null) _list.value = listOf(entry) + old.drop(1)
            return
        }
        _list.value = (listOf(entry) + old.filterNot { it.same(entry) }).take(MAX)
    }
}
