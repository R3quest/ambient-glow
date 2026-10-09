package app.lumement

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// The element's touch as the hand-off's LED lands ([LedHandOff]): one small gesture each, in the
// element's own way, so the landing has a character without competing with the LED.
// - Water: a splash where it closed, fine spray bursting out and gone, and a ripple on the surface.
// - Fire: the flame catches at the bottom of the ring and runs up both sides as two warm streams;
//   where they meet at the top a warm bloom swells and fades, and embers drift off like fireflies.
// - Air: wind streaming round it, as the gust's lines do on the lock screen, and what the gust
//   carries ([GlowSettings.airCarry]) fluttering off.
// - Earth: chips of stone gather round it as it settles, on a tilted orbit (passing behind it and
//   in front), slow, and sink into the ring.
// - Neon: a ripple, and a small sparkle where it closed.
// Each is built once per size, in units [u] (the token's), with only what it draws; a frame only
// reads the time since the landing. The LED sits at the top of the screen, so what is thrown fans
// sideways and down rather than up.
// ---------------------------------------------------------------------------------------------

/**
 * Where the LED lands: at [center], what is thrown leaving it [from] out (its ring, or its dot's
 * edge), its ripples starting at [base], and where it closed, [seal]. [around]: the ring.
 */
internal class Landing(val center: Offset, val from: Float, val base: Float, val seal: Offset, val around: Boolean) {
    /** Outwards from where it closed; off a dot, up. */
    val normal: Offset = if (around && seal != center) (seal - center) / (seal - center).getDistance() else Offset(0f, -1f)

    /** Where what is thrown leaves from. */
    val start: Offset = if (around) seal else center + normal * from
}

/** The touch for [token]: in the message's [brand] colour (the token's [landing] and [trail] colours) and units of [u] px. */
internal fun landingTouch(token: HandOffToken, settings: GlowSettings, brand: Color, landing: Color, trail: Color, u: Float): LandingTouch =
    when (token) {
        HandOffToken.DROP -> WaterTouch(landing, u)
        HandOffToken.EMBER -> FireTouch(firePalette(settings.fireColor, brand), landing, u)
        HandOffToken.WISP -> AirTouch(settings.airCarry, airPalette(settings.airColor, brand), landing, trail, u)
        HandOffToken.GEM -> EarthTouch(earthPalette(settings.earthColor, brand), landing, u)
        HandOffToken.SPARK -> NeonTouch(landing, u)
    }

internal abstract class LandingTouch(protected val landing: Color, protected val u: Float) {
    protected val hot = lerp(landing, Color.White, 0.6f)

    /** [t] ms after the landing, at [at]: under the LED, or ([front]) what passes in front of it. */
    fun draw(scope: DrawScope, t: Float, at: Landing, front: Boolean) {
        if (t <= 0f) return
        with(scope) { if (front) drawFront(t, at) else drawUnder(t, at) }
    }

    protected abstract fun DrawScope.drawUnder(t: Float, at: Landing)

    /** Only Earth's chips pass in front of the LED. */
    protected open fun DrawScope.drawFront(t: Float, at: Landing) = Unit

    /** A soft glow of [radius] at [at], [brush] built about the origin at a radius of 1. */
    protected fun DrawScope.glowAt(brush: Brush, at: Offset, radius: Float, alpha: Float, blend: BlendMode = BlendMode.SrcOver) {
        if (alpha <= 0f || radius <= 0f) return
        translate(at.x, at.y) { scale(radius, Offset.Zero) { drawCircle(brush, 1f, Offset.Zero, alpha = alpha, blendMode = blend) } }
    }
}

private class WaterTouch(landing: Color, u: Float) : LandingTouch(landing, u) {
    private val crest = Stroke(max(1f, 0.18f * u))
    private val trough = Stroke(1.1f * u)
    private val ripple = lerp(landing, Color.White, 0.3f)

