package com.simoneluconi.betterauto.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoneluconi.betterauto.R

/**
 * The launcher's palette: glass, after the "Square" player the driver likes. A page ([bg], with the cover blurred
 * behind it, see `Backdrop`), panes of frosted glass on it with a hairline edge, floating pills of more opaque glass
 * over content that moves (the map, lists, the cover), and one baby blue for the focus, what is selected and what
 * plays. By night dark glass on a near-black page; by day white glass on a light page. The map follows.
 */
@Immutable
data class CarColors(
    /** The page under everything: opaque. */
    val bg: Color,
    /** Glass: the cards on the page. Translucent, so the page (and the blurred cover) shows through. */
    val surface: Color,
    /** Stronger glass: chips, buttons and cards that sit on [surface]. */
    val surface2: Color,
    /** Hairlines between rows. */
    val line: Color,
    /** Floating glass over content that moves or is busy (the map, lists, the cover): nearly opaque. */
    val glass: Color,
    /** The 1 dp edge every pane of glass has: light by night, a soft grey by day. */
    val glassEdge: Color,
    val text: Color,
    /** Labels and secondary text. */
    val text2: Color,
    /** The knob's ring. */
    val focus: Color,
    /** What is selected or plays: the current section, the progress, the play button. */
    val accent: Color,
    /** Readable on [accent] (and on a [text] fill). */
    val onAccent: Color,
    /** The map's own colors, fading with the rest. */
    val map: MapColors,
)

/** The driver's main colour: baby blue (night), with dark ink on it. */
private val Gold = Color(0xFF89CFF0)
private val GoldInk = Color(0xFF0A1A24)

val NightColors = CarColors(
    bg = Color(0xFF0A0A0C),
    surface = Color(0x14FFFFFF),
    surface2 = Color(0x1FFFFFFF),
    line = Color(0x1AFFFFFF),
    glass = Color(0xE0141417),
    glassEdge = Color(0x24FFFFFF),
    text = Color(0xFFF5F5F7),
    text2 = Color(0xFFA9A9AE),
    focus = Gold,
    accent = Gold,
    onAccent = GoldInk,
    // A little lighter than the screen's own bg, roads well above it: on a dimmed car display at night the first
    // palette was nearly black, the road hard to tell from the land (a road test)
    map = MapColors(
        land = Color(0xFF171B22),
        landuse = Color(0xFF1A1F26),
        park = Color(0xFF17211D),
        water = Color(0xFF11253B),
        building = Color(0xFF232933),
        rail = Color(0xFF363D49),
        roadMinor = Color(0xFF3B4452),
        // Casings the color of the land: at night roads stand out by their own shade
        roadMinorCasing = Color(0xFF171B22),
        roadMajor = Color(0xFF4C5566),
        roadMajorCasing = Color(0xFF171B22),
        motorway = Color(0xFF657186),
        motorwayCasing = Color(0xFF171B22),
        boundary = Color(0xFF555E6D),
        labelRoad = Color(0xFFB4BCC8),
        labelPlace = Color(0xFFF3F5F8),
        labelMinor = Color(0xFF8A94A3),
        labelMotorway = Color(0xFFF3F5F8),
        halo = Color(0xFF171B22),
        route = Color(0xFF5EA8FF),
        puck = Color(0xFFA9B1BE),
    ),
)

/**
 * By day white glass on a light page, Square's look turned light: frosted white panes, dark text, a deeper baby blue so it
 * still reads on white (3.8:1, large text and fills; dark ink on it 4.9:1). The map light.
 */
