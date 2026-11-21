package com.simoneluconi.betterauto.ui.widgets

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.R
import com.simoneluconi.betterauto.radio.Radio
import com.simoneluconi.betterauto.radio.Station
import com.simoneluconi.betterauto.settings.LauncherSettings
import com.simoneluconi.betterauto.settings.Section
import com.simoneluconi.betterauto.ui.CarIcon
import com.simoneluconi.betterauto.ui.CarIcons
import com.simoneluconi.betterauto.ui.CarLabel
import com.simoneluconi.betterauto.ui.LazyColumnWithBar
import com.simoneluconi.betterauto.ui.carClickable
import com.simoneluconi.betterauto.ui.launcher.carZone
import com.simoneluconi.betterauto.ui.launcher.contentFocus
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import com.simoneluconi.betterauto.ui.theme.glass
import com.simoneluconi.betterauto.ui.vehicle.WarningAmber
import com.simoneluconi.betterauto.media.NowPlaying
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.sin

/** What the radio shows now: the list, the station on (or last on) and how it is going. */
private class RadioState(val stations: List<Station>?, val current: Station?, val status: Radio.Status) {
    val on get() = status == Radio.Status.PLAYING || status == Radio.Status.CONNECTING
    val shown get() = current ?: stations?.firstOrNull()
}

@Composable
private fun rememberRadio(): RadioState {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Radio.load(context) }
    val stations by Radio.stations.collectAsStateWithLifecycle()
    val current by Radio.current.collectAsStateWithLifecycle()
    val status by Radio.status.collectAsStateWithLifecycle()
    return RadioState(stations, current, status)
}

/**
 * Egypt's radio on the home screen, a card of glass drawn like the music player: the cover, the station,
 * previous, play/pause and next, and the stations below as far as the slot has room. A double tap on the card opens the
 * radio's own page (`WidgetArea`).
 */
@Composable
fun RadioWidget(modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    val radio = rememberRadio()
    Column(
        modifier
            .glass(RoundedCornerShape(32.dp))
            .clip(RoundedCornerShape(32.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CarIcon(CarIcons.Radio, colors.accent, Modifier.size(26.dp))
            CarLabel(stringResource(R.string.widget_radio), 24.sp, colors.text, weight = FontWeight.SemiBold)
            radio.stations?.takeIf { it.isNotEmpty() }?.let { CarLabel(stringResource(R.string.radio_count, it.size), 19.sp, colors.text2) }
        }
        when {
            radio.stations == null -> PanelMessage(stringResource(R.string.radio_loading))
            radio.stations.isEmpty() -> PanelMessage(stringResource(R.string.radio_no_list))
            else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val width = maxWidth
                val height = maxHeight
                // Room for the list under the player only in a tall slot
                if (height < 330.dp) {
                    RadioPlayer(radio, width, height)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.fillMaxWidth().weight(0.45f)) { RadioPlayer(radio, width, height * 0.45f) }
                        Box(Modifier.fillMaxWidth().weight(0.55f)) { StationList(radio) }
                    }
                }
            }
        }
    }
}

/**
 * The radio's page, in the music pages' look (on the launcher's backdrop, no page of its own): the station on air big (its picture, name, buttons) and the stations
 * beside it (stacked on tall screens) as rows; while a song is there and the radio is off, the music's mini player
 * floats at the bottom of the stations, to go back to it.
 */
