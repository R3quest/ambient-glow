package com.example.ambientglow

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Physical sizes of every glow element. Full-screen values follow TODO.md (4dp frame). */
@Immutable
data class GlowMetrics(
    val stroke: Dp,
    val haloStroke: Dp,
    val ringGap: Dp,
    val fallbackCornerRadius: Dp,
    val fallbackCameraCenterY: Dp,
    val fallbackCameraRadius: Dp,
) {
    companion object {
        val FullScreen = GlowMetrics(
            stroke = 4.dp,
            haloStroke = 12.dp,
            ringGap = 6.dp,
            fallbackCornerRadius = 28.dp,
            fallbackCameraCenterY = 26.dp,
            fallbackCameraRadius = 12.dp,
        )

        /** Mini phone inside a style tile. */
        val Tile = GlowMetrics(
            stroke = 2.dp,
            haloStroke = 5.dp,
            ringGap = 2.dp,
            fallbackCornerRadius = 13.dp,
            fallbackCameraCenterY = 9.dp,
            fallbackCameraRadius = 3.dp,
        )

        /** Larger phone mock-up in the dot-position panel. */
        val Panel = GlowMetrics(
            stroke = 2.5.dp,
            haloStroke = 6.dp,
            ringGap = 3.dp,
            fallbackCornerRadius = 13.dp,
            fallbackCameraCenterY = 12.dp,
            fallbackCameraRadius = 4.dp,
        )
    }
}

/** Halo radius as a multiple of the dot radius. Also the edge margin that keeps the dot on screen. */
const val DOT_HALO_FACTOR = 2f

/** Punch-hole position in window pixels. */
@Immutable
data class CutoutSpot(val centerX: Float, val centerY: Float, val radius: Float)

/** Real display geometry, known only inside the wake window; previews use [Unknown]. */
@Immutable
data class ScreenGeometry(val cutout: CutoutSpot?, val cornerRadiusPx: Float?) {
    companion object {
        val Unknown = ScreenGeometry(cutout = null, cornerRadiusPx = null)

        fun from(insets: WindowInsetsCompat): ScreenGeometry {
            val topRect = insets.displayCutout
                ?.boundingRects
                ?.filterNot { it.isEmpty }
                ?.minByOrNull { it.top }
            val spot = topRect?.let {
                CutoutSpot(
                    centerX = it.exactCenterX(),
                    centerY = it.exactCenterY(),
                    radius = min(it.width(), it.height()) / 2f,
                )
            }
            val corner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                insets.toWindowInsets()
                    ?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                    ?.radius
                    ?.takeIf { it > 0 }
                    ?.toFloat()
            } else {
                null
            }
            return ScreenGeometry(cutout = spot, cornerRadiusPx = corner)
        }
    }
}

private const val HALO_ALPHA = 0.28f

/**
 * Draws one glow style. [alpha] is read inside a graphics layer, so the pulse animation
 * re-renders only the layer: no recomposition, no relayout, and no allocations per frame.
 */
