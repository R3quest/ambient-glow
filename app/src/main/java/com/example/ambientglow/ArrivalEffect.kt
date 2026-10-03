package com.example.ambientglow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Timeline. Everything is a pure function of one clock (ms since the effect started), so a frame
// reads one value and allocates nothing.
// ---------------------------------------------------------------------------------------------

/** Whole effect. Ends before the LED takes the lock screen back (2.5 s after the wake). */
private const val ARRIVAL_MS = 2_300

/** Light pools in the camera before the wave is released: the flash is the cause, the wave its effect. */
internal const val SPAWN_GATHER_MS = 110f

/** The spawn wave's trip from the camera to past the farthest corner, after the gather. */
internal const val SPAWN_MS = 950f

/** Without the spawn, the style simply fades in. */
private const val FADE_IN_MS = 250f

/** The closing fade, shared by every style; ends exactly at [ARRIVAL_MS]. */
private const val FADE_OUT_MS = 600f
private const val FADE_OUT_AT_MS = ARRIVAL_MS - FADE_OUT_MS

/** With the spawn, the edge motion settles in once the wave has lit the whole frame. */
private const val MOTION_IN_AT_MS = SPAWN_GATHER_MS + 700f
private const val MOTION_IN_MS = 350f

/** Flash peaks this long after release, as the ring leaves it. */
private const val BLOOM_PEAK_MS = 40f
private const val BLOOM_DECAY_MS = 700f

/**
 * Bursts out of the camera, gathers speed for a few frames, then glides out: fast enough to read
 * as a release, slow enough to read as motion at 120 Hz.
 */
private val SpawnEasing = CubicBezierEasing(0.2f, 0.45f, 0.2f, 1f)

/** Hermite ease from 0 at [e0] to 1 at [e1]. */
internal fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** The spawn's linear progress, 0 at release (after the gather) to 1 at the end of its trip. */
internal fun spawnLinearAt(ms: Float): Float = ((ms - SPAWN_GATHER_MS) / SPAWN_MS).coerceIn(0f, 1f)

/**
 * The spawn's energy: full while the crest crosses, then dissipating; the light and the blur
 * under it share it, so they end together.
 */
internal fun spawnFadeAt(ms: Float): Float = 1f - smoothstep(0.4f, 0.85f, spawnLinearAt(ms))

/** Wave radius as a fraction of its reach: 0 at the camera (through the gather), 1 past the farthest corner. */
private fun waveAt(ms: Float, spawn: Boolean): Float =
    if (spawn) SpawnEasing.transform(spawnLinearAt(ms)) else 1f

/** The spawn wave's radius as a fraction of its reach, at [ms] into the effect. */
internal fun spawnWaveAt(ms: Float): Float = waveAt(ms, spawn = true)

/** When the spawn wave reaches [fraction] of its reach (inverse of [waveAt]); cache-time only. */
private fun spawnMsAt(fraction: Float): Float {
    var lo = SPAWN_GATHER_MS
    var hi = SPAWN_GATHER_MS + SPAWN_MS
    repeat(24) {
        val mid = (lo + hi) / 2f
        if (waveAt(mid, spawn = true) < fraction) lo = mid else hi = mid
    }
    return hi
}

/** The closing fade: zero slope at both ends, so no kink where it starts or stops. */
private fun fadeOutAt(ms: Float): Float = 1f - smoothstep(FADE_OUT_AT_MS, ARRIVAL_MS.toFloat(), ms)

/**
 * Overall opacity: an eased fade in (only without the spawn, whose wave does the revealing) and
 * [fadeOutAt]. Beacons ignite instead of fading in, and shape the fade out: the halo draws in and
 * the core lingers as an ember where the LED will blink.
 */
private fun levelAt(ms: Float, spawn: Boolean): Float {
    val fadeIn = if (spawn) 1f else LinearOutSlowInEasing.transform((ms / FADE_IN_MS).coerceIn(0f, 1f))
    return minOf(fadeIn, fadeOutAt(ms))
}

/** How far the edge motion (comet mask, pulse) has taken over from the plain lit frame. */
private fun motionAt(ms: Float, spawn: Boolean): Float =
    if (spawn) smoothstep(MOTION_IN_AT_MS, MOTION_IN_AT_MS + MOTION_IN_MS, ms) else 1f

