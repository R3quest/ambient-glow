package app.lumement

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * (Round the camera the token closes the ring instead: [LedHandOff].)
 */
internal class HandOffPath(
    private val token: HandOffToken,
    private val start: Offset,
    private val end: Offset,
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
                val path = HandOffPath(token, origin, target, top = TOKEN_ROOM * u)
                val look = TokenLook(token, settings, color, u)
                // The LED itself, in the material it will breathe in.
                val ledLook = LedLook(ledMaterial(settings, elementFramesSupported), color, led.light, around)
                val touch = landingTouch(token, settings, color, look.landing, look.trail, u)
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
                val landing = Landing(target, landFrom, if (around) ledRing else ledCore, seal, around)
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
                    touch.draw(this, ms - HANDOFF_LANDED_MS, landing, front = false)
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
                    touch.draw(this, ms - HANDOFF_LANDED_MS, landing, front = true)
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
            for (s in 0 until if (twin) 2 else 1) {
                val side = if (s == 0) 1 else -1
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

/**
 * The LED's jolt as it lands on stone (Earth), in units, at [ms]: one sharp knock down, a
 * rebound, and still within a fifth of a second.
 */
internal fun handOffJoltAt(ms: Float): Float {
    val t = ms - HANDOFF_LANDED_MS
    if (t <= 0f) return 0f
    return exp(-t / 70f) * sin(2f * PI.toFloat() * t / 90f)
}

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
