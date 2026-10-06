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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// The fire wave: the spawn wave as a ring of fire. Its front runs where the glass wave's crest
// does, so the frame and beacons still light as it passes; behind it the flames, sparks and what
// it leaves ([FireWake]); ahead of it, with Burn, the char it burns open. Like every effect
// curve, all of it is a pure function of the effect's clock.
// ---------------------------------------------------------------------------------------------

/** Burn: how dark the char over the lock screen is at its thickest. */
internal const val BURN_CHAR = 0.82f

/** Coals: glowing from as the front leaves them, cooling from and gone by these times into the effect. */
private const val COALS_RISE_MS = 200f
internal const val COALS_COOL_FROM_MS = 850f
internal const val COALS_COOL_TO_MS = 1_600f

/** Sparks: they thin out over this stretch of the spawn's trip, after the flames (which go at 0.5..0.88). */
private const val SPARKS_OUT_FROM = 0.45f
private const val SPARKS_OUT_TO = 0.95f

/** Gradient fire (below Android 13): inner edge of the band it lights, as a fraction of the reach. */
private const val FIRE_BAND_INNER = 0.66f
private const val COAL_BAND_INNER = 0.5f

/** Gradient fire: how far its tongues turn over the trip, and how far they flicker either way. */
private const val FIRE_TURN_DEGREES = 30f
private const val FIRE_FLICKER_DEGREES = 2.5f

/**
 * Ignition: a starburst of rays out of the camera as the fire is released, like an impact frame
 * in animation. It opens from just before release, so it peaks with the flash, and is gone as the
 * ring leaves.
 */
private val STARBURST_RADIUS = 120.dp
private const val STARBURST_LEAD_MS = 20f
private const val STARBURST_MS = 220f

/** The rays' lengths as shares of the radius, round the burst: uneven, so it reads as light, not a cog. */
private val STARBURST_RAYS = floatArrayOf(1f, 0.5f, 0.8f, 0.45f, 0.95f, 0.55f, 0.75f, 0.5f, 1f, 0.6f, 0.85f, 0.45f)

/** How far each ray spreads either side at its root, in radians. */
private const val STARBURST_SPREAD = 0.12f

/** Char: a burnt brown too dark to tell from black, as the shader's. */
private val Char = Color(0xFF090402)

/**
 * The char's cover, 0..1 of [BURN_CHAR]: it rises with the gather, as the swim-out frost does,
 * and what the front can't reach (the far corners) burns off on the frost's curve too, so both
 * reveals keep one timing ([hazeAt]).
 */
internal fun charAt(ms: Float): Float = hazeAt(ms, GlassArea.REVEAL)

/** How brightly the coals glow at [ms], 0..1: lit as the front leaves the camera, then cooling. */
internal fun coalsAt(ms: Float): Float =
    smoothstep(SPAWN_GATHER_MS, SPAWN_GATHER_MS + COALS_RISE_MS, ms) * (1f - smoothstep(COALS_COOL_FROM_MS, COALS_COOL_TO_MS, ms))

/**
 * The share of sparks still flying at [ms], 0..1: they rise out of the flash with the flames, and
 * thin out a little after them, so the last ones drift on as the fire dies.
 */
internal fun sparksAt(ms: Float): Float =
    smoothstep(MIN_WAVE, 0.15f, spawnWaveAt(ms)) * (1f - smoothstep(SPARKS_OUT_FROM, SPARKS_OUT_TO, spawnLinearAt(ms)))

/** How far through the starburst the effect is at [ms]: 0..1 while it plays, outside that range otherwise. */
internal fun starburstAt(ms: Float): Float = (ms - SPAWN_GATHER_MS + STARBURST_LEAD_MS) / STARBURST_MS

/**
 * The fire wave: light gathers in the camera and flashes in the fire's colours, releasing a ring
 * of fire that sweeps the screen and dies down as it reaches the bottom ([crestEnergyAt]). The
 * flames take [color] as [GlowSettings.fireColor] says ([firePalette]).
 * [overBlack]: there is no screen under the char to darken, so only its smouldering edge shows.
 */
