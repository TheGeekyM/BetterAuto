package com.simoneluconi.betterauto.ui.voice

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private const val STRANDS = 12
private const val POINTS = 240
private const val PARTICLES = 1800
private val HARMONICS = intArrayOf(2, 3, 5, 7)
private const val TWO_PI = (2 * PI).toFloat()

/**
 * The source draws on a canvas up to 440 px wide; its line widths and dot sizes are in those pixels, so they are
 * scaled with the ring. The hairlines (threads, dots, core ring) never go below the source's own pixel size: in a
 * small car frame (800x480) they would fade to nothing.
 */
private const val SOURCE_SIZE = 440f

/**
 * The recognizer's level reaches 1 on ordinary speech; aidle's input volume (the mean of the voice-band
 * spectrum) sits around 0.3 to 0.6 then. Scaled so a spoken word swells the ring as much as there.
 */
private const val LEVEL_TO_INPUT = 0.6f

/**
 * A Jarvis blue (the driver's choice over aidle's coral #ee6c55) and its "bright" tint, 35% toward white.
 * Hard-coded on purpose, unlike the rest of the car UI: the ring is a copy of aidle's, on the darkened screen
 * in day and night alike.
 */
private val Base = Color(0xFF3D8BFF)

/** The ring while the assistant speaks. */
val VoiceRingSpeaking = Color(0xFF2FD180)
/** The ring while it listens (the driver's blue). */
val VoiceRingListening = Base

/** The listening pill's edge: a little of the ring's light on the glass under it. */
internal val VoiceRingEdge = Base.copy(alpha = 0.5f)

/**
 * The ring's state between frames: strands, particles and the smoothed energy. Flat arrays and one [Path],
 * so a frame allocates nothing.
 */
private class Orb(random: Random) {
    // Per strand and harmonic (strand * 4 + h): amplitude, phase, drift speed (alternating direction).
    val amp = FloatArray(STRANDS * 4)
    val ph = FloatArray(STRANDS * 4)
    val drift = FloatArray(STRANDS * 4)
    val off = FloatArray(STRANDS)

    val pt = FloatArray(PARTICLES)
    val pd = FloatArray(PARTICLES)
    val ps = FloatArray(PARTICLES)
    val pStrand = IntArray(PARTICLES)

    // sin/cos of k·θ for each harmonic over the loop's points, and of θ itself
    val sinK = FloatArray(4 * (POINTS + 1))
    val cosK = FloatArray(4 * (POINTS + 1))
    val sinT = FloatArray(POINTS + 1)
    val cosT = FloatArray(POINTS + 1)

    // This frame's sin/cos of each harmonic's moving phase, so a harmonic is sinK·cos q + cosK·sin q
    val qSin = FloatArray(STRANDS * 4)
    val qCos = FloatArray(STRANDS * 4)

