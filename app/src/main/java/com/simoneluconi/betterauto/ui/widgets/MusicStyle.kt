package com.simoneluconi.betterauto.ui.widgets

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.media.Track
import com.simoneluconi.betterauto.radio.Radio
import com.simoneluconi.betterauto.ui.CarBringIntoView
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.launcher.carZone
import com.simoneluconi.betterauto.ui.launcher.contentFocus
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.theme.glass
import androidx.compose.ui.draw.shadow
import kotlinx.coroutines.delay

// The music and radio pages' pieces, in the look of the "Square" player the driver likes (the launcher's glass
// theme): no cards or borders around rows, rounded artwork, big semi-bold section titles, floating pills of glass.

/** Artwork corners: rows and thumbnails, cards in a carousel, and the floating pills. */
internal val RowArtShape = RoundedCornerShape(16.dp)
internal val CardArtShape = RoundedCornerShape(28.dp)
internal val PillShape = RoundedCornerShape(28.dp)

/** Room the floating pills take at the bottom of the page: lists end with this much space, to scroll out from under them. */
internal val LocalBarSpace = staticCompositionLocalOf { 0.dp }

/** The floating pills' height (mini player, sections bar) and their margin from the page's edges. */
internal val PILL_HEIGHT = 80.dp
internal val PILL_MARGIN = 12.dp

/** A section's title over its row of cards or list: big, semi-bold, as the player it follows has them. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    CarLabel(text, 30.sp, LocalCarColors.current.text, modifier, weight = FontWeight.SemiBold)
}

/** A picture cut to [shape]; [fallback] on the placeholder when there is none, a single-color [glyph] tinted. */
@Composable
internal fun Artwork(art: ImageBitmap?, shape: Shape, fallback: ImageVector, modifier: Modifier = Modifier, glyph: Boolean = false) {
    val colors = LocalCarColors.current
    Box(modifier.clip(shape).background(colors.surface), contentAlignment = Alignment.Center) {
        when {
            art == null -> CarIcon(fallback, colors.text2, Modifier.fillMaxSize(0.4f))
            glyph -> Image(art, null, colorFilter = ColorFilter.tint(colors.text2), modifier = Modifier.fillMaxSize(0.5f))
            else -> Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A playlist's face: the first four covers in a 2x2 square. */
@Composable
internal fun Mosaic(arts: List<ImageBitmap>, shape: Shape, modifier: Modifier = Modifier) {
    Column(modifier.clip(shape).background(LocalCarColors.current.surface)) {
        for (row in 0..1) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0..1) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        arts.getOrNull(row * 2 + col)?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }
}

/**
 * A song or source as a list row: rounded artwork, the title big beside it, the artist under it in grey; no card
 * around it. [playing] puts the title in the accent. At least 76 dp tall, for a finger in a moving car.
 */
@Composable
internal fun MusicRow(
    art: ImageBitmap?,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    fallback: ImageVector = CarIcons.Music,
    playing: Boolean = false,
    round: Boolean = false,
    glyph: Boolean = false,
    trailing: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = LocalCarColors.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .carClickable(RoundedCornerShape(20.dp), if (subtitle.isEmpty()) title else "$title, $subtitle", onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Artwork(art, if (round) CircleShape else RowArtShape, fallback, Modifier.size(68.dp), glyph)
        Column(Modifier.weight(1f)) {
            CarLabel(title, 23.sp, if (playing) colors.accent else colors.text, weight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) CarLabel(subtitle, 19.sp, colors.text2)
        }
        trailing?.let { CarIcon(it, colors.text2, Modifier.size(28.dp)) }
    }
}

/** A card of a carousel or grid: big rounded artwork ([art] draws it), the title and a grey line under it; [width] or its cell's. */
@Composable
internal fun ArtCard(
    width: Dp = Dp.Unspecified,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    art: @Composable (Modifier) -> Unit,
) {
    val colors = LocalCarColors.current
    Column(
        modifier
            .then(if (width.isSpecified) Modifier.width(width) else Modifier.fillMaxWidth())
            .carClickable(CardArtShape, if (subtitle.isEmpty()) title else "$title, $subtitle", onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        art(Modifier.fillMaxWidth().aspectRatio(1f))
        Column(Modifier.padding(horizontal = 6.dp).padding(bottom = 6.dp)) {
            CarLabel(title, 22.sp, colors.text, weight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) CarLabel(subtitle, 18.sp, colors.text2)
        }
    }
}

/** The last card of a cut-short carousel: the whole folder in the library. */
@Composable
internal fun MoreCard(width: Dp = Dp.Unspecified, onClick: () -> Unit) {
    val colors = LocalCarColors.current
    val label = stringResource(R.string.music_more)
    ArtCard(width, label, "", onClick = onClick) { m ->
        Box(m.clip(CardArtShape).background(colors.surface), contentAlignment = Alignment.Center) {
            Box(Modifier.size(64.dp).background(colors.surface2, CircleShape), contentAlignment = Alignment.Center) {
                CarIcon(CarIcons.Chevron, colors.text, Modifier.size(30.dp))
            }
        }
    }
}

/**
 * How lists under a floating bar scroll to the control the knob lands on: as [CarBringIntoView], over the part the
 * bar leaves free. Only for vertical lists: a carousel inside gets [CarBringIntoView] back.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun bringIntoViewAbove(barPx: Float): BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        CarBringIntoView.calculateScrollDistance(offset, size, (containerSize - barPx).coerceAtLeast(1f))
}

/** The page fading out under the floating pills, so what scrolls under them doesn't fight with their words. */
@Composable
internal fun BottomScrim(height: Dp, modifier: Modifier = Modifier) {
    val bg = LocalCarColors.current.bg
    Box(modifier.fillMaxWidth().height(height).background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg.copy(alpha = 0.92f)))))
}