    override fun DrawScope.drawUnder(t: Float, at: Landing) {
        // A ripple running out on the surface, a bright crest over a soft trough.
        val p = t / 620f
        if (p < 1f) {
            val q = 1f - p
            val r = at.base + 2.8f * u * glideOut(p)
            val a = 0.5f * q * q * (p / 0.08f).coerceAtMost(1f)
            drawCircle(ripple, r, at.center, alpha = a, style = crest)
            drawCircle(landing, r - 0.6f * u, at.center, alpha = 0.07f * a, style = trough)
        }
        // The splash: fine spray bursting out of where it closed, slowing at once and gone.
        for (i in 0 until 11) {
            val sp = t / (220f + 140f * jitter(i + 83))
            if (sp >= 1f) continue
            val sq = 1f - sp
            val dir = rotateBy(at.normal, (-1f + 2f * (i + jitter(i)) / 11f) * 1.5f)
            val reach = (1.1f + 1.3f * jitter(i + 31)) * u
            val r = (0.09f + 0.08f * jitter(i + 57)) * u * (0.4f + 0.6f * sq)
            drawCircle(hot, r, at.start + dir * (reach * glideOut(sp)), alpha = sq * (sp / 0.06f).coerceAtMost(1f))
        }
    }
}

private class FireTouch(fire: FirePalette, landing: Color, u: Float) : LandingTouch(landing, u) {
    // The two streams' steps of flame, hot at the head.
    private val flameStroke = Array(STREAK_STEPS) { k -> Stroke((0.36f - 0.045f * k) * u, cap = if (k == 0) StrokeCap.Round else StrokeCap.Butt) }
    private val flameColors = Array(STREAK_STEPS) { k -> lerp(fire.hot, fire.flare, k / (STREAK_STEPS - 1f)) }

    private val ember = Brush.radialGradient(
        0f to fire.core,
        0.22f to fire.hot,
        0.5f to fire.flare.copy(alpha = 0.45f),
        1f to fire.flare.copy(alpha = 0f),
        center = Offset.Zero,
        radius = 1f,
    )

    override fun DrawScope.drawUnder(t: Float, at: Landing) {
        // The flame catches at the bottom of the ring and runs up both sides, two warm streams with
        // their heat at the head, as Air's wind runs round it.
        if (at.around) {
            val fade = 1f - smoothstep(250f, 520f, t)
            if (fade > 0f) {
                val travel = 180f * FlameRun.transform((t / 300f).coerceAtMost(1f))
                val box = Offset(at.center.x - at.base, at.center.y - at.base)
                val span = Size(2f * at.base, 2f * at.base)
                for (s in 0..1) {
                    val side = if (s == 0) 1f else -1f
                    for (k in 0 until STREAK_STEPS) {
                        val near = travel - k * FLAME_STEP_DEG
                        if (near <= 0f) break
                        val far = max(0f, near - FLAME_STEP_DEG)
                        drawArc(
                            flameColors[k], 90f + side * far, side * (near - far), false, box, span,
                            alpha = fade * (1f - k / STREAK_STEPS.toFloat()), style = flameStroke[k], blendMode = BlendMode.Plus,
                        )
                    }
                }
            }
        }
        // Where they meet at the top, a warm bloom of light swells and fades (a flame would rise
        // straight out of sight: the ring sits right under the top of the screen).
        val top = Offset(at.center.x, at.center.y - at.from + 0.3f * u)
        val bt = t - (if (at.around) 220f else 0f)
        if (bt > 0f) {
            val k = (1f - exp(-bt / 60f)) * exp(-bt / 260f)
            val flick = 1f + 0.08f * sin(bt * 0.05f)
            glowAt(ember, top, 1.8f * u * (0.7f + 0.3f * k) * flick, 0.9f * k, BlendMode.Plus)
        }
        // And embers drifting off the shoulders like fireflies, out and up, swaying, glowing and
        // going out.
        for (i in 0 until 3) {
            val born = (if (at.around) 240f else 60f) + 120f * i
            val p = (t - born) / (900f + 300f * jitter(i + 71))
            if (p <= 0f || p >= 1f) continue
            val s = (t - born) / 1000f
            val side = if (i % 2 == 0) -1f else 1f
            val angle = (270f + side * (SHOULDER_DEG + 12f * i)) * (PI.toFloat() / 180f)
            val start = at.center + Offset(cos(angle), sin(angle)) * at.from
            val drift = Offset(side * (1.6f + 0.6f * i) * u * s + 0.35f * u * sin(s * 6f + i * 2.1f), -(1.2f * s + 2f * s * s) * u)
            val flick = 0.75f + 0.25f * sin(s * 23f + i * 2.3f)
            glowAt(ember, start + drift, 0.4f * u * (1f - 0.4f * p), (1f - p) * (1f - p) * (p / 0.12f).coerceAtMost(1f) * flick, BlendMode.Plus)
        }
    }
}

