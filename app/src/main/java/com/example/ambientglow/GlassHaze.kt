package com.example.ambientglow

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

// ---------------------------------------------------------------------------------------------
// The glass wave's blur of whatever is under it. The effect can't blur what it is drawn over;
// whoever owns that screen does: the lock-screen overlay through the system's window blur
// ([GlowShield]), the dashboard and its preview through [glassHaze]. The effect only reports,
// every frame of the wave, how strong the blur is and how far the wave has spread.
// ---------------------------------------------------------------------------------------------

/** Steps for blurs that cost something per change: a window relayout (Android's), a cached effect (in app). */
const val GLASS_BLUR_STEPS = 20

// Whole screen: rises with the gather, full as the crest crosses mid-screen, clears in an S-curve
// as it nears the bottom.
/** Full blur this long after release; it starts rising with the gather. */
private const val SCREEN_RISE_MS = 160f
/** Release-relative start of the S-curve clear, as the crest nears the bottom. */
private const val SCREEN_CLEAR_FROM_MS = 330f
/** Release-relative end of the clear: the screen is sharp again. */
private const val SCREEN_CLEAR_TO_MS = 780f

// Under the wave: on as the wave leaves the camera, dissipating with its light ([spawnFadeAt]).
/** The band's blur rises over this long after release. */
private const val WAVE_RISE_MS = 120f

// Swim out: frosted during the gather, so the whole screen is frosted at release, then swept
// clear by the wave; the last unswept frost (bottom band, corners) melts as the wave dissipates.
/** Wave fraction where that last frost starts to melt. */
private const val REVEAL_CLEAR_FROM = 0.88f
/** Wave fraction by which it has melted. */
private const val REVEAL_CLEAR_TO = 0.98f

/**
 * The blur band under the crest, and the edge the swim-out frost is swept back to: fractions of
 * the wave's radius. The edge sits just behind the crest's brightest line (0.94), so the glow
 * hides the blur's hard edge and the frame the system blur lags behind.
 */
internal const val HAZE_BAND_INNER = 0.8f
internal const val HAZE_BAND_OUTER = 0.975f
internal const val HAZE_REVEAL_EDGE = 0.935f

/** The glass wave blurs what is under it: on, with a blur to show. */
internal val GlowSettings.hazes: Boolean get() = spawn && glass && glassBlur != GlassBlur.OFF

/**
 * How blurred, 0..1 of [GlassBlur.radius], the screen under the wave is at [ms] into the effect.
 * The whole screen and the swim-out frost rise during the gather ([SPAWN_GATHER_MS]); the band
 * under the wave rises after release. The band shares the light's dissipation ([spawnFadeAt]),
 * so blur and light end together.
 */
internal fun hazeAt(ms: Float, area: GlassArea): Float {
    val t = ms - SPAWN_GATHER_MS
    return when (area) {
        GlassArea.SCREEN -> {
            val rise = (ms / (SPAWN_GATHER_MS + SCREEN_RISE_MS)).coerceIn(0f, 1f)
            rise * (2f - rise) * (1f - smoothstep(SCREEN_CLEAR_FROM_MS, SCREEN_CLEAR_TO_MS, t))
        }
        GlassArea.WAVE -> smoothstep(0f, WAVE_RISE_MS, t) * spawnFadeAt(ms)
        // The wave takes the frost away, so it stays full until only the last unswept edge is left.
        GlassArea.REVEAL -> {
            val g = (ms / SPAWN_GATHER_MS).coerceIn(0f, 1f)
            g * (2f - g) * (1f - smoothstep(REVEAL_CLEAR_FROM, REVEAL_CLEAR_TO, spawnWaveAt(ms)))
        }
    }
}

/** When the haze has cleared, in ms into the effect (for the swim-out frost, a cap). */
internal fun hazeEndMs(area: GlassArea): Float = when (area) {
    GlassArea.SCREEN -> SPAWN_GATHER_MS + SCREEN_CLEAR_TO_MS
    GlassArea.WAVE -> SPAWN_GATHER_MS + 0.88f * SPAWN_MS
    GlassArea.REVEAL -> SPAWN_GATHER_MS + SPAWN_MS
}

