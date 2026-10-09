package com.example.ambientglow

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// The Edge Frame's hand-over to the LED, told in beats as an animator would: the heads meet at the
// camera (the gather: their landing glint), the last of the light pops out of it as a token of
// what the frame was made of (an ember, a drop, a wisp, a gem, or a plain spark for neon), the
// token makes the LED (it flies to the dot, stretched by its speed, or closes the ring round the
// lens the way the frame moved: Twin's two lights pour down both sides and meet below, Comet's one
// runs on round, Pulse's ring opens out of the lens), and the LED lands with an impact: a flash,
// a spring, and the element's splash (droplets, sparks, puffs of wind, shards, speed lines). What
// it lands as is the LED itself ([LedLook]), at the peak of its first breath: it goes out on the
// LED's own exhale, moving as the LED moves, and the next breath rises in the same spot.
// Like every effect curve, all of it is a pure function of the effect's clock.
// ---------------------------------------------------------------------------------------------

/** The token is born out of the heads' landing glint, and pops open over this long. */
internal const val HANDOFF_BORN_MS = LANDING_MS
private const val HANDOFF_POP_MS = 110f

/** It sets off as it pops, and is at the LED (or has closed the ring) by the end of its way. */
internal const val HANDOFF_FLY_FROM_MS = LANDING_MS + 10f
internal const val HANDOFF_FLY_MS = 240f

/** Just before it lands it starts to take the LED's shape, and has it as it lands. */
private const val HANDOFF_MORPH_FROM_MS = HANDOFF_FLY_FROM_MS + HANDOFF_FLY_MS - 50f
private const val HANDOFF_MORPH_MS = 90f

/** Where it lands: the peak of the LED's first breath. */
internal const val HANDOFF_LANDED_MS = HANDOFF_FLY_FROM_MS + HANDOFF_FLY_MS

/** It goes out on the LED's exhale, and the Edge Frame ends with it ([arrivalMsFor]). */
internal const val HANDOFF_END_MS = HANDOFF_LANDED_MS + LED_FALL_MS

/** The token's size unit at full screen; the shapes are a few units across. */
private val TOKEN_UNIT = 4.5.dp
private const val TOKEN_UNIT_MIN_PX = 1.2f

/** How far from the top of the screen the token's path keeps, in units. */
private const val TOKEN_ROOM = 2.5f

/** Closer than this to the LED, the token doesn't fly: it pops and turns into the LED where it is. */
private val HANDOFF_MIN_FLIGHT = 6.dp

/** Pops open with a little overshoot, as something released. */
private val PopEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** Leaves the camera gathering pace and lands softly. */
private val FlightEasing = CubicBezierEasing(0.55f, 0f, 0.25f, 1f)

/** How far the token has popped open, 0 before it is born, about 1 after (it overshoots on the way). */
internal fun handOffPopAt(ms: Float): Float =
    if (ms <= HANDOFF_BORN_MS) 0f else PopEasing.transform(((ms - HANDOFF_BORN_MS) / HANDOFF_POP_MS).coerceAtMost(1f))

/** How far along its way to the LED the token is, 0..1. */
internal fun handOffFlightAt(ms: Float): Float =
    FlightEasing.transform(((ms - HANDOFF_FLY_FROM_MS) / HANDOFF_FLY_MS).coerceIn(0f, 1f))

/** How far the token has taken the LED's shape, 0..1. */
internal fun handOffMorphAt(ms: Float): Float = smoothstep(HANDOFF_MORPH_FROM_MS, HANDOFF_MORPH_FROM_MS + HANDOFF_MORPH_MS, ms)

/** The LED's level: full as it lands, then the LED's own exhale ([ledBreathAt]), to a true zero with no slope. */
internal fun handOffFadeAt(ms: Float): Float = if (ms <= HANDOFF_LANDED_MS) 1f else ledBreathAt(handOffBreathMs(ms))

/** Where in the LED's breath the landed light is at [ms]: its peak as it lands. */
internal fun handOffBreathMs(ms: Float): Float = LED_RISE_MS + ms - HANDOFF_LANDED_MS

/**
 * The landing's spring at [ms], about -0.4..0.8: it overshoots as it lands (squash), swings back
 * once and settles within ~0.4 s, as something soft caught.
 */
internal fun handOffSpringAt(ms: Float): Float {
    val t = ms - HANDOFF_LANDED_MS
    if (t <= 0f) return 0f
    return (1f - exp(-t / 12f)) * exp(-t / 150f) * cos(2f * PI.toFloat() * t / 300f)
}

/** The impact's flash at [ms], 0..1: a blink of light as it lands, gone in a few frames' fade. */
internal fun handOffFlashAt(ms: Float): Float {
    val t = ms - HANDOFF_LANDED_MS
    if (t <= 0f) return 0f
    return (1f - exp(-t / 15f)) * exp(-t / 120f)
}

/** The splash's particles live this long after the impact. */
private const val SPLASH_MS = 560f

/** How far through its life a splash particle is at [ms], 0..1 (it lives [life] ms); outside that it isn't showing. */
internal fun handOffSplashAt(ms: Float, life: Float = SPLASH_MS): Float = (ms - HANDOFF_LANDED_MS) / life

/** What the last of the light gathers into. */
internal enum class HandOffToken { SPARK, EMBER, DROP, WISP, GEM }

/** The token for this frame: its element's when it is made of one ([elementFramesSupported]), else a spark. */
internal fun handOffToken(settings: GlowSettings, shaders: Boolean): HandOffToken =
    if (!settings.elementalEdge || !shaders) {
        HandOffToken.SPARK
    } else {
        when (settings.element) {
            SpawnElement.FIRE -> HandOffToken.EMBER
            SpawnElement.WATER -> HandOffToken.DROP
            SpawnElement.AIR -> HandOffToken.WISP
            SpawnElement.EARTH -> HandOffToken.GEM
        }
    }

