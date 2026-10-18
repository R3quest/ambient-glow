package app.lumement

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// Arrivals that end in the frame LED. There is no one spot for a token to fly to, so nothing
// flies: light runs out of the camera both ways round the edge, laying the frame as it goes, the
// two lights meeting at the bottom. The beacon's leaves the camera as its ember fades; the Edge
// Frame's comet heads, home at the camera, turn round there and run back out as the frame's light
// ([frameRunsFromCamera]). Pulse has no heads to gather: its whole frame settles into the LED
// instead. Either way the frame is at the peak of the LED's first breath as it lands and goes out
// on its exhale, so the next breath rises in the same place. Like every effect curve, a pure
// function of the effect's clock.
// ---------------------------------------------------------------------------------------------

/**
 * How far the Edge Frame has settled into the LED, 0..1: from the heads' landing on the camera
 * ([LANDING_MS]) to the LED's peak ([HANDOFF_LANDED_MS]), where the hand-off's token would land.
 * Its own frame fades out over it.
 */
internal fun frameSettleAt(ms: Float): Float = smoothstep(LANDING_MS, HANDOFF_LANDED_MS, ms)

/**
 * The Edge Frame's light runs back out of the camera into the frame LED, as the beacon's does: its
 * comet heads (Comet, Twin) come home to the camera and the light turns round there. Pulse, with
 * no heads, settles where it is ([LedFrameSettle]).
 */
internal fun frameRunsFromCamera(settings: GlowSettings): Boolean =
    settings.style == GlowStyle.EDGE_FRAME && settings.ledOnEdge && settings.edgeMotion != EdgeMotion.PULSE

/** The Edge Frame's run leaves the camera as the heads land on it. */
internal const val EDGE_RUN_FROM_MS = LANDING_MS

/** The beacon's lights meet at the bottom as its ember is gone, as the beacon ends: the frame laid, at the LED's peak. */
internal const val FRAME_LAID_MS = ARRIVAL_MS.toFloat()

/** They run round the edge over this long, leaving as the ember starts to fade. */
internal const val FRAME_RUN_MS = 600f
internal const val FRAME_RUN_FROM_MS = FRAME_LAID_MS - FRAME_RUN_MS

/** The laid frame goes out on the LED's exhale, and the beacon's arrival ends with it ([arrivalMsFor]). */
internal const val FRAME_RUN_END_MS = FRAME_LAID_MS + LED_FALL_MS

/** Leaves the camera gathering pace, and slows as the two lights come together. */
private val RunEasing = CubicBezierEasing(0.5f, 0f, 0.3f, 1f)

/** How far round its half of the frame each light has run, 0..1, for a run leaving the camera at [from]. */
internal fun frameRunAt(ms: Float, from: Float = FRAME_RUN_FROM_MS): Float = RunEasing.transform(((ms - from) / FRAME_RUN_MS).coerceIn(0f, 1f))

/**
 * The laid frame's level, for a run leaving the camera at [from]: full as the lights meet (the
 * LED's peak), then the LED's own exhale ([ledBreathAt]).
 */
internal fun frameRunFadeAt(ms: Float, from: Float = FRAME_RUN_FROM_MS): Float {
    val laid = from + FRAME_RUN_MS
    return if (ms <= laid) 1f else ledBreathAt(LED_RISE_MS + ms - laid)
}

/** When a run leaving the camera at [from] has gone out on the LED's exhale, and its arrival ends ([arrivalMsFor]). */
internal fun frameRunEndMs(from: Float): Float = from + FRAME_RUN_MS + LED_FALL_MS

/** As they meet, the laid line becomes the whole LED, its glow and all, over this last share of the run. */
private const val FRAME_RUN_WHOLE_FROM = 0.85f

/**
 * The frame LED as an effect at [scale] lands on it, its corners as that effect draws the screen's
 * ([frameCornerPx]).
 */
private fun Density.frameLanding(settings: GlowSettings, geometry: ScreenGeometry, size: Size, scale: Float): LedLanding =
    ledLanding(settings, geometry.lens(size.width, density, scale), size, scale, geometry.frameCornerPx(density, scale))

/**
 * The Edge Frame settling into the frame LED, in its place ([LedHandOff] for the dot and the ring):
 * the LED rises as the effect's frame fades ([frameSettleAt]), and goes out on its exhale.
 */
@Composable
internal fun LedFrameSettle(settings: GlowSettings, color: Color, geometry: ScreenGeometry, scale: Float, time: () -> Float) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val led = frameLanding(settings, geometry, size, scale)
                val look = LedLook(LedMaterial.NEON, color, led.light, LedForm.FRAME, settings)
                onDrawBehind {
                    val ms = time()
                    val level = frameSettleAt(ms) * handOffFadeAt(ms)
                    // The frame doesn't move within its breath: only its level changes.
                    if (level > 0f) look.draw(this, led.center, level, ms = 0f)
                }
            },
    )
}