private class AirTouch(private val carry: AirCarry, air: AirPalette, landing: Color, private val trail: Color, u: Float) : LandingTouch(landing, u) {
    // A streak of wind, widest at its head, in steps.
    private val streak = Array(STREAK_STEPS) { k -> Stroke(max(1f, (0.2f - 0.025f * k) * u), cap = if (k == 0) StrokeCap.Round else StrokeCap.Butt) }
    private val vein = Stroke(max(1f, 0.16f * u))

    // What the gust carried: a leaf (pointed at both ends, with its midrib), or a petal (a rounded
    // teardrop notched at its tip, as a cherry blossom's is).
    private val carried = Path()
    private val carriedVein = Path()
    private val carriedLight: Color
    private val carriedShade: Color

    init {
        if (carry == AirCarry.LEAVES) {
            carried.apply {
                moveTo(0f, -0.55f * u)
                quadraticTo(0.42f * u, -0.1f * u, 0f, 0.55f * u)
                quadraticTo(-0.42f * u, -0.1f * u, 0f, -0.55f * u)
                close()
            }
            carriedVein.apply {
                moveTo(0f, -0.45f * u)
                lineTo(0f, 0.5f * u)
            }
            carriedLight = air.leaf
            carriedShade = air.leafShade
        } else {
            carried.apply {
                moveTo(0f, 0.42f * u)
                cubicTo(0.42f * u, 0.2f * u, 0.36f * u, -0.38f * u, 0.1f * u, -0.42f * u)
                lineTo(0f, -0.3f * u)
                lineTo(-0.1f * u, -0.42f * u)
                cubicTo(-0.36f * u, -0.38f * u, -0.42f * u, 0.2f * u, 0f, 0.42f * u)
                close()
            }
            carriedVein.apply {
                moveTo(0f, 0.38f * u)
                lineTo(0f, 0.05f * u)
            }
            carriedLight = air.petal
            carriedShade = air.petalShade
        }
    }