/** Glide for the comet heads and tints: leaves at 1.5x the average pace, ends at 0.5x, never stalls. */
private fun glideAt(x: Float): Float = 1.5f * x - 0.5f * x * x

/** Origin flash: pools in (ease-in) during the gather, peaks just after release, then a long cubic decay. */
private fun bloomAlphaAt(ms: Float): Float {
    if (ms < SPAWN_GATHER_MS) {
        val g = ms / SPAWN_GATHER_MS
        return 0.55f * g * g
    }
    val t = ms - SPAWN_GATHER_MS
    val r = (t / BLOOM_PEAK_MS).coerceIn(0f, 1f)
    val x = 1f - ((t - BLOOM_PEAK_MS) / BLOOM_DECAY_MS).coerceIn(0f, 1f)
    return (0.55f + 0.45f * r * (2f - r)) * x * x * x
}

/** Flash size: contracts slightly as it gathers (an inhale), then grows with the wave. */
private fun bloomScaleAt(ms: Float, wave: Float): Float =
    if (ms < SPAWN_GATHER_MS) {
        val g = ms / SPAWN_GATHER_MS
        0.34f - 0.08f * g * g
    } else {
        0.26f + 0.74f * wave
    }

// ---------------------------------------------------------------------------------------------
// Shapes and sizes (full screen; the dashboard preview passes a scale)
// ---------------------------------------------------------------------------------------------

/** The wave's reach past the farthest corner, so its bright rim leaves the screen before it fades. */
private const val WAVE_OVERSHOOT = 1.15f

/** Below this wave scale nothing is drawn (the wave is still inside the camera). */
internal const val MIN_WAVE = 0.02f

private val BLOOM_RADIUS = 150.dp
private val RIPPLE_STROKE = 2.dp
private val RING_RIPPLE_SPAN = 54.dp
private val DOT_RIPPLE_SPAN = 42.dp
private val BEACON_GLOW = 26.dp

/**
 * Where the wave's bright crest sits, as a fraction of its reach (the wash rim, the glass crest).
 * The frame and beacons light under it.
 */
private const val CREST_AT = 0.94f

/** Beacon ignition, on its own clock from the moment the wave's crest reaches it. */
private const val IGNITE_MS = 140f
private const val FLARE_RISE_MS = 40f
private const val FLARE_TAU_MS = 160f
private const val FLARE_GLOW = 0.45f

/** Damped spring the core settles with: overshoot at ignition, a slight undershoot, then still. */
private const val SETTLE_TAU_MS = 110f
private const val SETTLE_PERIOD_MS = 420f
private const val RING_SETTLE = 0.35f
private const val DOT_SETTLE = 0.25f

/** The ring sits inside the origin flash; it lights as the flash peaks (its flare just after) rather than under it. */
private const val RING_IGNITE_MS = SPAWN_GATHER_MS + BLOOM_PEAK_MS - 30f

/**
 * Ripples: a strong ring and a softer echo, a pause, then the pair again; timed from the
 * ignition. The echo follows like the glass wave's second ripple, so they read as one language.
 */
private val RIPPLE_AT_MS = floatArrayOf(0f, 170f, 900f, 1_070f)
private val RIPPLE_STRENGTH = floatArrayOf(1f, 0.55f, 0.8f, 0.45f)
private const val RIPPLE_LIFE_MS = 950f

/** How far past the span a ripple coasts, and how long (of its life) it takes to fade in from the core. */
private const val RIPPLE_REACH = 1.15f
private const val RIPPLE_BIRTH = 0.08f

/** Pulse: exhale, quicker inhale, final exhale, as shares of the breathing window, which ends under the fade. */
private const val PULSE_END_MS = 2_050f
private const val PULSE_EXHALE = 0.36f
private const val PULSE_INHALE = 0.27f

/** How far round the frame the heads glide with the spawn: entering on the right, ending home. */
private const val COMET_TRAVEL = 0.72f
private const val TWIN_TRAVEL = 0.45f
private const val DUO_TURN_DEGREES = 160f
private const val SPECTRUM_TURN_DEGREES = 360f
private const val DUO_HUE_SHIFT = 55f