@Composable
fun RadioScreen(modifier: Modifier = Modifier) {
    val radio = rememberRadio()
    val track by NowPlaying.track.collectAsStateWithLifecycle()
    val pill = track != null && !radio.on
    BoxWithConstraints(modifier.clip(RoundedCornerShape(32.dp))) {
        val stacked = maxWidth / maxHeight < 1.25f
        when {
            radio.stations == null -> PanelMessage(stringResource(R.string.radio_loading))
            radio.stations.isEmpty() -> PanelMessage(stringResource(R.string.radio_no_list))
            stacked -> Column(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.weight(0.42f).fillMaxWidth().carZone().padding(28.dp)) { RadioPlayer(radio, maxWidth, maxHeight, main = true) }
                Stations(radio, pill, Modifier.weight(0.58f).fillMaxWidth())
            }
            else -> Row(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.weight(1.1f).fillMaxHeight().carZone().padding(28.dp)) { RadioPlayer(radio, maxWidth, maxHeight, main = true) }
                Stations(radio, pill, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** The stations' title and list, the mini player floating at their bottom when [pill]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Stations(radio: RadioState, pill: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val bar = barSpace(if (pill) 1 else 0)
    val barPx = with(LocalDensity.current) { bar.toPx() }
    val spec = remember(barPx) { bringIntoViewAbove(barPx) }
    Box(modifier) {
        Column(Modifier.fillMaxSize().carZone(bottomCovered = bar).padding(start = 12.dp, end = 18.dp, top = 18.dp)) {
            SectionTitle(stringResource(R.string.radio_stations), Modifier.padding(start = 6.dp, bottom = 8.dp))
            CompositionLocalProvider(LocalBringIntoViewSpec provides spec, LocalBarSpace provides bar) { StationList(radio) }
        }
        if (pill) {
            BottomScrim(bar + 24.dp, Modifier.align(Alignment.BottomCenter))
            FloatingMiniPlayer(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(PILL_MARGIN),
                radio = false,
                onOpenMusic = {
                    LauncherSettings.get(context).apply {
                        setMusicPanel(false)
                        setSection(Section.MUSIC)
                    }
                },
                onOpenRadio = null,
            )
        }
    }
}

/** The cover beside the station's name and the buttons, sized to [width] x [height]. */
@Composable
private fun RadioPlayer(radio: RadioState, width: Dp, height: Dp, main: Boolean = false) {
    val colors = LocalCarColors.current
    val station = radio.shown ?: return
    val gap = if (height < 200.dp) 18.dp else 32.dp
    val cover = minOf(height, width * 0.42f).takeIf { it >= 90.dp }
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        cover?.let { RadioCover(radio.status == Radio.Status.PLAYING, radio.status == Radio.Status.CONNECTING, it) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (height < 200.dp) 8.dp else 16.dp)) {
            CarLabel(station.name, if (height < 200.dp) 26.sp else 40.sp, colors.text, weight = FontWeight.Bold, maxLines = 2)
            val line = when (radio.status) {
                Radio.Status.CONNECTING -> stringResource(R.string.radio_connecting)
                Radio.Status.PLAYING -> stringResource(R.string.radio_live)
                Radio.Status.FAILED -> stringResource(R.string.radio_failed)
                Radio.Status.IDLE -> station.tags
            }
            if (line.isNotEmpty()) {
                // On air in the accent, as what plays is everywhere in these pages
                val lineColor = when (radio.status) {
                    Radio.Status.FAILED -> WarningAmber
                    Radio.Status.PLAYING -> colors.accent
                    else -> colors.text2
                }
                CarLabel(line, if (height < 200.dp) 18.sp else 22.sp, lineColor, maxLines = 1)
            }
            RadioTransport(radio, playSize = (height * 0.28f).coerceIn(60.dp, 140.dp), main = main)
        }
    }
}

/**
 * The radio's picture, alive while it plays: rings spread from the radio and the bars beside it pulse, the
 * picture breathes. Connecting gets only slower, fainter rings; otherwise it is still and nothing runs.
 */
@Composable
private fun RadioCover(playing: Boolean, connecting: Boolean, size: Dp) {
    // Composed only while on, so a stopped radio keeps no animation ticking; key restarts it at the new pace
    val waves = if (playing || connecting) key(playing) { coverWaves(calm = !playing) } else Modifier
    Image(
        painterResource(R.drawable.radio_default), null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(CardArtShape).then(waves),
    )
}

/** The picture's own amber glow: the waves belong to the art, so they don't follow the day/night theme. */
private val CoverGlow = Color(0xFFFFB347)

/** Where the art puts the radio (the rings' centre) and its bars' midline, as fractions of the picture. */
private const val CoverCentreY = 0.46f
private const val CoverBarsY = 0.456f

/** The art's bars, from the radio outwards: distance from the centre and height, as fractions of the width. */
private val CoverBarX = floatArrayOf(0.230f, 0.253f, 0.277f, 0.303f, 0.326f, 0.352f, 0.377f, 0.396f, 0.412f)
private val CoverBarH = floatArrayOf(0.056f, 0.102f, 0.167f, 0.105f, 0.064f, 0.035f, 0.021f, 0.012f, 0.012f)

/** Whole beats per loop for each bar, so the loop wraps seamlessly yet the bars never move together. */
private val CoverBarBeats = intArrayOf(3, 2, 4, 3, 5, 2, 4, 3, 5)

private const val TAU = 6.2831855f

/**
 * The waves drawn over the cover. One transition drives everything; its value is read only while drawing,
 * so a frame redraws the cover without recomposing, and all the geometry is made once per size.
 */
