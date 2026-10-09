package com.example.ambientglow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
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
// What the waiting LED is made of. Neon is a plain light. After an Edge Frame made of an element,
// the LED stays that element's: a live coal for Fire, a bead of water, a curl of air, a cut gem
// for Earth, so the token that flew into it doesn't turn back into neon. It is always the app's
// colour at its own hue (only lightness and chroma move, in OKLCh) and the same size, so which
// app is waiting reads as before; the material is the second read. Each look is a few shapes a
// 3 dp LED can still show (where its light sits, the shape of its glow) and one short motion
// inside every breath that the eye catches even at that size: sparks off the coal, the bead
// filling and wobbling, the curl unfurling as it spins, a sheen across the gem and its glint.
// The breath's timing and brightness stay the signal; the motion rides on them. Built once per
// colour and size; a frame only reads the breath's clock.
// ---------------------------------------------------------------------------------------------

internal enum class LedMaterial { NEON, COAL, BEAD, WISP, GEM }

/**
 * The LED's material: its element's after an Edge Frame made of one ([handOffToken]), else neon,
 * as it is when no effect plays at all: there is nothing for it to carry on.
 */
internal fun ledMaterial(settings: GlowSettings, shaders: Boolean): LedMaterial =
    if (settings.style != GlowStyle.EDGE_FRAME || !settings.arrival.playsEffect) {
        LedMaterial.NEON
    } else {
        when (handOffToken(settings, shaders)) {
            HandOffToken.SPARK -> LedMaterial.NEON
            HandOffToken.EMBER -> LedMaterial.COAL
            HandOffToken.DROP -> LedMaterial.BEAD
            HandOffToken.WISP -> LedMaterial.WISP
            HandOffToken.GEM -> LedMaterial.GEM
        }
    }

/**
 * Coal: how much of the glow round it shows at [ms] into a breath. The coal holds steady; its
 * glow wavers slowly (about 2 and 5 Hz, at most a quarter down), so it smoulders, never strobes.
 */
internal fun coalGlowAt(ms: Float): Float {
    val tau = 2f * PI.toFloat()
    return 0.86f + 0.09f * sin(tau * ms / 470f) + 0.05f * sin(tau * ms / 190f + 1.3f)
}

/** Coal: sparks leave its top as it nears full heat, each this long into the breath, from this far across (in cores). */
private val COAL_SPARK_AT = floatArrayOf(330f, 600f, 900f)
private val COAL_SPARK_X = floatArrayOf(-0.35f, 0.3f, -0.05f)
private const val COAL_SPARK_MS = 760f

/** How far up its way spark [i] is at [ms], 0..1; outside that it isn't flying. */
internal fun coalSparkAt(ms: Float, i: Int): Float = (ms - COAL_SPARK_AT[i]) / COAL_SPARK_MS

internal val COAL_SPARKS: Int get() = COAL_SPARK_AT.size

/** Bead: the drop is full this far into the rise, and wobbles there as water does. */
private const val BEAD_FULL_MS = 0.6f * LED_RISE_MS

/**
 * How far the bead bulges at [ms], -1..1: still until it is full, then a wobble that dies away
 * (about 3 Hz, gone within the breath), as a drop settles.
 */
internal fun beadSwellAt(ms: Float): Float {
    val t = ms - BEAD_FULL_MS
    if (t <= 0f) return 0f
    return exp(-t / 260f) * sin(2f * PI.toFloat() * t / 340f)
}

/** Bead: two ripples as it fills, the second fainter, like a drop landing in water. */
private val BEAD_RIPPLE_AT = floatArrayOf(BEAD_FULL_MS, BEAD_FULL_MS + 280f)
private val BEAD_RIPPLE_STRENGTH = floatArrayOf(0.55f, 0.3f)
private const val BEAD_RIPPLE_MS = 900f

/** How far ripple [i] has spread at [ms], 0..1; outside that it isn't showing. */
internal fun beadRippleAt(ms: Float, i: Int = 0): Float = (ms - BEAD_RIPPLE_AT[i]) / BEAD_RIPPLE_MS

/**
 * Wisp: its turn at [ms], in degrees, anticlockwise. A gust: it spins up as it lights and settles
 * as it fades, most of a turn a breath, so the curl is always on its way somewhere.
 */
