package com.example.ambientglow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Timeline. Everything is a pure function of one clock (ms since the effect started), so a frame
// reads one value and allocates nothing.
// ---------------------------------------------------------------------------------------------

/** Whole effect. Ends before the LED takes the lock screen back (2.5 s after the wake). */
internal const val ARRIVAL_MS = 2_300

/** Light pools in the camera before the wave is released: the flash is the cause, the wave its effect. */
internal const val SPAWN_GATHER_MS = 110f

/** The spawn wave's trip from the camera to past the farthest corner, after the gather. */
internal const val SPAWN_MS = 950f

/** Without the spawn, the style simply fades in. */
private const val FADE_IN_MS = 250f

/** The closing fade, shared by every style; ends exactly at [ARRIVAL_MS]. */
private const val FADE_OUT_MS = 600f
private const val FADE_OUT_AT_MS = ARRIVAL_MS - FADE_OUT_MS

/** With the spawn, the Pulse breath settles in once the wave has lit the whole frame. */
private const val MOTION_IN_AT_MS = SPAWN_GATHER_MS + 700f
private const val MOTION_IN_MS = 350f

/**
 * Comet heads: with the spawn they gather out of the lit frame as the crest clears the bottom
 * centre (702 ms), launch from rest there and come home to the camera by [HEADS_HOME_MS], half the
 * frame away: Comet up the left side, Twin up both.
 */
private const val HEADS_IN_AT_MS = SPAWN_GATHER_MS + 620f
private const val HEADS_HOME_MS = 2_050f
private const val HEADS_FROM = 0.5f

/** Objects moving from rest to rest: the comet heads and the tints that turn with them. */
private val HeadEasing = CubicBezierEasing(0.4f, 0f, 0.4f, 1f)

/** The homecoming: the comet tails draw into their heads, then the lit frame drains into the camera. */
private const val TAIL_IN_AT_MS = 1_600f
private const val TAIL_IN_MS = 400f
private const val EXIT_AT_MS = FADE_OUT_AT_MS
private val ExitEasing = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)

/** The Edge Frame's own last fade: later than the shared one, as the drain does most of the work. */
private const val EDGE_FADE_AT_MS = 2_000f

/** The heads land on the camera with a small glint, this long into the effect. */
internal const val LANDING_MS = 1_830f
private const val LANDING_SPREAD_MS = 110f
internal val LANDING_GLINT = 16.dp

/** Flash peaks this long after release, as the ring leaves it. */
private const val BLOOM_PEAK_MS = 40f
private const val BLOOM_DECAY_MS = 520f

/**
 * Leaves the flash already moving, then glides out: the release reads as caused by the flash, and
 * the crest never moves more than ~40 px a frame at 120 Hz, so it reads as motion, not a jump.
 */
private val SpawnEasing = CubicBezierEasing(0.4f, 0.6f, 0.4f, 1f)

/** The spawn's linear progress, 0 at release (after the gather) to 1 at the end of its trip. */
internal fun spawnLinearAt(ms: Float): Float = ((ms - SPAWN_GATHER_MS) / SPAWN_MS).coerceIn(0f, 1f)

/**
 * The spawn's energy: full while the crest crosses, then dissipating; the light and the blur
 * under it share it, so they end together.
 */
internal fun spawnFadeAt(ms: Float): Float = 1f - smoothstep(0.5f, 0.88f, spawnLinearAt(ms))

/** How much of its light the crest has lost by the time it has spread to its full reach. */
private const val CREST_SPREAD_LOSS = 0.3f

/**
 * The crest's light at [ms] with the wave at [wave]: its energy spreads thinner as the ring
 * grows ([CREST_SPREAD_LOSS]), then dissipates with the spawn ([spawnFadeAt]).
 */
internal fun crestEnergyAt(ms: Float, wave: Float): Float =
    (1f - CREST_SPREAD_LOSS * smoothstep(0.1f, 0.9f, wave)) * spawnFadeAt(ms)

/** Wave radius as a fraction of its reach: 0 at the camera (through the gather), 1 past the farthest corner. */
private fun waveAt(ms: Float, spawn: Boolean): Float =
    if (spawn) SpawnEasing.transform(spawnLinearAt(ms)) else 1f

/** The spawn wave's radius as a fraction of its reach, at [ms] into the effect. */
internal fun spawnWaveAt(ms: Float): Float = waveAt(ms, spawn = true)

/** When the spawn wave reaches [fraction] of its reach (inverse of [waveAt]); cache-time only. */
private fun spawnMsAt(fraction: Float): Float =
    solveRising(fraction, SPAWN_GATHER_MS, SPAWN_GATHER_MS + SPAWN_MS, steps = 24) { waveAt(it, spawn = true) }

/** The closing fade: zero slope at both ends, so no kink where it starts or stops. */
private fun fadeOutAt(ms: Float): Float = 1f - smoothstep(FADE_OUT_AT_MS, ARRIVAL_MS.toFloat(), ms)

/**
 * The Edge Frame's opacity: a fade in (only without the spawn, whose wave does the revealing) and
 * a short last fade once the frame has mostly drained into the camera. Beacons ignite instead of
 * fading in, and shape the fade out themselves ([fadeOutAt]).
 */
private fun levelAt(ms: Float, spawn: Boolean): Float {
    val fadeIn = if (spawn) 1f else smoothstep(0f, FADE_IN_MS, ms)
    return minOf(fadeIn, 1f - smoothstep(EDGE_FADE_AT_MS, ARRIVAL_MS.toFloat(), ms))
}

/** How far the comet heads have taken over from the plain lit frame. */
private fun motionAt(ms: Float, spawn: Boolean): Float =
    if (spawn) smoothstep(HEADS_IN_AT_MS, HEADS_IN_AT_MS + MOTION_IN_MS, ms) else 1f

/** Glide for the comet heads and tints: leaves at 1.5x the average pace, ends at 0.5x, never stalls. */
private fun glideAt(x: Float): Float = 1.5f * x - 0.5f * x * x

/**
 * Origin flash: pools in (ease-in) during the gather, peaks just after release, then a cubic decay
 * that is gone as the crest leaves the screen. The base matches at release, so it never steps.
 */