val DayColors = CarColors(
    bg = Color(0xFFEEF0F4),
    surface = Color(0xB8FFFFFF),
    surface2 = Color(0x0F14161B),
    line = Color(0x1F14161B),
    glass = Color(0xEBFFFFFF),
    glassEdge = Color(0x1F14161B),
    text = Color(0xFF14161B),
    text2 = Color(0xFF5E6470),
    // A deeper baby blue by day: the light one fades into white glass (this one ~3.2:1 on white, dark ink ~6:1)
    focus = Color(0xFF3A9AD9),
    accent = Color(0xFF3A9AD9),
    onAccent = GoldInk,
    map = MapColors(
        land = Color(0xFFEEF1F4),
        landuse = Color(0xFFE8EBEF),
        park = Color(0xFFDCEAD9),
        water = Color(0xFFBFD5EA),
        building = Color(0xFFDFE3E9),
        rail = Color(0xFFC9CFD8),
        roadMinor = Color(0xFFFFFFFF),
        roadMinorCasing = Color(0xFFD8DDE4),
        roadMajor = Color(0xFFFFFFFF),
        roadMajorCasing = Color(0xFFC5CDD7),
        motorway = Color(0xFFF9D99B),
        motorwayCasing = Color(0xFFE0B868),
        boundary = Color(0xFF9AA3AF),
        labelRoad = Color(0xFF4A5462),
        labelPlace = Color(0xFF0F1318),
        labelMinor = Color(0xFF6B7584),
        labelMotorway = Color(0xFF5A3E06),
        halo = Color(0xFFFFFFFF),
        route = Color(0xFF0B63CE),
        puck = Color(0xFF4A5462),
    ),
)

/**
 * A pane of glass: [fill] (a glass token: [CarColors.surface] for cards, [CarColors.glass] for what floats over
 * content) in [shape], with the light hairline edge. Not clipped, so a knob ring drawn outside stays visible.
 */
fun Modifier.glass(shape: Shape, fill: (CarColors) -> Color = { it.surface }): Modifier = composed {
    val colors = LocalCarColors.current
    background(fill(colors), shape).border(1.dp, colors.glassEdge, shape)
}

/** A road sign on the map (the speed limit): the same by day and by night, like the real ones. */
val SignRed = Color(0xFFD7262E)
val SignWhite = Color(0xFFFFFFFF)
val SignInk = Color(0xFF15181D)

/** Road-number signs, in the colours each country uses (motorway green, state road blue...). */
val ShieldGreen = Color(0xFF00804A)
val ShieldBlue = Color(0xFF1C4BA0)
val ShieldRed = Color(0xFFC8102E)
val ShieldYellow = Color(0xFFF7C600)
val ShieldUkYellow = Color(0xFFFFD200)

/**
 * The map's palette. Each name is a placeholder in `res/raw/map_style.json` ("@water"), so the style
 * file says where a color goes and this says which one; day and night fade like the rest of the UI.
 */
@Immutable
data class MapColors(
    val land: Color,
    val landuse: Color,
    val park: Color,
    val water: Color,
    val building: Color,
    val rail: Color,
    val roadMinor: Color,
    val roadMinorCasing: Color,
    val roadMajor: Color,
    val roadMajorCasing: Color,
    val motorway: Color,
    val motorwayCasing: Color,
    val boundary: Color,
    val labelRoad: Color,
    val labelPlace: Color,
    val labelMinor: Color,
    val labelMotorway: Color,
    val halo: Color,
    /** The route line and the maneuver arrow: drawn by code, not in the style file. */
    val route: Color,
    /** The car on the map, standing out on the route. */
    val puck: Color,
) {
    /** The colors by their placeholder names. */
    fun byName(): Map<String, Color> = mapOf(
        "land" to land, "landuse" to landuse, "park" to park, "water" to water, "building" to building,
        "rail" to rail, "roadMinor" to roadMinor, "roadMinorCasing" to roadMinorCasing, "roadMajor" to roadMajor,
        "roadMajorCasing" to roadMajorCasing, "motorway" to motorway, "motorwayCasing" to motorwayCasing,
        "boundary" to boundary, "labelRoad" to labelRoad, "labelPlace" to labelPlace, "labelMinor" to labelMinor,
        "labelMotorway" to labelMotorway, "halo" to halo,
    )

    companion object {
        fun lerp(a: MapColors, b: MapColors, t: Float) = MapColors(
            land = lerp(a.land, b.land, t),
            landuse = lerp(a.landuse, b.landuse, t),
            park = lerp(a.park, b.park, t),
            water = lerp(a.water, b.water, t),
            building = lerp(a.building, b.building, t),
            rail = lerp(a.rail, b.rail, t),
            roadMinor = lerp(a.roadMinor, b.roadMinor, t),
            roadMinorCasing = lerp(a.roadMinorCasing, b.roadMinorCasing, t),
            roadMajor = lerp(a.roadMajor, b.roadMajor, t),
            roadMajorCasing = lerp(a.roadMajorCasing, b.roadMajorCasing, t),
            motorway = lerp(a.motorway, b.motorway, t),
            motorwayCasing = lerp(a.motorwayCasing, b.motorwayCasing, t),
            boundary = lerp(a.boundary, b.boundary, t),
            labelRoad = lerp(a.labelRoad, b.labelRoad, t),
            labelPlace = lerp(a.labelPlace, b.labelPlace, t),
            labelMinor = lerp(a.labelMinor, b.labelMinor, t),
            labelMotorway = lerp(a.labelMotorway, b.labelMotorway, t),
            halo = lerp(a.halo, b.halo, t),
            route = lerp(a.route, b.route, t),
            puck = lerp(a.puck, b.puck, t),
        )
    }
}

