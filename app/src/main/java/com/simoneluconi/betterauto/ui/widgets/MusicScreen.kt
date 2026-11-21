package com.simoneluconi.betterauto.ui.widgets

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.media.LibraryItem
import com.simoneluconi.betterauto.media.LibrarySection
import com.simoneluconi.betterauto.media.LibraryState
import com.simoneluconi.betterauto.media.Lyrics
import com.simoneluconi.betterauto.media.MediaApps
import com.simoneluconi.betterauto.media.MediaLibrary
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.media.RecentTracks
import com.simoneluconi.betterauto.media.SongLyrics
import com.simoneluconi.betterauto.media.Track
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.ui.AlphabetButton
import com.simoneluconi.betterauto.ui.AlphabetJumpEffect
import com.simoneluconi.betterauto.ui.AlphabetPicker
import com.simoneluconi.betterauto.ui.CarBringIntoView
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.LazyColumnWithBar
import com.simoneluconi.betterauto.ui.LazyVerticalGridWithBar
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.firstOfLetter
import com.simoneluconi.betterauto.ui.launcher.carZone
import com.simoneluconi.betterauto.ui.rememberAlphabetJump
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

private enum class MusicTab { HOME, QUEUE, LYRICS, LIBRARY, APPS }

/**
 * Full music section, in the look of the "Square" player: the music home (what plays next, the app's library in
 * rows of cards, the sources) and its other sections, with the floating mini player and the sections bar at the
 * bottom; the mini player opens the full-screen player, Back closes it again. With nothing playing there is no
 * player to open: the home shows. The choice and the open section are remembered.
 */
@Composable
fun MusicScreen(settings: LauncherSettings, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { RecentTracks.start() }
    val showPanel by settings.musicPanel.collectAsStateWithLifecycle()
    val track by NowPlaying.track.collectAsStateWithLifecycle()
    val player = !showPanel && track != null
    Box(
        modifier
            // No page of its own: the rows and pills sit on the launcher's backdrop, the cover blurred
            .clip(RoundedCornerShape(32.dp))
            // Back from the full player goes to the music home it was opened from
            .onKeyEvent { event ->
                if (!player || event.key != Key.Back) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) settings.setMusicPanel(true)
                true
            },
    ) {
        if (player) {
            MusicWidget(Modifier.fillMaxSize().carZone(), extended = true, onShowPanel = { settings.setMusicPanel(true) })
        } else {
            MusicBrowse(settings, track)
        }
    }
}

