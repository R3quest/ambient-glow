package com.example.ambientglow

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// The earth wave: the spawn wave as a quake. The ground trembles as the light gathers, the camera
// strikes it, cracks snap out of the lens and the ground jolts; then a shock ring runs out and
// the ground behind it breaks open in light and stone. Its front runs where the glass wave's
// crest does, so the frame and beacons still light as it passes. Like every effect curve, all of
// it is a pure function of the effect's clock.
// ---------------------------------------------------------------------------------------------

/** The light and the stone die down over this stretch of the spawn's trip, as the glass wave's crest does (0.5..0.88). */
private const val QUAKE_OUT_FROM = 0.5f
private const val QUAKE_OUT_TO = 0.9f

/** What the quake throws up: in the air from as it leaves the camera, settled from and by these times into the effect. */
internal const val DEBRIS_OUT_FROM_MS = 900f
internal const val DEBRIS_OUT_TO_MS = 1_450f

/** The smoke burst round the camera as the ground is struck plays out over this long. */
private const val SMOKE_BURST_MS = 650f

/** Rubble is thrown up at this many dp a second as the ground is struck, and falls back at this many dp/s². */
private const val THROW_DP_PER_S = 220f
private const val GRAVITY_DP_PER_S2 = 640f

/**
 * The impact: cracks snapping out of the lens as the camera strikes the ground, like an impact
 * frame in animation. They charge up small and faint through the gather, snap open at release and
 * are gone as the shock ring leaves.
 */
private val IMPACT_RADIUS = 130.dp
private const val IMPACT_MS = 300f

/** The cracks round the lens: angles as shares of the turn, uneven so it reads as breaking, not a cog. */
private val IMPACT_ANGLES = floatArrayOf(0.03f, 0.15f, 0.29f, 0.4f, 0.53f, 0.66f, 0.79f, 0.9f)

/** Each crack's length as a share of the radius, and whether it forks. */
private val IMPACT_LENGTHS = floatArrayOf(1f, 0.55f, 0.85f, 0.5f, 0.95f, 0.6f, 0.8f, 0.5f)

/** The zigzag along a crack: sideways offsets as shares of its length, at even steps from root to tip. */
private val IMPACT_ZIG = floatArrayOf(0f, 0.07f, -0.05f, 0.08f, -0.04f, 0.03f)

/** A crack's half-width at its root, as a share of the radius; it starts this far out of the lens. */
private const val IMPACT_WIDTH = 0.045f
private const val IMPACT_ROOT = 0.14f

/**
 * The shake: the ground trembles through the gather, jolts as it is struck and settles over
 * [RUMBLE_MS]. Only the earth shakes, never the frame or the flash, which sit on the camera.
 */
private const val RUMBLE_MS = 420f
private const val RUMBLE_GATHER = 0.25f
private const val SHAKE_X_HZ = 19f
private const val SHAKE_Y_HZ = 14f

/** Gradient earth (below Android 13): inner edge of the band it lights, as a fraction of the reach. */
private const val EARTH_BAND_INNER = 0.7f

/** The light and the stone at [ms]: they break out of the flash as the front leaves the camera, and die down as it reaches the bottom. */
internal fun quakeAt(ms: Float): Float =
    smoothstep(MIN_WAVE, 0.1f, spawnWaveAt(ms)) * (1f - smoothstep(QUAKE_OUT_FROM, QUAKE_OUT_TO, spawnLinearAt(ms)))

/** The share of what the quake throws up still in the air at [ms]: kicked up as the front leaves the camera, settled well after the light dies. */
internal fun debrisAt(ms: Float): Float =
    smoothstep(MIN_WAVE, 0.15f, spawnWaveAt(ms)) * (1f - smoothstep(DEBRIS_OUT_FROM_MS, DEBRIS_OUT_TO_MS, ms))

/** How far up the screen the rubble has been thrown at [ms], in dp: below 0 while it rises, then falling back past where it started. */
internal fun rubbleLiftAt(ms: Float): Float {
    val t = max(0f, ms - SPAWN_GATHER_MS) / 1000f
    return -THROW_DP_PER_S * t + 0.5f * GRAVITY_DP_PER_S2 * t * t
}

/** How far through the smoke burst round the camera the effect is at [ms]: 0..1 while it plays. */
internal fun smokeBurstAt(ms: Float): Float = (ms - SPAWN_GATHER_MS) / SMOKE_BURST_MS

/** How far through the impact the effect is at [ms]: below 0 while it charges, past 1 once it has gone. */
internal fun impactAt(ms: Float): Float = (ms - SPAWN_GATHER_MS) / IMPACT_MS

/**
 * How hard the ground shakes at [ms], 0..1: a tremble building through the gather, a jolt as it
 * is struck, settling to nothing.
 */
