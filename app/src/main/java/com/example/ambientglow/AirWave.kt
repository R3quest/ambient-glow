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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// The air wave: the spawn wave as a gust. The camera winds up and lets out a whirl, and lines
// of wind sweep the screen, curling, with the air they move through and what they carry. Its
// front runs where the glass wave's crest does, so the frame and beacons still light as it
// passes. Like every effect curve, all of it is a pure function of the effect's clock.
// ---------------------------------------------------------------------------------------------

/** The lines die down over this stretch of the spawn's trip, a little after the glass wave's crest (0.5..0.88). */
private const val GUST_OUT_FROM = 0.5f
private const val GUST_OUT_TO = 0.92f

/** What the gust carries: in the air from as it leaves the camera, gone from and by these times into the effect. */
internal const val CARRY_OUT_FROM_MS = 950f
internal const val CARRY_OUT_TO_MS = 1_500f

/** What the gust carries drifts on out at this many dp a second, and falls from this time into the effect at this many dp/s². */
private const val DRIFT_DP_PER_S = 50f
private const val FALL_FROM_MS = 600f
private const val FALL_DP_PER_S2 = 170f

/**
 * The whirl: blades round the camera that wind up into it during the gather, turning back
 * against the wind as a spring is wound, then spin out with the gust as it is released, like the
 * wind up before a throw in animation. Gone as the lines come in.
 */
private val WHIRL_RADIUS = 72.dp
private const val WHIRL_BLADES = 5
private const val WHIRL_MS = 320f
private const val WHIRL_WIND_UP_DEGREES = 40f
private const val WHIRL_TURN_DEGREES = 80f

/** How far each blade bends round as it runs out, in radians, and its width as a share of the whirl. */
private const val WHIRL_BEND = 1.5f
private const val WHIRL_WIDTH = 0.17f

/** Gradient air (below Android 13): inner edge of the band it lights, as a fraction of the reach. */
private const val AIR_BAND_INNER = 0.7f

/** Gradient air: how far its streaks turn over the trip, at a lean of 1 (so not at all for Streaks). */
private const val AIR_TURN_DEGREES = 60f

/** The puff thrown out with the whirl: there as the gust is let go, gone over this stretch of its trip. */
private const val PUFF_OUT_FROM = 0.04f
private const val PUFF_OUT_TO = 0.38f

/** The puff's light at [ms]: it bursts out of the camera as the gust is let go and thins away as it spreads. */
internal fun puffAt(ms: Float): Float {
    val trip = spawnLinearAt(ms)
    return smoothstep(0f, 0.02f, trip) * (1f - smoothstep(PUFF_OUT_FROM, PUFF_OUT_TO, trip))
}

/** The lines' light at [ms]: they rise out of the flash as the gust leaves the camera, and die down as it reaches the bottom. */
internal fun gustAt(ms: Float): Float =
    smoothstep(MIN_WAVE, 0.12f, spawnWaveAt(ms)) * (1f - smoothstep(GUST_OUT_FROM, GUST_OUT_TO, spawnLinearAt(ms)))

/** The share of what the gust carries still in the air at [ms]: picked up as it leaves the camera, settling well after the lines die. */
internal fun carryAt(ms: Float): Float =
    smoothstep(MIN_WAVE, 0.2f, spawnWaveAt(ms)) * (1f - smoothstep(CARRY_OUT_FROM_MS, CARRY_OUT_TO_MS, ms))

/** How far what the gust carries has drifted on past where the gust took it, in dp. */
internal fun airDriftAt(ms: Float): Float = DRIFT_DP_PER_S * max(0f, ms - SPAWN_GATHER_MS) / 1000f

/** How far what the gust carries has fallen, in dp: nothing while the gust holds it up, then gathering speed. */
internal fun airFallAt(ms: Float): Float {
    val t = max(0f, ms - FALL_FROM_MS) / 1000f
    return 0.5f * FALL_DP_PER_S2 * t * t
}

/** How far through the whirl's spin out the effect is at [ms]: below 0 while it winds up, past 1 once it has gone. */
internal fun whirlAt(ms: Float): Float = (ms - SPAWN_GATHER_MS) / WHIRL_MS