internal fun wispTurnAt(ms: Float): Float {
    val f = (ms / LED_BREATH_MS).coerceIn(0f, 1f)
    val g = 1f - f
    return -320f * (1f - g * g * g)
}

/** How much of the wisp's curl is out at [ms]: it unfurls as it lights, all of it by the peak. */
internal fun wispUnfurlAt(ms: Float): Float = 0.3f + 0.7f * smoothstep(0f, LED_RISE_MS, ms)

/** The wind lines running round outside the wisp's ring, in degrees. */
private const val WISP_WIND_SWEEP = 80f

/** Gem: a glint across it, brightest at the breath's peak and gone this long either side. */
private const val GEM_GLINT_MS = 280f

/** The gem's glint at [ms], 0..1, with no slope at either end. */
internal fun gemGlintAt(ms: Float): Float {
    val g = 1f - abs(ms - LED_RISE_MS) / GEM_GLINT_MS
    return if (g <= 0f) 0f else g * g * (3f - 2f * g)
}

/**
 * Coal: where the flame running round the ring has got to at [ms], in degrees clockwise from the
 * bottom: once round each breath, from where the hand-off's flame caught, easing in and out.
 */
internal fun coalRunAt(ms: Float): Float = 360f * smoothstep(0f, LED_BREATH_MS, ms)

/**
 * Gem: where moonlet [i] is on its orbit round the stone at [ms], in radians: a little under a
 * turn a breath, the two of them half a turn apart.
 */
internal fun gemOrbitAt(ms: Float, i: Int): Float = 0.4f + i * PI.toFloat() + 2f * PI.toFloat() * ms / GEM_ORBIT_MS

private const val GEM_ORBIT_MS = 2_400f

/** The moonlets' orbit is a ring seen a little from above, tilted: this flat, at this angle. */
private const val GEM_ORBIT_FLAT = 0.38f
private const val GEM_ORBIT_TILT = -18f

internal const val GEM_MOONLETS = 2

/** Where the light comes from, for the bead's highlight and the gem's facets: the top left. */
private val LIGHT_FROM = Offset(-0.6f, -0.8f)

/** The facets the gem ring is cut into. */
private const val GEM_RING_FACETS = 12

/**
 * How much lighter a facet turned [facing] the light (-1..1) is. Shaded more than it is lit, so
 * a light app colour keeps its hue on the lit facets rather than going white.
 */
private fun facetLight(facing: Float): Float = if (facing > 0f) 0.07f * facing else 0.15f * facing

/** The app's colour at another lightness and chroma, its hue kept: lit and shaded, never retinted. */
private class Tones(color: Color) {
    private val lch = toOklch(color)

    /** [dl] lighter (or darker), with [chroma] of its chroma. */
    fun at(dl: Float, chroma: Float = 1f): Color = pale(lch[0] + dl, chroma)

    /** At lightness [l] outright: the near-white light inside it, still faintly its hue. */
    fun pale(l: Float, chroma: Float): Color = oklch(l.coerceIn(0f, 0.985f), lch[1] * chroma, lch[2])
}

/**
 * The LED in [material] and [color], sized by [light], built about the origin: the dot, or with
 * [onCamera] the ring round the lens. [draw] puts it at a centre; everything stays inside
 * [LedLight.bloom], so [LedDot]'s layer holds it. [moves]: a frame changes more than its alpha.
 */
internal class LedLook(val material: LedMaterial, private val color: Color, light: LedLight, private val onCamera: Boolean) {
    val moves: Boolean = material != LedMaterial.NEON

    private val core = light.core
    private val line = light.line
    private val ring = light.ring
    private val bloom = light.bloom
    private val tones = Tones(color)
    private val clear = color.copy(alpha = 0f)