@Composable
private fun coverWaves(calm: Boolean): Modifier {
    val phase = rememberInfiniteTransition(label = "radioCover").animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (calm) 5000 else 3000, easing = LinearEasing)), label = "radioCoverPhase",
    )
    return remember(phase, calm) {
        Modifier
            .graphicsLayer {
                // Grows only (never below 1), so the clipped edges never show
                val s = if (calm) 1f else 1f + 0.012f * (0.5f + 0.5f * sin(TAU * phase.value))
                scaleX = s
                scaleY = s
            }
            .drawWithCache {
                val w = size.width
                val centre = Offset(w * 0.5f, size.height * CoverCentreY)
                val ring = Stroke(w * 0.006f)
                val barW = w * 0.009f
                val barsY = size.height * CoverBarsY
                val strength = if (calm) 0.5f else 1f
                onDrawWithContent {
                    drawContent()
                    val p = phase.value
                    // Three rings, a third of a loop apart, from just outside the radio to the edge
                    for (i in 0 until 3) {
                        val q = (p + i / 3f) % 1f
                        val alpha = strength * 0.45f * (1f - q) * (1f - q) * minOf(1f, q * 8f)
                        drawCircle(CoverGlow, w * (0.24f + 0.28f * q), centre, alpha, ring, blendMode = BlendMode.Plus)
                    }
                    if (calm) return@onDrawWithContent
                    for (i in CoverBarX.indices) {
                        for (side in 0..1) {
                            val wave = 0.5f + 0.5f * sin(TAU * (CoverBarBeats[i] * p + i * 0.13f + side * 0.5f))
                            val half = maxOf(0f, w * CoverBarH[i] * (0.7f + 0.6f * wave) - barW) / 2f
                            val x = w * (0.5f + if (side == 0) -CoverBarX[i] else CoverBarX[i])
                            val top = Offset(x, barsY - half)
                            val bottom = Offset(x, barsY + half)
                            // A soft halo, then the bright core
                            drawLine(CoverGlow, top, bottom, barW * 2.4f, StrokeCap.Round, alpha = 0.16f + 0.14f * wave, blendMode = BlendMode.Plus)
                            drawLine(CoverGlow, top, bottom, barW, StrokeCap.Round, alpha = 0.55f, blendMode = BlendMode.Plus)
                        }
                    }
                }
            }
    }
}

/** Previous, play/pause and next, as the music player has them: the play button a circle while on. */
@Composable
private fun RadioTransport(radio: RadioState, playSize: Dp, main: Boolean = false) {
    val colors = LocalCarColors.current
    val context = LocalContext.current
    val skip = playSize * 0.78f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.carClickable(CircleShape, stringResource(R.string.radio_previous)) { Radio.previous(context) }.size(skip), contentAlignment = Alignment.Center) {
            CarIcon(CarIcons.Previous, colors.text, Modifier.size(skip * 0.5f))
        }
        val corner by animateIntAsState(if (radio.on) 50 else 28, spring(dampingRatio = 0.5f, stiffness = 300f), label = "radioPlayCorner")
        val shape = RoundedCornerShape(corner)
        Box(
            Modifier
                // The radio page's main control for the knob (not on the home card: the music player has it there)
                .contentFocus(main)
                .carClickable(shape, stringResource(if (radio.on) R.string.music_pause else R.string.music_play)) { Radio.toggle(context) }
                .background(colors.accent, shape)
                .size(playSize),
            contentAlignment = Alignment.Center,
        ) {
            val icon = when (radio.status) {
                Radio.Status.CONNECTING -> null
                Radio.Status.PLAYING -> CarIcons.Pause
                else -> CarIcons.Play
            }
            Crossfade(icon, animationSpec = tween(180), label = "radioPlayIcon") {
                if (it == null) LoadingSpinner(colors.onAccent, Modifier.size(playSize * 0.42f))
                else CarIcon(it, colors.onAccent, Modifier.size(playSize * 0.4f))
            }
        }
        Box(Modifier.carClickable(CircleShape, stringResource(R.string.radio_next)) { Radio.next(context) }.size(skip), contentAlignment = Alignment.Center) {
            CarIcon(CarIcons.Next, colors.text, Modifier.size(skip * 0.5f))
        }
    }
}

/** Every station, the one on highlighted and kept in view. */
@Composable
private fun StationList(radio: RadioState) {
    val context = LocalContext.current
    val list = radio.stations.orEmpty()
    val state = rememberLazyListState()
    val at = list.indexOfFirst { it.id == radio.current?.id }
    LaunchedEffect(at) { if (at > 0) state.scrollToItem(at - 1) }
    LazyColumnWithBar(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(list, key = { it.id }) { station ->
            MusicRow(
                art = null,
                title = station.name,
                subtitle = station.tags,
                fallback = CarIcons.Radio,
                playing = station.id == radio.current?.id && radio.on,
            ) { Radio.play(context, station) }
        }
        item { Spacer(Modifier.height(LocalBarSpace.current)) }
    }
}