    override fun DrawScope.drawUnder(t: Float, at: Landing) {
        val secs = t / 1000f
        val center = at.center
        // Wind streaming round it, as the gust's lines do across the lock screen: thin streaks,
        // brightest at their heads, sweeping round it the way the LED's curl turns (anticlockwise),
        // easing off and thinning away.
        for (i in 0 until 6) {
            val p = t / (440f + 200f * jitter(i + 7))
            if (p >= 1f) continue
            val q = 1f - p
            val r = at.from + (0.5f + 2.1f * jitter(i + 13)) * u + 0.8f * u * p
            val step = (50f + 45f * jitter(i + 19)) / STREAK_STEPS
            val head = 360f * jitter(i + 3) - 230f * glideOut(p)
            val a = q * sqrt(q) * (p / 0.1f).coerceAtMost(1f)
            for (k in 0 until STREAK_STEPS) {
                val fade = 1f - k / STREAK_STEPS.toFloat()
                drawArc(
                    trail, head + k * step, step, false, Offset(center.x - r, center.y - r), Size(2f * r, 2f * r),
                    alpha = 0.85f * a * fade * fade, style = streak[k],
                )
            }
        }
        // What the gust carried, let go: a petal or leaf each way, turning over as it flutters off.
        when (carry) {
            AirCarry.NONE -> Unit
            AirCarry.DUST -> for (i in 0 until 5) {
                val p = t / (600f + 300f * jitter(i + 29))
                if (p >= 1f) continue
                val angle = (i + jitter(i + 2)) * 2f * PI.toFloat() / 5f
                val d = at.from + 3f * u * glideOut(p)
                drawCircle(trail, 0.11f * u, Offset(center.x + d * cos(angle), center.y + d * sin(angle)), alpha = 0.7f * (1f - p))
            }
            else -> for (i in 0 until 2) {
                val p = t / (950f + 200f * jitter(i + 17))
                if (p >= 1f) continue
                val side = if (i == 0) 1f else -1f
                val start = if (at.around) center + Offset(side * at.from * 0.9f, 0.35f * at.from) else center
                val pos = start + Offset(side * 4.5f * u * glideOut(p), (1.2f + 4f * secs) * u * secs * 4f) +
                    Offset(0.5f * u * sin(secs * 9f + i * 1.7f), 0f)
                // Turning in the air: spun round, and its face tipping away and back (a flip).
                val flip = cos(secs * 10f + i * 1.3f)
                val a = 0.9f * (1f - smoothstep(0.6f, 1f, p)) * (p / 0.06f).coerceAtMost(1f)
                translate(pos.x, pos.y) {
                    rotate(side * 220f * secs + i * 70f, Offset.Zero) {
                        scale((0.25f + 0.75f * abs(flip)) * 0.85f, 0.85f, Offset.Zero) {
                            drawPath(carried, if (flip > 0f) carriedLight else carriedShade, alpha = a)
                            drawPath(carriedVein, carriedShade, alpha = 0.6f * a, style = vein)
                        }
                    }
                }
            }
        }
    }
}

private class EarthTouch(earth: EarthPalette, landing: Color, u: Float) : LandingTouch(landing, u) {
    // A chip of stone, lit and shaded faces about the origin, a unit across, lit from the top left.
    private val chipLit = Path().apply {
        moveTo(-0.5f * u, -0.1f * u)
        lineTo(-0.15f * u, -0.5f * u)
        lineTo(0.35f * u, -0.38f * u)
        lineTo(0.08f * u, 0.05f * u)
        close()
    }
    private val chipShade = Path().apply {
        moveTo(0.35f * u, -0.38f * u)
        lineTo(0.5f * u, 0.1f * u)
        lineTo(0.2f * u, 0.45f * u)
        lineTo(-0.3f * u, 0.38f * u)
        lineTo(-0.5f * u, -0.1f * u)
        lineTo(0.08f * u, 0.05f * u)
        close()
    }
    private val chipLight = earth.light
    private val chipDeep = earth.deep

    override fun DrawScope.drawUnder(t: Float, at: Landing) = chips(t, at, front = false)

    override fun DrawScope.drawFront(t: Float, at: Landing) = chips(t, at, front = true)