/** One item of the sections bar. */
internal class NavItem<T>(val value: T, val icon: ImageVector, val label: String)

/**
 * The floating bar of the page's sections: a pill of dark glass, each item its icon over its name (only the icon
 * when [compact]), the one open in a lighter inner pill with its icon in gold. [fill]: the items share the bar's whole width. With [main]
 * the open item is the page's main control for the knob (when no mini player is there to be it).
 */
@Composable
internal fun <T> PillNav(
    items: List<NavItem<T>>,
    selected: T,
    compact: Boolean,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    main: Boolean = false,
    onSelect: (T) -> Unit,
) {
    val colors = LocalCarColors.current
    Row(
        modifier
            .height(PILL_HEIGHT)
            .shadow(16.dp, CircleShape, clip = false)
            .glass(CircleShape) { it.glass }
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in items) {
            val on = item.value == selected
            Column(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .fillMaxHeight()
                    .contentFocus(main && on)
                    .carClickable(CircleShape, item.label) { onSelect(item.value) }
                    .then(if (on) Modifier.glass(CircleShape) { it.surface2 } else Modifier)
                    .padding(horizontal = if (fill) 4.dp else if (compact) 22.dp else 18.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CarIcon(item.icon, if (on) colors.accent else colors.text2, Modifier.size(28.dp))
                if (!compact) CarLabel(item.label, 17.sp, if (on) colors.text else colors.text2, weight = FontWeight.Bold)
            }
        }
    }
}

/**
 * The floating mini player: a pill of dark glass with the cover, the title and the artist, next and play/pause on
 * the right, and a thin accent line along its bottom for how far the song is. Shows the radio while it is on (with
 * [radio]), else the song; nothing when neither. A tap on the cover and words opens the full player ([onOpenMusic])
 * or the radio's page ([onOpenRadio]). Its play button is the page's main control for the knob when [main].
 */
@Composable
internal fun MiniPlayer(
    modifier: Modifier = Modifier,
    radio: Boolean = true,
    main: Boolean = false,
    onOpenMusic: (() -> Unit)? = null,
    onOpenRadio: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val track by NowPlaying.track.collectAsStateWithLifecycle()
    val station by Radio.current.collectAsStateWithLifecycle()
    val status by Radio.status.collectAsStateWithLifecycle()
    val radioOn = status == Radio.Status.PLAYING || status == Radio.Status.CONNECTING
    val shownStation = station?.takeIf { radio && radioOn }
    val song = track
    when {
        shownStation != null -> PillFrame(
            modifier,
            art = null,
            fallback = CarIcons.Radio,
            title = shownStation.name,
            subtitle = stringResource(if (status == Radio.Status.CONNECTING) R.string.radio_connecting else R.string.radio_live),
            playing = status == Radio.Status.PLAYING,
            loading = status == Radio.Status.CONNECTING,
            main = main,
            nextLabel = stringResource(R.string.radio_next),
            onNext = { Radio.next(context) },
            onPlay = { Radio.toggle(context) },
            onOpen = onOpenRadio,
            progress = null,
        )
        song != null -> PillFrame(
            modifier,
            art = song.art,
            fallback = CarIcons.Music,
            title = song.title.ifBlank { stringResource(R.string.music_unknown_title) },
            subtitle = song.artist,
            playing = song.playing,
            loading = song.buffering,
            main = main,
            nextLabel = stringResource(R.string.music_next),
            onNext = { NowPlaying.next() },
            onPlay = { NowPlaying.playPause() },
            onOpen = onOpenMusic,
            progress = song,
        )
    }
}