private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(manrope(500), manrope(600), manrope(700), manrope(800))

// The palette changes on every frame of a theme fade: a tracked local
// recomposes only what reads it, a static one would recompose the whole launcher each frame
val LocalCarColors = compositionLocalOf { NightColors }

/** Base text style; sizes are in design units (1 unit = 1 px on a 1280x720 screen). */
val CarText = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)

private const val THEME_FADE_MS = 1400

/**
 * Day/night palette (the map's too) with a slow fade instead of a flash. Surfaces fade over the whole duration while
 * text flips quickly around the point where the background crosses mid-luminance, so text never fades into it.
 */
@Composable
fun CarTheme(night: Boolean, content: @Composable () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = if (night) 1f else 0f,
        animationSpec = tween(THEME_FADE_MS, easing = FastOutSlowInEasing),
        label = "theme",
    )
    val textProgress = smoothStep((progress - TEXT_FLIP_START) / (TEXT_FLIP_END - TEXT_FLIP_START))
    val day = DayColors
    val dark = NightColors

    CompositionLocalProvider(
        LocalCarColors provides CarColors(
            bg = lerp(day.bg, dark.bg, progress),
            surface = lerp(day.surface, dark.surface, progress),
            surface2 = lerp(day.surface2, dark.surface2, progress),
            line = lerp(day.line, dark.line, progress),
            glass = lerp(day.glass, dark.glass, progress),
            glassEdge = lerp(day.glassEdge, dark.glassEdge, progress),
            text = lerp(day.text, dark.text, textProgress),
            text2 = lerp(day.text2, dark.text2, textProgress),
            focus = lerp(day.focus, dark.focus, progress),
            accent = lerp(day.accent, dark.accent, progress),
            onAccent = dark.onAccent,
            // Labels (and the car drawn on the map) flip with the text, so they never fade into the land
            map = MapColors.lerp(day.map, dark.map, progress).copy(
                labelRoad = lerp(day.map.labelRoad, dark.map.labelRoad, textProgress),
                labelPlace = lerp(day.map.labelPlace, dark.map.labelPlace, textProgress),
                labelMinor = lerp(day.map.labelMinor, dark.map.labelMinor, textProgress),
                labelMotorway = lerp(day.map.labelMotorway, dark.map.labelMotorway, textProgress),
                halo = lerp(day.map.halo, dark.map.halo, textProgress),
                puck = lerp(day.map.puck, dark.map.puck, textProgress),
            ),
        ),
        content = content,
    )
}

private const val TEXT_FLIP_START = 0.42f
private const val TEXT_FLIP_END = 0.58f

private fun smoothStep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