@Composable
internal fun FireWave(
    color: Color,
    settings: GlowSettings,
    geometry: ScreenGeometry,
    scale: Float,
    overBlack: Boolean,
    time: () -> Float,
) {
    val wake = settings.fireWake
    val sparks = settings.fireSparks.density
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val origin = waveOrigin(geometry, size.width, density, scale)
                val reach = waveReach(origin, size.width, size.height)
                val palette = firePalette(settings.fireColor, color)
                val veil = wake == FireWake.BURN && !overBlack
                val coals = wake == FireWake.COALS
                val shader = fireShader(origin, palette, settings.fireFlames, coals, density, scale)
                // The shader drawn as a ring band, its width set per frame (mutating it allocates nothing).
                val paint = shader?.let {
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        this.shader = it.runtime
                    }
                }
                // Below Android 13: gradients scaled with the wave; no sparks.
                val front = if (shader == null) fireFront(origin, reach, palette) else null
                val frontBand = Stroke(reach * (1f - FIRE_BAND_INNER))
                val frontRadius = reach * (1f + FIRE_BAND_INNER) / 2f
                val char = if (shader == null && veil) charMask(origin, reach) else null
                val coalGlow = if (shader == null && coals) coalGlow(origin, reach, palette) else null
                val coalBand = Stroke(reach * (1f - COAL_BAND_INNER))
                val coalRadius = reach * (1f + COAL_BAND_INNER) / 2f
                val flash = spawnFlash(palette.body, settings, geometry, scale, origin)
                val burstRadius = STARBURST_RADIUS.toPx() * scale
                val burst = starburst(origin, burstRadius)
                val burstLight = fixedRadial(
                    origin,
                    burstRadius,
                    0f to palette.core,
                    0.25f to palette.hot,
                    0.55f to palette.body,
                    1f to palette.flare.copy(alpha = 0f),
                )
                onDrawBehind {
                    val ms = time()
                    val wave = spawnWaveAt(ms)
                    // The flames rise out of the flash from nothing, hiding the first frames, where
                    // the front moves more than a tongue's length.
                    val energy = crestEnergyAt(ms, wave) * smoothstep(MIN_WAVE, 0.15f, wave)
                    val cover = if (veil) BURN_CHAR * charAt(ms) else 0f
                    val glow = if (coals) coalsAt(ms) else 0f
                    if (shader != null && paint != null) {
                        val flying = sparks * sparksAt(ms)
                        if (energy > 0f || cover > 0f || glow > 0f || flying > 0f) {
                            val radius = CREST_AT * reach * wave
                            val scorch = if (wake == FireWake.BURN) energy else 0f
                            shader.update(radius, energy, cover, scorch, glow, flying, ms)
                            // Only the ring the shader can draw in, out to the screen's end while
                            // there is char ahead.
                            val from = (radius - shader.behindPx).coerceAtLeast(0f)
                            val to = if (cover > 0f) reach else radius + shader.aheadPx
                            paint.strokeWidth = to - from
                            drawIntoCanvas { it.nativeCanvas.drawCircle(origin.x, origin.y, (from + to) / 2f, paint) }
                        }
                    } else {
                        val s = wave.coerceAtLeast(MIN_WAVE)
                        if (char != null && cover > 0f) fillScaled(char, s, origin, alpha = cover)
                        if (wave >= MIN_WAVE) {
                            if (coalGlow != null && glow > 0f) drawBand(coalGlow, s, 0f, origin, coalRadius, coalBand, glow)
                            if (front != null && energy > 0f) {
                                val turn = FIRE_TURN_DEGREES * spawnLinearAt(ms) + FIRE_FLICKER_DEGREES * sin(ms / 45f)
                                drawBand(front, s, turn, origin, frontRadius, frontBand, energy)
                            }
                        }
                    }
                    val t = starburstAt(ms)
                    if (t > 0f && t < 1f) {
                        // Bursts open fast and slows; bright at once, gone before the ring is far: a snap, not a glow.
                        val open = 1f - (1f - t) * (1f - t) * (1f - t)
                        val alpha = 0.9f * (1f - t) * (1f - t) * (1f - t) * minOf(1f, 8f * t)
                        rotate(8f * t, origin) {
                            scale(0.35f + 0.75f * open, origin) { drawPath(burst, burstLight, alpha = alpha) }
                        }
                    }
                    drawSpawnFlash(flash, origin, scale, ms)
                }
            },
    )
}

