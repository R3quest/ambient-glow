package com.example.ambientglow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

// ---------------------------------------------------------------------------------------------
// Timeline. Everything is a pure function of one clock (ms since the effect started), so a frame
// reads one value and allocates nothing.
// ---------------------------------------------------------------------------------------------

/** Whole effect. Ends before the LED takes the lock screen back (2.5 s after the wake). */
private const val ARRIVAL_MS = 2_300

/** The spawn wave's trip from the camera to past the farthest corner. */
private const val SPAWN_MS = 950f

/** Without the spawn, the style simply fades in. */
private const val FADE_IN_MS = 250f
private const val FADE_OUT_MS = 450f

/** With the spawn, the edge motion settles in once the wave has lit the whole frame. */
private const val MOTION_IN_AT_MS = 650f
private const val MOTION_IN_MS = 350f

/** Origin flash: quick rise, slower decay. */
private const val BLOOM_RISE_MS = 120f
private const val BLOOM_DECAY_MS = 600f

/** Burst, then a long glide: the wave races out of the camera and slows as it spreads. */
private val SpawnEasing = CubicBezierEasing(0.12f, 0.6f, 0.2f, 1f)

/** Wave radius as a fraction of its reach: 0 at the camera, 1 past the farthest corner. */
private fun waveAt(ms: Float, spawn: Boolean): Float =
    if (spawn) SpawnEasing.transform((ms / SPAWN_MS).coerceIn(0f, 1f)) else 1f

/** Overall opacity: fade in (only without the spawn, whose wave does the revealing) and out. */
private fun levelAt(ms: Float, spawn: Boolean): Float {
    val fadeIn = if (spawn) 1f else (ms / FADE_IN_MS).coerceIn(0f, 1f)
    val fadeOut = ((ARRIVAL_MS - ms) / FADE_OUT_MS).coerceIn(0f, 1f)
    return minOf(fadeIn, fadeOut)
}

/** How far the edge motion (comet mask, pulse) has taken over from the plain lit frame. */
private fun motionAt(ms: Float, spawn: Boolean): Float =
    if (spawn) ((ms - MOTION_IN_AT_MS) / MOTION_IN_MS).coerceIn(0f, 1f) else 1f

private fun bloomAt(ms: Float): Float {
    val rise = (ms / BLOOM_RISE_MS).coerceIn(0f, 1f)
    val decay = 1f - ((ms - BLOOM_RISE_MS) / BLOOM_DECAY_MS).coerceIn(0f, 1f)
    return rise * decay * decay
}

// ---------------------------------------------------------------------------------------------
// Shapes and sizes (full screen; the dashboard preview passes a scale)
// ---------------------------------------------------------------------------------------------

/** The wave's reach past the farthest corner, so its bright rim leaves the screen before it fades. */
private const val WAVE_OVERSHOOT = 1.15f

/** Below this wave scale nothing is drawn (the wave is still inside the camera). */
private const val MIN_WAVE = 0.02f

private val BLOOM_RADIUS = 150.dp
private val RIPPLE_STROKE = 2.dp
private val RING_RIPPLE_SPAN = 54.dp
private val DOT_RIPPLE_SPAN = 42.dp
private val BEACON_GLOW = 26.dp

/** Wave progress spent igniting a beacon once its rim arrives; every beacon finishes by wave 1. */
private const val IGNITE_WAVE = 0.06f

private const val RIPPLE_COUNT = 3
private const val RIPPLE_PERIOD_MS = 1_100f

private const val PULSE_BREATHS = 2
private const val PULSE_DEPTH = 0.4f
private const val COMET_TURNS = 1.2f
private const val TWIN_TURNS = 0.7f
private const val DUO_TURN_DEGREES = 160f
private const val SPECTRUM_TURN_DEGREES = 360f
private const val DUO_HUE_SHIFT = 55f

/** Comet mask brightness away from the heads: the frame stays faintly lit behind them. */
private const val COMET_BASE = 0.15f