/**
 * Gets the glass wave's blur every frame it runs: [level] 0..1 of the chosen [GlassBlur], and
 * [wave] the wave's radius as a fraction of its reach (for [GlassArea.WAVE] and
 * [GlassArea.REVEAL]). 0, 0 clears it.
 */
fun interface GlassHazeTarget {
    fun haze(level: Float, wave: Float)
}

/**
 * Where the blur is, as a soft mask in [color] to scale by the wave (drawn blurs, and the frost's base), or
 * null for the whole screen. The swim-out mask runs past the gradient's end, so all the screen
 * the wave hasn't reached yet is covered.
 */
internal fun hazeMask(area: GlassArea, origin: Offset, reach: Float, color: Color): Brush? = when (area) {
    GlassArea.SCREEN -> null
    GlassArea.WAVE -> fixedRadial(
        origin,
        reach,
        (HAZE_BAND_INNER - 0.08f) to color.copy(alpha = 0f),
        (HAZE_BAND_INNER + 0.02f) to color.copy(alpha = 0.45f),
        0.9f to color,
        HAZE_BAND_OUTER to color,
        1f to color.copy(alpha = 0f),
    )
    GlassArea.REVEAL -> fixedRadial(
        origin,
        reach,
        (HAZE_REVEAL_EDGE - 0.06f) to color.copy(alpha = 0f),
        HAZE_REVEAL_EDGE to color,
        1f to color,
    )
}

/** The glass wave's blur, for content the app draws itself (the dashboard and its preview). */
@Stable
class GlassHaze : GlassHazeTarget {
    var level by mutableFloatStateOf(0f)
        private set
    var wave by mutableFloatStateOf(0f)
        private set

    override fun haze(level: Float, wave: Float) {
        this.level = level
        this.wave = wave
    }
}

/**
 * Blurs this content as the glass wave passes, the way the lock screen gets it: all of it, or
 * a soft band under the crest. While the wave runs the content is drawn once more, blurred and
 * cut to the band; the rest of the time it is drawn as usual. Android 12+; below, nothing.
 * [geometry] and [scale] place the wave as the effect drawn over this content places it.
 */
@Composable
fun Modifier.glassHaze(haze: GlassHaze, settings: GlowSettings, geometry: ScreenGeometry, scale: Float = 1f): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !settings.hazes) return this
    val sharp = rememberGraphicsLayer()
    val soft = rememberGraphicsLayer()
    return drawWithCache {
        val origin = waveOrigin(geometry, size.width, density, scale)
        val reach = waveReach(origin, size.width, size.height)
        val peak = settings.glassBlur.radius.toPx() * scale
        val blurs = Array(GLASS_BLUR_STEPS) { i -> (peak * (i + 1) / GLASS_BLUR_STEPS).let { BlurEffect(it, it) } }
        val mask = hazeMask(settings.glassArea, origin, reach, Color.White)
        val waveOnly = settings.glassArea == GlassArea.WAVE
        // Offscreen, so the band cuts only this layer's copy of the content.
        soft.compositingStrategy = CompositingStrategy.Offscreen
        onDrawWithContent {
            val step = (haze.level * GLASS_BLUR_STEPS + 0.5f).toInt().coerceAtMost(GLASS_BLUR_STEPS)
            val wave = haze.wave
            if (step <= 0 || (waveOnly && wave < MIN_WAVE)) {
                drawContent()
                return@onDrawWithContent
            }
            sharp.record { this@onDrawWithContent.drawContent() }
            drawLayer(sharp)
            soft.renderEffect = blurs[step - 1]
            soft.record {
                drawLayer(sharp)
                if (mask != null) fillScaled(mask, wave.coerceAtLeast(MIN_WAVE), origin, blendMode = BlendMode.DstIn)
            }
            drawLayer(soft)
        }
    }
}