    /**
     * Chips of stone rise from where it closed and gather round it on a tilted orbit, as dust round
     * a planet: they spread out and swing round, passing behind it and in front (smaller and dimmer
     * behind), slow, and sink back into the ring as it settles. [front]: the near side of the orbit.
     */
    private fun DrawScope.chips(t: Float, at: Landing, front: Boolean) {
        if (t >= EARTH_CHIPS_MS) return
        val sealAngle = if (at.around && at.seal != at.center) atan2(at.seal.y - at.center.y, at.seal.x - at.center.x) else PI.toFloat() / 2f
        val out = smoothstep(0f, 220f, t) * (1f - smoothstep(480f, EARTH_CHIPS_MS, t))
        val radius = at.from + 1.3f * u * out
        val flat = 1f - 0.55f * out
        val tilt = -18f * out * (PI.toFloat() / 180f)
        val tiltCos = cos(tilt)
        val tiltSin = sin(tilt)
        val spread = glideOut((t / 260f).coerceAtMost(1f))
        val swing = 1.5f * PI.toFloat() * (1f - exp(-t / 380f))
        val fade = (t / 60f).coerceAtMost(1f) * (1f - smoothstep(560f, EARTH_CHIPS_MS, t))
        for (i in 0 until EARTH_CHIPS) {
            val a = sealAngle + spread * i * 2f * PI.toFloat() / EARTH_CHIPS + swing
            val depth = sin(a)
            // The lower half of the orbit is the near side.
            if ((depth >= 0f) != front) continue
            val x = radius * cos(a)
            val y = radius * flat * depth
            val near = 0.5f + 0.5f * depth * out + 0.5f * (1f - out)
            val size = (0.65f + 0.35f * jitter(i + 23)) * (0.7f + 0.3f * near)
            val alpha = fade * (0.45f + 0.55f * near)
            translate(at.center.x + x * tiltCos - y * tiltSin, at.center.y + x * tiltSin + y * tiltCos) {
                rotate(200f * t / 1000f + 70f * i, Offset.Zero) {
                    scale(size, Offset.Zero) {
                        drawPath(chipShade, chipDeep, alpha = alpha)
                        drawPath(chipLit, chipLight, alpha = alpha)
                    }
                }
            }
        }
    }
}

private class NeonTouch(landing: Color, u: Float) : LandingTouch(landing, u) {
    private val thin = Stroke(max(1f, 0.22f * u))

    // A four-point sparkle, a unit from its centre to each point.
    private val star = Path().apply {
        moveTo(0f, -1f)
        lineTo(0.16f, -0.16f)
        lineTo(1f, 0f)
        lineTo(0.16f, 0.16f)
        lineTo(0f, 1f)
        lineTo(-0.16f, 0.16f)
        lineTo(-1f, 0f)
        lineTo(-0.16f, -0.16f)
        close()
    }

    override fun DrawScope.drawUnder(t: Float, at: Landing) {
        // A ripple, and a small sparkle where it closed.
        val rp = t / 320f
        if (rp < 1f) {
            val rq = 1f - rp
            drawCircle(landing, at.base + 2.6f * u * glideOut(rp), at.center, alpha = 0.5f * rq * rq, style = thin)
        }
        val p = t / 360f
        if (p >= 1f) return
        val q = 1f - p
        val g = q * q * (p / 0.08f).coerceAtMost(1f)
        translate(at.seal.x, at.seal.y) {
            rotate(15f * p, Offset.Zero) { scale(1.6f * u * g, Offset.Zero) { drawPath(star, hot, alpha = g) } }
        }
    }
}

/** Air's streaks and Fire's streams, in this many steps from head to tail. */
private const val STREAK_STEPS = 5

/** Earth: the chips of stone that gather round the LED as it lands, and how long they take to settle. */
private const val EARTH_CHIPS = 5
private const val EARTH_CHIPS_MS = 820f

/** Fire: where on the ring its flames lift off and its embers leave, either side of the top. */
private const val SHOULDER_DEG = 40f

/** Fire: the streams' steps of flame, each this many degrees of the ring. */
private const val FLAME_STEP_DEG = 16f

/** Fire: running up the ring, quick off the mark and easing as it reaches the top. */
private val FlameRun = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)

/** [v] turned by [radians]. */
private fun rotateBy(v: Offset, radians: Float): Offset {
    val c = cos(radians)
    val s = sin(radians)
    return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}

/** A stable pseudo-random 0..1 for particle [i], so every run splashes the same. */
private fun jitter(i: Int): Float {
    val x = sin(i * 12.9898f + 4.1414f) * 43758.547f
    return x - kotlin.math.floor(x)
}

/** Leaves fast and loses energy as it spreads. */
internal fun glideOut(x: Float): Float = 1f - (1f - x) * (1f - x)