/** Glass wave: inner edge of the band it lights, as a fraction of the reach; inside it is clear. */
private const val GLASS_BAND_INNER = 0.5f

/** Glass wave: how far the shimmer along the crest drifts round during the spawn. */
private const val GLASS_SHIMMER_DEGREES = 50f

/**
 * Glass wave: the fainter ripple that follows the crest, like the second ring on water. It rides
 * [GLASS_ECHO_GAP] of the crest's radius behind it, so the two spread apart as they travel, like
 * a real ripple train.
 */
private const val GLASS_ECHO_GAP = 0.085f
private const val GLASS_ECHO_ALPHA = 0.35f
private const val GLASS_ECHO_TURN = 35f

/** Comet mask brightness away from the heads: the frame stays faintly lit behind them. */
private const val COMET_BASE = 0.15f

/**
 * The new-message effect, once, then [onDone]. Drawn over the lock screen by [GlowShield], on
 * the black panel by the glow screen, and looped in the dashboard preview (with [scale] < 1).
 *
 * With [GlowSettings.spawn], an AirDrop-style wave bursts out of the camera, washes across the
 * screen, and lights the chosen style as its rim passes. Then the style plays: the Edge Frame in
 * its chosen motion and colours, the Camera Ring and Custom Dot as a beacon sending out ripples.
 *
 * With [GlowSettings.glass] too, [onBlurBehind] is told every frame of the wave how blurred the
 * screen under it should be and where ([GlassHazeTarget]), and 0 when the effect ends or is
 * dropped. The effect can't blur what is under it; whoever owns that screen does.
 */
@Composable
fun ArrivalEffect(
    settings: GlowSettings,
    color: Int,
    geometry: ScreenGeometry,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    onBlurBehind: GlassHazeTarget? = null,
) {
    val clock = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    val blur = onBlurBehind.takeIf { settings.hazes }
    // A signal, not decoration: keep its timing even with animations scaled down or off.
    LaunchedEffect(Unit) {
        try {
            withContext(RealTimeMotion) {
                coroutineScope {
                    val trace = launch { traceFrames(settings, preview = scale < 1f) }
                    val haze = blur?.let { launch { followHaze(it, settings.glassArea) { clock.value } } }
                    clock.animateTo(ARRIVAL_MS.toFloat(), tween(ARRIVAL_MS, easing = LinearEasing))
                    trace.cancel()
                    haze?.cancel()
                }
            }
        } finally {
            blur?.haze(0f, 0f)
        }
        done()
    }
    val time = remember(clock) { { clock.value } }
    val glow = Color(color)
    Box(modifier.fillMaxSize()) {
        if (settings.spawn) SpawnWave(glow, settings, geometry, scale, time)
        when (settings.style) {
            GlowStyle.EDGE_FRAME -> EdgeArrival(settings, glow, geometry, scale, time)
            GlowStyle.CAMERA_RING, GlowStyle.CUSTOM_DOT -> BeaconArrival(settings, glow, geometry, scale, time)
        }
    }
}

/** Tells [target] the glass wave's blur every frame until it has risen and cleared (the last call sends 0). */
private suspend fun followHaze(target: GlassHazeTarget, area: GlassArea, time: () -> Float) {
    val end = hazeEndMs(area)
    var seen = false
    while (true) {
        val ms = withFrameNanos { time() }
        val level = hazeAt(ms, area)
        target.haze(level, spawnWaveAt(ms))
        if (level > 0f) seen = true else if (seen) return
        if (ms >= end) return
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
            "effect ${if (preview) "preview" else "live"} $what spawn=${settings.spawn} " +
                "glass=${settings.glass}/${settings.glassBlur}/${settings.glassArea}/${settings.glassFrost}: " +
                "${if (seconds > 0f) (frames / seconds).toInt() else 0} fps, $dropped stutters, worst ${worst / 1_000_000} ms",
        )
    }
}

/** Where the wave comes from: the punch-hole camera, or the top centre where it usually is. */
internal fun waveOrigin(geometry: ScreenGeometry, width: Float, density: Float, scale: Float): Offset =
    geometry.cutout?.let { Offset(it.centerX, it.centerY) }
        ?: Offset(width / 2f, GlowMetrics.FullScreen.fallbackCameraCenterY.value * density * scale)