private fun bloomAlphaAt(ms: Float): Float {
    if (ms < SPAWN_GATHER_MS) {
        val g = ms / SPAWN_GATHER_MS
        return 0.6f * g * g
    }
    val t = ms - SPAWN_GATHER_MS
    val r = (t / BLOOM_PEAK_MS).coerceIn(0f, 1f)
    val x = 1f - ((t - BLOOM_PEAK_MS) / BLOOM_DECAY_MS).coerceIn(0f, 1f)
    return (0.6f + 0.4f * r * (2f - r)) * x * x * x
}

/** How fast the flash pops open at release: a critically damped spring's time constant. */
private const val BLOOM_POP_MS = 45f

/**
 * Flash size: draws in as it gathers (an inhale), then pops open at release without overshoot
 * and stays put: the light source, not the wave, so it doesn't follow the crest out.
 */
private fun bloomScaleAt(ms: Float): Float =
    if (ms < SPAWN_GATHER_MS) {
        val g = ms / SPAWN_GATHER_MS
        0.55f - 0.33f * g * g
    } else {
        val t = (ms - SPAWN_GATHER_MS) / BLOOM_POP_MS
        0.22f + 0.28f * (1f - (1f + t) * exp(-t))
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
internal const val CREST_AT = 0.94f

/** Beacon ignition, on its own clock from the moment the wave's crest reaches it. */
private const val IGNITE_MS = 140f
private const val FLARE_RISE_MS = 40f
private const val FLARE_TAU_MS = 160f
private const val FLARE_GLOW = 0.45f

/**
 * Damped spring the core settles with: it ignites small and grows out past its size, a slight
 * undershoot, then still, as something released rather than something shrinking back.
 */
private const val SETTLE_TAU_MS = 110f
private const val SETTLE_PERIOD_MS = 420f
private const val RING_SETTLE = 0.3f
private const val DOT_SETTLE = 0.5f

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

/** How far Duo and Spectrum turn round the frame while the heads travel. */
private const val DUO_TURN_DEGREES = 90f
private const val SPECTRUM_TURN_DEGREES = 150f
private const val DUO_HUE_SHIFT = 55f

/** Comet heads in perimeter space ([EdgeLight]): the short glow ahead of a head, and its tail. */
private val HEAD_LEAD = 5.dp
private const val HEAD_TAIL_PX = 420f

/** An element's comet heads fade in ahead of them over this share of how far in its frame reaches. */
private const val ELEMENT_HEAD_LEAD = 0.35f

/** How far the element's frame dies down as it drains into the camera, as a share of its spread. */
private const val ELEMENT_CALM = 0.7f

/** The element's frame ([ElementFrame]) runs hottest over this share of a head's tail. */
private const val ELEMENT_HEAT_TAIL = 0.6f

/** Earth's frame: its cracks branch out over this long, from when the wave has lit the frame. */
private const val CRACKS_FROM_MS = SPAWN_GATHER_MS + 250f
private const val CRACKS_MS = 900f

/** Glass wave (gradient crest): inner edge of the band it lights, as a fraction of the reach; inside it is clear. */
private const val GLASS_BAND_INNER = 0.7f

/** Plain wave: inner edge of the band its wash lights, as a fraction of the reach; inside it is clear. */
private const val WASH_INNER = 0.7f

/** Glass wave: how far the shimmer along the crest drifts round during the spawn. */
private const val GLASS_SHIMMER_DEGREES = 50f

/**
 * Glass wave: the fainter ripple that follows the crest, like the second ring on water. It rides
 * [GLASS_ECHO_GAP] of the crest's radius behind it, so the two spread apart as they travel, like
 * a real ripple train.
 */
private const val GLASS_ECHO_GAP = 0.085f
private const val GLASS_ECHO_ALPHA = 0.22f
private const val GLASS_ECHO_TURN = 35f

/**
 * Frost: a mid grey, like frosted glass diffusing the screen behind it (a white veil only lifts
 * the blacks and reads as a dirty screen). It takes on 8% of the message's colour.
 */
private val FROST_GREY = Color(0xFF9E9EA3)

/** Frost: how wide its melting edge is behind the wave, as a fraction of the reach. */
private const val FROST_MELT = 0.09f

/** Frost: it condenses over this long from the start (the blur itself rises with the gather). */
private const val FROST_RISE_MS = SPAWN_GATHER_MS + 110f

/** Frost: the crest's light in it, at most this many times the mist, over this band of the reach. */
private const val FROST_LIGHT = 1.4f
private const val FROST_LIGHT_INNER = 0.88f
private const val FROST_LIGHT_OUTER = 1.3f

/** Frost (shader): the crest's light in it, at most this many times the frost. */
private const val FROST_SHADER_LIGHT = 0.8f

/** Frost (shader) on the black panel: the crest's light is all there is to see it by. */
private const val FROST_SHADER_LIGHT_BLACK = 1.3f

/** Comet mask brightness away from the heads: the frame stays faintly lit behind them. */
private const val COMET_BASE = 0.15f

/**
 * The new-message effect, once, then [onDone]. Drawn over the lock screen by [GlowShield], on
 * the black panel by the glow screen, and looped in the dashboard preview (with [scale] < 1).
 *
 * With [GlowSettings.spawn], an AirDrop-style wave bursts out of the camera, washes across the
 * screen, and lights the chosen style as its rim passes. Then the style plays: the Edge Frame in
 * its chosen motion and colours, its last light flying to the LED ([LedHandOff]); the LED Beacon
 * where the LED waits, in its form, sending out ripples.
 *
 * With [GlowSettings.fire], the wave is a ring of fire instead ([FireWave]); with
 * [GlowSettings.air], a gust of wind ([AirWave]); with [GlowSettings.earth], a quake ([EarthWave]).
 *
 * With a blur ([GlowSettings.hazes]: Water's glass or Air's gust), [onBlurBehind] is told every
 * frame of the wave how blurred the screen under it should be and where ([GlassHazeTarget]), and
 * 0 when the effect ends or is dropped. The effect can't blur what is under it; whoever owns that
 * screen does.
 *
 * [overBlack]: it plays on the black panel, so there is nothing under the frost to diffuse: it
 * shows only where the crest's light catches it, and nothing is blurred, even where a preview
 * passes [onBlurBehind] over a lit screen, so it plays as it would on the phone.
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
    overBlack: Boolean = false,
) {
    val clock = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    val blur = onBlurBehind.takeIf { settings.hazes && !overBlack }
    // A signal, not decoration: keep its timing even with animations scaled down or off.
    LaunchedEffect(Unit) {
        try {
            withContext(RealTimeMotion) {
                coroutineScope {
                    // Debug builds only: in release no extra coroutine wakes on every frame.
                    val trace = if (BuildConfig.DEBUG) launch { traceFrames(settings, preview = scale < 1f) } else null
                    val haze = blur?.let { launch { followHaze(it, settings.hazeArea) { clock.value } } }
                    clock.animateTo(ARRIVAL_MS.toFloat(), tween(ARRIVAL_MS, easing = LinearEasing))
                    trace?.cancel()
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
        if (settings.spawn) {
            when {
                settings.fire -> FireWave(glow, settings, geometry, scale, overBlack, time)
                settings.air -> AirWave(glow, settings, geometry, scale, time)
                settings.earth -> EarthWave(glow, settings, geometry, scale, time)
                else -> SpawnWave(glow, settings, geometry, scale, overBlack, time)
            }
        }
        when (settings.style) {
            GlowStyle.EDGE_FRAME -> {
                EdgeArrival(settings, glow, geometry, scale, time)
                LedHandOff(settings, glow, geometry, scale, time)
            }
            GlowStyle.BEACON -> BeaconArrival(settings, glow, geometry, scale, time)
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
            "${settings.style} ${settings.edgeMaterial} ${settings.edgeMotion} ${settings.edgeColor} ${settings.edgeWidth} ${settings.edgeGlow}"
        } else {
            settings.style.name
        }
        GlowLog.d {
            "effect ${if (preview) "preview" else "live"} $what spawn=${settings.spawn} " +
                "${settings.element} glass=${settings.glassBlur}/${settings.glassArea}/${settings.glassFrost} " +
                "fire=${settings.fireFlames}/${settings.fireColor}/${settings.fireSparks}/${settings.fireWake} " +
                "air=${settings.airGust}/${settings.airFlow}/${settings.airColor}/${settings.airCarry}/${settings.airBlur} " +
                "earth=${settings.earthForce}/${settings.earthForm}/${settings.earthColor}/${settings.earthDebris}: " +
                "${if (seconds > 0f) (frames / seconds).toInt() else 0} fps, $dropped stutters, worst ${worst / 1_000_000} ms"
        }
    }
}

/** Where the wave comes from: the punch-hole camera, or the top centre where it usually is. */
internal fun waveOrigin(geometry: ScreenGeometry, width: Float, density: Float, scale: Float): Offset =
    geometry.lens(width, density, scale).center

/** The wave's full radius: past the farthest corner from [origin]. */
internal fun waveReach(origin: Offset, width: Float, height: Float): Float =
    hypot(max(origin.x, width - origin.x), max(origin.y, height - origin.y)) * WAVE_OVERSHOOT

private fun CacheDrawScope.spawnOrigin(geometry: ScreenGeometry, scale: Float): Offset =
    waveOrigin(geometry, size.width, density, scale)

private fun CacheDrawScope.waveReach(origin: Offset): Float = waveReach(origin, size.width, size.height)

/**
 * The glass wave's crest below Android 13 ([CrestShader] above): a band of light, steep in front
 * and trailing a short coloured glow behind. Its brightness varies around the ring (a sweep laid
 * on with DstIn), so it shimmers rather than looking drawn with a compass. Both gradients centre
 * on [origin], so the crest grows by scaling and the shimmer turns by rotating, neither changing
 * the other.
 */
private fun glassCrest(origin: Offset, reach: Float, color: Color): Brush {
    val glow = lerp(color, Color.White, 0.25f)
    val crest = lerp(color, Color.White, 0.85f)
    // Fades end in their own colour at zero alpha, not transparent black, so no grey seams.
    val profile = listOf(
        GLASS_BAND_INNER to color.copy(alpha = 0f),
        0.84f to color.copy(alpha = 0.08f),
        0.91f to glow.copy(alpha = 0.3f),
        CREST_AT to crest.copy(alpha = 0.85f),
        0.952f to crest.copy(alpha = 0.35f),
        0.962f to crest.copy(alpha = 0f),
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
 * The frost the glass wave leaves where the screen blurs, as on breathed-on glass: a grey
 * diffusing [mist] ([FROST_GREY]). Where the wave sweeps it away ([GlassArea.REVEAL]) it melts
 * over a smoothstep, not a linear ramp, so the clearing edge has no visible line; it ends where
 * the blur does ([HAZE_REVEAL_EDGE]).
 */
private fun frostMask(area: GlassArea, origin: Offset, reach: Float, mist: Color): Brush? {
    if (area != GlassArea.REVEAL) return hazeMask(area, origin, reach, mist)
    val from = HAZE_REVEAL_EDGE - FROST_MELT
    return fixedRadial(
        origin,
        reach,
        from to mist.copy(alpha = 0f),
        (from + 0.25f * FROST_MELT) to mist.copy(alpha = 0.16f),
        (from + 0.5f * FROST_MELT) to mist.copy(alpha = 0.5f),
        (from + 0.75f * FROST_MELT) to mist.copy(alpha = 0.84f),
        HAZE_REVEAL_EDGE to mist,
        1f to mist,
    )
}

/**
 * The crest's light caught in the frost: frosted glass scatters what shines into it, so the
 * mist glows brightest at the edge the wave is clearing and the glow trails off into the frost
 * still ahead. It also hides the system blur's hard edge. Stops are fractions of the reach,
 * spread over a gradient of [FROST_LIGHT_OUTER] reaches; drawn as a ring band, never the clear middle.
 */
private fun frostLight(origin: Offset, reach: Float, light: Color): Brush {
    val outer = FROST_LIGHT_OUTER
    // Fades end in their own colour at zero alpha, not transparent black, so no grey seams.
    return fixedRadial(
        origin,
        reach * outer,
        FROST_LIGHT_INNER / outer to light.copy(alpha = 0f),
        HAZE_REVEAL_EDGE / outer to light.copy(alpha = 0.85f),
        0.955f / outer to light.copy(alpha = 1f),
        0.99f / outer to light.copy(alpha = 0.55f),
        1.06f / outer to light.copy(alpha = 0.22f),
        1.16f / outer to light.copy(alpha = 0.07f),
        1f to light.copy(alpha = 0f),
    )
}

/**
 * How frosted the screen is, 0..1 of the chosen [GlassFrost]: the blur's curve ([hazeAt]), eased
 * in so the mist condenses rather than snapping on with the gather.
 */
private fun frostAt(ms: Float, area: GlassArea): Float = hazeAt(ms, area) * smoothstep(0f, FROST_RISE_MS, ms)

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
 * The AirDrop part: light gathers in the camera and flashes, releasing a ring of light, brand
 * colour with a hot white rim, that washes over the whole screen and fades as it reaches the
 * bottom ([crestEnergyAt]). Only the ring's band is drawn, never the clear middle.
 *
 * With [glass], it rolls in like the wave on an iPhone instead: a thin line of light in a coloured
 * halo ([CrestShader], [glassCrest] below Android 13) with a fainter ripple following it, and a
 * frost ([GlassFrost]) where the screen under it blurs.
 * [overBlack]: the frost is always the one the wave sweeps away, seen only in the crest's light.
 */
@Composable
private fun SpawnWave(
    color: Color,
    settings: GlowSettings,
    geometry: ScreenGeometry,
    scale: Float,
    overBlack: Boolean,
    time: () -> Float,
) {
    val glass = settings.glass
    // On black there is nothing to blur, so no area to choose: one behaviour, the swept frost.
    val frostArea = if (overBlack) GlassArea.REVEAL else settings.glassArea
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
                    WASH_INNER to color.copy(alpha = 0f),
                    0.82f to color.copy(alpha = 0.14f),
                    0.9f to color.copy(alpha = 0.3f),
                    0.95f to rim.copy(alpha = 0.6f),
                    0.97f to rim.copy(alpha = 0f),
                    1f to rim.copy(alpha = 0f),
                )
                // Covers WASH_INNER..1 of the reach, before the wave's scale is applied.
                val washBand = Stroke(reach * (1f - WASH_INNER))
                val washRadius = reach * (1f + WASH_INNER) / 2f
                val crestLine = if (glass) crestShader(origin, color, density, scale) else null
                // The line's band: CREST_BEHIND behind it to CREST_AHEAD ahead, in px whatever the wave.
                val lineBand = Stroke((CREST_AHEAD + CREST_BEHIND).toPx() * scale)
                val lineOffset = (CREST_AHEAD - CREST_BEHIND).toPx() * scale / 2f
                val lineAhead = CREST_AHEAD.toPx() * scale
                // Where the last frame's crest was, for the motion smear (a frame's worth before the first).
                var lastMs = -1f
                val crest = if (glass && crestLine == null) glassCrest(origin, reach, color) else null
                // Covers GLASS_BAND_INNER..1 of the reach, before the wave's scale is applied.
                val crestBand = Stroke(reach * (1f - GLASS_BAND_INNER))
                val crestRadius = reach * (1f + GLASS_BAND_INNER) / 2f
                val frostGlow = lerp(color, Color.White, 0.55f)
                val frostMist = lerp(FROST_GREY, color, 0.08f)
                val frostShader = if (frost > 0f) {
                    frostShader(frostArea, origin, frost, frostMist, frostGlow, if (overBlack) 0f else 1f, density, scale)
                } else {
                    null
                }
                // The shader drawn as a ring band, its width set per frame (mutating it allocates nothing).
                val frostPaint = frostShader?.let {
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        shader = it.runtime
                    }
                }
                // Below Android 13: the frost as gradients, a mist and the crest's light in it; on
                // black only the light.
                val frostMask = if (frost > 0f && frostShader == null && !overBlack) frostMask(frostArea, origin, reach, frostMist) else null
                // The band under the crest is frost already lit by it; elsewhere the crest lights the frost ahead.
                val frostLight = if (frost > 0f && frostShader == null && frostArea != GlassArea.WAVE) frostLight(origin, reach, frostGlow) else null
                val frostLightBand = Stroke(reach * (FROST_LIGHT_OUTER - FROST_LIGHT_INNER))
                val frostLightRadius = reach * (FROST_LIGHT_OUTER + FROST_LIGHT_INNER) / 2f
                val flash = spawnFlash(color, settings, geometry, scale, origin)
                onDrawBehind {
                    val ms = time()
                    val wave = waveAt(ms, spawn = true)
                    if (frostShader != null && frostPaint != null) {
                        val melt = frostAt(ms, frostArea)
                        if (melt > 0f && (wave >= MIN_WAVE || frostArea != GlassArea.WAVE)) {
                            val light = if (frostArea == GlassArea.WAVE) {
                                0f
                            } else {
                                val k = if (overBlack) FROST_SHADER_LIGHT_BLACK else FROST_SHADER_LIGHT
                                k * crestEnergyAt(ms, wave) * smoothstep(MIN_WAVE, 0.15f, wave)
                            }
                            val r = reach * wave.coerceAtLeast(MIN_WAVE)
                            val trail = reach * waveAt(ms - FROST_SHADER_MELT_MS, spawn = true).coerceAtLeast(MIN_WAVE)
                            frostShader.update(r, trail, melt, light)
                            if (frostArea == GlassArea.SCREEN) {
                                drawRect(frostShader)
                            } else {
                                // Only the ring the shader can draw in: from where it returns
                                // nothing (as the shader works it out) out to the frost still ahead.
                                val from: Float
                                val to: Float
                                if (frostArea == GlassArea.REVEAL) {
                                    val outer = HAZE_REVEAL_EDGE * r
                                    val inner = min(max(HAZE_REVEAL_EDGE * trail, (HAZE_REVEAL_EDGE - FROST_SHADER_MELT) * r), outer - 0.04f * r)
                                    from = (inner - FROST_WARP * (outer - inner) - 2f).coerceAtLeast(0f)
                                    // On black the frost ahead shows only as far as the light scatters.
                                    to = if (overBlack) min(reach, HAZE_REVEAL_EDGE * r + 6f * frostShader.scatterPx) else reach
                                } else {
                                    from = (0.72f * r - 2f).coerceAtLeast(0f)
                                    to = r + 2f
                                }
                                frostPaint.strokeWidth = to - from
                                drawIntoCanvas { it.nativeCanvas.drawCircle(origin.x, origin.y, (from + to) / 2f, frostPaint) }
                            }
                        }
                    } else if (frost > 0f) {
                        val mist = frost * frostAt(ms, frostArea)
                        if (mist > 0f) {
                            // On black there is nothing under it to diffuse (no mask): only the light shows.
                            if (frostMask != null) {
                                if (wave >= MIN_WAVE || frostArea == GlassArea.REVEAL) {
                                    fillScaled(frostMask, wave.coerceAtLeast(MIN_WAVE), origin, alpha = mist)
                                }
                            } else if (!overBlack) {
                                drawRect(frostMist, alpha = mist)
                            }
                            val lit = (mist * FROST_LIGHT * crestEnergyAt(ms, wave) * smoothstep(MIN_WAVE, 0.15f, wave)).coerceAtMost(1f)
                            if (frostLight != null && lit > 0f) {
                                scale(wave, origin) { drawCircle(frostLight, frostLightRadius, origin, alpha = lit, style = frostLightBand) }
                            }
                        }
                    }
                    // The crest rises out of the flash from nothing, hiding the first frames, where
                    // it moves more than its width.
                    val energy = crestEnergyAt(ms, wave) * smoothstep(MIN_WAVE, 0.15f, wave)
                    if (crestLine != null) {
                        val prev = if (lastMs in (ms - 34f)..<ms) lastMs else ms - 8.3f
                        lastMs = ms
                        if (wave >= MIN_WAVE && energy > 0f) {
                            val radius = CREST_AT * reach * wave
                            val smear = CREST_AT * reach * (wave - waveAt(prev, spawn = true))
                            val glint = Math.toRadians(30.0 + 120.0 * spawnLinearAt(ms)).toFloat()
                            // The echo emerges once the crest is full.
                            val echoEnergy = energy * smoothstep(0.08f, 0.25f, wave)
                            crestLine.update(radius, radius * (1f - GLASS_ECHO_GAP), energy, echoEnergy, smear, glint)
                            val ring = radius + lineOffset
                            if (ring > lineBand.width / 2f) {
                                drawCircle(crestLine, ring, origin, style = lineBand)
                            } else {
                                drawCircle(crestLine, radius + lineAhead, origin)
                            }
                        }
                    } else if (crest != null) {
                        val turn = GLASS_SHIMMER_DEGREES * spawnLinearAt(ms)
                        // The echo emerges once the crest is full.
                        val echoAlpha = GLASS_ECHO_ALPHA * crestEnergyAt(ms, wave) * smoothstep(0.15f, 0.4f, wave)
                        if (echoAlpha > 0f) {
                            val echo = wave * (1f - GLASS_ECHO_GAP)
                            drawGlassRipple(crest, echo, turn + GLASS_ECHO_TURN, origin, crestRadius, crestBand, echoAlpha)
                        }
                        if (wave >= MIN_WAVE && energy > 0f) {
                            drawGlassRipple(crest, wave, turn, origin, crestRadius, crestBand, energy)
                        }
                    } else if (wave >= MIN_WAVE && energy > 0f) {
                        drawGlassRipple(wash, wave, 0f, origin, washRadius, washBand, energy)
                    }
                    drawSpawnFlash(flash, origin, scale, ms)
                }
            },
    )
}

