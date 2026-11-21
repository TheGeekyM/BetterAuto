package com.simoneluconi.betterauto.media

import android.util.LruCache
import com.simoneluconi.betterauto.EventLog
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/** A song's words: [lines] with their time when [synced], else the plain text one line each. */
data class SongLyrics(val lines: List<LyricLine>, val synced: Boolean)

data class LyricLine(val timeMs: Long, val text: String)

/**
 * Lyrics from LRCLIB (https://lrclib.net, free, no key): players don't share theirs. Only the title, artist,
 * album and length of the song go out. Timed lyrics when there are, so the line being sung can be shown.
 */
object Lyrics {
    private const val API = "https://lrclib.net/api"
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    // Null held as NONE: a song without lyrics isn't asked again
    private val cache by lazy { LruCache<String, Any>(64) }
    private val NONE = Any()

    /** Blocking: off the main thread. Null when LRCLIB has none (or can't be reached). */
    fun find(title: String, artist: String, album: String, durationMs: Long): SongLyrics? {
        if (title.isBlank()) return null
        val key = "$artist|$title|${durationMs / 1000}"
        cache.get(key)?.let { return it as? SongLyrics }
        val found = runCatching { exact(title, artist, album, durationMs) ?: search(plain(title), plain(artist)) }
            .onFailure { EventLog.log("Lyrics: LRCLIB not reached ($it)") }
            .getOrElse { return null }
        cache.put(key, found ?: NONE)
        return found
    }

    /** The song itself: same title, artist and length (within 2 s, LRCLIB's rule). */
    private fun exact(title: String, artist: String, album: String, durationMs: Long): SongLyrics? {
        val url = "$API/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (album.isNotBlank()) addQueryParameter("album_name", album) }
            .apply { if (durationMs > 0) addQueryParameter("duration", (durationMs / 1000).toString()) }
            .build()
        val body = get(url.toString()) ?: return null
        return of(JSONObject(body))
    }

    /** Else the best match by name: the titles of streaming players often carry extras ("(Official Video)"). */
    private fun search(title: String, artist: String): SongLyrics? {
        val url = "$API/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title.replace(Extras, "").trim())
            .apply { if (artist.isNotBlank()) addQueryParameter("artist_name", artist) }
            .build()
        val body = get(url.toString()) ?: return null
        val results = JSONArray(body)
        val all = (0 until results.length()).map { results.getJSONObject(it) }
        return (all.firstOrNull { it.optString("syncedLyrics").isNotBlank() } ?: all.firstOrNull())?.let(::of)
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", "BetterAuto (https://github.com/simoneluconi/BetterAuto)").build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body?.string()
        }
    }

    private fun of(song: JSONObject): SongLyrics? {
        if (song.optBoolean("instrumental")) return null
        parseSynced(song.optString("syncedLyrics").takeIf { it != "null" }.orEmpty()).takeIf { it.isNotEmpty() }
            ?.let { return SongLyrics(it, synced = true) }
        val plain = song.optString("plainLyrics").takeIf { it != "null" }.orEmpty().lines().map { it.trim() }
        return plain.takeIf { lines -> lines.any { it.isNotEmpty() } }?.let { lines -> SongLyrics(lines.map { LyricLine(0, it) }, synced = false) }
    }

    /** "[01:02.50] words" lines, in time order; a line may carry several times. */
    internal fun parseSynced(lrc: String): List<LyricLine> = lrc.lines().flatMap { line ->
        val times = Stamp.findAll(line).toList()
        if (times.isEmpty()) return@flatMap emptyList()
        val text = line.substring(times.last().range.last + 1).trim()
        times.map { m ->
            val (min, sec, frac) = m.destructured
            val fracMs = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').take(3).toInt()
            LyricLine(min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs, text)
        }
    }.sortedBy { it.timeMs }

    /** The line being sung at [positionMs]: the last one started, -1 before the first. */
    fun lineAt(lines: List<LyricLine>, positionMs: Long): Int = lines.indexOfLast { it.timeMs <= positionMs }

    /** Without harakat and tatweel: LRCLIB's titles are mostly written plain. */
    private fun plain(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Marks, "").replace("ـ", "")

    private val Stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val Marks = Regex("\\p{Mn}")
    private val Extras = Regex("\\s*[(\\[][^)\\]]*(official|video|audio|lyrics|clip|كليب|فيديو)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
}