/** Whether [MiniPlayer] has something to show (with the radio counted or not). */
@Composable
internal fun miniPlayerShown(radio: Boolean = true): Boolean {
    val track by NowPlaying.track.collectAsStateWithLifecycle()
    val status by Radio.status.collectAsStateWithLifecycle()
    val radioOn = status == Radio.Status.PLAYING || status == Radio.Status.CONNECTING
    return track != null || (radio && radioOn)
}

@Composable
private fun PillFrame(
    modifier: Modifier,
    art: ImageBitmap?,
    fallback: ImageVector,
    title: String,
    subtitle: String,
    playing: Boolean,
    loading: Boolean,
    main: Boolean,
    nextLabel: String,
    onNext: () -> Unit,
    onPlay: () -> Unit,
    onOpen: (() -> Unit)?,
    progress: Track?,
) {
    val colors = LocalCarColors.current
    var widthPx by remember { mutableIntStateOf(0) }
    val line = progress?.let { pillProgress(it, widthPx) }
    Row(
        modifier
            .height(PILL_HEIGHT)
            .onSizeChanged { widthPx = it.width }
            .shadow(16.dp, PillShape, clip = false)
            .glass(PillShape) { it.glass }
            .drawWithContent {
                drawContent()
                val fraction = line?.invoke() ?: return@drawWithContent
                // Along the bottom edge, between the rounded corners: the rest of it a faint rail
                val h = 3.dp.toPx()
                val inset = 26.dp.toPx()
                val y = size.height - h
                val length = (size.width - inset * 2).coerceAtLeast(0f)
                drawRect(colors.text.copy(alpha = 0.18f), Offset(inset, y), Size(length, h))
                drawRect(colors.accent, Offset(inset, y), Size(length * fraction, h))
            }
            .padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val body = Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp)
        Row(
            if (onOpen != null) body.carClickable(RoundedCornerShape(20.dp), stringResource(R.string.music_open_player), onClick = onOpen) else body,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Artwork(art, RowArtShape, fallback, Modifier.size(60.dp))
            Column(Modifier.weight(1f)) {
                CarLabel(title, 22.sp, colors.text, weight = FontWeight.SemiBold)
                if (subtitle.isNotEmpty()) CarLabel(subtitle, 18.sp, colors.text2)
            }
        }
        Box(Modifier.carClickable(CircleShape, nextLabel, onClick = onNext).size(60.dp), contentAlignment = Alignment.Center) {
            CarIcon(CarIcons.Next, colors.text, Modifier.size(28.dp))
        }
        Box(
            Modifier
                .contentFocus(main)
                .carClickable(CircleShape, stringResource(if (playing) R.string.music_pause else R.string.music_play), onClick = onPlay)
                .size(60.dp),
            contentAlignment = Alignment.Center,
        ) {
            val icon = when {
                loading -> null
                playing -> CarIcons.Pause
                else -> CarIcons.Play
            }
            Crossfade(icon, animationSpec = tween(180), label = "pillPlay") {
                if (it == null) LoadingSpinner(colors.text, Modifier.size(28.dp))
                else CarIcon(it, colors.text, Modifier.size(32.dp))
            }
        }
    }
}

/**
 * How far [track] is, read when drawn: the clock ticks about once per pixel of the pill (as the big player's bar
 * does), so the line moves smoothly without redrawing, and re-encoding for the car, every frame.
 */
@Composable
private fun pillProgress(track: Track, widthPx: Int): () -> Float? {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track.playing, track.positionUpdatedAt, track.durationMs, widthPx) {
        now = SystemClock.elapsedRealtime()
        val step = if (widthPx > 0 && track.durationMs > 0) (track.durationMs / widthPx).coerceIn(100L, 1000L) else 1000L
        while (track.playing) {
            delay(step)
            now = SystemClock.elapsedRealtime()
        }
    }
    return { if (track.durationMs > 0) (track.positionAt(now).toFloat() / track.durationMs).coerceIn(0f, 1f) else null }
}

/** Room the floating pills take at the bottom of a page, margins included: one row of them, or two stacked. */
internal fun barSpace(rows: Int): Dp = if (rows <= 0) 0.dp else PILL_HEIGHT * rows + PILL_MARGIN * (rows + 1)

/** The mini player alone at the bottom of a page (the radio's), as its own zone for the knob. */
@Composable
internal fun FloatingMiniPlayer(modifier: Modifier = Modifier, radio: Boolean, onOpenMusic: (() -> Unit)?, onOpenRadio: (() -> Unit)?) {
    MiniPlayer(modifier.carZone(), radio = radio, onOpenMusic = onOpenMusic, onOpenRadio = onOpenRadio)
}