@Composable
fun GlowGraphic(
    style: GlowStyle,
    color: Color,
    alpha: () -> Float,
    dotX: Float,
    dotY: Float,
    modifier: Modifier = Modifier,
    metrics: GlowMetrics = GlowMetrics.FullScreen,
    geometry: ScreenGeometry = ScreenGeometry.Unknown,
    dotRadius: Dp = DotSize.LED.radius,
) {
    val density = LocalDensity.current
    val px = remember(metrics, dotRadius, density) {
        with(density) {
            ResolvedMetrics(
                stroke = metrics.stroke.toPx(),
                haloStroke = metrics.haloStroke.toPx(),
                dotRadius = dotRadius.toPx(),
                haloDotRadius = dotRadius.toPx() * DOT_HALO_FACTOR,
                ringGap = metrics.ringGap.toPx(),
                cornerRadius = metrics.fallbackCornerRadius.toPx(),
                cameraCenterY = metrics.fallbackCameraCenterY.toPx(),
                cameraRadius = metrics.fallbackCameraRadius.toPx(),
            )
        }
    }
    val stroke = remember(px) { Stroke(width = px.stroke) }
    val haloStroke = remember(px) { Stroke(width = px.haloStroke) }
    val halo = remember(color) { color.copy(alpha = HALO_ALPHA) }
    val cornerRadius = geometry.cornerRadiusPx ?: px.cornerRadius

    Canvas(modifier.graphicsLayer { this.alpha = alpha() }) {
        when (style) {
            GlowStyle.EDGE_FRAME -> {
                val inset = px.stroke / 2f
                val haloInset = px.stroke + px.haloStroke / 2f
                drawRoundRect(
                    color = halo,
                    topLeft = Offset(haloInset, haloInset),
                    size = Size(size.width - haloInset * 2f, size.height - haloInset * 2f),
                    cornerRadius = CornerRadius((cornerRadius - haloInset).coerceAtLeast(0f)),
                    style = haloStroke,
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - px.stroke, size.height - px.stroke),
                    cornerRadius = CornerRadius((cornerRadius - inset).coerceAtLeast(0f)),
                    style = stroke,
                )
            }

            GlowStyle.CAMERA_RING -> {
                val spot = geometry.cutout
                val center = if (spot != null) {
                    Offset(spot.centerX, spot.centerY)
                } else {
                    Offset(size.width / 2f, px.cameraCenterY)
                }
                val ringRadius = (spot?.radius ?: px.cameraRadius) + px.ringGap + px.stroke / 2f
                drawCircle(
                    color = halo,
                    radius = ringRadius + px.stroke / 2f + px.haloStroke / 2f,
                    center = center,
                    style = haloStroke,
                )
                drawCircle(color = color, radius = ringRadius, center = center, style = stroke)
            }

            GlowStyle.CUSTOM_DOT -> {
                val center = dotCenter(dotX, dotY, size, px.haloDotRadius)
                drawCircle(color = halo, radius = px.haloDotRadius, center = center)
                drawCircle(color = color, radius = px.dotRadius, center = center)
            }
        }
    }
}

// Arrival effect: two quick pulses, short enough to finish before the LED takes over the lock
// screen (2.5 s).
private const val ARRIVAL_PULSES = 2
private const val ARRIVAL_FADE_IN_MS = 250
private const val ARRIVAL_HOLD_MS = 550L
private const val ARRIVAL_FADE_OUT_MS = 400
private const val ARRIVAL_GAP_MS = 50L

/**
 * The new-message effect: the user's chosen style pulsed [ARRIVAL_PULSES] times in [color], then
 * [onDone]. Drawn over the lock screen by [GlowShield], or on the black panel by the glow screen.
 */
@Composable
fun ArrivalEffect(settings: GlowSettings, color: Int, geometry: ScreenGeometry, onDone: () -> Unit) {
    val glow = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    // A signal, not decoration: keep its timing even with animations scaled down or off.
    LaunchedEffect(Unit) {
        withContext(RealTimeMotion) {
            repeat(ARRIVAL_PULSES) {
                glow.animateTo(1f, tween(ARRIVAL_FADE_IN_MS, easing = FastOutSlowInEasing))
                delay(ARRIVAL_HOLD_MS)
                glow.animateTo(0f, tween(ARRIVAL_FADE_OUT_MS, easing = LinearOutSlowInEasing))
                delay(ARRIVAL_GAP_MS)
            }
        }
        done()
    }
    GlowGraphic(
        style = settings.style,
        color = Color(color),
        alpha = { glow.value },
        dotX = settings.dotX,
        dotY = settings.dotY,
        modifier = Modifier.fillMaxSize(),
        geometry = geometry,
        dotRadius = settings.dotSize.radius,
    )
}

/** Maps 0..1 slider fractions to a dot centre that always stays fully on screen. */
fun dotCenter(fractionX: Float, fractionY: Float, canvas: Size, margin: Float): Offset = Offset(
    x = margin + fractionX * (canvas.width - margin * 2f),
    y = margin + fractionY * (canvas.height - margin * 2f),
)

/** Inverse of [dotCenter]: a touch position back to clamped 0..1 fractions. */
fun dotFraction(position: Offset, canvas: Size, margin: Float): Offset = Offset(
    x = ((position.x - margin) / (canvas.width - margin * 2f).coerceAtLeast(1f)).coerceIn(0f, 1f),
    y = ((position.y - margin) / (canvas.height - margin * 2f).coerceAtLeast(1f)).coerceIn(0f, 1f),
)

@Immutable
private data class ResolvedMetrics(
    val stroke: Float,
    val haloStroke: Float,
    val dotRadius: Float,
    val haloDotRadius: Float,
    val ringGap: Float,
    val cornerRadius: Float,
    val cameraCenterY: Float,
    val cameraRadius: Float,
)
