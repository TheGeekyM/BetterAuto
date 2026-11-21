package com.simoneluconi.betterauto.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class LibraryItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageBitmap?,
    val browsable: Boolean,
    val playable: Boolean,
    /** Single-color glyph on transparency: tint it with the theme instead of showing it as a picture. */
    val glyph: Boolean = false,
)

/** A folder of the library's root with its first items, for the music home; [folder] null for loose tracks at the root. */
data class LibrarySection(val folder: LibraryItem?, val items: List<LibraryItem>, val more: Boolean)

sealed interface LibraryState {
    data object Connecting : LibraryState

    /** The app has no media browser, or refuses apps other than the ones it trusts. */
    data object Unavailable : LibraryState

    data class Loaded(val title: String?, val items: List<LibraryItem>, val canGoBack: Boolean) : LibraryState
}

/** Icons are shown at about 60 design units: decoding bigger ones only costs memory. */
private const val ICON_PX = 128

/** Folders are fetched in one page; apps that page by themselves slice at this size. */
private const val PAGE_SIZE = 2000

/** The home's previews: a few folders of the root, a few items each, their art big enough for cards. */
private const val PREVIEW_SECTIONS = 6
private const val PREVIEW_ITEMS = 8
private const val PREVIEW_PX = 224
private const val PREVIEW_ROOT = 24

/** Covers for the previews are fetched a few at a time: some come from the web. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val artDispatcher = Dispatchers.IO.limitedParallelism(4)

/**
 * Browses a music app's library through its media browser service (Media3 or the older kind), like
 * Android Auto does. Many apps only accept the callers they trust, so [LibraryState.Unavailable] is a
 * normal outcome. Create, use and close on the main thread.
 */
@OptIn(UnstableApi::class)
class MediaLibrary(private val context: Context, private val packageName: String) {
    private val _state = MutableStateFlow<LibraryState>(LibraryState.Connecting)
    val state: StateFlow<LibraryState> = _state

    private val _sections = MutableStateFlow<List<LibrarySection>?>(null)

    /** The root's folders with their first items (the music home's rows); null while loading, empty without a library. */
    val sections: StateFlow<List<LibrarySection>?> = _sections

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null
    private var closed = false

    /** Opened folders, root first: id to title. */
    private val path = ArrayList<Pair<String, String?>>()

    /** Only the latest folder request may publish: going back quickly must not show a stale folder. */
    private var request = 0