    // Neon's, and the base the others build on.
    private val hot = lerp(color, Color.White, 0.45f)
    private val halo: Brush = if (onCamera) {
        Brush.radialGradient(
            0f to clear,
            light.lens / bloom to clear,
            ring / bloom to color.copy(alpha = 0.65f),
            (ring + (bloom - ring) * 0.35f) / bloom to color.copy(alpha = 0.22f),
            1f to clear,
            center = Offset.Zero,
            radius = bloom,
        )
    } else {
        Brush.radialGradient(
            0f to color.copy(alpha = 0.65f),
            0.35f to color.copy(alpha = 0.22f),
            1f to clear,
            center = Offset.Zero,
            radius = bloom,
        )
    }
    private val ringStroke = Stroke(line)
    private val hotStroke = Stroke(line * 0.4f)

    // Coal: lit from inside and above, a crust below, its glow rising off it.
    private val coalRise = 0.3f * core
    private val coalHeart = tones.pale(0.95f, 0.35f)
    private lateinit var coalHalo: Brush
    private lateinit var coalBody: Brush
    private lateinit var coalPlume: Brush
    private val outsideRing = Path()
    // Round the camera: warmest across the top, and a flame running round it once a breath.
    private lateinit var coalWarm: Brush
    private lateinit var flameRun: Brush
    private lateinit var flameRunHot: Brush
    private val sparkHot = tones.pale(0.93f, 0.5f)
    private lateinit var sparkGlow: Brush
    private val sparkGlowRadius = 0.5f * core

    // Bead: glass lit from the top left, a highlight, light focused on its far side, a ripple.
    private val beadLight = tones.at(0.16f, 0.55f)
    private val beadSpecular = tones.pale(0.97f, 0.15f)
    private val rippleStroke = Stroke(max(1f, 0.2f * core))
    private lateinit var beadBody: Brush
    private lateinit var beadRing: Brush
    private val specularStroke = Stroke(line * 0.42f, cap = StrokeCap.Round)
    private val causticStroke = Stroke(if (onCamera) line * 0.3f else 0.22f * core, cap = StrokeCap.Round)

    // Wisp: no hard edge, a soft glow drawn off to one side and a curl of wind round it.
    private val wispBodyColor = tones.at(0.06f, 0.85f)
    private val wispPale = tones.pale(0.96f, 0.3f)
    private lateinit var wispHalo: Brush
    private lateinit var wispCore: Brush
    private lateinit var wispLobe: Brush
    private lateinit var wispCurlBrush: Brush
    private lateinit var wispRing: Brush
    private lateinit var wispRingHot: Brush
    private lateinit var wispWind: Brush
    private val wispCurl = Path()
    private val wispCurlMeasure = PathMeasure()
    private var wispCurlLength = 0f
    private val wispCurlOut = Path()
    private val wispCurlStroke = Stroke(max(1f, 0.16f * core), cap = StrokeCap.Round)
    private val wispWindStroke = Stroke(line * 0.35f, cap = StrokeCap.Round)

    // Gem: the token's cut crystal, its facets lit by where they face, a table of light, a glint.
    private val gemFacets = List(5) { Path() }
    private var gemFacetColors: List<Color> = emptyList()
    private var gemTable = Offset.Zero
    private var glintSpot = Offset.Zero
    private val gemPale = tones.pale(0.98f, 0.1f)
    private var gemRingColors: List<Color> = emptyList()
    private var gemRingHotAlpha: List<Float> = emptyList()
    // Where each facet lies along the sheen's way: across the gem in cores, or round the ring in radians.
    private val gemFacetAlong = FloatArray(if (onCamera) GEM_RING_FACETS else 5)
    private val glintWidth = max(1f, 0.13f * core)
    // Two little stones orbiting it, a moonlet's lit and shaded faces about the origin.
    private val moonLit = Path()
    private val moonShade = Path()
    private val moonLight = tones.at(0.1f)
    private val moonDeep = tones.at(-0.18f)
    private val orbitX = if (onCamera) ring + 1.05f * core else 1.9f * core
    private val orbitY = GEM_ORBIT_FLAT * orbitX