/**
 * The air wave: the camera winds up and releases a gust, its lines of wind sweeping the screen
 * and dying down as it reaches the bottom ([gustAt]), carrying what [GlowSettings.airCarry] says
 * out with it and letting it fall. The wind takes [color] as [GlowSettings.airColor] says
 * ([airPalette]). On black it plays as it does anywhere: wind has nothing to leave behind.
 */
@Composable
internal fun AirWave(
    color: Color,
    settings: GlowSettings,
    geometry: ScreenGeometry,
    scale: Float,
    time: () -> Float,
) {
    val carrying = settings.airCarry != AirCarry.NONE
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val origin = waveOrigin(geometry, size.width, density, scale)
                val reach = waveReach(origin, size.width, size.height)
                val palette = airPalette(settings.airColor, color)
                val shader = airShader(origin, palette, settings.airGust, settings.airFlow, settings.airCarry, density, scale)
                // The shader drawn as a ring band, its width set per frame (mutating it allocates nothing).
                val paint = shader?.let {
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        this.shader = it.runtime
                    }
                }
                // Below Android 13: a streaked gradient scaled with the wave, turning with the flow; nothing carried.
                val front = if (shader == null) windFront(origin, reach, palette) else null
                val frontBand = Stroke(reach * (1f - AIR_BAND_INNER))
                val frontRadius = reach * (1f + AIR_BAND_INNER) / 2f
                // Positive degrees turn clockwise on screen; the wind leans the other way.
                val frontTurn = -AIR_TURN_DEGREES * settings.airFlow.pitch
                val flash = spawnFlash(palette.glow, settings, geometry, scale, origin)
                val whirlRadius = WHIRL_RADIUS.toPx() * scale
                val whirl = whirl(origin, whirlRadius)
                val whirlLight = fixedRadial(
                    origin,
                    whirlRadius,
                    0f to palette.core,
                    0.3f to palette.body,
                    0.65f to palette.glow,
                    1f to palette.glow.copy(alpha = 0f),
                )
                onDrawBehind {
                    val ms = time()
                    val wave = spawnWaveAt(ms)
                    val energy = gustAt(ms)
                    val held = if (carrying) carryAt(ms) else 0f
                    val puff = puffAt(ms)
                    if (shader != null && paint != null) {
                        if (energy > 0f || puff > 0f || held > 0f) {
                            val radius = CREST_AT * reach * wave
                            shader.update(radius, wave, energy, puff, held, ms)
                            // Only the ring the shader can draw in: the lines round the front and
                            // the tongues surging ahead of it, and what the gust carries, as far as
                            // it has drifted and fallen.
                            var from = radius - shader.behindPx
                            var to = radius * (1f + GUST_SURGE) + shader.aheadPx
                            if (held > 0f) {
                                val spread = shader.fallPx + 2f * shader.carrySizePx
                                from = min(from, AIR_CARRY_FROM * radius + shader.driftPx - spread)
                                to = max(to, radius + shader.driftPx + spread)
                            }
                            from = from.coerceAtLeast(0f)
                            paint.strokeWidth = to - from
                            drawIntoCanvas { it.nativeCanvas.drawCircle(origin.x, origin.y, (from + to) / 2f, paint) }
                        }
                    } else if (front != null && wave >= MIN_WAVE && energy > 0f) {
                        drawBand(front, wave, frontTurn * spawnLinearAt(ms), origin, frontRadius, frontBand, energy)
                    }
                    val t = whirlAt(ms)
                    if (t < 1f) {
                        val s: Float
                        val turn: Float
                        val alpha: Float
                        if (t < 0f) {
                            // Winding up: drawn in and turned back against the wind, pooling as the flash does.
                            val g = (ms / SPAWN_GATHER_MS).coerceIn(0f, 1f)
                            s = 0.75f - 0.4f * g * g
                            turn = WHIRL_WIND_UP_DEGREES * g * (2f - g)
                            alpha = 0.5f * g * g
                        } else {
                            // Let go: it spins out fast and slows, fading before the lines are far.
                            val open = 1f - (1f - t).pow(3)
                            s = 0.35f + 0.95f * open
                            turn = WHIRL_WIND_UP_DEGREES - (WHIRL_WIND_UP_DEGREES + WHIRL_TURN_DEGREES) * open
                            alpha = (0.5f + 0.2f * min(1f, 6f * t)) * (1f - t) * (1f - t)
                        }
                        if (alpha > 0f) {
                            rotate(turn, origin) {
                                scale(s, origin) { drawPath(whirl, whirlLight, alpha = alpha) }
                            }
                        }
                    }
                    drawSpawnFlash(flash, origin, scale, ms)
                }
            },
    )
}