/** The wave's full radius: past the farthest corner from [origin]. */
internal fun waveReach(origin: Offset, width: Float, height: Float): Float =
    hypot(max(origin.x, width - origin.x), max(origin.y, height - origin.y)) * WAVE_OVERSHOOT

private fun CacheDrawScope.spawnOrigin(geometry: ScreenGeometry, scale: Float): Offset =
    waveOrigin(geometry, size.width, density, scale)

private fun CacheDrawScope.waveReach(origin: Offset): Float = waveReach(origin, size.width, size.height)

/**
 * A gradient shader built once and reused at every size. Compose rebuilds a gradient brush
 * whenever the drawn size changes; the wave masks change size every frame.
 */
internal class FixedShaderBrush(private val shader: Shader) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}

internal fun fixedRadial(center: Offset, radius: Float, vararg stops: Pair<Float, Color>): Brush =
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
internal fun DrawScope.fillScaled(
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
 * The glass wave's crest: a smooth band of light, steep in front and trailing a long soft glow
 * behind as a real wave does, with a faint trough just ahead of it that reads as the ripple
 * bending the light. Its brightness varies around the ring (a sweep laid on with DstIn), so it
 * shimmers rather than looking drawn with a compass. Both gradients centre on [origin], so the
 * crest grows by scaling and the shimmer turns by rotating, neither changing the other.
 */
private fun glassCrest(origin: Offset, reach: Float, color: Color): Brush {
    val wake = lerp(color, Color.White, 0.35f)
    val glow = lerp(color, Color.White, 0.6f)
    val crest = lerp(color, Color.White, 0.88f)
    // Fades end in their own colour at zero alpha, not transparent black, so no grey seams.
    val profile = listOf(
        GLASS_BAND_INNER to wake.copy(alpha = 0f),
        0.68f to wake.copy(alpha = 0.05f),
        0.82f to wake.copy(alpha = 0.14f),
        0.9f to glow.copy(alpha = 0.34f),
        0.94f to crest.copy(alpha = 0.8f),
        0.955f to crest.copy(alpha = 0.55f),
        0.968f to crest.copy(alpha = 0.12f),
        0.98f to Color.Black.copy(alpha = 0.06f),
        1f to Color.Black.copy(alpha = 0f),
    )
    fun shine(alpha: Float) = Color.White.copy(alpha = alpha)
    val shimmer = listOf(
        0f to shine(1f),
        0.14f to shine(0.55f),
        0.3f to shine(0.95f),
        0.46f to shine(0.5f),
        0.6f to shine(1f),
        0.76f to shine(0.6f),
        0.9f to shine(0.9f),
        1f to shine(1f),
    )
    return FixedShaderBrush(
        android.graphics.ComposeShader(
            RadialGradientShader(origin, reach, profile.map { it.second }, profile.map { it.first }),
            SweepGradientShader(origin, shimmer.map { it.second }, shimmer.map { it.first }),
            android.graphics.PorterDuff.Mode.DST_IN,
        ),
    )
}

/**
 * One glass ripple at wave scale [s]: the crest band only (a stroke over the lit part of the
 * gradient, so the clear middle is never painted), turned [turn] degrees for the shimmer.
 */
private fun DrawScope.drawGlassRipple(brush: Brush, s: Float, turn: Float, origin: Offset, radius: Float, band: Stroke, alpha: Float) {
    rotate(turn, origin) {
        scale(s, origin) { drawCircle(brush, radius, origin, alpha = alpha, style = band) }
    }
}

/**
 * The AirDrop part: light gathers in the camera and flashes, releasing a wide ring of light,
 * brand colour with a hot white rim, that washes over the whole screen and fades as it reaches
 * the bottom ([spawnFadeAt]).
 *
 * With [glass], it rolls in like the wave on an iPhone instead: a soft, shimmering crest of
 * light ([glassCrest]) with a fainter ripple following it, and a frost ([GlassFrost]) where the
 * screen under it blurs. All of it is gradients, so it costs about what the plain wave does.
 */
@Composable
private fun SpawnWave(color: Color, settings: GlowSettings, geometry: ScreenGeometry, scale: Float, time: () -> Float) {
    val glass = settings.glass
    val area = settings.glassArea
    val frost = if (glass) settings.glassFrost.alpha else 0f
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val origin = spawnOrigin(geometry, scale)
                val reach = waveReach(origin)
                val rim = lerp(color, Color.White, 0.7f)
                // Fades end in their own colour at zero alpha, not transparent black, so no grey seams.
                val wash = fixedRadial(
                    origin,
                    reach,
                    0f to color.copy(alpha = 0f),
                    0.62f to color.copy(alpha = 0f),
                    0.86f to color.copy(alpha = 0.30f),
                    0.95f to rim.copy(alpha = 0.55f),
                    1f to rim.copy(alpha = 0f),
                )
                val crest = if (glass) glassCrest(origin, reach, color) else null
                // Covers GLASS_BAND_INNER..1 of the reach, before the wave's scale is applied.
                val crestBand = Stroke(reach * (1f - GLASS_BAND_INNER))
                val crestRadius = reach * (1f + GLASS_BAND_INNER) / 2f
                val frostMask = if (frost > 0f) hazeMask(area, origin, reach, Color.White) else null
                val bloomRadius = BLOOM_RADIUS.toPx() * scale
                // A light source: a hot core wide enough to show round the lens, then a long
                // coloured falloff.
                val bloom = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.95f),
                    0.12f to lerp(color, Color.White, 0.6f).copy(alpha = 0.85f),
                    0.24f to lerp(color, Color.White, 0.25f).copy(alpha = 0.55f),
                    0.42f to color.copy(alpha = 0.26f),
                    0.65f to color.copy(alpha = 0.09f),
                    0.85f to color.copy(alpha = 0.025f),
                    1f to color.copy(alpha = 0f),
                    center = origin,
                    radius = bloomRadius,
                )
                onDrawBehind {
                    val ms = time()
                    val wave = waveAt(ms, spawn = true)
                    val fade = spawnFadeAt(ms)
                    if (frost > 0f) {
                        val mist = frost * hazeAt(ms, area)
                        if (mist > 0f) {
                            if (frostMask == null) {
                                drawRect(Color.White, alpha = mist)
                            } else if (wave >= MIN_WAVE || area == GlassArea.REVEAL) {
                                fillScaled(frostMask, wave.coerceAtLeast(MIN_WAVE), origin, alpha = mist)
                            }
                        }
                    }
                    if (crest != null) {
                        val turn = GLASS_SHIMMER_DEGREES * spawnLinearAt(ms)
                        // The echo emerges once the crest is full; the crest rises out of the flash
                        // from a hairline, hiding the first frames, where it moves more than its width.
                        val echoAlpha = GLASS_ECHO_ALPHA * fade * smoothstep(0.15f, 0.4f, wave)
                        if (echoAlpha > 0f) {
                            val echo = wave * (1f - GLASS_ECHO_GAP)
                            drawGlassRipple(crest, echo, turn + GLASS_ECHO_TURN, origin, crestRadius, crestBand, echoAlpha)
                        }
                        val crestAlpha = fade * smoothstep(MIN_WAVE, 0.15f, wave)
                        if (wave >= MIN_WAVE && crestAlpha > 0f) {
                            drawGlassRipple(crest, wave, turn, origin, crestRadius, crestBand, crestAlpha)
                        }
                    } else if (wave >= MIN_WAVE && fade > 0f) {
                        fillScaled(wash, wave, origin, alpha = fade)
                    }
                    val flash = bloomAlphaAt(ms)
                    if (flash > 0f) {
                        scale(bloomScaleAt(ms, wave), pivot = origin) {
                            drawCircle(bloom, bloomRadius, origin, alpha = flash)
                        }
                    }
                }
            },
    )
}