internal fun rumbleAt(ms: Float): Float {
    if (ms < SPAWN_GATHER_MS) {
        val g = (ms / SPAWN_GATHER_MS).coerceAtLeast(0f)
        return RUMBLE_GATHER * g * g
    }
    val k = 1f - (ms - SPAWN_GATHER_MS) / RUMBLE_MS
    return if (k <= 0f) 0f else k * k
}

/**
 * The earth wave: light gathers in the camera as the ground trembles, the camera strikes it and
 * a shock ring runs out, the ground behind it breaking as [GlowSettings.earthForm] says, and dying
 * down as it reaches the bottom ([quakeAt]); it throws up what [GlowSettings.earthDebris] says.
 * The light and the stone take [color] as [GlowSettings.earthColor] says ([earthPalette]). On
 * black it plays as it does anywhere: the stone brings its own ground.
 */
@Composable
internal fun EarthWave(
    color: Color,
    settings: GlowSettings,
    geometry: ScreenGeometry,
    scale: Float,
    time: () -> Float,
) {
    val throws = settings.earthDebris != EarthDebris.NONE
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val origin = waveOrigin(geometry, size.width, density, scale)
                val reach = waveReach(origin, size.width, size.height)
                val palette = earthPalette(settings.earthColor, color)
                val crystal = settings.earthColor == EarthColor.CRYSTAL
                val shader = earthShader(origin, palette, settings.earthForce, settings.earthForm, settings.earthDebris, crystal, density, scale)
                // The shader drawn as a ring band, its width set per frame (mutating it allocates nothing).
                val paint = shader?.let {
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        this.shader = it.runtime
                    }
                }
                // Below Android 13: a band of light broken into cracks round the ring, scaled with the wave; no stone.
                val front = if (shader == null) quakeFront(origin, reach, palette) else null
                val frontBand = Stroke(reach * (1f - EARTH_BAND_INNER))
                val frontRadius = reach * (1f + EARTH_BAND_INNER) / 2f
                val flash = spawnFlash(palette.glow, settings, geometry, scale, origin)
                val impactRadius = IMPACT_RADIUS.toPx() * scale
                val impact = impactCracks(origin, impactRadius)
                val impactLight = fixedRadial(
                    origin,
                    impactRadius,
                    0f to palette.core,
                    0.3f to palette.glow,
                    0.7f to palette.deep,
                    1f to palette.deep.copy(alpha = 0f),
                )
                val shake = settings.earthForce.shake.toPx() * scale
                onDrawBehind {
                    val ms = time()
                    val wave = spawnWaveAt(ms)
                    val energy = quakeAt(ms)
                    val debris = if (throws) debrisAt(ms) else 0f
                    val jolt = shake * rumbleAt(ms)
                    val s = ms / 1000f * 2f * PI.toFloat()
                    translate(jolt * sin(SHAKE_X_HZ * s), jolt * sin(SHAKE_Y_HZ * s + 1.3f)) {
                        if (shader != null && paint != null) {
                            if (energy > 0f || debris > 0f) {
                                val radius = CREST_AT * reach * wave
                                shader.update(radius, energy, debris, ms)
                                // Only the ring the shader can draw in: the ground round the front,
                                // and the rubble as far as it has been thrown.
                                var from = radius - shader.behindPx
                                var to = radius + shader.aheadPx
                                // The smoke burst round the camera, while it lasts: the whole disc.
                                if (shader.burstPx > 0f) {
                                    from = 0f
                                    to = max(to, shader.burstPx)
                                }
                                if (shader.rubbleSizePx > 0f && debris > 0f) {
                                    val spread = abs(shader.liftPx) + 2f * shader.rubbleSizePx
                                    from = min(from, EARTH_DEBRIS_FROM * radius - spread)
                                    to = max(to, radius + spread)
                                }
                                from = from.coerceAtLeast(0f)
                                paint.strokeWidth = to - from
                                drawIntoCanvas { it.nativeCanvas.drawCircle(origin.x, origin.y, (from + to) / 2f, paint) }
                            }
                        } else if (front != null && wave >= MIN_WAVE && energy > 0f) {
                            drawBand(front, wave, 0f, origin, frontRadius, frontBand, energy)
                        }
                        val t = impactAt(ms)
                        if (t < 1f) {
                            val k: Float
                            val alpha: Float
                            if (t < 0f) {
                                // Charging: small and faint, brightening as the light pools.
                                val g = (ms / SPAWN_GATHER_MS).coerceIn(0f, 1f)
                                k = 0.18f + 0.12f * g * g
                                alpha = 0.5f * g * g
                            } else {
                                // Struck: the cracks snap open fast and slow, gone before the ring is far.
                                val open = 1f - (1f - t).pow(3)
                                k = 0.3f + 0.8f * open
                                alpha = (0.55f + 0.4f * min(1f, 8f * t)) * (1f - t) * (1f - t)
                            }
                            if (alpha > 0f) scale(k, origin) { drawPath(impact, impactLight, alpha = alpha) }
                        }
                    }
                    drawSpawnFlash(flash, origin, scale, ms)
                }
            },
    )
}