/**
 * The whirl's blades round [origin]: crescents that run out from near the centre, bending round
 * the way the wind turns (anticlockwise on screen, as the lines lean), pointed at both ends and
 * widest past their middle.
 */
private fun whirl(origin: Offset, radius: Float): Path {
    val steps = 14
    // The blade's centre line at t (0 root, 1 tip): angle measured from straight down towards the right.
    fun at(blade: Int, t: Float): Offset {
        val a = 2f * PI.toFloat() * blade / WHIRL_BLADES + WHIRL_BEND * t
        val r = radius * (0.15f + 0.85f * t)
        return Offset(origin.x + r * sin(a), origin.y + r * cos(a))
    }
    return Path().apply {
        repeat(WHIRL_BLADES) { blade ->
            val left = ArrayList<Offset>(steps + 1)
            val right = ArrayList<Offset>(steps + 1)
            for (j in 0..steps) {
                val t = j / steps.toFloat()
                val p = at(blade, t)
                val ahead = at(blade, min(1f, t + 0.01f))
                val behind = at(blade, max(0f, t - 0.01f))
                val dx = ahead.x - behind.x
                val dy = ahead.y - behind.y
                val n = hypot(dx, dy).coerceAtLeast(1e-3f)
                val w = WHIRL_WIDTH * radius * sin(PI.toFloat() * t.pow(0.7f)) / 2f
                left += Offset(p.x - dy / n * w, p.y + dx / n * w)
                right += Offset(p.x + dy / n * w, p.y - dx / n * w)
            }
            moveTo(left[0].x, left[0].y)
            for (j in 1..steps) lineTo(left[j].x, left[j].y)
            for (j in steps downTo 0) lineTo(right[j].x, right[j].y)
            close()
        }
    }
}

/**
 * The gust below Android 13: a band white at the front and trailing its glow, its brightness
 * streaked round the ring (a sweep laid on with DstIn) so it reads as lines of wind.
 */
private fun windFront(origin: Offset, reach: Float, palette: AirPalette): Brush {
    // Fades end in their own colour at zero alpha, not transparent black, so no grey seams.
    val profile = listOf(
        AIR_BAND_INNER to palette.shade.copy(alpha = 0f),
        0.82f to palette.glow.copy(alpha = 0.12f),
        0.9f to palette.glow.copy(alpha = 0.3f),
        0.93f to palette.body.copy(alpha = 0.6f),
        CREST_AT to palette.core.copy(alpha = 0.85f),
        0.955f to palette.body.copy(alpha = 0.25f),
        0.97f to palette.glow.copy(alpha = 0f),
    )
    // Uneven, so no two streaks look alike; the last stop matches the first.
    val levels = floatArrayOf(1f, 0.15f, 0.7f, 0.05f, 0.9f, 0.3f, 0.1f, 0.8f, 0.2f, 0.6f, 0.05f, 0.5f)
    val streaks = List(STREAK_STOPS + 1) { i -> i / STREAK_STOPS.toFloat() to Color.White.copy(alpha = levels[i % levels.size]) }
    return FixedShaderBrush(
        android.graphics.ComposeShader(
            RadialGradientShader(origin, reach, profile.map { it.second }, profile.map { it.first }),
            SweepGradientShader(origin, streaks.map { it.second }, streaks.map { it.first }),
            android.graphics.PorterDuff.Mode.DST_IN,
        ),
    )
}

/** Gradient air: streaks round the ring; a multiple of the levels' count, so the sweep closes seamlessly. */
private const val STREAK_STOPS = 96