/**
 * The Edge Frame, drawn in one offscreen layer: glow and line, recoloured (Duo, Spectrum),
 * brightened under the spawn wave's crest (fading with the wave), cut to what the wave has
 * reached, then cut to the comet heads. Every recolour and mask is painted only over the band
 * the frame occupies (a full-screen pass each was too much for the GPU at 120 Hz), with its
 * gradient moved in place.
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
                alpha = levelAt(ms, spawn)
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
                val glowOff = halos.isEmpty()
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
                    0.9f to Color.White,
                    0.97f to Color.Transparent,
                )
                // Fades end in white at zero alpha, not transparent black, so no grey seams.
                val rim = movingRadial(
                    origin,
                    reach,
                    0f to Color.White.copy(alpha = 0f),
                    0.84f to Color.White.copy(alpha = 0f),
                    CREST_AT to Color.White.copy(alpha = 0.9f),
                    0.975f to Color.White.copy(alpha = 0f),
                )
                onDrawBehind {
                    val ms = time()
                    val wave = waveAt(ms, spawn)
                    if (wave < MIN_WAVE) return@onDrawBehind
                    // Pulse breathes in the glow; the line barely dims, so it stays crisp. 1 elsewhere.
                    val b = if (motion == EdgeMotion.PULSE) breathAt(ms, spawn) else 1f
                    for (i in halos.indices) {
                        val (stroke, alpha) = halos[i]
                        val breath = 0.35f + 0.65f * b
                        drawRoundRect(paint, frameTopLeft, frameSize, frameCorner, style = stroke, alpha = alpha * breath)
                    }
                    // Without the glow the line carries the breath, so it dims more.
                    val coreAlpha = if (glowOff) 0.55f + 0.45f * b else 0.72f + 0.28f * b
                    drawRoundRect(paint, frameTopLeft, frameSize, frameCorner, style = core, alpha = coreAlpha)
                    if (tint != null) {
                        tint.rotateTo(tintTurnAt(ms, settings.edgeColor), center)
                        drawRoundRect(tint, frameTopLeft, frameSize, frameCorner, style = band, blendMode = BlendMode.SrcIn)
                    }
                    val hotAlpha = 0.5f * (0.4f + 0.6f * b)
                    drawRoundRect(Color.White, frameTopLeft, frameSize, frameCorner, style = hot, alpha = hotAlpha)
                    if (spawn && spawnLinearAt(ms) < 1f) {
                        // The highlight shares the wave's fade; by the time the reveal stops being
                        // drawn it covers every pixel, so neither ends in a step.
                        val crest = spawnFadeAt(ms)
                        if (crest > 0f) {
                            rim.scaleTo(wave, origin)
                            drawRoundRect(
                                rim,
                                frameTopLeft,
                                frameSize,
                                frameCorner,
                                alpha = crest,
                                style = band,
                                blendMode = BlendMode.SrcAtop,
                            )
                        }
                        reveal.scaleTo(wave, origin)
                        drawRoundRect(reveal, frameTopLeft, frameSize, frameCorner, style = band, blendMode = BlendMode.DstIn)
                    }
                    if (heads != null) {
                        // The mask holds 1 - brightness and is laid on with DstOut, so drawing it
                        // at partial alpha blends from the fully lit frame into the comets.
                        val strength = motionAt(ms, spawn)
                        if (strength > 0f) {
                            heads.rotateTo(headAt(ms, motion, spawn, size.width, size.height), center)
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

/**
 * [EdgeMotion.PULSE]'s breath, 1 full to 0 at rest: lit frame, a slow exhale, a quicker inhale,
 * then a final exhale that ends under the fade.
 */