/**
 * The token's way from [start] (the camera) to [end] (the LED), as each element moves: an ember
 * hops up and over, a drop falls, a wisp swirls, a gem and a spark arc gently. An arc with no
 * room above it ([top], the highest the token may go) bows downwards instead, and a wisp always
 * swirls below its line: the camera and the LED usually sit right at the top of the screen.
 * With [around], it circles the lens at [end]'s distance from [start] instead, from below it,
 * once round.
 */
internal class HandOffPath(
    private val token: HandOffToken,
    private val start: Offset,
    private val end: Offset,
    private val around: Boolean,
    private val radius: Float,
    top: Float = 0f,
) {
    private val span = end - start
    private val length = span.getDistance()

    /** Across the line, the side that faces down the screen. */
    private val down: Offset = run {
        val side = if (length > 0f) Offset(-span.y / length, span.x / length) else Offset.Zero
        if (side.y < 0f) -side else side
    }

    /** The quadratic's control point; a wisp's is the midpoint (a straight line), as it swirls about it. */
    private val control: Offset = run {
        val mid = (start + end) / 2f
        val bow = when (token) {
            HandOffToken.EMBER -> Offset(mid.x, min(start.y, end.y) - 0.45f * length)
            HandOffToken.DROP -> Offset(end.x, start.y)
            HandOffToken.GEM -> mid - down * (0.2f * length)
            HandOffToken.SPARK -> mid - down * (0.12f * length)
            HandOffToken.WISP -> mid
        }
        // A quadratic's farthest point from its chord is halfway to the control point.
        val peak = mid.y + 0.5f * (bow.y - mid.y)
        if (peak < top) mid - (bow - mid) else bow
    }

    /** Where the token is [f] of the way along. */
    fun at(f: Float): Offset {
        if (around) {
            val a = PI.toFloat() / 2f + 2f * PI.toFloat() * f
            return Offset(start.x + radius * cos(a), start.y + radius * sin(a))
        }
        val g = 1f - f
        var p = start * (g * g) + control * (2f * g * f) + end * (f * f)
        if (token == HandOffToken.WISP && length > 0f) {
            // A swirl below the line: it dips, loops back on itself a little and rises to land.
            val pi = PI.toFloat()
            p += down * (0.2f * length * (sin(pi * f) + 0.3f * sin(3f * pi * f)))
            p += span * (0.06f * sin(4f * pi * f) * g)
        }
        return p
    }

    /** Which way it is heading at [f], in degrees clockwise from 3 o'clock. */
    fun heading(f: Float): Float {
        val a = at((f - 0.02f).coerceAtLeast(0f))
        val b = at((f + 0.02f).coerceAtMost(1f))
        return atan2(b.y - a.y, b.x - a.x) * (180f / PI.toFloat())
    }
}

/**
 * The hand-over, over the Edge Frame. [color] is the message's, which the LED lights in; the
 * token starts in its element's colours ([GlowSettings.fireColor] and so on) and turns to it as
 * it takes the LED's shape and material. Placed and sized as the LED is at this [scale] ([LedDot]).
 */