/**
 * The new-message effect, once, then [onDone]. Drawn over the lock screen by [GlowShield], on
 * the black panel by the glow screen, and looped in the dashboard preview (with [scale] < 1).
 *
 * With [GlowSettings.spawn], an AirDrop-style wave bursts out of the camera, washes across the
 * screen, and lights the chosen style as its rim passes. Then the style plays: the Edge Frame in
 * its chosen motion and colours, the Camera Ring and Custom Dot as a beacon sending out ripples.
 */
@Composable
fun ArrivalEffect(
    settings: GlowSettings,
    color: Int,
    geometry: ScreenGeometry,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
) {
    val clock = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    // A signal, not decoration: keep its timing even with animations scaled down or off.
    LaunchedEffect(Unit) {
        withContext(RealTimeMotion) {
            coroutineScope {
                val trace = launch { traceFrames(settings, preview = scale < 1f) }
                clock.animateTo(ARRIVAL_MS.toFloat(), tween(ARRIVAL_MS, easing = LinearEasing))
                trace.cancel()
            }
        }
        done()
    }
    val time = remember(clock) { { clock.value } }
    val glow = Color(color)
    Box(modifier.fillMaxSize()) {
        if (settings.spawn) SpawnWave(glow, geometry, scale, time)
        when (settings.style) {
            GlowStyle.EDGE_FRAME -> EdgeArrival(settings, glow, geometry, scale, time)
            GlowStyle.CAMERA_RING, GlowStyle.CUSTOM_DOT -> BeaconArrival(settings, glow, geometry, scale, time)
        }
    }
}

/** A frame gap above this counts as dropped (120 Hz panels give 8.3 ms, 60 Hz 16.7 ms). */
private const val SLOW_FRAME_NS = 20_000_000L

/**
 * Debug trace (`adb logcat -s AmbientGlow`): how smoothly one effect played. Counts frame gaps
 * long enough to be a visible stutter. Only counters per frame; the log call is stripped from
 * release builds.
 */
private suspend fun traceFrames(settings: GlowSettings, preview: Boolean) {
    var last = 0L
    var first = 0L
    var frames = 0
    var dropped = 0
    var worst = 0L
    try {
        while (true) {
            withFrameNanos { now ->
                if (last == 0L) first = now else {
                    val gap = now - last
                    if (gap > SLOW_FRAME_NS) dropped++
                    if (gap > worst) worst = gap
                    frames++
                }
                last = now
            }
        }
    } finally {
        val seconds = (last - first) / 1e9f
        val what = if (settings.style == GlowStyle.EDGE_FRAME) {
            "${settings.style} ${settings.edgeMotion} ${settings.edgeColor} ${settings.edgeWidth} ${settings.edgeGlow}"
        } else {
            settings.style.name
        }
        GlowLog.d(
            "effect ${if (preview) "preview" else "live"} $what spawn=${settings.spawn}: " +
                "${if (seconds > 0f) (frames / seconds).toInt() else 0} fps, $dropped stutters, worst ${worst / 1_000_000} ms",
        )
    }
}

/** Where the wave comes from: the punch-hole camera, or the top centre where it usually is. */
private fun CacheDrawScope.spawnOrigin(geometry: ScreenGeometry, scale: Float): Offset =
    geometry.cutout?.let { Offset(it.centerX, it.centerY) }
        ?: Offset(size.width / 2f, GlowMetrics.FullScreen.fallbackCameraCenterY.toPx() * scale)

private fun CacheDrawScope.waveReach(origin: Offset): Float =
    hypot(max(origin.x, size.width - origin.x), max(origin.y, size.height - origin.y)) * WAVE_OVERSHOOT

/**
 * A gradient shader built once and reused at every size. Compose rebuilds a gradient brush
 * whenever the drawn size changes; the wave masks change size every frame.
 */
