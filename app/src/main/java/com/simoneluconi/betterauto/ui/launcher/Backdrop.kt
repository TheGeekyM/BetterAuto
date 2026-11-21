package com.simoneluconi.betterauto.ui.launcher

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.betterauto.media.NowPlaying
import com.simoneluconi.betterauto.ui.theme.LocalCarColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * What the glass sits on, behind the whole launcher: the page color, the playing song's cover blurred over it,
 * darkened by night and washed white by day, or with no cover a faint warm glow. Static between songs, to stay cheap for the video sent to the car:
 * the cover is blurred once, on a small copy off the main thread (that alone is the blur on Android 10 and 11), and
 * on Android 12+ softened by the GPU, a blur of a layer that does not change (the system keeps its result until
 * the layer changes; not measured on the car).
 */
@Composable
fun Backdrop(modifier: Modifier = Modifier) {
    val colors = LocalCarColors.current
    // Only the cover: a new Track arrives on every position update, this must not recompose for those
    val art by remember { NowPlaying.track.map { it?.art }.distinctUntilChanged() }.collectAsStateWithLifecycle(null)
    // A light page (day) wants the cover pale under dark text, a dark one dark under light text: flips once, mid-fade
    val light = colors.bg.luminance() > 0.5f
    val blurred by produceState<ImageBitmap?>(null, art, light) {
        value = art?.let { withContext(Dispatchers.Default) { runCatching { blurredCopy(it, light) }.getOrNull() } }
    }
    Box(
        modifier
            .background(colors.bg)
            .drawBehind {
                // The page's own light, for when nothing plays: warm from the top corner, as a lamp on the glass
                drawRect(
                    Brush.radialGradient(
                        listOf(colors.accent.copy(alpha = 0.10f), colors.accent.copy(alpha = 0f)),
                        center = Offset(size.width * 0.12f, 0f),
                        radius = maxOf(size.width, size.height) * 0.75f,
                    ),
                )
            },
    ) {
        Crossfade(blurred, animationSpec = tween(900), label = "backdrop") { image ->
            if (image != null) {
                Image(
                    image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(24.dp, BlurredEdgeTreatment.Rectangle) else Modifier),
                )
            }
        }
        // The page's color over it, more toward the bottom where the bar and the pills sit (dark by night, white by
        // day): the words stay legible whatever the cover
        if (blurred != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(colors.bg.copy(alpha = 0.66f), colors.bg.copy(alpha = 0.84f)))),
            )
        }
    }
}

/** Side of the small copy the cover is blurred at: drawn screen-wide, its pixels are what makes it soft. */
private const val BLUR_PX = 40
private const val BLUR_RADIUS = 3

/**
 * By night, the brightest a channel of the blurred cover may be (0..255). A white cover blurred is a white wash:
 * under the scrim (66% of the page at the top) and a pane of glass, grey text on it fell to about 2.3:1. Capped
 * here, the worst case stays near 4.5:1 for `text2` and well above for `text`; a dark cover keeps its own colors.
 */
private const val MAX_CHANNEL = 110

/**
 * By day, the darkest a channel may be: the mirror case, a black cover under the white scrim left grey text on the
 * bare page at 2.2:1. Lifted toward white, it stays at 4.6:1 (and dark text at 14:1): a pastel tint of the cover.
 */
private const val MIN_CHANNEL = 200

/**
 * The cover's center square brought down to [BLUR_PX] and box-blurred three times (close to a gaussian), its
 * brightness kept within what the text on it needs: no lighter than [MAX_CHANNEL] for a dark page, no darker than
 * [MIN_CHANNEL] for a [light] one.
 */
private fun blurredCopy(art: ImageBitmap, light: Boolean): ImageBitmap {
    var source = art.asAndroidBitmap()
    // A hardware bitmap cannot be read pixel by pixel
    if (source.config == Bitmap.Config.HARDWARE) source = source.copy(Bitmap.Config.ARGB_8888, false)
    val side = minOf(source.width, source.height)
    val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    val small = Bitmap.createScaledBitmap(square, BLUR_PX, BLUR_PX, true)
    val pixels = IntArray(BLUR_PX * BLUR_PX)
    small.getPixels(pixels, 0, BLUR_PX, 0, 0, BLUR_PX, BLUR_PX)
    val scratch = IntArray(pixels.size)
    repeat(3) {
        boxBlur(pixels, scratch, BLUR_PX, horizontal = true)
        boxBlur(scratch, pixels, BLUR_PX, horizontal = false)
    }
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = p shr 16 and 0xFF
        val g = p shr 8 and 0xFF
        val b = p and 0xFF
        // Scaled as a whole (toward black, or toward white by day), so the cover's hue stays
        if (light) {
            val min = minOf(r, g, b)
            if (min < MIN_CHANNEL) {
                val k = 255 - MIN_CHANNEL
                val d = 255 - min
                pixels[i] = (0xFF shl 24) or (255 - (255 - r) * k / d shl 16) or (255 - (255 - g) * k / d shl 8) or (255 - (255 - b) * k / d)
            }
        } else {
            val max = maxOf(r, g, b)
            if (max > MAX_CHANNEL) pixels[i] = (0xFF shl 24) or (r * MAX_CHANNEL / max shl 16) or (g * MAX_CHANNEL / max shl 8) or (b * MAX_CHANNEL / max)
        }
    }
    return Bitmap.createBitmap(pixels, BLUR_PX, BLUR_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** One pass of a box blur along rows or columns of a square of [n] pixels, edges clamped. */
private fun boxBlur(from: IntArray, to: IntArray, n: Int, horizontal: Boolean) {
    val window = BLUR_RADIUS * 2 + 1
    for (line in 0 until n) {
        var r = 0
        var g = 0
        var b = 0
        fun at(i: Int): Int {
            val c = i.coerceIn(0, n - 1)
            return if (horizontal) from[line * n + c] else from[c * n + line]
        }
        for (i in -BLUR_RADIUS..BLUR_RADIUS) {
            val p = at(i)
            r += p shr 16 and 0xFF
            g += p shr 8 and 0xFF
            b += p and 0xFF
        }
        for (i in 0 until n) {
            val index = if (horizontal) line * n + i else i * n + line
            to[index] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val out = at(i - BLUR_RADIUS)
            val inn = at(i + BLUR_RADIUS + 1)
            r += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
            g += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
            b += (inn and 0xFF) - (out and 0xFF)
        }
    }
}