/** The starburst's rays round [origin], each a thin spike from near the centre out to its length. */
private fun starburst(origin: Offset, radius: Float): Path = Path().apply {
    val root = 0.12f * radius
    STARBURST_RAYS.forEachIndexed { i, length ->
        val a = 2f * PI.toFloat() * i / STARBURST_RAYS.size
        fun at(r: Float, angle: Float) = Offset(origin.x + r * cos(angle), origin.y + r * sin(angle))
        val left = at(root, a - STARBURST_SPREAD)
        val tip = at(length * radius, a)
        val right = at(root, a + STARBURST_SPREAD)
        moveTo(left.x, left.y)
        lineTo(tip.x, tip.y)
        lineTo(right.x, right.y)
        close()
    }
}

/** One band of a gradient fire at wave scale [s], turned [turn] degrees: the lit ring only. */
internal fun DrawScope.drawBand(brush: Brush, s: Float, turn: Float, origin: Offset, radius: Float, band: Stroke, alpha: Float) {
    rotate(turn, origin) {
        scale(s, origin) { drawCircle(brush, radius, origin, alpha = alpha, style = band) }
    }
}

/**
 * The fire's front below Android 13: white-hot at the crest, reddening into tips trailing behind
 * it, its brightness uneven round the ring (a sweep laid on with DstIn) so it reads as tongues.
 */
private fun fireFront(origin: Offset, reach: Float, palette: FirePalette): Brush {
    // Fades end in their own colour at zero alpha, not transparent black, so no grey seams.
    val profile = listOf(
        FIRE_BAND_INNER to palette.tip.copy(alpha = 0f),
        0.8f to palette.tip.copy(alpha = 0.25f),
        0.87f to palette.flare.copy(alpha = 0.5f),
        0.91f to palette.body.copy(alpha = 0.75f),
        0.93f to palette.hot.copy(alpha = 0.9f),
        CREST_AT to palette.core.copy(alpha = 0.95f),
        0.95f to palette.hot.copy(alpha = 0.4f),
        0.962f to palette.body.copy(alpha = 0f),
    )
    // Uneven, so no two tongues look alike; the last stop matches the first.
    val levels = floatArrayOf(1f, 0.45f, 0.85f, 0.35f, 0.95f, 0.5f, 0.75f, 0.3f)
    val tongues = List(TONGUE_STOPS + 1) { i -> i / TONGUE_STOPS.toFloat() to Color.White.copy(alpha = levels[i % levels.size]) }
    return FixedShaderBrush(
        android.graphics.ComposeShader(
            RadialGradientShader(origin, reach, profile.map { it.second }, profile.map { it.first }),
            SweepGradientShader(origin, tongues.map { it.second }, tongues.map { it.first }),
            android.graphics.PorterDuff.Mode.DST_IN,
        ),
    )
}

/** Gradient fire: tongues round the ring; a multiple of the levels' count, so the sweep closes seamlessly. */
private const val TONGUE_STOPS = 48

/** Burn's char below Android 13: clear behind the front, dark from just ahead of it to past the screen. */
private fun charMask(origin: Offset, reach: Float): Brush = fixedRadial(
    origin,
    reach,
    0.925f to Char.copy(alpha = 0f),
    0.945f to Char,
    1f to Char,
)

/** The coals below Android 13: a glow trailing the front, warmest just behind it. */
private fun coalGlow(origin: Offset, reach: Float, palette: FirePalette): Brush = fixedRadial(
    origin,
    reach,
    COAL_BAND_INNER to palette.ember.copy(alpha = 0f),
    0.8f to palette.ember.copy(alpha = 0.3f),
    0.92f to palette.flare.copy(alpha = 0.45f),
    0.94f to palette.flare.copy(alpha = 0f),
)