private class FixedShaderBrush(private val shader: Shader) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}

private fun fixedRadial(center: Offset, radius: Float, vararg stops: Pair<Float, Color>): Brush =
    FixedShaderBrush(
        RadialGradientShader(
            center = center,
            radius = radius,
            colors = stops.map { it.second },
            colorStops = stops.map { it.first },
        ),
    )

/**
 * A gradient that moves through its local matrix while the shape it paints stays put, so the
 * Edge Frame's masks only ever touch the frame band instead of the whole screen. Every draw of
 * it sits between solid-colour draws, so the paint re-reads the moved shader each time.
 */
private class MovingShaderBrush(private val shader: Shader) : ShaderBrush() {
    private val matrix = android.graphics.Matrix()

    override fun createShader(size: Size): Shader = shader

    fun rotateTo(degrees: Float, pivot: Offset) {
        matrix.setRotate(degrees, pivot.x, pivot.y)
        shader.setLocalMatrix(matrix)
    }

    fun scaleTo(scale: Float, pivot: Offset) {
        matrix.setScale(scale, scale, pivot.x, pivot.y)
        shader.setLocalMatrix(matrix)
    }
}

private fun movingRadial(center: Offset, radius: Float, vararg stops: Pair<Float, Color>) =
    MovingShaderBrush(
        RadialGradientShader(
            center = center,
            radius = radius,
            colors = stops.map { it.second },
            colorStops = stops.map { it.first },
        ),
    )

private fun movingSweep(center: Offset, vararg stops: Pair<Float, Color>) =
    MovingShaderBrush(
        SweepGradientShader(
            center = center,
            colors = stops.map { it.second },
            colorStops = stops.map { it.first },
        ),
    )

/**
 * Fills exactly the canvas with [brush] scaled by [s] around [pivot]: the wave masks grow by
 * scaling one fixed gradient. The rect is the canvas's pre-image, so nothing off screen is drawn.
 */
private fun DrawScope.fillScaled(
    brush: Brush,
    s: Float,
    pivot: Offset,
    alpha: Float = 1f,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
) {
    val canvas = size
    scale(s, pivot) {
        drawRect(
            brush = brush,
            topLeft = Offset(pivot.x - pivot.x / s, pivot.y - pivot.y / s),
            size = Size(canvas.width / s, canvas.height / s),
            alpha = alpha,
            blendMode = blendMode,
        )
    }
}

/**
 * The AirDrop part: a flash at the camera and a wide ring of light, brand colour with a hot
 * white rim, that washes over the whole screen and fades as it reaches the bottom.
 */
@Composable
private fun SpawnWave(color: Color, geometry: ScreenGeometry, scale: Float, time: () -> Float) {
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val origin = spawnOrigin(geometry, scale)
                val reach = waveReach(origin)
                val rim = lerp(color, Color.White, 0.7f)
                val wash = fixedRadial(
                    origin,
                    reach,
                    0f to Color.Transparent,
                    0.62f to Color.Transparent,
                    0.86f to color.copy(alpha = 0.30f),
                    0.95f to rim.copy(alpha = 0.55f),
                    1f to Color.Transparent,
                )
                val bloomRadius = BLOOM_RADIUS.toPx() * scale
                val bloom = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.95f),
                    0.18f to lerp(color, Color.White, 0.5f).copy(alpha = 0.75f),
                    0.5f to color.copy(alpha = 0.3f),
                    1f to Color.Transparent,
                    center = origin,
                    radius = bloomRadius,
                )
                onDrawBehind {
                    val ms = time()
                    val wave = waveAt(ms, spawn = true)
                    val linear = (ms / SPAWN_MS).coerceIn(0f, 1f)
                    if (wave >= MIN_WAVE && linear < 1f) {
                        fillScaled(wash, wave, origin, alpha = 1f - linear * linear)
                    }
                    val flash = bloomAt(ms)
                    if (flash > 0f) {
                        scale(0.35f + 0.65f * wave, pivot = origin) {
                            drawCircle(bloom, bloomRadius, origin, alpha = flash)
                        }
                    }
                }
            },
    )
}