/** The home and the other sections under the floating pills: one row of them on wide screens, stacked on narrow ones. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicBrowse(settings: LauncherSettings, track: Track?) {
    val context = LocalContext.current
    val packageName = track?.packageName
    // One browser for the home's rows and the library section, so the library stays where it was between the two
    val library = remember(packageName) { packageName?.let { MediaLibrary(context, it).also(MediaLibrary::connect) } }
    DisposableEffect(library) { onDispose { library?.close() } }
    val savedTab by settings.musicTab.collectAsStateWithLifecycle()
    val tabs = buildList {
        add(NavItem(MusicTab.HOME, CarIcons.Tiles, stringResource(R.string.music_tab_home)))
        // "Up next" only exists when the player shares its queue
        if (track?.queue.orEmpty().isNotEmpty()) add(NavItem(MusicTab.QUEUE, CarIcons.UpNext, stringResource(R.string.music_tab_queue)))
        if (track != null) add(NavItem(MusicTab.LYRICS, CarIcons.Lyrics, stringResource(R.string.music_tab_lyrics)))
        add(NavItem(MusicTab.LIBRARY, CarIcons.Library, stringResource(R.string.music_tab_library)))
        add(NavItem(MusicTab.APPS, CarIcons.Speaker, stringResource(R.string.music_tab_apps)))
    }
    val tab = tabs.firstOrNull { it.value.name == savedTab }?.value ?: MusicTab.HOME
    val select: (MusicTab) -> Unit = { settings.setMusicTab(it.name) }
    val pill = miniPlayerShown()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Wide car screens: the bar and the mini player side by side, so the page keeps most of its little height
        val oneRow = maxWidth >= ONE_ROW_MIN_WIDTH && maxWidth > maxHeight
        // Icons only where the names would not fit: beside the mini player on a middling screen, or a narrow full bar
        val compactNav = if (oneRow) pill && maxWidth < LABELS_MIN_WIDTH else (maxWidth - PILL_MARGIN * 2) / tabs.size < MIN_LABELLED_ITEM
        val bar = barSpace(if (oneRow || !pill) 1 else 2)
        val barPx = with(LocalDensity.current) { bar.toPx() }
        val spec = remember(barPx) { bringIntoViewAbove(barPx) }
        val pageWidth = maxWidth
        val pageHeight = maxHeight
        Box(Modifier.fillMaxSize().carZone(bottomCovered = bar)) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides spec, LocalBarSpace provides bar) {
                when (tab) {
                    MusicTab.HOME -> MusicHome(library, track, pageWidth, pageHeight - bar, settings) { select(MusicTab.LIBRARY) }
                    MusicTab.QUEUE -> QueueList(track)
                    MusicTab.LYRICS -> LyricsList(track)
                    MusicTab.LIBRARY -> LibraryList(library, packageName, pageHeight - bar)
                    MusicTab.APPS -> AppsList(packageName)
                }
            }
        }
        BottomScrim(bar + 24.dp, Modifier.align(Alignment.BottomCenter))
        val openPlayer = { settings.setMusicPanel(false) }
        val openRadio = { settings.setSection(Section.RADIO) }
        if (oneRow) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(PILL_MARGIN),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillNav(tabs, tab, compactNav, Modifier.carZone(), main = !pill, onSelect = select)
                if (pill) MiniPlayer(Modifier.weight(1f).carZone(), main = true, onOpenMusic = openPlayer, onOpenRadio = openRadio)
            }
        } else {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(PILL_MARGIN),
                verticalArrangement = Arrangement.spacedBy(PILL_MARGIN),
            ) {
                if (pill) MiniPlayer(Modifier.fillMaxWidth().carZone(), main = true, onOpenMusic = openPlayer, onOpenRadio = openRadio)
                PillNav(tabs, tab, compactNav, Modifier.fillMaxWidth().carZone(), fill = true, main = !pill, onSelect = select)
            }
        }
    }
}

/** Narrower than this, the bar goes under the mini player; narrower than [LABELS_MIN_WIDTH] beside it, icons only. */
private val ONE_ROW_MIN_WIDTH = 760.dp
private val LABELS_MIN_WIDTH = 1000.dp
private val MIN_LABELLED_ITEM = 104.dp

/** The page's side margin, as wide as the player it follows has it. */
private val PAGE_MARGIN = 24.dp

/** Lists start this far down, with room for a focus ring on their first row. */
private val LIST_TOP = 14.dp

/**
 * The music home: what plays next as rows of covers that page sideways, the songs heard lately, the app's library
 * (its first folders, as carousels of cards, and a row of its folders' faces), and the sources (music apps, radio).
 * Whatever the app shares or not, the sources are always there. [height]: what the floating pills leave of the page.
 */
