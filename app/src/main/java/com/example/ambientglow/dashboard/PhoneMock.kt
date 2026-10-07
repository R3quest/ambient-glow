package com.example.ambientglow.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ambientglow.CutoutSpot
import com.example.ambientglow.DotSize
import com.example.ambientglow.ScreenGeometry
import com.example.ambientglow.ledRadiusAt
import com.example.ambientglow.ui.theme.GlowPalette
import com.example.ambientglow.ui.theme.GlowShapes

// ---------------------------------------------------------------------------------------------
// Phone mock-ups, with this phone's real camera drawn where it is.
// ---------------------------------------------------------------------------------------------

/**
 * Camera position as screen fractions (radius as a fraction of screen width). Without a
 * punch-hole ([present] false) it is where one usually is, so top spots still have a centre.
 */
@Immutable
internal data class ScreenCamera(val x: Float, val y: Float, val radius: Float, val present: Boolean = true)

internal val LocalCamera = staticCompositionLocalOf { ScreenCamera(x = 0.5f, y = 0.03f, radius = 0.03f) }

@Composable
internal fun rememberCamera(geometry: ScreenGeometry): ScreenCamera {
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(geometry, window, density) {
        val w = window.width.toFloat().coerceAtLeast(1f)
        val h = window.height.toFloat().coerceAtLeast(1f)
        val lens = geometry.lens(w, density.density)
        ScreenCamera(x = lens.centerX / w, y = lens.centerY / h, radius = lens.radius / w, present = !geometry.noCamera)
    }
}

/**
 * A minimal handset silhouette: black panel, hairline bezel and this phone's punch-hole
 * camera drawn where it really is. [content] receives that camera scaled into the mock-up,
 * so Camera Ring previews circle the drawn lens.
 */
@Composable
internal fun PhoneMock(
    modifier: Modifier = Modifier,
    shape: Shape = GlowShapes.Phone,
    content: @Composable BoxScope.(ScreenGeometry) -> Unit,
) {
    val camera = LocalCamera.current
    val minRadius = with(LocalDensity.current) { 1.5.dp.toPx() }
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(GlowPalette.Void)
            .border(1.dp, GlowPalette.Outline, shape),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val lens = remember(camera, w, h) {
            CutoutSpot(centerX = camera.x * w, centerY = camera.y * h, radius = (camera.radius * w).coerceAtLeast(minRadius))
        }
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(GlowPalette.SurfaceHighest, radius = lens.radius, center = Offset(lens.centerX, lens.centerY))
        }
        content(remember(lens) { ScreenGeometry(cutout = lens, cornerRadiusPx = null) })
    }
}

/** How much smaller a style tile's mini phone is than the screen. */
internal const val TILE_PREVIEW_SCALE = 0.22f

/** The real dot scaled down to a mock-up, kept just large enough to see. */
internal fun previewDotRadius(size: DotSize, scale: Float): Dp = ledRadiusAt(size, scale)