private fun breathAt(ms: Float, spawn: Boolean): Float {
    val start = if (spawn) MOTION_IN_AT_MS else FADE_IN_MS
    val u = (ms - start) / (PULSE_END_MS - start)
    return when {
        u <= 0f -> 1f
        u < PULSE_EXHALE -> 1f - FastOutSlowInEasing.transform(u / PULSE_EXHALE)
        u < PULSE_EXHALE + PULSE_INHALE -> LinearOutSlowInEasing.transform((u - PULSE_EXHALE) / PULSE_INHALE)
        u < 1f -> 1f - FastOutSlowInEasing.transform((u - PULSE_EXHALE - PULSE_INHALE) / (1f - PULSE_EXHALE - PULSE_INHALE))
        else -> 0f
    }
}

/**
 * Comet heads glide clockwise at an even pace along the frame (a [w] x [h] canvas), slowing as
 * the effect ends, and come to rest under the camera (Twin: top and bottom centre). With the
 * spawn they enter on the right side as the motion takes over; without it the Comet does one
 * full loop from the top centre and the Twin pair swaps ends.
 */
private fun headAt(ms: Float, motion: EdgeMotion, spawn: Boolean, w: Float, h: Float): Float {
    val start = if (spawn) MOTION_IN_AT_MS else 0f
    val g = glideAt(((ms - start) / (ARRIVAL_MS - start)).coerceIn(0f, 1f))
    val p = if (motion == EdgeMotion.TWIN) {
        0.5f - (if (spawn) TWIN_TRAVEL else 0.5f) * (1f - g)
    } else {
        1f - (if (spawn) COMET_TRAVEL else 1f) * (1f - g)
    }
    return perimeterAngle(p, w, h)
}