/**
 * The flash the spawn wave is released from, in [color]: a light source, a hot core wide enough
 * to show round the lens, then a long coloured falloff. Round a camera ring it is carved out
 * instead, so the ring lighting inside it stays readable: the flash surrounds the ring, not
 * covers it. Drawn with [drawSpawnFlash].
 */
internal fun CacheDrawScope.spawnFlash(color: Color, settings: GlowSettings, geometry: ScreenGeometry, scale: Float, origin: Offset): Brush {
    val bloomRadius = BLOOM_RADIUS.toPx() * scale
    val ringSource = settings.style == GlowStyle.BEACON && settings.ledOnCamera
    return if (ringSource) {
        val metrics = GlowMetrics.FullScreen
        val lens = geometry.lens(size.width, density, scale).radius
        val clear = lens + (metrics.ringGap.toPx() + 2f * metrics.stroke.toPx()) * scale
        // In the gradient's terms at the flash's smallest (0.22 of its radius).
        val hole = (1.2f * clear / (bloomRadius * 0.22f)).coerceIn(0f, 0.7f)
        val rest = 1f - hole
        Brush.radialGradient(
            0f to color.copy(alpha = 0f),
            hole to color.copy(alpha = 0f),
            hole + 0.06f to lerp(color, Color.White, 0.5f).copy(alpha = 0.5f),
            hole + rest * 0.35f to color.copy(alpha = 0.18f),
            hole + rest * 0.65f to color.copy(alpha = 0.06f),
            hole + rest * 0.85f to color.copy(alpha = 0.02f),
            1f to color.copy(alpha = 0f),
            center = origin,
            radius = bloomRadius,
        )
    } else {
        Brush.radialGradient(
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
    }
}

/** The spawn's flash at [ms]: pooling during the gather, popping open at release, then decaying. */
internal fun DrawScope.drawSpawnFlash(flash: Brush, origin: Offset, scale: Float, ms: Float) {
    val alpha = bloomAlphaAt(ms)
    if (alpha <= 0f) return
    val radius = BLOOM_RADIUS.toPx() * scale
    scale(bloomScaleAt(ms), pivot = origin) {
        drawCircle(flash, radius, origin, alpha = alpha)
    }
}

/**
 * The Edge Frame, drawn in one offscreen layer: its glow and line, recoloured (Duo, Spectrum),
 * brightened under the spawn wave's crest (fading with the wave), cut to what the wave has
 * reached, then cut to the comet heads, and at the end drained back into the camera. Every
 * recolour and mask is painted only over the band the frame occupies (a full-screen pass each
 * was too much for the GPU at 120 Hz), with its gradient moved in place.
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
                // The light spilling inward from the line: thin rings stepping in, none overlapping,
                // their light falling off as a Gaussian, so it reads as glow, not as stacked bands.
                val glowReach = settings.edgeGlow.reach.toPx() * scale
                val ringStep = max(1f, round(2.dp.toPx() * scale))
                val rings = if (glowReach <= 0f) 0 else max(2, round(glowReach / ringStep).toInt())
                val ringStroke = Stroke(ringStep)
                val ringTopLeft = Array(rings) { i -> (round(line) + ringStep * (i + 0.5f)).let { Offset(it, it) } }
                val ringSize = Array(rings) { i -> (round(line) + ringStep * (i + 0.5f)).let { Size(size.width - 2f * it, size.height - 2f * it) } }
                val ringCorner = Array(rings) { i -> CornerRadius((corner - ringTopLeft[i].x).coerceAtLeast(0f)) }
                val ringAt = FloatArray(rings) { i -> (i + 0.5f) / rings }
                val glowPeak = settings.edgeGlow.peak
                // The hot filament, flush with the bezel where the light is brightest.
                val filament = max(1.dp.toPx() * scale, line * 0.35f)
                val hot = Stroke(filament)
                val hotTopLeft = Offset(filament / 2f, filament / 2f)
                val hotSize = Size(size.width - filament, size.height - filament)
                val hotCorner = CornerRadius((corner - filament / 2f).coerceAtLeast(0f))
                val origin = spawnOrigin(geometry, scale)
                val reach = waveReach(origin)
                val axis = origin.x - size.width / 2f
                // Made of the element: one shader paints it in place of the glow, line and tint.
                val element = if (settings.elementalEdge) {
                    elementFrame(
                        settings,
                        color,
                        size,
                        corner,
                        axis,
                        mirror = motion == EdgeMotion.TWIN,
                        line = line,
                        reach = glowReach,
                        peak = glowPeak,
                        lead = HEAD_LEAD.toPx() * scale,
                        tail = HEAD_TAIL_PX * scale * ELEMENT_HEAT_TAIL,
                        density = density,
                        scale = scale,
                    )
                } else {
                    null
                }
                // Covers every pixel the frame and its glow can light, from the edge in (half of
                // it is off screen).
                val band = Stroke(2f * max(line + glowReach, element?.inwardPx ?: 0f) + 2f)
                val bandCorner = CornerRadius(corner)
                // Tinted modes draw white and recolour it afterwards with SrcIn.
                val paint = if (settings.edgeColor == EdgeColor.APP) color else Color.White
                val tint = if (element == null) edgeTint(settings.edgeColor, color) else null
                val center = Offset(size.width / 2f, size.height / 2f)

                // The heads: in perimeter space where runtime shaders exist, as a sweep below. An
                // element's frame reaches far in from the edge, so its heads lead with a longer
                // fade: cut as close as a neon line's, the light ahead of them ends in a square edge.
                val headLead = max(HEAD_LEAD.toPx() * scale, ELEMENT_HEAD_LEAD * (element?.inwardPx ?: 0f))
                val heads = if (motion == EdgeMotion.PULSE) {
                    null
                } else {
                    edgeLight(
                        size,
                        corner,
                        lead = headLead,
                        tail = HEAD_TAIL_PX * scale,
                        base = COMET_BASE,
                        mirror = motion == EdgeMotion.TWIN,
                        axis = axis,
                    )
                }
                val sweep = if (motion != EdgeMotion.PULSE && heads == null) cometSweep() else null
                val seam = floor(size.width / 2f)
                // Lit up to just behind the crest line and dark just past it, so the frame never
                // lights ahead of the light that lights it.
                val reveal = movingRadial(
                    origin,
                    reach,
                    0f to Color.White,
                    0.925f to Color.White,
                    0.948f to Color.Transparent,
                )
                // Fades end in white at zero alpha, not transparent black, so no grey seams.
                val rim = movingRadial(
                    origin,
                    reach,
                    0f to Color.White.copy(alpha = 0f),
                    0.9f to Color.White.copy(alpha = 0f),
                    CREST_AT to Color.White.copy(alpha = 0.9f),
                    0.955f to Color.White.copy(alpha = 0f),
                )
                // The light returns to its source: what is still lit shrinks into the camera.
                val drain = movingRadial(
                    origin,
                    reach,
                    0f to Color.White,
                    0.84f to Color.White,
                    1f to Color.White.copy(alpha = 0f),
                )
                val glintRadius = LANDING_GLINT.toPx() * scale
                val glintColor = element?.landing ?: if (settings.edgeColor == EdgeColor.APP) color else Color.White
                val glint = fixedRadial(
                    origin,
                    glintRadius,
                    0f to lerp(glintColor, Color.White, 0.6f),
                    0.4f to glintColor.copy(alpha = 0.5f),
                    1f to glintColor.copy(alpha = 0f),
                )
                val glintAlpha = if (element != null || settings.edgeColor == EdgeColor.APP) 0.45f else 0.27f
                onDrawBehind {
                    val ms = time()
                    val wave = waveAt(ms, spawn)
                    if (wave < MIN_WAVE) return@onDrawBehind
                    // Pulse breathes in the glow, which also draws in and spreads out; the line
                    // barely dims, so it stays crisp. 1 elsewhere.
                    val b = if (motion == EdgeMotion.PULSE) breathAt(ms, spawn) else 1f
                    val spread = 0.55f + 0.45f * b
                    if (element != null) {
                        val focus = if (motion == EdgeMotion.PULSE) 0f else motionAt(ms, spawn)
                        // As the light drains into the camera the element dies down with it, rather
                        // than only being cut away: flames sink, the liquid ebbs, the wind drops and
                        // the cracks close up, their light gathering into the hand-off's token.
                        val calm = ExitEasing.transform(smoothstep(EXIT_AT_MS, ARRIVAL_MS.toFloat(), ms))
                        element.update(
                            ms,
                            headAt(ms, spawn),
                            focus,
                            spread * (1f - ELEMENT_CALM * calm),
                            crackGrowthAt(ms, spawn) * (1f - calm),
                        )
                        drawRoundRect(element, Offset.Zero, size, bandCorner, style = band)
                    } else {
                        for (i in 0 until rings) {
                            val t = ringAt[i] / spread
                            val alpha = glowPeak * exp(-4.5f * t * t) * (0.5f + 0.5f * b)
                            drawRoundRect(paint, ringTopLeft[i], ringSize[i], ringCorner[i], style = ringStroke, alpha = alpha)
                        }
                        // Without the glow the line carries the breath, so it dims more.
                        val coreAlpha = if (rings == 0) 0.55f + 0.45f * b else 0.72f + 0.28f * b
                        drawRoundRect(paint, frameTopLeft, frameSize, frameCorner, style = core, alpha = coreAlpha)
                        if (tint != null) {
                            tint.rotateTo(tintTurnAt(ms, settings.edgeColor, spawn), center)
                            drawRoundRect(tint, Offset.Zero, size, bandCorner, style = band, blendMode = BlendMode.SrcIn)
                        }
                        drawRoundRect(Color.White, hotTopLeft, hotSize, hotCorner, style = hot, alpha = 0.55f * (0.4f + 0.6f * b))
                    }
                    if (spawn && spawnLinearAt(ms) < 1f) {
                        // The highlight shares the crest's light; by the time the reveal stops being
                        // drawn it covers every pixel, so neither ends in a step.
                        val crest = crestEnergyAt(ms, wave)
                        if (crest > 0f) {
                            rim.scaleTo(wave, origin)
                            drawRoundRect(rim, Offset.Zero, size, bandCorner, alpha = crest, style = band, blendMode = BlendMode.SrcAtop)
                        }
                        reveal.scaleTo(wave, origin)
                        drawRoundRect(reveal, Offset.Zero, size, bandCorner, style = band, blendMode = BlendMode.DstIn)
                    }
                    if (heads != null || sweep != null) {
                        // The mask holds 1 - brightness and is laid on with DstOut, so drawing it
                        // at partial alpha blends from the fully lit frame into the comets; drawn
                        // twice at the end, the tails draw into the heads.
                        val strength = motionAt(ms, spawn)
                        if (strength > 0f) {
                            val p = headAt(ms, spawn)
                            val tailIn = smoothstep(TAIL_IN_AT_MS, TAIL_IN_AT_MS + TAIL_IN_MS, ms)
                            if (heads != null) {
                                heads.moveTo(p)
                                cutToComets(heads, bandCorner, band, strength, tailIn)
                            } else if (sweep != null) {
                                val angle = perimeterAngle(p, size.width, size.height)
                                val twin = motion == EdgeMotion.TWIN
                                clipRect(right = if (twin) seam else size.width) {
                                    sweep.rotateTo(angle, center)
                                    cutToComets(sweep, bandCorner, band, strength, tailIn)
                                }
                                if (twin) {
                                    clipRect(left = seam) {
                                        sweep.rotateMirroredTo(angle, center)
                                        cutToComets(sweep, bandCorner, band, strength, tailIn)
                                    }
                                }
                            }
                        }
                    }
                    val exit = smoothstep(EXIT_AT_MS, ARRIVAL_MS.toFloat(), ms)
                    if (exit > 0f) {
                        drain.scaleTo(1f - 0.97f * ExitEasing.transform(exit), origin)
                        drawRoundRect(drain, Offset.Zero, size, bandCorner, style = band, blendMode = BlendMode.DstIn)
                    }
                    if (heads != null || sweep != null) {
                        val landing = (ms - LANDING_MS) / LANDING_SPREAD_MS
                        val g = exp(-landing * landing)
                        if (g > 0.02f) drawCircle(glint, glintRadius, origin, alpha = glintAlpha * g)
                    }
                }
            },
    )
}

/**
 * Lays a comet [mask] (1 - brightness) on the frame's band with DstOut: at [strength] it blends
 * the fully lit frame into the comets, and a second pass at [tailIn] draws the tails into the heads.
 */
private fun DrawScope.cutToComets(mask: Brush, corner: CornerRadius, band: Stroke, strength: Float, tailIn: Float) {
    drawRoundRect(mask, Offset.Zero, size, corner, alpha = strength, style = band, blendMode = BlendMode.DstOut)
    if (tailIn > 0f) drawRoundRect(mask, Offset.Zero, size, corner, alpha = tailIn, style = band, blendMode = BlendMode.DstOut)
}

/** Raised cosine, 0 to 1 with zero slope at both ends: a breath, with no corner where it turns. */
private fun sine(t: Float): Float = 0.5f - 0.5f * cos(PI.toFloat() * t)

/**
 * [EdgeMotion.PULSE]'s breath, 1 full to 0 at rest: lit frame, a slow exhale, a quicker inhale,
 * then a final exhale that ends under the fade.
 */
private fun breathAt(ms: Float, spawn: Boolean): Float {
    val start = if (spawn) MOTION_IN_AT_MS else FADE_IN_MS
    val u = (ms - start) / (PULSE_END_MS - start)
    return when {
        u <= 0f -> 1f
        u < PULSE_EXHALE -> 1f - sine(u / PULSE_EXHALE)
        u < PULSE_EXHALE + PULSE_INHALE -> sine((u - PULSE_EXHALE) / PULSE_INHALE)
        u < 1f -> 1f - sine((u - PULSE_EXHALE - PULSE_INHALE) / (1f - PULSE_EXHALE - PULSE_INHALE))
        else -> 0f
    }
}

/**
 * Where the comet heads are, as a fraction of the way round the frame clockwise from the camera:
 * from rest at the bottom ([HEADS_FROM]) home to the camera, up the left side (Twin mirrors it up
 * the right too). Without the spawn they set off at once.
 */
private fun headAt(ms: Float, spawn: Boolean): Float {
    val start = if (spawn) HEADS_IN_AT_MS else 0f
    val g = HeadEasing.transform(((ms - start) / (HEADS_HOME_MS - start)).coerceIn(0f, 1f))
    return HEADS_FROM + (1f - HEADS_FROM) * g
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

/** How far Earth's frame has cracked open, 0..1: branching out as the wave lights it, or at once without it. */
private fun crackGrowthAt(ms: Float, spawn: Boolean): Float {
    val from = if (spawn) CRACKS_FROM_MS else 0f
    return EaseOutCubic.transform(((ms - from) / CRACKS_MS).coerceIn(0f, 1f))
}

/** Cracks race out and slow as they run out of force. */
private val EaseOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/** Duo and Spectrum turn with the heads, from rest to rest. */
private fun tintTurnAt(ms: Float, mode: EdgeColor, spawn: Boolean): Float {
    val degrees = if (mode == EdgeColor.SPECTRUM) SPECTRUM_TURN_DEGREES else DUO_TURN_DEGREES
    val start = if (spawn) HEADS_IN_AT_MS else 0f
    return -90f + degrees * HeadEasing.transform(((ms - start) / (HEADS_HOME_MS - start)).coerceIn(0f, 1f))
}

/**
 * Sweep gradient that recolours the frame, or null for the plain brand colour. The hues are
 * picked and blended in OKLab, so every step of the turn is as light as the next and no band
 * between two hues goes muddy or neon.
 */
private fun CacheDrawScope.edgeTint(mode: EdgeColor, color: Color): MovingShaderBrush? {
    val center = Offset(size.width / 2f, size.height / 2f)
    return when (mode) {
        EdgeColor.APP -> null
        EdgeColor.DUO -> {
            val (l, c, h) = toOklch(color)
            // The neighbour on the side that keeps more of its colour inside sRGB.
            val left = oklch(l, c, h - DUO_HUE_SHIFT)
            val right = oklch(l, c, h + DUO_HUE_SHIFT)
            val partner = if (toOklch(left)[1] >= toOklch(right)[1]) left else right
            val mid = oklabMix(color, partner)
            movingSweep(
                center,
                0f to color, 0.125f to mid, 0.25f to partner, 0.375f to mid, 0.5f to color,
                0.625f to mid, 0.75f to partner, 0.875f to mid, 1f to color,
            )
        }
        EdgeColor.SPECTRUM -> {
            // Twelve even steps round the hue circle from the brand colour, all equally light
            // and vivid even for a muted brand.
            val h = toOklch(color)[2]
            val hues = Array(13) { (it / 12f) to oklch(0.72f, 0.18f, h + it * 30f) }
            movingSweep(center, *hues)
        }
    }
}

/**
 * One comet head as a sweep gradient of 1 - brightness, for when runtime shaders are missing:
 * head at angle 0, its tail behind it (counter-clockwise, since it runs clockwise), bright at
 * the head and quick to fade, then a faint linger. Twin draws it twice, mirrored.
 */
private fun CacheDrawScope.cometSweep(): MovingShaderBrush {
    val center = Offset(size.width / 2f, size.height / 2f)
    fun dark(brightness: Float) = Color.Black.copy(alpha = 1f - brightness)
    return movingSweep(
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
}

/**
 * The LED Beacon: where the LED waits, in its form (a dot, or a ring round the camera), a beacon
 * ignites with a flare when the wave's crest reaches it (or on its own without the spawn),
 * settles, then sends out two double pulses. At the end the halo draws in and the core lingers as
 * an ember that takes the LED's exact shape before it goes, so the hand-over doesn't jump.
 */
@Composable
private fun BeaconArrival(settings: GlowSettings, color: Color, geometry: ScreenGeometry, scale: Float, time: () -> Float) {
    val spawn = settings.spawn
    val ring = settings.ledOnCamera
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val metrics = GlowMetrics.FullScreen
                val line = metrics.stroke.toPx() * scale
                val center: Offset
                val radius: Float
                val camera = geometry.lens(size.width, density, scale)
                val lens = camera.radius
                // The LED's own core: the dot, or the ring's thickness.
                val ledCore = ledRadiusAt(settings.dotSize, scale).toPx()
                if (ring) {
                    center = camera.center
                    radius = lens + (metrics.ringGap.toPx() + metrics.stroke.toPx() / 2f) * scale
                } else {
                    radius = ledCore
                    center = dotCenter(settings.dotX, settings.dotY, size, radius * DOT_HALO_FACTOR)
                }
                val ledLine = ledCore * LED_RING_STROKE_FACTOR
                val ledRing = lens + ledRingGapAt(scale).toPx() + ledLine / 2f
                val ledStroke = Stroke(ledLine)
                val ledHot = Stroke(ledLine * 0.4f)
                // The ring never grows in over the lens it surrounds.
                val minRing = lens + line / 2f
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
                // The dot's halo draws in to the LED's bloom.
                val haloEnd = (radius * LED_HALO_FACTOR / glowRadius).coerceAtMost(1f)
                // A ring lit inside the flash answers once the wave has passed: its first pair would
                // be lost in the flash. Any pulse that can't play out before the closing fade is skipped.
                val firstRipple = if (ring && spawn) 2 else 0
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
                    // Flare at ignition, brightest as it lights; the core grows out past its size and settles.
                    val flare = min(1f, t / FLARE_RISE_MS) * exp(-t / FLARE_TAU_MS)
                    val settle = 1f - settleDepth * exp(-t / SETTLE_TAU_MS) * cos(2f * PI.toFloat() * t / SETTLE_PERIOD_MS)
                    // The closing fade: the halo draws in and the core lingers as an ember, which
                    // takes the LED's shape and ends at a true zero with no slope (the panel
                    // steps its brightness in BLACK mode, so no dim tail).
                    val e = ((ms - FADE_OUT_AT_MS) / FADE_OUT_MS).coerceIn(0f, 1f)
                    val k = 1f - e * e
                    val ember = k * k
                    val m = smoothstep(0f, 0.6f, e)
                    val coreAlpha = lit * ember
                    val haloAlpha = lit * ember
                    val haloScale = if (ring) 1f - 0.45f * e * e else 1f + (haloEnd - 1f) * m
                    scale((1f + FLARE_GLOW * flare) * haloScale, center) {
                        drawCircle(glow, glowRadius, center, alpha = haloAlpha)
                    }
                    val base = radius * max(settle, 1f)
                    for (i in firstRipple until RIPPLE_AT_MS.size) {
                        if (igniteMs + RIPPLE_AT_MS[i] + 0.6f * RIPPLE_LIFE_MS > FADE_OUT_AT_MS) continue
                        val p = (t - RIPPLE_AT_MS[i]) / RIPPLE_LIFE_MS
                        if (p < 0f || p >= 1f) continue
                        val q = 1f - p
                        val grow = glideAt(p) // leaves briskly, loses energy as it spreads
                        val birth = min(1f, p / RIPPLE_BIRTH) // fades in: no pop at the core
                        drawCircle(
                            color,
                            base + grow * span * RIPPLE_REACH,
                            center,
                            alpha = level * RIPPLE_STRENGTH[i] * 0.8f * birth * q * sqrt(q),
                            style = ripple,
                        )
                    }
                    if (ring) {
                        val r = max(radius * settle, minRing) + (ledRing - max(radius * settle, minRing)) * m
                        val was = coreAlpha * (1f - m)
                        drawCircle(color, r, center, alpha = was, style = core)
                        drawCircle(hot, r, center, alpha = was * (0.7f + 0.3f * flare), style = hotLine)
                        if (flare > 0.01f) {
                            drawCircle(Color.White, r, center, alpha = was * 0.8f * flare, style = hotLine)
                        }
                        if (m > 0f) {
                            drawCircle(color, r, center, alpha = coreAlpha * m, style = ledStroke)
                            drawCircle(hot, r, center, alpha = coreAlpha * m, style = ledHot)
                        }
                    } else {
                        val r = radius * settle
                        drawCircle(color, r, center, alpha = coreAlpha)
                        drawCircle(hot, r * 0.5f, center, alpha = coreAlpha)
                        // A white pinpoint inside the hot centre, only while it flares.
                        if (flare > 0.01f) drawCircle(Color.White, r * 0.4f, center, alpha = coreAlpha * 0.9f * flare)
                    }
                }
            },
    )
}