    init {
        if (material == LedMaterial.GEM) {
            // An irregular chip, lit from the top left.
            val r = 0.45f * core
            moonLit.apply {
                moveTo(-0.9f * r, -0.2f * r)
                lineTo(-0.3f * r, -0.95f * r)
                lineTo(0.6f * r, -0.7f * r)
                lineTo(0.15f * r, 0.1f * r)
                close()
            }
            moonShade.apply {
                moveTo(0.6f * r, -0.7f * r)
                lineTo(0.95f * r, 0.15f * r)
                lineTo(0.4f * r, 0.85f * r)
                lineTo(-0.55f * r, 0.7f * r)
                lineTo(-0.9f * r, -0.2f * r)
                lineTo(0.15f * r, 0.1f * r)
                close()
            }
        }
        when (material) {
            LedMaterial.NEON -> Unit
            LedMaterial.COAL -> buildCoal()
            LedMaterial.BEAD -> buildBead()
            LedMaterial.WISP -> buildWisp()
            LedMaterial.GEM -> buildGem()
        }
    }

    private fun buildCoal() {
        val glow = tones.at(0.16f, 1.1f)
        val crust = tones.at(-0.24f, 0.9f)
        // Its glow sits a little high, as heat rises: centred above the coal, and no bigger for it.
        coalHalo = Brush.radialGradient(
            0f to glow.copy(alpha = 0.6f),
            0.3f to color.copy(alpha = 0.26f),
            1f to clear,
            center = Offset(0f, -coalRise),
            radius = bloom - coalRise,
        )
        coalBody = Brush.radialGradient(
            0f to glow,
            0.4f to color,
            1f to crust,
            center = Offset(0f, -0.35f * core),
            radius = 1.25f * core,
        )
        // Round the camera: hottest across the top, the glow lifting off it.
        coalPlume = Brush.radialGradient(
            0f to glow.copy(alpha = 0.35f),
            1f to glow.copy(alpha = 0f),
            center = Offset(0f, -ring),
            radius = 2.2f * core,
        )
        // It rises off the ring, not in towards the lens, which neon's halo leaves dark too.
        outsideRing.fillType = PathFillType.EvenOdd
        outsideRing.addRect(Rect(Offset.Zero, bloom))
        outsideRing.addOval(Rect(Offset.Zero, ring))
        // A spark: a hot point in a glow of the coal's own light, scaled as it burns down.
        sparkGlow = Brush.radialGradient(
            0f to glow.copy(alpha = 0.85f),
            0.35f to glow.copy(alpha = 0.35f),
            1f to glow.copy(alpha = 0f),
            center = Offset.Zero,
            radius = sparkGlowRadius,
        )
        // A sweep starts at 3 o'clock and runs clockwise: a quarter in is the bottom, three the top.
        coalWarm = Brush.sweepGradient(0f to color, 0.25f to color, 0.5f to color, 0.75f to glow, 1f to color, center = Offset.Zero)
        // The flame: a head of heat at a quarter round (the bottom, unturned) with its glow
        // trailing behind it, the way it runs (clockwise).
        flameRun = Brush.sweepGradient(
            0f to glow.copy(alpha = 0f),
            0.12f to glow.copy(alpha = 0.35f),
            0.24f to glow,
            0.27f to glow.copy(alpha = 0f),
            1f to glow.copy(alpha = 0f),
            center = Offset.Zero,
        )
        flameRunHot = Brush.sweepGradient(
            0f to coalHeart.copy(alpha = 0f),
            0.18f to coalHeart.copy(alpha = 0.3f),
            0.245f to coalHeart,
            0.265f to coalHeart.copy(alpha = 0f),
            1f to coalHeart.copy(alpha = 0f),
            center = Offset.Zero,
        )
    }

    private fun buildBead() {
        val deep = tones.at(-0.12f)
        beadBody = Brush.radialGradient(
            0f to beadLight,
            0.5f to color,
            1f to deep,
            center = Offset(-0.35f * core, -0.35f * core),
            radius = 1.5f * core,
        )
        beadRing = Brush.linearGradient(0f to beadLight, 0.5f to color, 1f to deep, start = Offset(-ring, -ring), end = Offset(ring, ring))
    }