    val dots = FloatArray(PARTICLES * 2)
    val path = Path()
    val dotPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        strokeCap = android.graphics.Paint.Cap.SQUARE
        blendMode = android.graphics.BlendMode.PLUS
    }

    var energy = 0f
    var phase = 0f

    // Cached per size
    var forSize = androidx.compose.ui.geometry.Size.Unspecified
    var glow = Stroke(5f)
    var thread = Stroke(0.8f)
    var coreRing = Stroke(1f)
    var coreBrush: Brush = Brush.radialGradient(listOf(Base, Base.copy(alpha = 0f)))
    var base = Base
    var bright = Base
    private var forColor = Color.Unspecified

    init {
        for (s in 0 until STRANDS) {
            for (h in 0 until 4) {
                val i = s * 4 + h
                amp[i] = 0.35f + random.nextFloat() * 0.65f
                ph[i] = random.nextFloat() * 6.28f
                drift[i] = (0.15f + random.nextFloat() * 0.35f) * if (s % 2 == 1) 1f else -1f
            }
            off[s] = (random.nextFloat() - 0.5f) * 0.09f
        }
        for (p in 0 until PARTICLES) {
            pt[p] = random.nextFloat() * TWO_PI
            pd[p] = (random.nextFloat() - 0.5f) * 2
            ps[p] = 0.4f + random.nextFloat() * 1.2f
            pStrand[p] = random.nextInt(STRANDS)
        }
        for (j in 0..POINTS) {
            val th = j * TWO_PI / POINTS
            sinT[j] = sin(th); cosT[j] = cos(th)
            for (h in 0 until 4) {
                sinK[h * (POINTS + 1) + j] = sin(HARMONICS[h] * th)
                cosK[h * (POINTS + 1) + j] = cos(HARMONICS[h] * th)
            }
        }
    }

    /** Advances by [dt] seconds; aidle's per-frame eases are written for 60 fps, so they are scaled by `dt·60`. */
    fun step(dt: Float, input: Float) {
        val f = dt * 60f
        val target = min(1.4f, 0.15f + input * 1.2f)
        energy += (target - energy) * (1f - 0.92f.pow(f))
        phase += dt
        val dotStep = 0.0015f * (1f + energy * 2f) * f
        for (p in 0 until PARTICLES) {
            var t = pt[p] + dotStep * ps[p]
            if (t > TWO_PI) t -= TWO_PI
            pt[p] = t
        }
    }

    fun draw(scope: DrawScope): Unit = with(scope) {
        val side = min(size.width, size.height)
        if (side <= 0f) return
        val u = side / SOURCE_SIZE
        val cx = size.width / 2
        val cy = size.height / 2
        // Big: the ring is the whole show while listening (the panel under it is a slim pill)
        val r0 = side * 0.40f
        val e = energy
        if (size != forSize || base != forColor) {
            forSize = size
            forColor = base
            glow = Stroke(5f * u)
            thread = Stroke(0.8f * max(u, 1f))
            coreRing = Stroke(1f * max(u, 1f))
            coreBrush = Brush.radialGradient(listOf(base, base.copy(alpha = 0f)), Offset(cx, cy), r0 * 0.32f)
            dotPaint.strokeWidth = 1.1f * max(u, 1f)
        }
        for (i in 0 until STRANDS * 4) {
            val q = ph[i] + phase * drift[i]
            qSin[i] = sin(q); qCos[i] = cos(q)
        }
        val wobble = 0.03f + e * 0.06f
        val swell = 1f + e * 0.05f

        // The core: a soft disc and a faint ring
        drawCircle(coreBrush, r0 * 0.32f, Offset(cx, cy), alpha = 0.10f + e * 0.14f, blendMode = BlendMode.Plus)
        drawCircle(bright, r0 * 0.14f, Offset(cx, cy), alpha = 0.12f + e * 0.12f, style = coreRing, blendMode = BlendMode.Plus)

        // The strands, each stroked twice: a wide faint glow, then the thin bright thread
        val glowAlpha = 0.035f + e * 0.04f
        val threadAlpha = min(1f, 0.20f + e * 0.2f)
        for (s in 0 until STRANDS) {
            path.reset()
            val b = s * 4
            for (j in 0..POINTS) {
                var w = 0f
                for (h in 0 until 4) {
                    val k = h * (POINTS + 1) + j
                    w += amp[b + h] * (sinK[k] * qCos[b + h] + cosK[k] * qSin[b + h])
                }
                val r = r0 * (swell + off[s] + w * wobble)
                val x = cx + cosT[j] * r
                val y = cy + sinT[j] * r
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, base, alpha = glowAlpha, style = glow, blendMode = BlendMode.Plus)
            drawPath(path, bright, alpha = threadAlpha, style = thread, blendMode = BlendMode.Plus)
        }

        // The dust: particles riding the strands, scattered a little off them
        val scatter = (7f + e * 16f) * u
        for (p in 0 until PARTICLES) {
            val t = pt[p]
            val s1 = sin(t)
            val c1 = cos(t)
            // sin/cos of 2t, 3t, 5t, 7t from t's, by the angle sums
            val s2 = 2 * s1 * c1
            val c2 = c1 * c1 - s1 * s1
            val s3 = s2 * c1 + c2 * s1
            val c3 = c2 * c1 - s2 * s1
            val s5 = s3 * c2 + c3 * s2
            val c5 = c3 * c2 - s3 * s2
            val s7 = s5 * c2 + c5 * s2
            val c7 = c5 * c2 - s5 * s2
            val b = pStrand[p] * 4
            val w = amp[b] * (s2 * qCos[b] + c2 * qSin[b]) +
                amp[b + 1] * (s3 * qCos[b + 1] + c3 * qSin[b + 1]) +
                amp[b + 2] * (s5 * qCos[b + 2] + c5 * qSin[b + 2]) +
                amp[b + 3] * (s7 * qCos[b + 3] + c7 * qSin[b + 3])
            val r = r0 * (swell + off[pStrand[p]] + w * wobble) + pd[p] * scatter
            dots[p * 2] = cx + c1 * r
            dots[p * 2 + 1] = cy + s1 * r
        }
        dotPaint.color = bright.copy(alpha = min(1f, 0.45f + e * 0.3f)).toArgb()
        drawIntoCanvas { it.nativeCanvas.drawPoints(dots, dotPaint) }
    }
}

/**
 * The listening ring, ported from aidle's Jarvis orb (`src/components/JarvisOrb.jsx`, listening state, dark theme):
 * twelve tangled strands of light around a circle, each a closed loop wobbling with its own harmonics, 1800
 * particles of dust riding them, and a soft core. Everything adds light (the source's `lighter`), inside its own
 * layer laid over the scrim, as aidle's canvas is over its page. With the voice ([level], 0 to 1) the strands swell,
 * wobble wider and brighten and the dust speeds up, eased as the source does.
 *
 * Colors are aidle's accent, hard-coded (see [Base]); aidle's other themes use other accents. Its other states
 * (thinking, working, speaking with the reply's spectrum) are left out: the ring shows only while listening.
 * Driven by the frame clock while on screen; a frame allocates nothing.
 */
@Composable
fun VoiceRing(level: Float, modifier: Modifier = Modifier, color: Color = VoiceRingListening) {
    val currentLevel by rememberUpdatedState(level)
    val orb = remember { Orb(Random(7)) }
    // Blue while listening, green while speaking: eased, the strands keep moving through the change
    val shade by androidx.compose.animation.animateColorAsState(color, androidx.compose.animation.core.tween(400), label = "ringColor")
    orb.base = shade
    orb.bright = androidx.compose.ui.graphics.lerp(shade, Color.White, 0.35f)
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(orb) {
        var last = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            orb.step(min(0.05f, (now - last) / 1e9f), currentLevel.coerceIn(0f, 1f) * LEVEL_TO_INPUT)
            last = now
            tick.intValue++
        }
    }
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        tick.intValue   // redraw on every frame of the clock
        orb.draw(this)
    }
}