/**
 * The Edge Frame, drawn in one offscreen layer: glow and line, recoloured (Duo, Spectrum),
 * brightened where the spawn wave's rim is, cut to what the wave has reached, then cut to the
 * comet heads. Every recolour and mask is painted only over the band the frame occupies (a
 * full-screen pass each was too much for the GPU at 120 Hz), with its gradient moved in place.
 */
@Composable
private fun EdgeArrival(settings: GlowSettings, color: Color, geometry: ScreenGeometry, scale: Float, time: () -> Float) {
    val spawn = settings.spawn
    val motion = settings.edgeMotion
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                val ms = time()
                alpha = levelAt(ms, spawn) * if (motion == EdgeMotion.PULSE) pulseAt(ms, spawn) else 1f
            }
            .drawWithCache {
                val line = settings.edgeWidth.stroke.toPx() * scale
                val inset = line / 2f
                val corner = geometry.cornerRadiusPx ?: (GlowMetrics.FullScreen.fallbackCornerRadius.toPx() * scale)
                val frameTopLeft = Offset(inset, inset)
                val frameSize = Size(size.width - line, size.height - line)
                val frameCorner = CornerRadius((corner - inset).coerceAtLeast(0f))
                val core = Stroke(line)
                val hot = Stroke(line * 0.4f)
                val halos = settings.edgeGlow.layers.map { (extra, alpha) -> Stroke(line + extra.toPx() * scale) to alpha }
                // Covers every pixel the frame and its glow can light (half of it is off screen).
                val band = Stroke(line + (halos.maxOfOrNull { it.first.width - line } ?: 0f) + 2f)
                // Tinted modes draw white and recolour it afterwards with SrcIn.
                val paint = if (settings.edgeColor == EdgeColor.APP) color else Color.White
                val tint = edgeTint(settings.edgeColor, color)
                val heads = cometMask(motion)
                val center = Offset(size.width / 2f, size.height / 2f)

                val origin = spawnOrigin(geometry, scale)
                val reach = waveReach(origin)
                val reveal = movingRadial(
                    origin,
                    reach,
                    0f to Color.White,
                    0.86f to Color.White,
                    0.96f to Color.Transparent,
                )
                val rim = movingRadial(
                    origin,
                    reach,
                    0f to Color.Transparent,
                    0.78f to Color.Transparent,
                    0.9f to Color.White.copy(alpha = 0.9f),
                    0.96f to Color.Transparent,
                )
                onDrawBehind {
                    val ms = time()
                    val wave = waveAt(ms, spawn)
                    if (wave < MIN_WAVE) return@onDrawBehind
                    for (i in halos.indices) {
                        val (stroke, alpha) = halos[i]
                        drawRoundRect(paint, frameTopLeft, frameSize, frameCorner, style = stroke, alpha = alpha)
                    }
                    drawRoundRect(paint, frameTopLeft, frameSize, frameCorner, style = core)
                    if (tint != null) {
                        tint.rotateTo(tintTurnAt(ms, settings.edgeColor), center)
                        drawRoundRect(tint, frameTopLeft, frameSize, frameCorner, style = band, blendMode = BlendMode.SrcIn)
                    }
                    drawRoundRect(Color.White, frameTopLeft, frameSize, frameCorner, style = hot, alpha = 0.5f)
                    if (spawn && wave < 1f) {
                        rim.scaleTo(wave, origin)
                        drawRoundRect(rim, frameTopLeft, frameSize, frameCorner, style = band, blendMode = BlendMode.SrcAtop)
                        reveal.scaleTo(wave, origin)
                        drawRoundRect(reveal, frameTopLeft, frameSize, frameCorner, style = band, blendMode = BlendMode.DstIn)
                    }
                    if (heads != null) {
                        // The mask holds 1 - brightness and is laid on with DstOut, so drawing it
                        // at partial alpha blends from the fully lit frame into the comets.
                        val strength = motionAt(ms, spawn)
                        if (strength > 0f) {
                            heads.rotateTo(headAt(ms, motion), center)
                            drawRoundRect(
                                heads,
                                frameTopLeft,
                                frameSize,
                                frameCorner,
                                alpha = strength,
                                style = band,
                                blendMode = BlendMode.DstOut,
                            )
                        }
                    }
                }
            },
    )
}

