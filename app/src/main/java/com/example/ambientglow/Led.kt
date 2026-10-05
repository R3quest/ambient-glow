package com.example.ambientglow

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

/** LED bloom radius as a multiple of its core. */
internal const val LED_HALO_FACTOR = 3.2f

internal object RealTimeMotion : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/** Ring LED: line thickness as a share of the dot radius, and its clearance from the lens. */
internal const val LED_RING_STROKE_FACTOR = 0.6f
internal val LED_RING_GAP = 1.5.dp

/**
 * A bright core, a hot white centre and a soft radial bloom. The brush is built once per
 * colour/size change in drawWithCache; each breath frame only changes the layer alpha.
 * The dashboard draws its real-size LED preview and the phone mock-up's LED with this too.
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
    Spacer(
        modifier
            .graphicsLayer { this.alpha = alpha() }
            .drawWithCache {
                val core = radius.toPx()
                val hot = lerp(color, Color.White, 0.45f)
                if (onCamera) {
                    val lens = geometry.lens(size.width, density)
                    val center = lens.center
                    val line = core * LED_RING_STROKE_FACTOR
                    val ring = lens.radius + ringGap.toPx() + line / 2f + ringGrowPx
                    val bloom = ring + line / 2f + core * (LED_HALO_FACTOR - 1f)
                    val halo = Brush.radialGradient(
                        0f to Color.Transparent,
                        lens.radius / bloom to Color.Transparent,
                        ring / bloom to color.copy(alpha = 0.65f),
                        (ring + (bloom - ring) * 0.35f) / bloom to color.copy(alpha = 0.22f),
                        1f to Color.Transparent,
                        center = center,
                        radius = bloom,
                    )
                    val coreStroke = Stroke(line)
                    val hotStroke = Stroke(line * 0.4f)
                    return@drawWithCache onDrawBehind {
                        drawCircle(halo, radius = bloom, center = center)
                        drawCircle(color, radius = ring, center = center, style = coreStroke)
                        drawCircle(hot, radius = ring, center = center, style = hotStroke)
                    }
                }
                val bloom = core * LED_HALO_FACTOR
                // Same margin as GlowGraphic, so the LED sits exactly where the dashboard showed it.
                val center = dotCenter(dotX, dotY, size, core * DOT_HALO_FACTOR)
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
