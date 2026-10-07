package com.example.ambientglow

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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// The Edge Frame's hand-over to the LED. Where the heads land at the camera, the last of the light
// gathers into a token of what the frame was made of (an ember, a drop, a wisp, a gem, or a plain
// spark for neon), which flies to where the LED will light, takes the LED's exact shape there and
// goes out on the LED's own exhale; the first breath then rises in the same spot. With the LED on
// the camera, the token circles the lens instead, leaving the ring that becomes the LED's.
// Like every effect curve, all of it is a pure function of the effect's clock.
// ---------------------------------------------------------------------------------------------

/** The token is born out of the heads' landing glint, and pops open over this long. */
internal const val HANDOFF_BORN_MS = LANDING_MS
private const val HANDOFF_POP_MS = 110f

/** It sets off as it pops, and is at the LED by the end of its flight. */
internal const val HANDOFF_FLY_FROM_MS = LANDING_MS + 10f
internal const val HANDOFF_FLY_MS = 240f

/** Just before it lands it starts to take the LED's shape, and has it as it lands. */
private const val HANDOFF_MORPH_FROM_MS = HANDOFF_FLY_FROM_MS + HANDOFF_FLY_MS - 50f
private const val HANDOFF_MORPH_MS = 90f

/** Then it goes out as the beacons' ember does, to a true zero with no slope as the effect ends. */
internal const val HANDOFF_FADE_FROM_MS = HANDOFF_MORPH_FROM_MS + HANDOFF_MORPH_MS

/** The ripple it sends out as it lands. */
private const val HANDOFF_RIPPLE_MS = 260f
private val HANDOFF_RIPPLE_SPAN = 14.dp

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

/** The LED-shaped light's level as it goes out: 1 until the fade, then down to 0 with no slope at [ARRIVAL_MS]. */
internal fun handOffFadeAt(ms: Float): Float {
    val e = ((ms - HANDOFF_FADE_FROM_MS) / (ARRIVAL_MS - HANDOFF_FADE_FROM_MS)).coerceIn(0f, 1f)
    val k = 1f - e * e
    return k * k
}

/** What the last of the light gathers into. */
internal enum class HandOffToken { SPARK, EMBER, DROP, WISP, GEM }

/** The token for this frame: its element's when it is made of one ([elementFramesSupported]), else a spark. */
internal fun handOffToken(settings: GlowSettings, shaders: Boolean): HandOffToken =
    if (!settings.elemental || !shaders) {
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
 * it takes the LED's shape. Placed and sized as the LED is at this [scale] ([LedDot]).
 */
@Composable
internal fun LedHandOff(settings: GlowSettings, color: Color, geometry: ScreenGeometry, scale: Float, time: () -> Float) {
    val token = handOffToken(settings, elementFramesSupported)
    val pulse = settings.edgeMotion == EdgeMotion.PULSE
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val camera = geometry.lens(size.width, density, scale)
                val origin = camera.center
                val around = settings.ledOnCamera
                // The LED, as the glow screen lights it: the dot, or the ring round the lens.
                val ledCore = ledRadiusAt(settings.dotSize, scale).toPx()
                val ledLine = ledCore * LED_RING_STROKE_FACTOR
                val ledRing = camera.radius + ledRingGapAt(scale).toPx() + ledLine / 2f
                val target = if (around) origin else dotCenter(settings.dotX, settings.dotY, size, ledCore * DOT_HALO_FACTOR)
                val flies = around || (target - origin).getDistance() > HANDOFF_MIN_FLIGHT.toPx() * scale
                val u = max(TOKEN_UNIT.toPx() * scale, TOKEN_UNIT_MIN_PX)
                // Room for the token and the glow round its heart.
                val path = HandOffPath(token, origin, target, around, ledRing, top = TOKEN_ROOM * u)
                val look = TokenLook(token, settings, color, u)
                // The LED's light, built about the origin and moved to the target.
                val bloom = ledCore * LED_HALO_FACTOR
                val ledHalo = Brush.radialGradient(
                    0f to color.copy(alpha = 0.65f),
                    0.35f to color.copy(alpha = 0.22f),
                    1f to color.copy(alpha = 0f),
                    center = Offset.Zero,
                    radius = bloom,
                )
                val ledHot = lerp(color, Color.White, 0.45f)
                val ringStroke = Stroke(ledLine)
                val ringHot = Stroke(ledLine * 0.4f)
                val ringGlow = Stroke(ledLine * 3f)
                val arcStroke = Stroke(ledLine * 1.6f, cap = StrokeCap.Round)
                val ripple = Stroke(max(1f, 1.5.dp.toPx() * scale))
                val rippleSpan = HANDOFF_RIPPLE_SPAN.toPx() * scale
                // Pulse has no heads to land: the token is born out of a flash of its own.
                val birthGlint = if (pulse) {
                    Brush.radialGradient(
                        0f to lerp(look.landing, Color.White, 0.6f),
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
                    val f = if (flies) handOffFlightAt(ms) else 1f
                    val morph = handOffMorphAt(ms)
                    val level = handOffFadeAt(ms)
                    if (level <= 0f) return@onDrawBehind
                    if (birthGlint != null) {
                        val g = 1f - ((ms - HANDOFF_BORN_MS) / 160f).coerceIn(0f, 1f)
                        if (g > 0f) drawCircle(birthGlint, LANDING_GLINT.toPx() * scale, origin, alpha = 0.45f * g * g)
                    }
                    // Around the lens: the ring it draws as it circles, turning to the LED's.
                    if (around && f > 0f) {
                        val ringColor = lerp(look.landing, color, morph)
                        val sweep = 360f * f
                        drawArc(
                            ringColor, 90f, sweep, useCenter = false,
                            topLeft = Offset(origin.x - ledRing, origin.y - ledRing), size = Size(2f * ledRing, 2f * ledRing),
                            alpha = (1f - morph) * level, style = arcStroke,
                        )
                    }
                    // The token, shrinking into the LED as it takes its shape.
                    val tokenAlpha = min(1f, pop) * (1f - morph)
                    if (tokenAlpha > 0f) {
                        look.drawTrail(this, path, f, ms, tokenAlpha)
                        val at = path.at(f)
                        val shrink = (if (around) 0.6f else 1f) * (1f - 0.6f * morph) * pop
                        translate(at.x, at.y) {
                            scale(shrink, Offset.Zero) { look.draw(this, ms, f, path.heading(f), tokenAlpha) }
                        }
                    }
                    if (morph > 0f) {
                        val a = morph * level
                        if (around) {
                            drawCircle(color, ledRing, origin, alpha = 0.2f * a, style = ringGlow)
                            drawCircle(color, ledRing, origin, alpha = a, style = ringStroke)
                            drawCircle(ledHot, ledRing, origin, alpha = a, style = ringHot)
                        } else {
                            translate(target.x, target.y) {
                                drawCircle(ledHalo, bloom, Offset.Zero, alpha = a)
                                drawCircle(color, ledCore, Offset.Zero, alpha = a)
                                drawCircle(ledHot, ledCore * 0.5f, Offset.Zero, alpha = a)
                            }
                        }
                    }
                    // The landing ripple, in the token's colour.
                    val r = (ms - HANDOFF_FLY_FROM_MS - HANDOFF_FLY_MS) / HANDOFF_RIPPLE_MS
                    if (flies && r > 0f && r < 1f) {
                        val q = 1f - r
                        val base = if (around) ledRing else ledCore
                        drawCircle(look.landing, base + glideOut(r) * rippleSpan, target, alpha = 0.55f * q * q, style = ripple)
                    }
                }
            },
    )
}

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
    private val trail: Color
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