/**
 * The impact's cracks round [origin]: each a jagged blade from just out of the lens to its
 * length, widest at its root and tapering to a point, the long ones forking once.
 */
private fun impactCracks(origin: Offset, radius: Float): Path = Path().apply {
    val steps = IMPACT_ZIG.size
    IMPACT_ANGLES.forEachIndexed { i, share ->
        val angle = 2f * PI.toFloat() * share
        val length = IMPACT_LENGTHS[i] * radius
        // Alternate cracks zig the other way, so no two look alike.
        val flip = if (i % 2 == 0) 1f else -1f
        val along = Offset(cos(angle), sin(angle))
        val across = Offset(-along.y, along.x)
        val points = Array(steps + 1) { k ->
            val t = k / steps.toFloat()
            val side = if (k < steps) flip * IMPACT_ZIG[k] * length else 0f
            origin + along * (IMPACT_ROOT * radius + t * (length - IMPACT_ROOT * radius)) + across * side
        }
        blade(points, IMPACT_WIDTH * radius)
        // The long ones fork a third of the way out, the branch turning away from the zig.
        if (IMPACT_LENGTHS[i] > 0.75f) {
            val at = points[2]
            val turn = angle - flip * 0.6f
            val tip = at + Offset(cos(turn), sin(turn)) * (0.35f * length)
            blade(arrayOf(at, (at + tip) / 2f + across * (0.03f * length * flip), tip), 0.5f * IMPACT_WIDTH * radius)
        }
    }
}

/** A blade along [points], [width] either side at its root and tapering to nothing at its tip. */
private fun Path.blade(points: Array<Offset>, width: Float) {
    val n = points.size - 1
    val left = ArrayList<Offset>(n + 1)
    val right = ArrayList<Offset>(n + 1)
    for (k in 0..n) {
        val ahead = points[min(n, k + 1)]
        val behind = points[max(0, k - 1)]
        val dx = ahead.x - behind.x
        val dy = ahead.y - behind.y
        val len = hypot(dx, dy).coerceAtLeast(1e-3f)
        val w = width * (1f - k / n.toFloat()).pow(0.8f)
        left += Offset(points[k].x - dy / len * w, points[k].y + dx / len * w)
        right += Offset(points[k].x + dy / len * w, points[k].y - dx / len * w)
    }
    moveTo(left[0].x, left[0].y)
    for (k in 1..n) lineTo(left[k].x, left[k].y)
    for (k in n downTo 0) lineTo(right[k].x, right[k].y)
    close()
}

/**
 * The quake below Android 13: a band of the cracks' light, white at the front and cooling behind
 * it, its brightness broken round the ring (a sweep laid on with DstIn) into narrow cracks.
 */
private fun quakeFront(origin: Offset, reach: Float, palette: EarthPalette): Brush {
    // Fades end in their own colour at zero alpha, not transparent black, so no grey seams.
    val profile = listOf(
        EARTH_BAND_INNER to palette.deep.copy(alpha = 0f),
        0.84f to palette.deep.copy(alpha = 0.25f),
        0.91f to palette.glow.copy(alpha = 0.55f),
        CREST_AT to palette.core.copy(alpha = 0.9f),
        0.95f to palette.glow.copy(alpha = 0.3f),
        0.965f to palette.glow.copy(alpha = 0f),
    )
    // Narrow bright cracks between dim ground; uneven, and the last stop matches the first.
    val levels = floatArrayOf(1f, 0.1f, 0.25f, 0.05f, 0.85f, 0.15f, 0.05f, 0.6f, 0.1f, 0.3f, 0.05f, 0.95f, 0.2f, 0.05f, 0.4f, 0.1f)
    val cracks = List(CRACK_STOPS + 1) { i -> i / CRACK_STOPS.toFloat() to Color.White.copy(alpha = levels[i % levels.size]) }
    return FixedShaderBrush(
        android.graphics.ComposeShader(
            RadialGradientShader(origin, reach, profile.map { it.second }, profile.map { it.first }),
            SweepGradientShader(origin, cracks.map { it.second }, cracks.map { it.first }),
            android.graphics.PorterDuff.Mode.DST_IN,
        ),
    )
}

/** Gradient earth: cracks round the ring; a multiple of the levels' count, so the sweep closes seamlessly. */
private const val CRACK_STOPS = 96
