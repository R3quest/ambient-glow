package com.example.ambientglow

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

// One LED breath: a soft rise, then a long exhale into the dark, like a notification LED. One
// clock (ms into the breath) drives it; the dashboard previews breathe with the same function.
internal const val LED_RISE_MS = 560f
internal const val LED_FALL_MS = 1_300f
internal const val LED_BREATH_MS = LED_RISE_MS + LED_FALL_MS // 1860

private val LedRise = CubicBezierEasing(0.45f, 0f, 0.3f, 1f)
private val LedFall = CubicBezierEasing(0.45f, 0f, 0.35f, 1f)

/**
 * LED level at [ms] into one breath. Both curves start and end at zero velocity, so it leaves the
 * dark, turns at the peak and settles back with no knee, and no frame jumps far even at 30 Hz.
 */
internal fun ledBreathAt(ms: Float): Float = when {
    ms <= 0f -> 0f
    ms < LED_RISE_MS -> LedRise.transform(ms / LED_RISE_MS)
    ms < LED_BREATH_MS -> 1f - LedFall.transform((ms - LED_RISE_MS) / LED_FALL_MS)
    else -> 0f
}

/** Window brightness while the dot is lit: fixed, so [LedBrightness] dims only the dot's pixels. */
internal const val LED_WINDOW_BRIGHTNESS = 1f

/** LED bloom radius as a multiple of its core. */
internal const val LED_HALO_FACTOR = 3.2f

internal object RealTimeMotion : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/** Ring LED: line thickness as a share of the dot radius, and its clearance from the lens. */
internal const val LED_RING_STROKE_FACTOR = 0.6f
internal val LED_RING_GAP = 1.5.dp

/** A scaled-down LED (a mock-up's) is kept at least this big, and its ring at least this clear of the lens. */
private val MIN_SCALED_LED = 1.4.dp
private val MIN_SCALED_RING_GAP = 0.5.dp

/**
 * The LED's radius at [scale]: the real dot, or scaled down to a mock-up and kept just large
 * enough to see. Whatever draws the LED or lands on it (the effects' endings) uses this, so the
 * light lands exactly on the dot the mock-up then breathes.
 */
internal fun ledRadiusAt(size: DotSize, scale: Float): Dp = (size.radius * scale).coerceAtLeast(MIN_SCALED_LED)

/** The ring LED's clearance from the lens at [scale], as [ledRadiusAt] is its size. */
internal fun ledRingGapAt(scale: Float): Dp = (LED_RING_GAP * scale).coerceAtLeast(MIN_SCALED_RING_GAP)

/**
 * A bright core, a hot white centre and a soft radial bloom. The brush is built once per
 * colour/size change in drawWithCache; each breath frame only changes the layer alpha.
 * The dashboard draws its real-size LED preview and the phone mock-up's LED with this too.
 *
 * The layer is only as big as the light: a breath frame then redraws that square, not the whole
 * window (and its alpha needs an offscreen buffer only that big). It is centred on the light with
 * a sub-pixel translation, so the light lands exactly where a full-size layer drew it.
 *
 * [onCamera]: the same light as a ring hugging the punch-hole, whose pixels cannot light;
 * [radius] then sets the ring's thickness and [ringGap] its clearance from the lens (the mock-up
 * scales it with its lens).
 */
@Composable
internal fun LedDot(
    color: Color,
    alpha: () -> Float,
    dotX: Float,
    dotY: Float,
    radius: Dp,
    modifier: Modifier = Modifier,
    onCamera: Boolean = false,
    geometry: ScreenGeometry = ScreenGeometry.Unknown,
    ringGrowPx: Float = 0f,
    ringGap: Dp = LED_RING_GAP,
) {
    val density = LocalDensity.current
    val light = remember(density, radius, onCamera, geometry, ringGrowPx, ringGap) {
        with(density) {
            ledLight(radius.toPx(), onCamera, geometry.lensRadius(this.density), ringGrowPx, ringGap.toPx())
        }
    }
    Spacer(
        modifier
            .layout { measurable, constraints ->
                val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
                val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
                val center = if (onCamera) {
                    geometry.lens(width.toFloat(), this.density).center
                } else {
                    // Same margin as GlowGraphic, so the LED sits exactly where the dashboard showed it.
                    dotCenter(dotX, dotY, Size(width.toFloat(), height.toFloat()), light.core * DOT_HALO_FACTOR)
                }
                val side = light.side
                val placeable = measurable.measure(Constraints.fixed(side, side))
                layout(width, height) {
                    placeable.placeWithLayer(0, 0) {
                        translationX = center.x - side / 2f
                        translationY = center.y - side / 2f
                        this.alpha = alpha()
                    }
                }
            }
            .drawWithCache {
                val center = size.center
                val bloom = light.bloom
                val hot = lerp(color, Color.White, 0.45f)
                if (onCamera) {
                    val ring = light.ring
                    val halo = Brush.radialGradient(
                        0f to Color.Transparent,
                        light.lens / bloom to Color.Transparent,
                        ring / bloom to color.copy(alpha = 0.65f),
                        (ring + (bloom - ring) * 0.35f) / bloom to color.copy(alpha = 0.22f),
                        1f to Color.Transparent,
                        center = center,
                        radius = bloom,
                    )
                    val coreStroke = Stroke(light.line)
                    val hotStroke = Stroke(light.line * 0.4f)
                    return@drawWithCache onDrawBehind {
                        drawCircle(halo, radius = bloom, center = center)
                        drawCircle(color, radius = ring, center = center, style = coreStroke)
                        drawCircle(hot, radius = ring, center = center, style = hotStroke)
                    }
                }
                val core = light.core
                val halo = Brush.radialGradient(
                    0f to color.copy(alpha = 0.65f),
                    0.35f to color.copy(alpha = 0.22f),
                    1f to Color.Transparent,
                    center = center,
                    radius = bloom,
                )
                onDrawBehind {
                    drawCircle(halo, radius = bloom, center = center)
                    drawCircle(color, radius = core, center = center)
                    drawCircle(hot, radius = core * 0.5f, center = center)
                }
            },
    )
}

/**
 * The LED's light in px: the dot's [core] (the ring's thickness), the [ring] of [line] width
 * round a [lens] of that radius, and the [bloom] that bounds all of it.
 */
@Immutable
internal class LedLight(val core: Float, val lens: Float, val line: Float, val ring: Float, val bloom: Float) {
    /** The layer's side: the bloom plus room for antialiasing all round, even so the light sits at its centre. */
    val side: Int = 2 * ceil(bloom + LED_LAYER_MARGIN_PX).toInt()
}

private const val LED_LAYER_MARGIN_PX = 2f

internal fun ledLight(core: Float, onCamera: Boolean, lens: Float, ringGrowPx: Float, ringGap: Float): LedLight {
    if (!onCamera) return LedLight(core, lens = 0f, line = 0f, ring = 0f, bloom = core * LED_HALO_FACTOR)
    val line = core * LED_RING_STROKE_FACTOR
    val ring = lens + ringGap + line / 2f + ringGrowPx
    return LedLight(core, lens, line, ring, bloom = ring + line / 2f + core * (LED_HALO_FACTOR - 1f))
}
