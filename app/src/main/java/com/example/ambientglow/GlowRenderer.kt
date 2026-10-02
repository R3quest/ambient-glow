package com.example.ambientglow

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
    val dotRadius: Dp,
    val haloDotRadius: Dp,
    val ringGap: Dp,
    val fallbackCornerRadius: Dp,
    val fallbackCameraCenterY: Dp,
    val fallbackCameraRadius: Dp,
) {
    companion object {
        val FullScreen = GlowMetrics(
            stroke = 4.dp,
            haloStroke = 12.dp,
            dotRadius = 12.dp,
            haloDotRadius = 22.dp,
            ringGap = 6.dp,
            fallbackCornerRadius = 28.dp,
            fallbackCameraCenterY = 26.dp,
            fallbackCameraRadius = 12.dp,
        )

        /** Mini phone inside a style tile. */
        val Tile = GlowMetrics(
            stroke = 2.dp,
            haloStroke = 5.dp,
            dotRadius = 4.dp,
            haloDotRadius = 7.dp,
            ringGap = 2.dp,
            fallbackCornerRadius = 13.dp,
            fallbackCameraCenterY = 9.dp,
            fallbackCameraRadius = 3.dp,
        )

        /** Larger phone mock-up in the dot-position panel. */
        val Panel = GlowMetrics(
            stroke = 2.5.dp,
            haloStroke = 6.dp,
            dotRadius = 6.dp,
            haloDotRadius = 11.dp,
            ringGap = 3.dp,
            fallbackCornerRadius = 13.dp,
            fallbackCameraCenterY = 12.dp,
            fallbackCameraRadius = 4.dp,
        )
    }
}

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
) {
    val density = LocalDensity.current
    val px = remember(metrics, density) {
        with(density) {
            ResolvedMetrics(
                stroke = metrics.stroke.toPx(),
                haloStroke = metrics.haloStroke.toPx(),
                dotRadius = metrics.dotRadius.toPx(),
                haloDotRadius = metrics.haloDotRadius.toPx(),
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

/** Maps 0..1 slider fractions to a dot centre that always stays fully on screen. */
fun dotCenter(fractionX: Float, fractionY: Float, canvas: Size, margin: Float): Offset = Offset(
    x = margin + fractionX * (canvas.width - margin * 2f),
    y = margin + fractionY * (canvas.height - margin * 2f),
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