/** [EdgeMotion.PULSE]: lit frame, then breathes [PULSE_BREATHS] times. */
private fun pulseAt(ms: Float, spawn: Boolean): Float {
    val start = if (spawn) MOTION_IN_AT_MS else 0f
    val x = ((ms - start) / (ARRIVAL_MS - start)).coerceIn(0f, 1f)
    return 1f - PULSE_DEPTH * (1f - cos(2f * PI.toFloat() * PULSE_BREATHS * x)) / 2f
}

/** Comet heads start at the top centre, where the spawn wave came from, and run clockwise. */
private fun headAt(ms: Float, motion: EdgeMotion): Float {
    val turns = if (motion == EdgeMotion.TWIN) TWIN_TURNS else COMET_TURNS
    return -90f + 360f * turns * (ms / ARRIVAL_MS)
}

private fun tintTurnAt(ms: Float, mode: EdgeColor): Float {
    val degrees = if (mode == EdgeColor.SPECTRUM) SPECTRUM_TURN_DEGREES else DUO_TURN_DEGREES
    return -90f + degrees * (ms / ARRIVAL_MS)
}

/** Sweep gradient that recolours the frame, or null for the plain brand colour. */
private fun CacheDrawScope.edgeTint(mode: EdgeColor, color: Color): MovingShaderBrush? {
    val center = Offset(size.width / 2f, size.height / 2f)
    return when (mode) {
        EdgeColor.APP -> null
        EdgeColor.DUO -> {
            val partner = shiftHue(color, DUO_HUE_SHIFT)
            movingSweep(center, 0f to color, 0.25f to partner, 0.5f to color, 0.75f to partner, 1f to color)
        }
        EdgeColor.SPECTRUM -> {
            // Six hue steps starting at the brand colour, vivid even for a muted brand.
            val hues = Array(7) { (it / 6f) to shiftHue(color, it * 60f, minSaturation = 0.75f) }
            movingSweep(center, *hues)
        }
    }
}

private fun shiftHue(color: Color, degrees: Float, minSaturation: Float = 0f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees) % 360f
    hsv[1] = max(hsv[1], minSaturation)
    if (minSaturation > 0f) hsv[2] = 1f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Comet heads as a sweep gradient of 1 - brightness: head at angle 0, tail fading in behind it
 * (counter-clockwise, since the heads run clockwise). Null for [EdgeMotion.PULSE].
 */
private fun CacheDrawScope.cometMask(motion: EdgeMotion): MovingShaderBrush? {
    val center = Offset(size.width / 2f, size.height / 2f)
    fun dark(brightness: Float) = Color.Black.copy(alpha = 1f - brightness)
    return when (motion) {
        EdgeMotion.PULSE -> null
        EdgeMotion.COMET -> movingSweep(
            center,
            0f to dark(1f),
            0.01f to dark(COMET_BASE),
            0.55f to dark(COMET_BASE),
            0.8f to dark(0.4f),
            1f to dark(1f),
        )
        EdgeMotion.TWIN -> movingSweep(
            center,
            0f to dark(1f),
            0.01f to dark(COMET_BASE),
            0.28f to dark(COMET_BASE),
            0.4f to dark(0.4f),
            0.5f to dark(1f),
            0.51f to dark(COMET_BASE),
            0.78f to dark(COMET_BASE),
            0.9f to dark(0.4f),
            1f to dark(1f),
        )
    }
}