@Composable
internal fun LedHandOff(settings: GlowSettings, color: Color, geometry: ScreenGeometry, scale: Float, time: () -> Float) {
    val token = handOffToken(settings, elementFramesSupported)
    val motion = settings.edgeMotion
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val camera = geometry.lens(size.width, density, scale)
                val origin = camera.center
                val around = settings.ledOnCamera
                // The LED, as the glow screen lights it: the dot, or the ring round the lens.
                val led = ledLanding(settings, camera, size, scale)
                val ledCore = led.light.core
                val ledLine = led.light.line
                val ledRing = led.light.ring
                val target = led.center
                val flies = !around && (target - origin).getDistance() > HANDOFF_MIN_FLIGHT.toPx() * scale
                val u = max(TOKEN_UNIT.toPx() * scale, TOKEN_UNIT_MIN_PX)
                // Room for the token and the glow round its heart.
                val path = HandOffPath(token, origin, target, around = false, radius = ledRing, top = TOKEN_ROOM * u)
                val look = TokenLook(token, settings, color, u)
                // The LED itself, in the material it will breathe in.
                val ledLook = LedLook(ledMaterial(settings, elementFramesSupported), color, led.light, around)
                val splash = Splash(token, settings, color, look, u)
                // Where the light lands from: round the ring, or off the dot's edge.
                val landFrom = if (around) ledRing + ledLine else ledCore * 1.3f
                // The spring swells the LED by about this much at its peak.
                val springAmp = if (around) 0.7f * ledLine / ledRing else 0.22f
                // The forming ring's comet tail, widest (and round) at the head.
                val trail = Array(TRAIL_STEPS) { k -> Stroke(ledLine * 1.6f * (1f - 0.1f * k), cap = if (k == 0) StrokeCap.Round else StrokeCap.Butt) }
                val laidStroke = Stroke(ledLine * 1.1f)
                val ringBox = Offset(target.x - ledRing, target.y - ledRing)
                val ringSize = Size(2f * ledRing, 2f * ledRing)
                val hot = lerp(look.landing, Color.White, 0.6f)
                // The impact's flash, about the origin: round the ring, or a burst on the dot.
                val flashRadius = if (around) ledRing + 4.5f * u else 3.5f * u
                val flash = if (around) {
                    Brush.radialGradient(
                        0f to look.landing.copy(alpha = 0f),
                        camera.radius / flashRadius to look.landing.copy(alpha = 0f),
                        ledRing / flashRadius to hot.copy(alpha = 0.8f),
                        (ledRing + 1.5f * u) / flashRadius to look.landing.copy(alpha = 0.25f),
                        1f to look.landing.copy(alpha = 0f),
                        center = Offset.Zero,
                        radius = flashRadius,
                    )
                } else {
                    Brush.radialGradient(
                        0f to hot.copy(alpha = 0.9f),
                        0.3f to look.landing.copy(alpha = 0.4f),
                        1f to look.landing.copy(alpha = 0f),
                        center = Offset.Zero,
                        radius = flashRadius,
                    )
                }
                val sealGlow = Brush.radialGradient(
                    0f to hot,
                    0.3f to look.landing.copy(alpha = 0.55f),
                    1f to look.landing.copy(alpha = 0f),
                    center = Offset.Zero,
                    radius = 1f,
                )
                // Where the ring closes: below for Twin's two lights, above for Comet's one, and
                // Pulse's opens all round at once, so its sparkle sits at the light's corner.
                val seal = when {
                    !around -> target
                    motion == EdgeMotion.TWIN -> Offset(target.x, target.y + ledRing)
                    motion == EdgeMotion.COMET -> Offset(target.x, target.y - ledRing)
                    else -> Offset(target.x + 0.71f * ledRing, target.y - 0.71f * ledRing)
                }
                // Pulse has no heads to land: the token is born out of a flash of its own.
                val birthGlint = if (motion == EdgeMotion.PULSE) {
                    Brush.radialGradient(
                        0f to hot,
                        0.4f to look.landing.copy(alpha = 0.5f),
                        1f to look.landing.copy(alpha = 0f),
                        center = origin,
                        radius = LANDING_GLINT.toPx() * scale,
                    )
                } else {
                    null
                }
                onDrawBehind {
                    val ms = time()
                    val pop = handOffPopAt(ms)
                    if (pop <= 0f) return@onDrawBehind
                    val level = handOffFadeAt(ms)
                    if (level <= 0f) return@onDrawBehind
                    val f = if (flies || around) handOffFlightAt(ms) else 1f
                    val morph = handOffMorphAt(ms)
                    if (birthGlint != null) {
                        val g = 1f - ((ms - HANDOFF_BORN_MS) / 160f).coerceIn(0f, 1f)
                        if (g > 0f) drawCircle(birthGlint, LANDING_GLINT.toPx() * scale, origin, alpha = 0.45f * g * g)
                    }
                    val tokenAlpha = min(1f, pop) * (1f - morph)
                    if (around) {
                        drawRingForming(motion, look, color, f, morph, pop, ms, tokenAlpha, target, ledRing, ringBox, ringSize, trail, laidStroke)
                    } else if (tokenAlpha > 0f) {
                        look.drawTrail(this, path, f, ms, tokenAlpha)
                        val at = path.at(f)
                        val heading = path.heading(f)
                        // Squash and stretch: drawn out along its way as it speeds, round again as it slows to land.
                        val stretch = if (flies) 1f + 0.55f * flightSpeedAt(ms) else 1f
                        val shrink = (1f - 0.6f * morph) * pop
                        translate(at.x, at.y) {
                            rotate(heading, Offset.Zero) {
                                scale(stretch * shrink, shrink / stretch, Offset.Zero) {
                                    rotate(-heading, Offset.Zero) { look.draw(this, ms, f, heading, tokenAlpha) }
                                }
                            }
                        }
                    }
                    // The impact: a flash, a sparkle where it closed, and the element's splash.
                    val flashLevel = handOffFlashAt(ms)
                    if (flashLevel > 0.01f) {
                        translate(target.x, target.y) { drawCircle(flash, flashRadius, Offset.Zero, alpha = 0.55f * flashLevel) }
                        // Where it closed, a burst of light rather than a disc.
                        translate(seal.x, seal.y) { scale(1.3f * u * flashLevel, Offset.Zero) { drawCircle(sealGlow, 1f, Offset.Zero, alpha = 0.8f * flashLevel) } }
                    }
                    splash.draw(this, ms, target, landFrom, if (around) ledRing else ledCore, seal, around)
                    // The LED itself, from the landing on: it swells on the spring and settles, and
                    // goes out on its own exhale, moving as the LED moves.
                    if (morph > 0f) {
                        val swell = 1f + springAmp * handOffSpringAt(ms)
                        // Landing on stone, it is knocked down once and rebounds, as a stone set down hard.
                        val jolt = if (token == HandOffToken.GEM) 0.12f * u * handOffJoltAt(ms) else 0f
                        translate(0f, jolt) {
                            scale(swell, target) {
                                ledLook.draw(this, target, morph * level, handOffBreathMs(ms), accents = true)
                            }
                        }
                    }
                    splash.draw(this, ms, target, landFrom, if (around) ledRing else ledCore, seal, around, front = true)
                }
            },
    )
}

/** How fast the token is moving at [ms], 0 at rest to 1 at the top of its speed (the flight's steepest). */
private fun flightSpeedAt(ms: Float): Float {
    val a = handOffFlightAt(ms - 8f)
    val b = handOffFlightAt(ms + 8f)
    // FlightEasing's steepest slope is a little over 2 (in flight per flight time).
    return ((b - a) / (16f / HANDOFF_FLY_MS) / 2.2f).coerceIn(0f, 1f)
}

/**
 * The ring closing round the lens, at [f] of its way: laid down where the light has been, brighter
 * at its head, with the token riding the head. Twin's two lights cross at the top (each carries
 * on the way it came) and pour down both sides to meet below; Comet's one runs on round and meets
 * its own tail; Pulse's ring opens out of the lens. It turns from the token's colours to [color]
 * as it becomes the LED ([morph]).
 */