/**
 * Light running out of the camera round the edge into the frame LED, leaving at [startMs]: after the
 * beacon round the camera ([FRAME_RUN_FROM_MS]), or as the Edge Frame's heads come home to it
 * ([EDGE_RUN_FROM_MS]). Two lights leave the top edge above the camera, one each way, laying the
 * LED's line behind them, and meet at the bottom ([frameRunAt]), where the line becomes the whole
 * LED. Then it goes out on the LED's exhale.
 */
@Composable
internal fun LedFrameRun(settings: GlowSettings, color: Color, geometry: ScreenGeometry, scale: Float, time: () -> Float, startMs: Float = FRAME_RUN_FROM_MS) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val led = frameLanding(settings, geometry, size, scale)
                val light = led.light
                // The laid line is the LED's own line, in its colours; as it closes the whole LED
                // (its glow and all) takes over. Both are built about the origin, as the LED is.
                val look = LedLook(LedMaterial.NEON, color, light, LedForm.FRAME, settings)
                // Where the camera is along the top edge, about the LED's centre: the lights leave from there.
                val cameraX = geometry.lens(size.width, density, scale).center.x - led.center.x
                val halves = arrayOf(
                    PathMeasure().apply { setPath(frameHalf(light, Offset.Zero, cameraX, clockwise = true), false) },
                    PathMeasure().apply { setPath(frameHalf(light, Offset.Zero, cameraX, clockwise = false), false) },
                )
                val laid = Path()
                // Each light's head: a hot point in a glow of its colour, scaled to size as it is drawn.
                val headRadius = max(2.2f * light.core, light.line * 3f)
                val head = Brush.radialGradient(
                    0f to lerp(color, Color.White, 0.7f),
                    0.3f to color.copy(alpha = 0.55f),
                    1f to color.copy(alpha = 0f),
                    center = Offset.Zero,
                    radius = 1f,
                )
                onDrawBehind {
                    val ms = time()
                    val run = frameRunAt(ms, startMs)
                    if (run <= 0f) return@onDrawBehind
                    val level = frameRunFadeAt(ms, startMs)
                    if (level <= 0f) return@onDrawBehind
                    val whole = smoothstep(FRAME_RUN_WHOLE_FROM, 1f, run)
                    if (whole < 1f) {
                        // Comes out of nothing at the camera, rather than popping on.
                        val heads = min(1f, run / 0.08f) * (1f - whole)
                        for (half in halves) {
                            val length = half.length
                            laid.rewind()
                            half.getSegment(0f, length * run, laid, true)
                            look.drawFrameLine(this, led.center, laid, level * (1f - whole))
                            drawHead(head, led.center + half.getPosition(length * run), headRadius, level * heads)
                        }
                    }
                    // The frame doesn't move within its breath: only its level changes.
                    if (whole > 0f) look.draw(this, led.center, level * whole, ms = 0f)
                }
            },
    )
}

private fun DrawScope.drawHead(head: Brush, at: Offset, radius: Float, alpha: Float) {
    if (alpha <= 0f) return
    translate(at.x, at.y) { scale(radius, Offset.Zero) { drawCircle(head, 1f, Offset.Zero, alpha = alpha) } }
}

/**
 * Half the frame [light]'s line round [center]: from the top edge at [fromX] (kept off the
 * corners) round one side to the bottom edge below it, clockwise or not. Both halves end at the
 * same point, so two lights running them at the same share of their length meet there.
 */
internal fun frameHalf(light: LedLight, center: Offset, fromX: Float, clockwise: Boolean): Path {
    val w = light.frame.width
    val h = light.frame.height
    val r = min(light.corner, min(w, h) / 2f)
    val left = center.x - w / 2f
    val right = center.x + w / 2f
    val top = center.y - h / 2f
    val bottom = center.y + h / 2f
    val x = fromX.coerceIn(left + r, right - r)
    return Path().apply {
        moveTo(x, top)
        if (clockwise) {
            lineTo(right - r, top)
            if (r > 0f) arcTo(Rect(right - 2f * r, top, right, top + 2f * r), -90f, 90f, false)
            lineTo(right, bottom - r)
            if (r > 0f) arcTo(Rect(right - 2f * r, bottom - 2f * r, right, bottom), 0f, 90f, false)
        } else {
            lineTo(left + r, top)
            if (r > 0f) arcTo(Rect(left, top, left + 2f * r, top + 2f * r), -90f, -90f, false)
            lineTo(left, bottom - r)
            if (r > 0f) arcTo(Rect(left, bottom - 2f * r, left + 2f * r, bottom), 180f, -90f, false)
        }
        lineTo(x, bottom)
    }
}