@Composable
private fun MusicHome(library: MediaLibrary?, track: Track?, width: Dp, height: Dp, settings: LauncherSettings, openLibrary: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val hasAccess by NowPlaying.hasAccess.collectAsStateWithLifecycle()
    val sectionsFlow = remember(library) { library?.sections ?: MutableStateFlow<List<LibrarySection>?>(emptyList()) }
    val sections by sectionsFlow.collectAsStateWithLifecycle()
    val recents by RecentTracks.list.collectAsStateWithLifecycle()
    val apps = remember { MediaApps.installed(context).mapNotNull { MediaApps.info(context, it) } }
    val appLabel = remember(track?.packageName) { track?.let { MediaApps.info(context, it.packageName)?.label ?: it.appLabel }.orEmpty() }

    val inner = width - PAGE_MARGIN * 2
    // Rows in columns that page sideways, the next column peeking in to say there is more
    val rowsPerColumn = ((height - SECTION_HEAD) / ROW_STEP).toInt().coerceIn(2, 4)
    val columnWidth = if (inner >= 700.dp) inner / 2.3f else inner * 0.88f
    // Cards as big as fit under their title, so a whole carousel shows above the pills
    val cardWidth = (height - SECTION_HEAD - CARD_TEXT).coerceIn(140.dp, 220.dp)

    val queue = track?.queue.orEmpty()
    val activeAt = queue.indexOfFirst { it.id == track?.activeQueueId }
    val upNext = queue.drop(activeAt + 1).take(MAX_PICKS)
    // Songs heard lately in this app, played again by a search in it (only a player that takes searches itself: the
    // other way would leave it on an empty item); the one on now is already in the pill
    val searchable = remember(track?.packageName) { NowPlaying.canPlayFromSearch() }
    val heard = if (!searchable) emptyList() else recents.filter { it.packageName == track?.packageName && !(it.title == track.title && it.artist == track.artist) }

    fun openItem(item: LibraryItem) {
        val l = library ?: return
        if (item.browsable) {
            l.openFromRoot(item)
            openLibrary()
        } else {
            l.select(item)
        }
    }

    LazyColumnWithBar(Modifier.fillMaxSize().padding(top = LIST_TOP), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        if (track == null) {
            item {
                Column(Modifier.padding(horizontal = PAGE_MARGIN), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.music_nothing_playing))
                    CarLabel(
                        stringResource(if (hasAccess) R.string.music_start_hint else R.string.music_access_hint),
                        21.sp, colors.text2, maxLines = 2,
                    )
                }
            }
        }
        if (upNext.isNotEmpty()) {
            item(key = "upNext") {
                HomeSection(stringResource(R.string.music_tab_queue)) {
                    Carousel(spacing = 16.dp) {
                        items(upNext.chunked(rowsPerColumn)) { column ->
                            Column(Modifier.width(columnWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (entry in column) {
                                    MusicRow(entry.icon, entry.title, entry.subtitle) { NowPlaying.skipToQueueItem(entry.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (heard.isNotEmpty()) {
            item(key = "heard") {
                HomeSection(stringResource(R.string.music_recently_played)) {
                    Carousel {
                        items(heard) { song ->
                            ArtCard(cardWidth, song.title, song.artist, onClick = { NowPlaying.playFromSearch("${song.title} ${song.artist}".trim()) }) { m ->
                                Artwork(song.art, CardArtShape, CarIcons.Music, m)
                            }
                        }
                    }
                }
            }
        }
        val rows = sections
        if (library != null && rows == null) {
            item(key = "loading") {
                CarLabel(stringResource(R.string.music_loading), 21.sp, colors.text2, Modifier.padding(horizontal = PAGE_MARGIN))
            }
        }
        for (section in rows.orEmpty()) {
            item(key = "section:" + (section.folder?.id ?: "")) {
                HomeSection(section.folder?.title?.takeIf { it.isNotBlank() } ?: appLabel) {
                    Carousel {
                        items(section.items, key = { it.id }) { item ->
                            ArtCard(cardWidth, item.title, item.subtitle, onClick = { openItem(item) }) { m ->
                                Artwork(item.icon, CardArtShape, if (item.browsable) CarIcons.Album else CarIcons.Music, m, item.glyph)
                            }
                        }
                        if (section.more) {
                            item(key = "more") {
                                MoreCard(cardWidth) {
                                    section.folder?.let { library?.openFromRoot(it) }
                                    openLibrary()
                                }
                            }
                        }
                    }
                }
            }
        }
        // The library's folders by their face: four of their covers, as playlists are shown
        val faces = rows.orEmpty().mapNotNull { section -> section.folder?.let { it to covers(section.items) } }.filter { it.second.size >= 4 }
        if (faces.size >= 2) {
            item(key = "faces") {
                HomeSection(stringResource(R.string.music_tab_library)) {
                    Carousel {
                        items(faces, key = { it.first.id }) { (folder, arts) ->
                            ArtCard(cardWidth, folder.title, folder.subtitle, onClick = { openItem(folder) }) { m -> Mosaic(arts, CardArtShape, m) }
                        }
                    }
                }
            }
        }
        item(key = "sources") {
            HomeSection(stringResource(R.string.music_tab_apps)) {
                Carousel {
                    items(apps, key = { it.packageName }) { app ->
                        val on = app.packageName == track?.packageName
                        ArtCard(cardWidth, app.label, if (on) stringResource(R.string.music_now_playing) else "", onClick = { NowPlaying.open(app.packageName) }) { m ->
                            AppFace(app.icon, m)
                        }
                    }
                    item(key = "radio") {
                        ArtCard(cardWidth, stringResource(R.string.widget_radio), "", onClick = { settings.setSection(Section.RADIO) }) { m ->
                            Box(m.clip(CardArtShape).background(colors.surface), contentAlignment = Alignment.Center) {
                                CarIcon(CarIcons.Radio, colors.accent, Modifier.fillMaxSize(0.36f))
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(LocalBarSpace.current)) }
    }
}

/** A section title's height with its gap, a row's step in the "up next" columns, the words under a card. */
private val SECTION_HEAD = 54.dp
private val ROW_STEP = 84.dp
private val CARD_TEXT = 70.dp

/** At most this many songs of the queue on the home: the whole queue is in its own section. */
private const val MAX_PICKS = 12

/** The pictures of [items] that are covers (not tinted glyphs), for a folder's face. */
private fun covers(items: List<LibraryItem>): List<ImageBitmap> = items.filter { !it.glyph }.mapNotNull { it.icon }

/** An app's icon on a card: whole, on the placeholder, not cropped like a cover. */
@Composable
private fun AppFace(icon: ImageBitmap, modifier: Modifier) {
    Box(modifier.clip(CardArtShape).background(LocalCarColors.current.surface), contentAlignment = Alignment.Center) {
        Image(icon, null, modifier = Modifier.fillMaxSize(0.5f).clip(CircleShape))
    }
}

@Composable
private fun HomeSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(title, Modifier.padding(horizontal = PAGE_MARGIN))
        content()
    }
}

/** A row that scrolls sideways; the knob scrolls it with the usual margin (not the page's, kept for the bar below). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Carousel(spacing: Dp = 18.dp, content: LazyListScope.() -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides CarBringIntoView) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_MARGIN - 6.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/**
 * The song's words from LRCLIB: timed ones follow the song, the line being sung bright and kept in view;
 * plain ones just scroll.
 */
@Composable
private fun LyricsList(track: Track?) {
    val colors = LocalCarColors.current
    if (track == null) return PanelMessage(stringResource(R.string.music_nothing_playing))
    val key = "${track.artist}|${track.title}"
    var lyrics by remember(key) { mutableStateOf<SongLyrics?>(null) }
    var looked by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        lyrics = withContext(Dispatchers.IO) { Lyrics.find(track.title, track.artist, track.album, track.durationMs) }
        looked = true
    }
    val song = lyrics
    if (song == null) return PanelMessage(stringResource(if (looked) R.string.music_no_lyrics else R.string.music_lyrics_loading))
    // Where the song is now, from the player's last position and its speed
    var now by remember { mutableStateOf(0L) }
    LaunchedEffect(song, track.playing, track.positionMs, track.positionUpdatedAt) {
        while (true) {
            val since = if (track.playing) SystemClock.elapsedRealtime() - track.positionUpdatedAt else 0L
            now = track.positionMs + (since * track.speed).toLong()
            if (!track.playing || !song.synced) break
            delay(250)
        }
    }
    val current = if (song.synced) Lyrics.lineAt(song.lines, now) else -1
    val listState = rememberLazyListState()
    LaunchedEffect(current) { if (current > 1) listState.animateScrollToItem(current - 1) }
    LazyColumnWithBar(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(LIST_TOP)) }
        itemsIndexed(song.lines) { index, line ->
            CarLabel(
                line.text.ifEmpty { "♪" },
                if (index == current) 27.sp else 24.sp,
                if (!song.synced || index == current) colors.text else colors.text2,
                weight = if (index == current) FontWeight.ExtraBold else FontWeight.Bold,
                maxLines = 3,
            )
        }
        item { Spacer(Modifier.height(LocalBarSpace.current)) }
    }
}

@Composable
private fun QueueList(track: Track?) {
    val queue = track?.queue.orEmpty()
    if (queue.isEmpty()) {
        PanelMessage(stringResource(if (track == null) R.string.music_nothing_playing else R.string.music_queue_empty))
        return
    }
    val listState = rememberLazyListState()
    val activeIndex = queue.indexOfFirst { it.id == track?.activeQueueId }
    LaunchedEffect(track?.packageName, activeIndex) {
        // The title is the first item: the song before the one on now stays in view above it
        if (activeIndex > 0) listState.scrollToItem(activeIndex)
    }
    LazyColumnWithBar(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN - 6.dp), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { SectionTitle(stringResource(R.string.music_tab_queue), Modifier.padding(start = 6.dp, top = LIST_TOP, bottom = 6.dp)) }
        items(queue, key = { it.id }) { entry ->
            MusicRow(entry.icon, entry.title, entry.subtitle, playing = entry.id == track?.activeQueueId) { NowPlaying.skipToQueueItem(entry.id) }
        }
        item { Spacer(Modifier.height(LocalBarSpace.current)) }
    }
}

/**
 * The app's library, a folder at a time: folders of albums and playlists as a grid of cards (the root's folders by
 * their face of four covers when the home fetched them), songs as rows, with A–Z on long ones. [height]: what the
 * floating pills leave of the page, to size the cards.
 */
@Composable
private fun LibraryList(library: MediaLibrary?, packageName: String?, height: Dp) {
    if (library == null || packageName == null) {
        PanelMessage(stringResource(R.string.music_pick_app))
        return
    }
    val context = LocalContext.current
    val colors = LocalCarColors.current
    val state by library.state.collectAsStateWithLifecycle()
    val sections by library.sections.collectAsStateWithLifecycle()
    val appLabel = remember(packageName) { MediaApps.info(context, packageName)?.label ?: packageName }
    val barSpace = LocalBarSpace.current

    when (val current = state) {
        LibraryState.Connecting -> PanelMessage(stringResource(R.string.music_loading))
        LibraryState.Unavailable -> PanelMessage(stringResource(R.string.music_library_unavailable, appLabel))
        is LibraryState.Loaded -> Column(
            // The car's back key goes up one folder before leaving the music section
            Modifier.fillMaxSize().onKeyEvent { event ->
                if (event.key != Key.Back || !current.canGoBack) return@onKeyEvent false
                if (event.type == KeyEventType.KeyUp) library.back()
                true
            },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val items = current.items
            // Mostly folders: a grid of cards, as albums and playlists are shown; songs read better as rows
            val grid = items.isNotEmpty() && items.count { it.browsable } * 2 >= items.size
            // A–Z only where it helps: long lists of rows
            val jump = rememberAlphabetJump()
            val listState = rememberLazyListState()
            AlphabetJumpEffect(jump, listState)
            val firstOfLetter = remember(items) { firstOfLetter(items) { it.title } }
            val showJump = !grid && items.size >= AZ_MIN_ITEMS && firstOfLetter.size > 1
            Row(
                Modifier.fillMaxWidth().padding(start = PAGE_MARGIN - 6.dp, end = PAGE_MARGIN, top = LIST_TOP),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (current.canGoBack) {
                    val back = stringResource(R.string.music_back)
                    Box(
                        Modifier.carClickable(CircleShape, back) { library.back() }.background(colors.surface2, CircleShape).size(56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CarIcon(CarIcons.Back, colors.text, Modifier.size(28.dp))
                    }
                }
                SectionTitle(current.title?.takeIf { it.isNotBlank() } ?: appLabel, Modifier.weight(1f).padding(start = if (current.canGoBack) 0.dp else 6.dp))
                if (showJump && !jump.open) AlphabetButton(jump)
            }
            when {
                items.isEmpty() -> PanelMessage(stringResource(R.string.music_library_empty))
                jump.open -> AlphabetPicker(firstOfLetter.keys.toList(), onPick = { jump.jumpTo(firstOfLetter.getValue(it)) }, onClose = { jump.open = false })
                grid -> {
                    val cell = (height - 56.dp - LIST_TOP - CARD_TEXT - 16.dp).coerceIn(140.dp, 220.dp)
                    LazyVerticalGridWithBar(
                        GridCells.Adaptive(cell),
                        Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN - 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(items, key = { it.id }) { item ->
                            // At the root, a folder the home fetched shows four of its covers instead of a glyph
                            val face = if (!current.canGoBack && (item.icon == null || item.glyph)) {
                                sections?.firstOrNull { it.folder?.id == item.id }?.items?.let(::covers)?.takeIf { it.size >= 4 }
                            } else {
                                null
                            }
                            ArtCard(title = item.title, subtitle = item.subtitle, onClick = { library.select(item) }) { m ->
                                if (face != null) Mosaic(face, CardArtShape, m)
                                else Artwork(item.icon, CardArtShape, if (item.browsable) CarIcons.Album else CarIcons.Music, m, item.glyph)
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(barSpace)) }
                    }
                }
                else -> LazyColumnWithBar(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN - 6.dp), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                        MusicRow(
                            item.icon,
                            item.title,
                            item.subtitle,
                            modifier = jump.focusTarget(index),
                            fallback = if (item.browsable) CarIcons.Album else CarIcons.Music,
                            glyph = item.glyph,
                            trailing = if (item.browsable) CarIcons.Chevron else null,
                        ) { library.select(item) }
                    }
                    item { Spacer(Modifier.height(barSpace)) }
                }
            }
        }
    }
}

@Composable
private fun AppsList(current: String?) {
    val context = LocalContext.current
    val apps = remember { MediaApps.installed(context).mapNotNull { MediaApps.info(context, it) } }
    LazyColumnWithBar(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN - 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { SectionTitle(stringResource(R.string.music_tab_apps), Modifier.padding(start = 6.dp, top = LIST_TOP, bottom = 6.dp)) }
        items(apps, key = { it.packageName }) { app ->
            val on = app.packageName == current
            MusicRow(app.icon, app.label, if (on) stringResource(R.string.music_now_playing) else "", playing = on, round = true) {
                NowPlaying.open(app.packageName)
            }
        }
        item { Spacer(Modifier.height(LocalBarSpace.current)) }
    }
}

/** A row of a list on a card: the phone's lists and the home's favorites share it (the music pages have [MusicRow]). */
@Composable
internal fun ListRow(
    icon: ImageBitmap?,
    modifier: Modifier = Modifier,
    fallback: ImageVector,
    title: String,
    subtitle: String,
    highlighted: Boolean,
    trailing: ImageVector? = null,
    roundIcon: Boolean = false,
    tintIcon: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalCarColors.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .carClickable(shape, if (subtitle.isEmpty()) title else "$title, $subtitle", onClick = onClick)
            .background(if (highlighted) colors.accent.copy(alpha = 0.22f) else colors.surface2, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val iconShape = if (roundIcon) CircleShape else RoundedCornerShape(12.dp)
        if (icon != null) {
            if (tintIcon) {
                Box(Modifier.size(60.dp).glass(iconShape), contentAlignment = Alignment.Center) {
                    Image(icon, null, colorFilter = ColorFilter.tint(colors.text2), modifier = Modifier.size(34.dp))
                }
            } else {
                Image(icon, null, contentScale = ContentScale.Crop, modifier = Modifier.size(60.dp).clip(iconShape))
            }
        } else {
            Box(Modifier.size(60.dp).glass(iconShape), contentAlignment = Alignment.Center) {
                CarIcon(fallback, colors.text2, Modifier.size(28.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            CarLabel(title, 23.sp, colors.text, weight = FontWeight.Bold)
            if (subtitle.isNotEmpty()) CarLabel(subtitle, 19.sp, colors.text2)
        }
        trailing?.let { CarIcon(it, colors.text2, Modifier.size(28.dp)) }
    }
}

@Composable
internal fun PanelMessage(text: String) {
    val colors = LocalCarColors.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // A short card (a small home widget) has no room for margins above and below: the words come first
        val short = maxHeight < SHORT_MESSAGE_HEIGHT
        CarLabel(
            text, if (short) 18.sp else 22.sp, colors.text2, maxLines = if (short) 2 else 3, textAlign = TextAlign.Center, shrinkToFit = short,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = if (short) 0.dp else 24.dp),
        )
    }
}

private val SHORT_MESSAGE_HEIGHT = 120.dp

/** Folders shorter than this are quick enough to scroll with the knob. */
private const val AZ_MIN_ITEMS = 20