private fun DrawScope.drawRingForming(
    motion: EdgeMotion,
    look: TokenLook,
    color: Color,
    f: Float,
    morph: Float,
    pop: Float,
    ms: Float,
    tokenAlpha: Float,
    center: Offset,
    ring: Float,
    box: Offset,
    span: Size,
    trail: Array<Stroke>,
    laid: Stroke,
) {
    if (f <= 0f && motion != EdgeMotion.PULSE) {
        // Born, not off yet: the token at the top of the ring.
        drawToken(look, Offset(center.x, center.y - ring), 0f, 0.55f * pop, ms, f, tokenAlpha)
        return
    }
    val gone = 1f - morph
    if (gone <= 0f) return
    val tint = lerp(look.landing, color, morph)
    when (motion) {
        EdgeMotion.PULSE -> {
            // An iris opening: out of the lens to the ring, a touch past it and back.
            val r = ring * (0.55f + 0.45f * PulseOpen.transform(f))
            drawCircle(tint, r, center, alpha = 0.9f * gone * f, style = laid)
            drawToken(look, center, 0f, 0.55f * pop * (1f - f), ms, f, tokenAlpha)
        }
        else -> {
            val twin = motion == EdgeMotion.TWIN
            val sweep = (if (twin) 180f else 360f) * f
            for (side in if (twin) intArrayOf(1, -1) else intArrayOf(1)) {
                // The ring laid down faintly where the light has been, and a comet's tail behind
                // the head: widest and brightest at it, thinning and fading over the last stretch.
                drawArc(tint, -90f, side * sweep, false, box, span, alpha = 0.3f * gone, style = laid)
                for (k in 0 until TRAIL_STEPS) {
                    val end = sweep - k * TRAIL_STEP_DEG
                    if (end <= 0f) break
                    val from = max(0f, end - TRAIL_STEP_DEG)
                    val a = gone * (1f - k / TRAIL_STEPS.toFloat()) * (1f - k / TRAIL_STEPS.toFloat())
                    drawArc(tint, -90f + side * from, side * (end - from), false, box, span, alpha = a, style = trail[k])
                }
                val a = (-90f + side * sweep) * (PI.toFloat() / 180f)
                val at = Offset(center.x + ring * cos(a), center.y + ring * sin(a))
                // The token melts into the light at the head as it goes round.
                drawToken(look, at, -90f + side * sweep + side * 90f, 0.55f * pop * (1f - 0.45f * f), ms, f, tokenAlpha)
            }
        }
    }
}

private fun DrawScope.drawToken(look: TokenLook, at: Offset, heading: Float, size: Float, ms: Float, f: Float, alpha: Float) {
    if (alpha <= 0f || size <= 0f) return
    translate(at.x, at.y) { scale(size, Offset.Zero) { look.draw(this, ms, f, heading, alpha) } }
}

/** The comet's tail behind each head, in steps of this many degrees. */
private const val TRAIL_STEPS = 7
private const val TRAIL_STEP_DEG = 14f

/** Pulse's ring opens out of the lens and a little past its place before it settles there. */
private val PulseOpen = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f)

/** A stable pseudo-random 0..1 for particle [i], so every run splashes the same. */
private fun jitter(i: Int): Float {
    val x = sin(i * 12.9898f + 4.1414f) * 43758.547f
    return x - kotlin.math.floor(x)
}

/**
 * The element's touch as the LED lands, built once per size in units [u]: one small gesture
 * each, in the element's own way, so the landing has a character without competing with the LED.
 * - Water: a splash where it closed, fine spray bursting out and gone, and a ripple on the surface.
 * - Fire: the flame catches at the bottom of the ring and runs up both sides as two warm streams;
 *   where they meet at the top a soft flame lifts off and fades, and embers drift up like fireflies.
 * - Air: wind streaming round it, as the gust's lines do on the lock screen, and what the gust
 *   carries ([GlowSettings.airCarry]) fluttering off.
 * - Earth: chips of stone gather round it as it settles, on a tilted orbit (passing behind it and
 *   in front), slow, and sink into the ring; it jolts once, gently, as a stone set down.
 * - Neon: a ripple, and a small sparkle where it closed.
 */