/**
 * Camera Ring and Custom Dot: a beacon that ignites when the spawn wave reaches it (or fades in
 * without the spawn), then sends out sonar ripples until the effect ends.
 */
@Composable
private fun BeaconArrival(settings: GlowSettings, color: Color, geometry: ScreenGeometry, scale: Float, time: () -> Float) {
    val spawn = settings.spawn
    val ring = settings.style == GlowStyle.CAMERA_RING
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val metrics = GlowMetrics.FullScreen
                val line = metrics.stroke.toPx() * scale
                val center: Offset
                val radius: Float
                if (ring) {
                    val spot = geometry.cutout
                    center = spot?.let { Offset(it.centerX, it.centerY) }
                        ?: Offset(size.width / 2f, metrics.fallbackCameraCenterY.toPx() * scale)
                    val lens = spot?.radius ?: (metrics.fallbackCameraRadius.toPx() * scale)
                    radius = lens + (metrics.ringGap.toPx() + metrics.stroke.toPx() / 2f) * scale
                } else {
                    radius = settings.dotSize.radius.toPx() * scale
                    center = dotCenter(settings.dotX, settings.dotY, size, radius * DOT_HALO_FACTOR)
                }
                val origin = spawnOrigin(geometry, scale)
                val reach = waveReach(origin)
                // Ignites as the wave's bright rim (~0.92 of its radius) reaches the beacon.
                val arriveAt = ((center - origin).getDistance() / (reach * 0.92f)).coerceAtMost(1f - IGNITE_WAVE)
                val span = (if (ring) RING_RIPPLE_SPAN else DOT_RIPPLE_SPAN).toPx() * scale
                val glowRadius = radius + BEACON_GLOW.toPx() * scale
                val inner = (radius / glowRadius).coerceIn(0f, 0.9f)
                val glow = if (ring) {
                    Brush.radialGradient(
                        0f to Color.Transparent,
                        inner * 0.6f to Color.Transparent,
                        inner to color.copy(alpha = 0.45f),
                        1f to Color.Transparent,
                        center = center,
                        radius = glowRadius,
                    )
                } else {
                    Brush.radialGradient(
                        0f to color.copy(alpha = 0.65f),
                        0.35f to color.copy(alpha = 0.22f),
                        1f to Color.Transparent,
                        center = center,
                        radius = glowRadius,
                    )
                }
                val hot = lerp(color, Color.White, 0.45f)
                val core = Stroke(line)
                val hotLine = Stroke(line * 0.4f)
                val ripple = Stroke(RIPPLE_STROKE.toPx() * scale)
                onDrawBehind {
                    val ms = time()
                    val lit = if (spawn) {
                        ((waveAt(ms, spawn = true) - arriveAt) / IGNITE_WAVE).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    val level = levelAt(ms, spawn) * lit
                    if (level <= 0f) return@onDrawBehind
                    // Brief flare as it ignites: the glow swells past full size, then settles.
                    val flare = 1f + 0.6f * (1f - lit)
                    scale(flare, center) { drawCircle(glow, glowRadius, center, alpha = level) }
                    for (i in 0 until RIPPLE_COUNT) {
                        val phase = ms / RIPPLE_PERIOD_MS + i / RIPPLE_COUNT.toFloat()
                        val p = phase - phase.toInt()
                        val fade = (1f - p) * (1f - p)
                        drawCircle(color, radius + p * span, center, alpha = level * fade * 0.8f, style = ripple)
                    }
                    if (ring) {
                        drawCircle(color, radius, center, alpha = level, style = core)
                        drawCircle(hot, radius, center, alpha = level * 0.7f, style = hotLine)
                    } else {
                        drawCircle(color, radius, center, alpha = level)
                        drawCircle(hot, radius * 0.5f, center, alpha = level)
                    }
                }
            },
    )
}