    private fun buildWisp() {
        wispHalo = if (onCamera) {
            Brush.radialGradient(
                0f to clear,
                ring / bloom * 0.85f to clear,
                ring / bloom to color.copy(alpha = 0.55f),
                (ring + (bloom - ring) * 0.4f) / bloom to color.copy(alpha = 0.2f),
                1f to clear,
                center = Offset.Zero,
                radius = bloom,
            )
        } else {
            Brush.radialGradient(
                0f to color.copy(alpha = 0.5f),
                0.4f to color.copy(alpha = 0.18f),
                1f to clear,
                center = Offset.Zero,
                radius = bloom,
            )
        }
        // A core with a soft rim instead of an edge, as much light as neon's.
        wispCore = Brush.radialGradient(
            0f to wispPale,
            0.4f to color,
            0.78f to color,
            1f to clear,
            center = Offset.Zero,
            radius = 1.15f * core,
        )
        // The glow drawn off to one side, as wind carries it: round the turn, it trails the curl.
        wispLobe = Brush.radialGradient(
            0f to wispBodyColor.copy(alpha = 0.42f),
            1f to wispBodyColor.copy(alpha = 0f),
            center = Offset(1.35f * core, 0f),
            radius = 1.5f * core,
        )
        // One arm opening out fast, clockwise as it turns anticlockwise, so its outer end trails:
        // under a turn, or at this size it reads as an orbit rather than a curl.
        val steps = 28
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val r = (1.05f + 1.5f * t * t) * core
            val a = 1.6f * PI.toFloat() * t
            if (i == 0) wispCurl.moveTo(r * cos(a), r * sin(a)) else wispCurl.lineTo(r * cos(a), r * sin(a))
        }
        wispCurlMeasure.setPath(wispCurl, false)
        wispCurlLength = wispCurlMeasure.length
        wispCurlBrush = Brush.radialGradient(
            0f to color,
            0.4f to color,
            1f to color.copy(alpha = 0f),
            center = Offset.Zero,
            radius = 2.6f * core,
        )
        // Round the camera: gusts along the ring, brighter and fainter, that the turn carries round.
        wispRing = Brush.sweepGradient(
            0f to color,
            0.25f to color.copy(alpha = 0.5f),
            0.5f to color.copy(alpha = 0.9f),
            0.75f to color.copy(alpha = 0.5f),
            1f to color,
            center = Offset.Zero,
        )
        wispRingHot = Brush.sweepGradient(
            0f to wispPale,
            0.14f to wispPale.copy(alpha = 0f),
            0.36f to wispPale.copy(alpha = 0f),
            0.5f to wispPale.copy(alpha = 0.7f),
            0.64f to wispPale.copy(alpha = 0f),
            0.86f to wispPale.copy(alpha = 0f),
            1f to wispPale,
            center = Offset.Zero,
        )
        // The wind line comes out of nothing and thins away again, as a gust does.
        wispWind = Brush.sweepGradient(
            0f to wispBodyColor.copy(alpha = 0f),
            WISP_WIND_SWEEP / 720f to wispBodyColor.copy(alpha = 0.6f),
            WISP_WIND_SWEEP / 360f to wispBodyColor.copy(alpha = 0f),
            1f to wispBodyColor.copy(alpha = 0f),
            center = Offset.Zero,
        )
    }

    private fun buildGem() {
        val light = LIGHT_FROM / LIGHT_FROM.getDistance()
        if (!onCamera) {
            // The hand-off's crystal, its box centred on the LED and as tall as the dot is wide.
            val u = core / 1.25f
            val drop = 0.175f * u
            val points = listOf(
                Offset(0f, -1.6f * u + drop), Offset(1.1f * u, -0.5f * u + drop), Offset(0.75f * u, 1.25f * u + drop),
                Offset(-0.75f * u, 1.25f * u + drop), Offset(-1.1f * u, -0.5f * u + drop),
            )
            gemTable = Offset(0f, -0.15f * u + drop)
            glintSpot = points[1]
            gemFacetColors = points.indices.map { i ->
                val a = points[i]
                val b = points[(i + 1) % points.size]
                gemFacets[i].apply {
                    moveTo(gemTable.x, gemTable.y)
                    lineTo(a.x, a.y)
                    lineTo(b.x, b.y)
                    close()
                }
                // Along the light's way, for the sheen that follows it across.
                val mid = (gemTable + a + b) / 3f
                gemFacetAlong[i] = -(mid.x * light.x + mid.y * light.y) / core
                // Lit by how squarely it faces the light.
                val out = (a + b) / 2f - gemTable
                tones.at(facetLight((out.x * light.x + out.y * light.y) / out.getDistance()))
            }
        } else {
            val facing = List(GEM_RING_FACETS) { i ->
                val a = (i + 0.5f) * 2f * PI.toFloat() / GEM_RING_FACETS
                gemFacetAlong[i] = a
                cos(a) * light.x + sin(a) * light.y
            }
            // Every other facet a touch brighter, so the cut shows where the light is even.
            gemRingColors = facing.mapIndexed { i, f -> tones.at(facetLight(f) + if (i % 2 == 0) 0.02f else -0.02f) }
            gemRingHotAlpha = facing.map { 0.25f + 0.5f * max(0f, it) }
            val g = -PI.toFloat() / 4f
            glintSpot = Offset(ring * cos(g), ring * sin(g))
        }
    }

    /** At [center] and [alpha], [ms] into its breath; [accents] off leaves out the one-off moments (sparks, ripples, sheen, glint). */
    fun draw(scope: DrawScope, center: Offset, alpha: Float, ms: Float, accents: Boolean = true) {
        if (alpha <= 0f) return
        scope.translate(center.x, center.y) {
            when (material) {
                LedMaterial.NEON -> drawNeon(alpha)
                LedMaterial.COAL -> drawCoal(alpha, ms, accents)
                LedMaterial.BEAD -> drawBead(alpha, ms, accents)
                LedMaterial.WISP -> drawWisp(alpha, ms, accents)
                LedMaterial.GEM -> drawGem(alpha, ms, accents)
            }
        }
    }

    private fun DrawScope.drawNeon(alpha: Float) {
        drawCircle(halo, bloom, Offset.Zero, alpha = alpha)
        if (onCamera) {
            drawCircle(color, ring, Offset.Zero, alpha = alpha, style = ringStroke)
            drawCircle(hot, ring, Offset.Zero, alpha = alpha, style = hotStroke)
        } else {
            drawCircle(color, core, Offset.Zero, alpha = alpha)
            drawCircle(hot, core * 0.5f, Offset.Zero, alpha = alpha)
        }
    }

    private fun DrawScope.drawCoal(alpha: Float, ms: Float, accents: Boolean) {
        val glow = coalGlowAt(ms)
        if (onCamera) {
            drawCircle(halo, bloom, Offset.Zero, alpha = alpha * glow)
            clipPath(outsideRing) { drawCircle(coalPlume, 2.2f * core, Offset(0f, -ring), alpha = alpha * glow) }
            // Warm all round and warmest at the top, with a flame running round it once a breath.
            drawCircle(coalWarm, ring, Offset.Zero, alpha = alpha, style = ringStroke)
            rotate(coalRunAt(ms), Offset.Zero) {
                drawCircle(flameRun, ring, Offset.Zero, alpha = alpha * glow, style = ringStroke)
                drawCircle(flameRunHot, ring, Offset.Zero, alpha = alpha * (0.6f + 0.4f * glow), style = hotStroke)
            }
        } else {
            drawCircle(coalHalo, bloom - coalRise, Offset(0f, -coalRise), alpha = alpha * glow)
            drawCircle(coalBody, core, Offset.Zero, alpha = alpha)
            // Its heart wanders inside it, slowly, as the hottest spot in an ember does.
            val heart = Offset(0.16f * core * sin(ms * 0.0042f), -0.3f * core + 0.1f * core * cos(ms * 0.0057f))
            drawCircle(coalHeart, 0.32f * core, heart, alpha = alpha * (0.7f + 0.3f * glow))
        }
        if (accents) for (i in 0 until COAL_SPARKS) drawSpark(i, coalSparkAt(ms, i), alpha)
    }

    /**
     * Spark [i], [p] of the way up: it leaves the top of the coal (or of the ring) fast, slows as
     * it cools, sways a little and burns down to nothing, all inside the light's bloom.
     */
    private fun DrawScope.drawSpark(i: Int, p: Float, alpha: Float) {
        if (p <= 0f || p >= 1f) return
        val x0: Float
        val y0: Float
        val rise: Float
        if (onCamera) {
            x0 = COAL_SPARK_X[i] * 0.9f * ring
            y0 = -sqrt(ring * ring - x0 * x0) - line / 2f
            rise = 1.7f * core
        } else {
            x0 = COAL_SPARK_X[i] * core
            y0 = -0.8f * core
            rise = 2f * core
        }
        val q = 1f - p
        val x = x0 + 0.3f * core * p * sin(PI.toFloat() * 1.6f * p + 1.7f * i)
        val y = y0 - rise * (1f - q * q)
        val size = 1f - 0.65f * p
        val a = alpha * q * sqrt(q) * (p / 0.1f).coerceAtMost(1f)
        translate(x, y) {
            scale(size, Offset.Zero) { drawCircle(sparkGlow, sparkGlowRadius, Offset.Zero, alpha = a) }
            drawCircle(sparkHot, 0.13f * core * size, Offset.Zero, alpha = a)
        }
    }

    private fun DrawScope.drawBead(alpha: Float, ms: Float, accents: Boolean) {
        drawCircle(halo, bloom, Offset.Zero, alpha = alpha)
        val swell = beadSwellAt(ms)
        val from: Float
        if (onCamera) {
            drawCircle(beadRing, ring, Offset.Zero, alpha = alpha, style = ringStroke)
            val box = Offset(-ring, -ring)
            val span = Size(2f * ring, 2f * ring)
            // The highlight high on the left, and the light it focuses low on the right, sloshing
            // round the ring as the water in it settles.
            val slosh = 16f * swell
            drawArc(beadSpecular, 195f + slosh, 50f, false, box, span, alpha = 0.9f * alpha, style = specularStroke)
            drawArc(beadLight, 15f + slosh, 50f, false, box, span, alpha = 0.55f * alpha, style = causticStroke)
            from = ring + line / 2f
        } else {
            // It fills and wobbles: wider as it is shorter and back, as a drop holds its volume.
            scale(1f + 0.09f * swell, 1f - 0.07f * swell, Offset.Zero) {
                drawCircle(beadBody, core, Offset.Zero, alpha = alpha)
                val inner = 0.68f * core
                drawArc(
                    beadLight, 20f, 60f, false, Offset(-inner, -inner), Size(2f * inner, 2f * inner),
                    alpha = 0.55f * alpha, style = causticStroke,
                )
                drawCircle(beadSpecular, 0.26f * core, Offset(-0.36f * core, -0.38f * core), alpha = 0.95f * alpha)
            }
            from = 1.15f * core
        }
        if (!accents) return
        val reach = bloom - rippleStroke.width - from
        for (i in BEAD_RIPPLE_AT.indices) {
            val p = beadRippleAt(ms, i)
            if (p <= 0f || p >= 1f) continue
            val q = 1f - p
            // Leaves fast and loses energy as it spreads; fades in, so it doesn't pop off the edge.
            val r = from + reach * (1f - q * q)
            val a = alpha * BEAD_RIPPLE_STRENGTH[i] * q * q * (p / 0.12f).coerceAtMost(1f)
            drawCircle(color, r, Offset.Zero, alpha = a, style = rippleStroke)
        }
    }

    private fun DrawScope.drawWisp(alpha: Float, ms: Float, accents: Boolean) {
        drawCircle(wispHalo, bloom, Offset.Zero, alpha = alpha)
        // Without the one-off moments (a hand-off landing on it), all of it is out already.
        val unfurl = if (accents) wispUnfurlAt(ms) else 1f
        rotate(wispTurnAt(ms), Offset.Zero) {
            if (onCamera) {
                drawCircle(wispRing, ring, Offset.Zero, alpha = alpha, style = ringStroke)
                drawCircle(wispRingHot, ring, Offset.Zero, alpha = alpha, style = hotStroke)
                // Two wind lines running just outside the ring, the far one closer in and fainter,
                // drawn out from their tails as the gust gets up.
                val sweep = WISP_WIND_SWEEP * unfurl
                val r = ring + 1.6f * line
                drawArc(wispWind, 0f, sweep, false, Offset(-r, -r), Size(2f * r, 2f * r), alpha = alpha, style = wispWindStroke)
                val near = ring + 1.15f * line
                rotate(180f, Offset.Zero) {
                    drawArc(wispWind, 0f, sweep, false, Offset(-near, -near), Size(2f * near, 2f * near), alpha = 0.6f * alpha, style = wispWindStroke)
                }
            } else {
                drawCircle(wispLobe, 1.5f * core, Offset(1.35f * core, 0f), alpha = alpha)
                // The curl unfurls from the light; a shorter, fainter arm opposite makes it a swirl.
                wispCurlOut.reset()
                wispCurlMeasure.getSegment(0f, wispCurlLength * unfurl, wispCurlOut, true)
                drawPath(wispCurlOut, wispCurlBrush, alpha = alpha, style = wispCurlStroke)
                rotate(180f, Offset.Zero) {
                    scale(0.72f, Offset.Zero) { drawPath(wispCurlOut, wispCurlBrush, alpha = 0.5f * alpha, style = wispCurlStroke) }
                }
            }
        }
        if (!onCamera) drawCircle(wispCore, 1.15f * core, Offset.Zero, alpha = alpha)
    }

    private fun DrawScope.drawGem(alpha: Float, ms: Float, accents: Boolean) {
        drawCircle(halo, bloom, Offset.Zero, alpha = alpha)
        // The moonlets on the far side of their orbit pass behind the stone.
        for (i in 0 until GEM_MOONLETS) drawMoonlet(i, ms, alpha, front = false)
        if (onCamera) {
            val sweep = 360f / GEM_RING_FACETS
            val box = Offset(-ring, -ring)
            val span = Size(2f * ring, 2f * ring)
            for (i in 0 until GEM_RING_FACETS) {
                drawArc(gemRingColors[i], i * sweep, sweep, false, box, span, alpha = alpha, style = ringStroke)
                drawArc(gemPale, i * sweep, sweep, false, box, span, alpha = alpha * gemRingHotAlpha[i], style = hotStroke)
            }
        } else {
            for (i in gemFacets.indices) drawPath(gemFacets[i], gemFacetColors[i], alpha = alpha)
            drawCircle(gemPale, 0.28f * core, gemTable, alpha = 0.75f * alpha)
        }
        // The near side of the orbit, in front of it.
        for (i in 0 until GEM_MOONLETS) drawMoonlet(i, ms, alpha, front = true)
        // And at the breath's peak, the glint on its shoulder.
        val g = if (accents) gemGlintAt(ms) else 0f
        if (g > 0f) twinkle(glintSpot, 1.3f * core * g, alpha * g)
    }

    /**
     * Moonlet [i] on its tilted orbit, if it is on that side: on the far side ([front] false)
     * smaller and dimmer, as further away, on the near side whole; tumbling slowly as it goes.
     */
    private fun DrawScope.drawMoonlet(i: Int, ms: Float, alpha: Float, front: Boolean) {
        val a = gemOrbitAt(ms, i)
        val depth = sin(a)
        if ((depth >= 0f) != front) return
        val tilt = GEM_ORBIT_TILT * (PI.toFloat() / 180f)
        val x = orbitX * cos(a)
        val y = orbitY * depth
        val at = Offset(x * cos(tilt) - y * sin(tilt), x * sin(tilt) + y * cos(tilt))
        val near = 0.5f + 0.5f * depth
        translate(at.x, at.y) {
            rotate(70f * ms / 1000f + 140f * i, Offset.Zero) {
                scale(0.75f + 0.25f * near, Offset.Zero) {
                    drawPath(moonShade, moonDeep, alpha = alpha * (0.45f + 0.55f * near))
                    drawPath(moonLit, moonLight, alpha = alpha * (0.45f + 0.55f * near))
                }
            }
        }
    }

    /** A four-point star of arms [s] at [at]: a pinpoint of light caught by the gem. */
    private fun DrawScope.twinkle(at: Offset, s: Float, alpha: Float) {
        if (s <= 0f || alpha <= 0f) return
        drawLine(gemPale, Offset(at.x - s, at.y), Offset(at.x + s, at.y), glintWidth, StrokeCap.Round, alpha = alpha)
        drawLine(gemPale, Offset(at.x, at.y - s), Offset(at.x, at.y + s), glintWidth, StrokeCap.Round, alpha = alpha)
        drawCircle(gemPale, 0.16f * s, at, alpha = alpha)
    }
}