private class Splash(
    private val token: HandOffToken,
    private val settings: GlowSettings,
    brand: Color,
    private val look: TokenLook,
    private val u: Float,
) {
    private val hot = lerp(look.landing, Color.White, 0.6f)
    private val thin = Stroke(max(1f, 0.22f * u))
    private val crisp = Stroke(max(1f, 0.16f * u))
    private val crest = Stroke(max(1f, 0.18f * u))
    private val trough = Stroke(1.1f * u)
    private val ripple = lerp(look.landing, Color.White, 0.3f)
    // A glow, about the origin at a radius of 1, scaled to its size.
    private val glow: Brush
    // A four-point sparkle, a unit from its centre to each point.
    private val star = Path()
    // Fire: the two streams' steps of flame, hot at the head; the soft flame that lifts off where
    // they meet (its root at the origin, its tip at (0, -1)) and its hot core; an ember's glow.
    private val flameStroke = Array(STREAK_STEPS) { k -> Stroke((0.36f - 0.045f * k) * u, cap = if (k == 0) StrokeCap.Round else StrokeCap.Butt) }
    private val flameColors: Array<Color>
    private val wisp = Path()
    private val wispOuter: Brush
    private val wispInner: Brush
    private val ember: Brush
    // Air: a streak of wind, widest at its head, in steps.
    private val streak = Array(STREAK_STEPS) { k -> Stroke(max(1f, (0.2f - 0.025f * k) * u), cap = if (k == 0) StrokeCap.Round else StrokeCap.Butt) }
    private val carried = Path()
    private val carriedVein = Path()
    private val carriedLight: Color
    private val carriedShade: Color
    // Earth: a chip of stone, lit and shaded faces about the origin, a unit across.
    private val chipLit = Path()
    private val chipShade = Path()
    private val chipLight: Color
    private val chipDeep: Color

    init {
        glow = Brush.radialGradient(
            0f to hot,
            0.3f to look.landing.copy(alpha = 0.55f),
            1f to look.landing.copy(alpha = 0f),
            center = Offset.Zero,
            radius = 1f,
        )
        star.apply {
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
        val fire = firePalette(settings.fireColor, brand)
        flameColors = Array(STREAK_STEPS) { k -> lerp(fire.hot, fire.flare, k / (STREAK_STEPS - 1f)) }
        wisp.apply {
            moveTo(0f, -1f)
            cubicTo(0.2f, -0.66f, 0.46f, -0.38f, 0.42f, -0.12f)
            cubicTo(0.38f, 0.06f, 0.2f, 0.12f, 0f, 0.12f)
            cubicTo(-0.2f, 0.12f, -0.38f, 0.06f, -0.42f, -0.12f)
            cubicTo(-0.46f, -0.38f, -0.2f, -0.66f, 0f, -1f)
            close()
        }
        wispOuter = Brush.verticalGradient(
            0f to fire.flare.copy(alpha = 0f),
            0.35f to fire.flare.copy(alpha = 0.6f),
            0.7f to fire.body,
            1f to fire.hot,
            startY = -1f,
            endY = 0.12f,
        )
        wispInner = Brush.verticalGradient(
            0f to fire.hot.copy(alpha = 0f),
            0.5f to fire.hot.copy(alpha = 0.7f),
            1f to fire.core,
            startY = -1f,
            endY = 0.12f,
        )
        ember = Brush.radialGradient(
            0f to fire.core,
            0.22f to fire.hot,
            0.5f to fire.flare.copy(alpha = 0.45f),
            1f to fire.flare.copy(alpha = 0f),
            center = Offset.Zero,
            radius = 1f,
        )
        val air = airPalette(settings.airColor, brand)
        if (settings.airCarry == AirCarry.LEAVES) {
            // A leaf: pointed at both ends, with its midrib.
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
            // A petal: a rounded teardrop, notched at its tip, as a cherry blossom's is.
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
        // A chip: an irregular stone, lit from the top left.
        chipLit.apply {
            moveTo(-0.5f * u, -0.1f * u)
            lineTo(-0.15f * u, -0.5f * u)
            lineTo(0.35f * u, -0.38f * u)
            lineTo(0.08f * u, 0.05f * u)
            close()
        }
        chipShade.apply {
            moveTo(0.35f * u, -0.38f * u)
            lineTo(0.5f * u, 0.1f * u)
            lineTo(0.2f * u, 0.45f * u)
            lineTo(-0.3f * u, 0.38f * u)
            lineTo(-0.5f * u, -0.1f * u)
            lineTo(0.08f * u, 0.05f * u)
            close()
        }
        val earth = earthPalette(settings.earthColor, brand)
        chipLight = earth.light
        chipDeep = earth.deep
    }

    /**
     * At [ms]: the LED at [center], what is thrown leaving it [from] out (its ring, or its dot's
     * edge), its ripples starting at [base], and where it closed, [seal]. [around]: the ring.
     */
    fun draw(scope: DrawScope, ms: Float, center: Offset, from: Float, base: Float, seal: Offset, around: Boolean, front: Boolean = false) = with(scope) {
        val t = ms - HANDOFF_LANDED_MS
        if (t <= 0f) return@with
        // Only Earth's chips pass in front of the LED; everything else is drawn under it.
        if (front) {
            if (token == HandOffToken.GEM) earth(t, center, from, seal, around, front = true)
            return@with
        }
        // Outwards from where it closed; off a dot, up for what is thrown and down into the ground.
        val normal = if (around && seal != center) (seal - center) / (seal - center).getDistance() else Offset(0f, -1f)
        val start = if (around) seal else center + normal * from
        when (token) {
            HandOffToken.DROP -> water(t, center, base, start, normal)
            HandOffToken.EMBER -> fire(t, center, base, from, around)
            HandOffToken.WISP -> air(t, center, from, around)
            HandOffToken.GEM -> earth(t, center, from, seal, around, front = false)
            HandOffToken.SPARK -> neon(t, center, base, seal)
        }
    }

    /** A soft glow of [radius] at [at]. */
    private fun DrawScope.glowAt(brush: Brush, at: Offset, radius: Float, alpha: Float, blend: BlendMode = BlendMode.SrcOver) {
        if (alpha <= 0f || radius <= 0f) return
        translate(at.x, at.y) { scale(radius, Offset.Zero) { drawCircle(brush, 1f, Offset.Zero, alpha = alpha, blendMode = blend) } }
    }

    private fun DrawScope.water(t: Float, center: Offset, base: Float, start: Offset, normal: Offset) {
        // A ripple running out on the surface, a bright crest over a soft trough.
        val p = t / 620f
        if (p < 1f) {
            val q = 1f - p
            val r = base + 2.8f * u * glideOut(p)
            val a = 0.5f * q * q * (p / 0.08f).coerceAtMost(1f)
            drawCircle(ripple, r, center, alpha = a, style = crest)
            drawCircle(look.landing, r - 0.6f * u, center, alpha = 0.07f * a, style = trough)
        }
        // The splash: fine spray bursting out of where it closed, slowing at once and gone.
        for (i in 0 until 11) {
            val life = 220f + 140f * jitter(i + 83)
            val sp = t / life
            if (sp >= 1f) continue
            val sq = 1f - sp
            val dir = rotateBy(normal, (-1f + 2f * (i + jitter(i)) / 11f) * 1.5f)
            val reach = (1.1f + 1.3f * jitter(i + 31)) * u
            val at = start + dir * (reach * glideOut(sp))
            val r = (0.09f + 0.08f * jitter(i + 57)) * u * (0.4f + 0.6f * sq)
            drawCircle(hot, r, at, alpha = sq * (sp / 0.06f).coerceAtMost(1f))
        }
    }

    private fun DrawScope.fire(t: Float, center: Offset, base: Float, from: Float, around: Boolean) {
        // The flame catches at the bottom of the ring and runs up both sides, two warm streams
        // with their heat at the head, as Air's wind runs round it.
        if (around) {
            val travel = 180f * FlameRun.transform((t / 300f).coerceAtMost(1f))
            val fade = 1f - smoothstep(250f, 520f, t)
            val box = Offset(center.x - base, center.y - base)
            val span = Size(2f * base, 2f * base)
            if (fade > 0f) {
                for (side in intArrayOf(1, -1)) {
                    for (k in 0 until STREAK_STEPS) {
                        val near = travel - k * FLAME_STEP_DEG
                        if (near <= 0f) break
                        val far = max(0f, near - FLAME_STEP_DEG)
                        val a = fade * (1f - k / STREAK_STEPS.toFloat())
                        drawArc(
                            flameColors[k], 90f + side * far, side * (near - far), false, box, span,
                            alpha = a, style = flameStroke[k], blendMode = BlendMode.Plus,
                        )
                    }
                }
            }
        }
        // Where they meet at the top a soft flame lifts off, sways and fades.
        val top = Offset(center.x, center.y - from + 0.2f * u)
        val wt = t - (if (around) 230f else 0f)
        val wp = wt / 560f
        if (wp > 0f && wp < 1f) {
            val grow = FlareUp.transform((wt / 140f).coerceAtMost(1f))
            val h = 1.9f * u * grow * (1f - 0.35f * wp)
            val w = 0.9f * u * (1f + 0.08f * sin(wt * 0.06f))
            val a = 1f - smoothstep(0.35f, 1f, wp)
            translate(top.x, top.y - 1.1f * u * wp) {
                rotate(8f * sin(wt * 0.018f), Offset.Zero) {
                    scale(w, h, Offset.Zero) { drawPath(wisp, wispOuter, alpha = a, blendMode = BlendMode.Plus) }
                    scale(0.5f * w, 0.55f * h, Offset.Zero) { drawPath(wisp, wispInner, alpha = a, blendMode = BlendMode.Plus) }
                }
            }
        }
        // And embers drifting up off it like fireflies, slow, swaying, glowing and going out.
        for (i in 0 until 3) {
            val born = (if (around) 240f else 60f) + 120f * i
            val life = 900f + 300f * jitter(i + 71)
            val p = (t - born) / life
            if (p <= 0f || p >= 1f) continue
            val s = (t - born) / 1000f
            val at = top + Offset((i - 1) * 0.9f * u + 0.4f * u * sin(s * 6f + i * 2.1f), -(1.4f * s + 2f * s * s) * u)
            val flick = 0.75f + 0.25f * sin(s * 23f + i * 2.3f)
            val a = (1f - p) * (1f - p) * (p / 0.12f).coerceAtMost(1f) * flick
            glowAt(ember, at, 0.4f * u * (1f - 0.4f * p), a, BlendMode.Plus)
        }
    }

    private fun DrawScope.air(t: Float, center: Offset, from: Float, around: Boolean) {
        val secs = t / 1000f
        // Wind streaming round it, as the gust's lines do across the lock screen: thin streaks,
        // brightest at their heads, sweeping round it the way the LED's curl turns (anticlockwise),
        // easing off and thinning away.
        for (i in 0 until 6) {
            val life = 440f + 200f * jitter(i + 7)
            val p = t / life
            if (p >= 1f) continue
            val q = 1f - p
            val r = from + (0.5f + 2.1f * jitter(i + 13)) * u + 0.8f * u * p
            val length = 50f + 45f * jitter(i + 19)
            val head = 360f * jitter(i + 3) - 230f * glideOut(p)
            val step = length / STREAK_STEPS
            val a = q * sqrt(q) * (p / 0.1f).coerceAtMost(1f)
            for (k in 0 until STREAK_STEPS) {
                val fade = 1f - k / STREAK_STEPS.toFloat()
                drawArc(
                    look.trail, head + k * step, step, false, Offset(center.x - r, center.y - r), Size(2f * r, 2f * r),
                    alpha = 0.85f * a * fade * fade, style = streak[k],
                )
            }
        }
        // What the gust carried, let go: a petal or leaf each way, turning over as it flutters off.
        when (settings.airCarry) {
            AirCarry.NONE -> Unit
            AirCarry.DUST -> for (i in 0 until 5) {
                val p = t / (600f + 300f * jitter(i + 29))
                if (p >= 1f) continue
                val angle = (i + jitter(i + 2)) * 2f * PI.toFloat() / 5f
                val d = from + 3f * u * glideOut(p)
                drawCircle(look.trail, 0.11f * u, Offset(center.x + d * cos(angle), center.y + d * sin(angle)), alpha = 0.7f * (1f - p))
            }
            else -> for (i in 0 until 2) {
                val life = 950f + 200f * jitter(i + 17)
                val p = t / life
                if (p >= 1f) continue
                val side = if (i == 0) 1f else -1f
                val start = if (around) center + Offset(side * from * 0.9f, 0.35f * from) else center
                val at = start + Offset(side * 4.5f * u * glideOut(p), (1.2f + 4f * secs) * u * secs * 4f) +
                    Offset(0.5f * u * sin(secs * 9f + i * 1.7f), 0f)
                val flip = cos(secs * 10f + i * 1.3f)
                val a = 0.9f * (1f - smoothstep(0.6f, 1f, p)) * (p / 0.06f).coerceAtMost(1f)
                translate(at.x, at.y) {
                    rotate(side * 220f * secs + i * 70f, Offset.Zero) {
                        scale((0.25f + 0.75f * abs(flip)) * 0.85f, 0.85f, Offset.Zero) {
                            drawPath(carried, if (flip > 0f) carriedLight else carriedShade, alpha = a)
                            drawPath(carriedVein, carriedShade, alpha = 0.6f * a, style = crisp)
                        }
                    }
                }
            }
        }
    }

    private fun DrawScope.earth(t: Float, center: Offset, from: Float, seal: Offset, around: Boolean, front: Boolean) {
        // Chips of stone rise from where it closed and gather round it on a tilted orbit, as
        // dust round a planet: they spread out and swing round, passing behind it and in front
        // (smaller and dimmer behind), slow, and sink back into the ring as it settles.
        val life = 820f
        if (t >= life) return
        val sealAngle = if (around && seal != center) atan2(seal.y - center.y, seal.x - center.x) else PI.toFloat() / 2f
        val out = smoothstep(0f, 220f, t) * (1f - smoothstep(480f, life, t))
        val radius = from + 1.3f * u * out
        val flat = 1f - 0.55f * out
        val tilt = -18f * out * (PI.toFloat() / 180f)
        val spread = glideOut((t / 260f).coerceAtMost(1f))
        val swing = 1.5f * PI.toFloat() * (1f - exp(-t / 380f))
        val fade = (t / 60f).coerceAtMost(1f) * (1f - smoothstep(560f, life, t))
        for (i in 0 until EARTH_CHIPS) {
            val a = sealAngle + spread * i * 2f * PI.toFloat() / EARTH_CHIPS + swing
            val depth = sin(a)
            // The lower half of the orbit is the near side.
            if ((depth >= 0f) != front) continue
            val x = radius * cos(a)
            val y = radius * flat * depth
            val at = center + Offset(x * cos(tilt) - y * sin(tilt), x * sin(tilt) + y * cos(tilt))
            val near = 0.5f + 0.5f * depth * out + 0.5f * (1f - out)
            val size = (0.65f + 0.35f * jitter(i + 23)) * (0.7f + 0.3f * near)
            val alpha = fade * (0.45f + 0.55f * near)
            translate(at.x, at.y) {
                rotate(200f * t / 1000f + 70f * i, Offset.Zero) {
                    scale(size, Offset.Zero) {
                        drawPath(chipShade, chipDeep, alpha = alpha)
                        drawPath(chipLit, chipLight, alpha = alpha)
                    }
                }
            }
        }
    }

    private fun DrawScope.neon(t: Float, center: Offset, base: Float, seal: Offset) {
        // A ripple, and a small sparkle where it closed.
        val rp = t / 320f
        if (rp < 1f) {
            val rq = 1f - rp
            drawCircle(look.landing, base + 2.6f * u * glideOut(rp), center, alpha = 0.5f * rq * rq, style = thin)
        }
        val p = t / 360f
        if (p >= 1f) return
        val q = 1f - p
        val g = q * q * (p / 0.08f).coerceAtMost(1f)
        translate(seal.x, seal.y) {
            rotate(15f * p, Offset.Zero) { scale(1.6f * u * g, Offset.Zero) { drawPath(star, hot, alpha = g) } }
        }
    }
}

/** The wind's streaks, in this many steps from head to tail. */
private const val STREAK_STEPS = 5

/** Earth: the chips of stone that gather round the LED as it lands. */
private const val EARTH_CHIPS = 5

/** Fire: the streams' steps of flame, each this many degrees of the ring. */
private const val FLAME_STEP_DEG = 16f

/** Fire: running up the ring, quick off the mark and easing as it reaches the top. */
private val FlameRun = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)

/**
 * The LED's jolt as it lands on stone (Earth), in units, at [ms]: one sharp knock down, a
 * rebound, and still within a fifth of a second.
 */
internal fun handOffJoltAt(ms: Float): Float {
    val t = ms - HANDOFF_LANDED_MS
    if (t <= 0f) return 0f
    return exp(-t / 70f) * sin(2f * PI.toFloat() * t / 90f)
}

private const val TAU_F = 2f * PI.toFloat()

/** [v] turned by [radians]. */
private fun rotateBy(v: Offset, radians: Float): Offset {
    val c = cos(radians)
    val s = sin(radians)
    return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}

/** A flame catching: up past its height in a blink, then back. */
private val FlareUp = CubicBezierEasing(0.2f, 1.5f, 0.5f, 1f)

/** A spire shooting out of the ring, a little past its length, and back. */
private val SpireOut = CubicBezierEasing(0.25f, 1.45f, 0.5f, 1f)

/** And sinking back into it, slowly at first, as something heavy settles. */
private val SpireIn = CubicBezierEasing(0.5f, 0f, 0.8f, 0.4f)

/** Leaves fast and loses energy as it spreads. */
private fun glideOut(x: Float): Float = 1f - (1f - x) * (1f - x)

/**
 * One token's look, built once per size: its shapes about the origin, [u] px to a unit, and its
 * colours from its element's palette (or the message's [brand]). [landing] is its main colour.
 */
private class TokenLook(private val token: HandOffToken, settings: GlowSettings, brand: Color, private val u: Float) {
    val landing: Color
    private val glow: Brush
    private val glowRadius = 3.2f * u
    private val body = Path()
    private val detail = Path()
    private val fill: Brush
    private val line: Color
    private val core: Color
    val trail: Color
    private val thin = Stroke(0.14f * u)
    private val hair = Stroke(0.08f * u)
    private val wispGlow = Stroke(0.9f * u, cap = StrokeCap.Round)
    private val wispLine = Stroke(0.34f * u, cap = StrokeCap.Round)
    private val wispCore = Stroke(0.15f * u, cap = StrokeCap.Round)

    init {
        when (token) {
            HandOffToken.EMBER -> {
                val fire = firePalette(settings.fireColor, brand)
                landing = fire.hot
                line = fire.flare
                core = fire.core
                trail = fire.hot
                // A flame standing on its round foot, its tip up: flames rise whichever way it flies.
                body.moveTo(0f, -2.3f * u)
                body.cubicTo(0.5f * u, -1.35f * u, 1f * u, -0.65f * u, 1f * u, 0.1f * u)
                body.cubicTo(1f * u, 0.75f * u, 0.55f * u, 1.05f * u, 0f, 1.05f * u)
                body.cubicTo(-0.55f * u, 1.05f * u, -1f * u, 0.75f * u, -1f * u, 0.1f * u)
                body.cubicTo(-1f * u, -0.65f * u, -0.5f * u, -1.35f * u, 0f, -2.3f * u)
                body.close()
                fill = Brush.verticalGradient(
                    0f to fire.flare,
                    0.4f to fire.body,
                    0.75f to fire.hot,
                    1f to fire.core,
                    startY = -2.3f * u,
                    endY = 1.05f * u,
                )
            }
            HandOffToken.DROP -> {
                landing = brand
                line = lerp(brand, Color.White, 0.7f)
                core = Color.White
                trail = lerp(brand, Color.White, 0.4f)
                // A drop, tip up; turned so the tip trails behind it.
                body.moveTo(0f, -1.9f * u)
                body.cubicTo(0.35f * u, -1.2f * u, u, -0.6f * u, u, 0.2f * u)
                body.cubicTo(u, 0.75f * u, 0.55f * u, 1.2f * u, 0f, 1.2f * u)
                body.cubicTo(-0.55f * u, 1.2f * u, -u, 0.75f * u, -u, 0.2f * u)
                body.cubicTo(-u, -0.6f * u, -0.35f * u, -1.2f * u, 0f, -1.9f * u)
                body.close()
                fill = Brush.radialGradient(
                    0f to lerp(brand, Color.White, 0.75f),
                    0.45f to brand,
                    1f to lerp(brand, Color.Black, 0.35f),
                    center = Offset(-0.3f * u, 0f),
                    radius = 1.7f * u,
                )
            }
            HandOffToken.WISP -> {
                val air = airPalette(settings.airColor, brand)
                landing = air.glow
                line = air.body
                core = air.core
                trail = air.glow
                // A curl: a spiral opening out over one and a half turns.
                val steps = 28
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val r = (0.2f + 1.4f * t) * u
                    val a = 3f * PI.toFloat() * t
                    val x = r * cos(a)
                    val y = r * sin(a)
                    if (i == 0) body.moveTo(x, y) else body.lineTo(x, y)
                }
                fill = Brush.radialGradient(0f to air.core, 1f to air.glow, center = Offset.Zero, radius = 1.6f * u)
            }
            HandOffToken.GEM -> {
                val earth = earthPalette(settings.earthColor, brand)
                landing = earth.glow
                line = earth.core
                core = earth.core
                trail = earth.glow
                // A cut crystal, and the facets meeting at its table.
                val points = listOf(
                    Offset(0f, -1.6f * u), Offset(1.1f * u, -0.5f * u), Offset(0.75f * u, 1.25f * u),
                    Offset(-0.75f * u, 1.25f * u), Offset(-1.1f * u, -0.5f * u),
                )
                body.moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { body.lineTo(it.x, it.y) }
                body.close()
                val table = Offset(0f, -0.15f * u)
                points.forEach {
                    detail.moveTo(table.x, table.y)
                    detail.lineTo(it.x, it.y)
                }
                fill = Brush.linearGradient(
                    0f to earth.light,
                    0.5f to earth.glow,
                    1f to earth.deep,
                    start = Offset(-u, -1.6f * u),
                    end = Offset(u, 1.25f * u),
                )
            }
            HandOffToken.SPARK -> {
                landing = brand
                line = brand
                core = Color.White
                trail = brand
                fill = Brush.radialGradient(
                    0f to Color.White,
                    0.5f to lerp(brand, Color.White, 0.5f),
                    1f to brand,
                    center = Offset.Zero,
                    radius = 0.6f * u,
                )
            }
        }
        glow = Brush.radialGradient(
            0f to landing.copy(alpha = 0.55f),
            0.35f to landing.copy(alpha = 0.22f),
            1f to landing.copy(alpha = 0f),
            center = Offset.Zero,
            radius = glowRadius,
        )
    }

    /** What it leaves behind it: sparks for an ember, beads of light for the rest; a wisp's curl repeats. */
    fun drawTrail(scope: DrawScope, path: HandOffPath, f: Float, ms: Float, alpha: Float) = with(scope) {
        if (f <= 0f || f >= 1f) return@with
        for (i in 1..3) {
            val back = f - 0.07f * i
            if (back <= 0f) break
            val p = path.at(back)
            val fade = alpha * (1f - 0.28f * i)
            if (token == HandOffToken.WISP) {
                translate(p.x, p.y) {
                    scale(1f - 0.18f * i, Offset.Zero) {
                        rotate(-0.9f * ms + 40f * i, Offset.Zero) { drawPath(body, trail, alpha = 0.35f * fade, style = wispLine) }
                    }
                }
            } else {
                // An ember's sparks drift up as they fall behind.
                val lift = if (token == HandOffToken.EMBER) -0.5f * u * i else 0f
                drawCircle(trail, (0.32f - 0.06f * i) * u, Offset(p.x, p.y + lift), alpha = 0.8f * fade)
            }
        }
    }

    /** The token about the origin: [f] of the way along, heading [heading] degrees, at [alpha]. */
    fun draw(scope: DrawScope, ms: Float, f: Float, heading: Float, alpha: Float) = with(scope) {
        drawCircle(glow, glowRadius, Offset.Zero, alpha = alpha)
        when (token) {
            HandOffToken.EMBER -> {
                // Flickers, taller and shorter, about its foot.
                val flick = 1f + 0.14f * sin(ms * 0.045f) + 0.06f * sin(ms * 0.11f)
                scale(1f + 0.05f * sin(ms * 0.07f), flick, Offset(0f, 1.05f * u)) {
                    drawPath(body, fill, alpha = alpha)
                }
                drawCircle(core, 0.42f * u, Offset(0f, 0.4f * u), alpha = alpha)
            }
            HandOffToken.DROP -> {
                rotate(heading - 90f, Offset.Zero) {
                    drawPath(body, fill, alpha = alpha)
                    drawPath(body, line, alpha = 0.6f * alpha, style = hair)
                }
                drawCircle(core, 0.22f * u, Offset(-0.35f * u, -0.05f * u), alpha = 0.85f * alpha)
            }
            HandOffToken.WISP -> {
                rotate(-0.9f * ms, Offset.Zero) {
                    drawPath(body, trail, alpha = 0.3f * alpha, style = wispGlow)
                    drawPath(body, line, alpha = alpha, style = wispLine)
                    drawPath(body, core, alpha = alpha, style = wispCore)
                }
            }
            HandOffToken.GEM -> {
                // Tumbles half a turn on its way, and comes to rest upright.
                rotate(180f * (1f - f), Offset.Zero) {
                    drawPath(body, fill, alpha = alpha)
                    drawPath(detail, line, alpha = 0.55f * alpha, style = hair)
                    drawPath(body, line, alpha = alpha, style = thin)
                }
                // A glint across its face as it lands.
                val g = 1f - kotlin.math.abs(f - 0.92f) / 0.12f
                if (g > 0f) {
                    val s = 0.9f * u * g
                    val at = Offset(0.45f * u, -0.7f * u)
                    drawLine(core, Offset(at.x - s, at.y), Offset(at.x + s, at.y), strokeWidth = 0.12f * u, alpha = alpha * g)
                    drawLine(core, Offset(at.x, at.y - s), Offset(at.x, at.y + s), strokeWidth = 0.12f * u, alpha = alpha * g)
                }
            }
            HandOffToken.SPARK -> drawCircle(fill, 0.6f * u, Offset.Zero, alpha = alpha)
        }
    }
}