    fun connect() {
        val service = context.packageManager
            .queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(packageName), 0)
            .firstOrNull()?.serviceInfo
        if (service == null) {
            _state.value = LibraryState.Unavailable
            _sections.value = emptyList()
            return
        }
        val token = SessionToken(context, ComponentName(service.packageName, service.name))
        val future = MediaBrowser.Builder(context, token).buildAsync()
        browserFuture = future
        future.addListener({
            val b = runCatching { future.get() }.getOrNull()
            if (b == null || closed) {
                b?.release()
                if (!closed) {
                    _state.value = LibraryState.Unavailable
                    _sections.value = emptyList()
                }
                return@addListener
            }
            browser = b
            val rootFuture = b.getLibraryRoot(null)
            rootFuture.addListener({
                val root = runCatching { rootFuture.get() }.getOrNull()?.value?.mediaId
                if (root == null) {
                    _state.value = LibraryState.Unavailable
                    _sections.value = emptyList()
                } else {
                    open(root, null)
                    loadSections(root)
                }
            }, context.mainExecutor)
        }, context.mainExecutor)
    }

    fun select(item: LibraryItem) {
        val b = browser ?: return
        when {
            item.browsable -> open(item.id, item.title)
            item.playable -> {
                b.setMediaItem(MediaItem.Builder().setMediaId(item.id).build())
                b.play()
            }
        }
    }

    /** Opens [folder] (from the home) as if picked at the root: back goes to the root, wherever the library was. */
    fun openFromRoot(folder: LibraryItem) {
        if (browser == null || path.isEmpty()) return
        val root = path.first()
        path.clear()
        path.add(root)
        open(folder.id, folder.title)
    }

    /** One folder up; false at the root. */
    fun back(): Boolean {
        if (path.size <= 1) return false
        path.removeAt(path.lastIndex)
        val (id, title) = path.removeAt(path.lastIndex)
        open(id, title)
        return true
    }

    fun close() {
        closed = true
        scope.cancel()
        browser?.release()
        browser = null
        browserFuture?.let { MediaBrowser.releaseFuture(it) }
        browserFuture = null
    }

    private fun open(id: String, title: String?) {
        val b = browser ?: return
        path.add(id to title)
        _state.value = LibraryState.Connecting
        val current = ++request
        val future = b.getChildren(id, 0, PAGE_SIZE, null)
        future.addListener({
            if (current != request || closed) return@addListener
            val result = runCatching { future.get() }.getOrNull()
            if (result == null || result.resultCode != LibraryResult.RESULT_SUCCESS) {
                _state.value = LibraryState.Unavailable
                return@addListener
            }
            val children = result.value.orEmpty()
            scope.launch {
                // Art bytes are decoded and glyphs detected off the main thread: folders can hold hundreds of items
                val items = withContext(Dispatchers.Default) {
                    children.map { item ->
                        val m = item.mediaMetadata
                        val icon = m.artworkData?.let { Artwork.decode(it, ICON_PX) }
                        LibraryItem(
                            id = item.mediaId,
                            title = m.title?.toString().orEmpty(),
                            subtitle = (m.subtitle ?: m.artist)?.toString().orEmpty(),
                            icon = icon?.asImageBitmap(),
                            glyph = icon?.let(::isGlyph) ?: false,
                            browsable = m.isBrowsable == true,
                            playable = m.isPlayable == true,
                        )
                    }
                }
                if (current != request) return@launch
                _state.value = LibraryState.Loaded(title, items, path.size > 1)
                loadIcons(current, children)
            }
        }, context.mainExecutor)
    }

    /**
     * The home's rows: loose tracks at the root, then the first folders with their first items, fetched side by side
     * without touching the browsing ([path], [state]). The words come first; the covers follow a section at a time
     * (web ones are downloaded: a slow one must not hold the rows back), only the previews' bigger.
     */
    private fun loadSections(root: String) {
        scope.launch {
            val top = children(root, PREVIEW_ROOT)
            if (top == null) {
                _sections.value = emptyList()
                return@launch
            }
            val loose = top.first.filter { it.mediaMetadata.isBrowsable != true && it.mediaMetadata.isPlayable == true }
            val folders = top.first.filter { it.mediaMetadata.isBrowsable == true }.take(PREVIEW_SECTIONS)
            val raw = buildList {
                if (loose.isNotEmpty()) add(null to (loose.take(PREVIEW_ITEMS) to (loose.size > PREVIEW_ITEMS)))
                addAll(folders.map { folder -> async { children(folder.mediaId, PREVIEW_ITEMS)?.let { plain(folder) to it } } }.awaitAll().filterNotNull())
            }.filter { it.second.first.isNotEmpty() }
            if (closed) return@launch
            _sections.value = raw.map { (folder, items) -> LibrarySection(folder, items.first.map(::plain), items.second) }
            raw.forEachIndexed { index, (_, items) ->
                launch {
                    val covers = items.first.map { item ->
                        async(artDispatcher) {
                            val m = item.mediaMetadata
                            Artwork.decode(context, m.artworkData, m.artworkUri, PREVIEW_PX)?.let { it to isGlyph(it) }
                        }
                    }.awaitAll()
                    val now = _sections.value ?: return@launch
                    if (closed || index >= now.size) return@launch
                    // On the main thread between suspensions: no other section's update can slip in between read and write
                    _sections.value = now.toMutableList().also { list ->
                        val section = list[index]
                        list[index] = section.copy(items = section.items.mapIndexed { i, it ->
                            val cover = covers.getOrNull(i)
                            if (cover == null || it.icon != null) it else it.copy(icon = cover.first.asImageBitmap(), glyph = cover.second)
                        })
                    }
                }
            }
        }
    }

    /** A folder's first [limit] items, and whether it holds more; null on refusal. Never touches the browsing. */
    private suspend fun children(id: String, limit: Int): Pair<List<MediaItem>, Boolean>? {
        val b = browser ?: return null
        // One more than shown, to know whether a "more" card is needed; some apps ignore the page size and send all
        val future = b.getChildren(id, 0, limit + 1, null)
        val result = suspendCancellableCoroutine { cont ->
            future.addListener({ cont.resumeWith(Result.success(runCatching { future.get() }.getOrNull())) }, context.mainExecutor)
        }
        if (result == null || result.resultCode != LibraryResult.RESULT_SUCCESS) return null
        val all = result.value.orEmpty()
        return all.take(limit) to (all.size > limit)
    }

    /** An item without its picture yet. */
    private fun plain(item: MediaItem): LibraryItem {
        val m = item.mediaMetadata
        return LibraryItem(
            id = item.mediaId,
            title = m.title?.toString().orEmpty(),
            subtitle = (m.subtitle ?: m.artist)?.toString().orEmpty(),
            icon = null,
            browsable = m.isBrowsable == true,
            playable = m.isPlayable == true,
        )
    }

    /** A glyph has plenty of transparent pixels and all opaque ones of about the same color. */
    private fun isGlyph(bitmap: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        var transparent = 0
        var reference = -1
        var sameColor = true
        for (y in 0 until 24) for (x in 0 until 24) {
            val c = small.getPixel(x, y)
            if (Color.alpha(c) < 40) {
                transparent++
                continue
            }
            if (reference == -1) reference = c
            val distance = abs(Color.red(c) - Color.red(reference)) +
                abs(Color.green(c) - Color.green(reference)) + abs(Color.blue(c) - Color.blue(reference))
            if (distance > 90) sameColor = false
        }
        return transparent > 24 * 24 / 4 && sameColor
    }

    /** Items often carry only an icon URI: decode those off the main thread, at list size, and republish. */
    private fun loadIcons(current: Int, children: List<MediaItem>) {
        val missing = children.filter { it.mediaMetadata.artworkData == null && it.mediaMetadata.artworkUri != null }
        if (missing.isEmpty()) return
        scope.launch {
            val icons = withContext(Dispatchers.IO) {
                missing.associate { item ->
                    item.mediaId to runCatching {
                        Artwork.decode(context, item.mediaMetadata.artworkUri!!, ICON_PX)?.let { it to isGlyph(it) }
                    }.getOrNull()
                }
            }
            val loaded = _state.value as? LibraryState.Loaded ?: return@launch
            if (current != request) return@launch
            _state.value = loaded.copy(
                items = loaded.items.map { item ->
                    val icon = icons[item.id]
                    if (item.icon != null || icon == null) item
                    else item.copy(icon = icon.first.asImageBitmap(), glyph = icon.second)
                }
            )
        }
    }
}