/**
 * Angle (degrees, clockwise from 3 o'clock) of the point a [p] fraction of the way round a
 * [w] x [h] rectangle, starting at the top centre and running clockwise. Equal steps in [p] are
 * equal distances along the frame. p and p + 0.5 are always opposite through the centre.
 */
private fun perimeterAngle(p: Float, w: Float, h: Float): Float {
    val d = (p - floor(p)) * 2f * (w + h)
    val hw = w / 2f
    val hh = h / 2f
    val x: Float
    val y: Float
    when {
        d < hw -> { x = d; y = -hh }
        d < hw + h -> { x = hw; y = d - hw - hh }
        d < hw + h + w -> { x = hw - (d - hw - h); y = hh }
        d < hw + 2f * h + w -> { x = -hw; y = hh - (d - hw - h - w) }
        else -> { x = d - (hw + 2f * h + w) - hw; y = -hh }
    }
    return atan2(y, x) * (180f / PI.toFloat())
}

/** Duo and Spectrum turn on the same glide as the heads, slowing with them. */
private fun tintTurnAt(ms: Float, mode: EdgeColor): Float {
    val degrees = if (mode == EdgeColor.SPECTRUM) SPECTRUM_TURN_DEGREES else DUO_TURN_DEGREES
    return -90f + degrees * glideAt((ms / ARRIVAL_MS).coerceIn(0f, 1f))
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
 * Comet heads as a sweep gradient of 1 - brightness: head at angle 0, its tail behind it
 * (counter-clockwise, since the heads run clockwise), bright at the head and quick to fade, then
 * a faint linger. Null for [EdgeMotion.PULSE].
 */
private fun CacheDrawScope.cometMask(motion: EdgeMotion): MovingShaderBrush? {
    val center = Offset(size.width / 2f, size.height / 2f)
    fun dark(brightness: Float) = Color.Black.copy(alpha = 1f - brightness)
    return when (motion) {
        EdgeMotion.PULSE -> null
        EdgeMotion.COMET -> movingSweep(
            center,
            0f to dark(1f),
            0.012f to dark(COMET_BASE),
            0.5f to dark(COMET_BASE),
            0.72f to dark(0.2f),
            0.86f to dark(0.32f),
            0.93f to dark(0.5f),
            0.975f to dark(0.75f),
            1f to dark(1f),
        )
        EdgeMotion.TWIN -> movingSweep(
            center,
            0f to dark(1f),
            0.006f to dark(COMET_BASE),
            0.25f to dark(COMET_BASE),
            0.36f to dark(0.2f),
            0.43f to dark(0.32f),
            0.465f to dark(0.5f),
            0.4875f to dark(0.75f),
            0.5f to dark(1f),
            0.506f to dark(COMET_BASE),
            0.75f to dark(COMET_BASE),
            0.86f to dark(0.2f),
            0.93f to dark(0.32f),
            0.965f to dark(0.5f),
            0.9875f to dark(0.75f),
            1f to dark(1f),
        )
    }
}

/**
 * Camera Ring and Custom Dot: a beacon that ignites with a flare when the wave's crest reaches it
 * (or on its own without the spawn), settles, then sends out two double pulses. At the end the
 * halo draws in and the core lingers as an ember where the LED will blink.
 */
@Composable
private fun BeaconArrival(settings: GlowSettings, color: Color, geometry: ScreenGeometry, scale: Float, time: () -> Float) {
    val spawn = settings.spawn
    // Custom Dot plays where the LED sits, so with the LED on the camera it is the ring too.
    val ring = settings.style == GlowStyle.CAMERA_RING || settings.ledOnCamera
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
                // Ignites as the wave's crest reaches the beacon; the ring not before the flash peaks.
                val igniteMs = if (spawn) {
                    val crestAt = spawnMsAt(((center - origin).getDistance() / (reach * CREST_AT)).coerceAtMost(1f))
                    if (ring) max(crestAt, RING_IGNITE_MS) else crestAt
                } else {
                    0f
                }
                val settleDepth = if (ring) RING_SETTLE else DOT_SETTLE
                val span = (if (ring) RING_RIPPLE_SPAN else DOT_RIPPLE_SPAN).toPx() * scale
                val glowRadius = radius + BEACON_GLOW.toPx() * scale
                val inner = (radius / glowRadius).coerceIn(0f, 0.9f)
                // Fades end in their own colour at zero alpha, not transparent black, so no grey seams.
                val glow = if (ring) {
                    Brush.radialGradient(
                        0f to color.copy(alpha = 0f),
                        inner * 0.6f to color.copy(alpha = 0f),
                        inner to color.copy(alpha = 0.45f),
                        1f to color.copy(alpha = 0f),
                        center = center,
                        radius = glowRadius,
                    )
                } else {
                    Brush.radialGradient(
                        0f to color.copy(alpha = 0.65f),
                        0.35f to color.copy(alpha = 0.22f),
                        1f to color.copy(alpha = 0f),
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
                    val t = ms - igniteMs
                    if (t < 0f) return@onDrawBehind
                    val lit = FastOutSlowInEasing.transform((t / IGNITE_MS).coerceIn(0f, 1f))
                    val out = fadeOutAt(ms)
                    val level = lit * out
                    if (level <= 0f) return@onDrawBehind
                    // Flare at ignition, brightest as it lights; the core overshoots and settles.
                    val flare = min(1f, t / FLARE_RISE_MS) * exp(-t / FLARE_TAU_MS)
                    val settle = 1f + settleDepth * exp(-t / SETTLE_TAU_MS) * cos(2f * PI.toFloat() * t / SETTLE_PERIOD_MS)
                    // The closing fade only: the halo fades sooner and draws in, the core lingers.
                    val e = ((ms - FADE_OUT_AT_MS) / FADE_OUT_MS).coerceIn(0f, 1f)
                    val coreAlpha = lit * out.pow(0.6f)
                    scale((1f + FLARE_GLOW * flare) * (1f - 0.45f * e * e), center) {
                        drawCircle(glow, glowRadius, center, alpha = lit * out.pow(1.6f))
                    }
                    for (i in RIPPLE_AT_MS.indices) {
                        val p = (t - RIPPLE_AT_MS[i]) / RIPPLE_LIFE_MS
                        if (p < 0f || p >= 1f) continue
                        val q = 1f - p
                        val grow = 1f - q * q * q // leaves fast, loses energy as it spreads
                        val birth = min(1f, p / RIPPLE_BIRTH) // fades in: no pop at the core
                        drawCircle(
                            color,
                            radius + grow * span * RIPPLE_REACH,
                            center,
                            alpha = level * RIPPLE_STRENGTH[i] * 0.8f * birth * q * sqrt(q),
                            style = ripple,
                        )
                    }
                    val r = radius * settle
                    if (ring) {
                        drawCircle(color, r, center, alpha = coreAlpha, style = core)
                        drawCircle(hot, r, center, alpha = coreAlpha * (0.7f + 0.3f * flare), style = hotLine)
                        if (flare > 0.01f) {
                            drawCircle(Color.White, r, center, alpha = coreAlpha * 0.8f * flare, style = hotLine)
                        }
                    } else {
                        drawCircle(color, r, center, alpha = coreAlpha)
                        drawCircle(hot, r * 0.5f, center, alpha = coreAlpha)
                        // A white pinpoint inside the hot centre, only while it flares.
                        if (flare > 0.01f) drawCircle(Color.White, r * 0.4f, center, alpha = coreAlpha * 0.9f * flare)
                    }
                }
            },
    )
}
