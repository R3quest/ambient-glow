package com.example.ambientglow

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.RoundedCorner
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
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
data class CutoutSpot(val centerX: Float, val centerY: Float, val radius: Float) {
    val center: Offset get() = Offset(centerX, centerY)
}

/** Real display geometry, known only inside the wake window; previews use [Unknown]. */
@Immutable
data class ScreenGeometry(val cutout: CutoutSpot?, val cornerRadiusPx: Float?) {
    /** The camera hole moved and resized by the user's fit ([GlowSettings.lensOffsetDp]). */
    fun fitted(settings: GlowSettings, density: Float): ScreenGeometry {
        val spot = cutout ?: return this
        if (settings.lensOffsetXDp == 0f && settings.lensOffsetDp == 0f && settings.lensGrowDp == 0f) return this
        return copy(
            cutout = spot.copy(
                centerX = spot.centerX + settings.lensOffsetXDp * density,
                centerY = spot.centerY + settings.lensOffsetDp * density,
                radius = (spot.radius + settings.lensGrowDp * density).coerceAtLeast(1f),
            ),
        )
    }

    /**
     * The camera hole, or where one usually is when the display reports none (previews, phones
     * without a punch-hole): top centre, at [metrics]' fallback size times [scale].
     */
    fun lens(width: Float, density: Float, scale: Float = 1f, metrics: GlowMetrics = GlowMetrics.FullScreen): CutoutSpot =
        cutout ?: CutoutSpot(
            centerX = width / 2f,
            centerY = metrics.fallbackCameraCenterY.value * density * scale,
            radius = lensRadius(density, scale, metrics),
        )

    /** [lens]'s radius alone, which doesn't depend on the screen's size. */
    fun lensRadius(density: Float, scale: Float = 1f, metrics: GlowMetrics = GlowMetrics.FullScreen): Float =
        cutout?.radius ?: (metrics.fallbackCameraRadius.value * density * scale)

    companion object {
        val Unknown = ScreenGeometry(cutout = null, cornerRadiusPx = null)

        fun from(insets: WindowInsetsCompat): ScreenGeometry {
            val topRect = insets.displayCutout
                ?.boundingRects
                ?.filterNot { it.isEmpty }
                ?.minByOrNull { it.top }
            val spot = topRect?.let { lensIn(it, insets) }
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

        /**
         * The punch-hole inside the top cutout's bounding rect, which runs up to the screen edge.
         * Where the cutout's own outline (Android 12+) is the round hole, that is exact. Some
         * OEMs (Samsung) outline only a rectangle from the top edge, which says nothing about
         * where the lens sits in it; its centre is the guess, and the user's fit corrects it.
         */
        private fun lensIn(rect: Rect, insets: WindowInsetsCompat): CutoutSpot {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                insets.toWindowInsets()?.displayCutout?.cutoutPath?.let { outline ->
                    // Only the part in this rect, in case the display has other cutouts too.
                    val hole = Path(outline).apply { op(Path().apply { addRect(RectF(rect), Path.Direction.CW) }, Path.Op.INTERSECT) }
                    val bounds = RectF().also { hole.computeBounds(it, true) }
                    if (!bounds.isEmpty) {
                        return CutoutSpot(bounds.centerX(), bounds.centerY(), min(bounds.width(), bounds.height()) / 2f)
                    }
                }
            }
            return CutoutSpot(rect.exactCenterX(), rect.exactCenterY(), min(rect.width(), rect.height()) / 2f)
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
                val lens = geometry.lens(size.width, this.density, metrics = metrics)
                val center = lens.center
                val ringRadius = lens.radius + px.ringGap + px.stroke / 2f
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
)
