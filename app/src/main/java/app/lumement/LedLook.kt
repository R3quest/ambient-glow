package app.lumement

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
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
 * as it is when no effect plays at all: there is nothing for it to carry on. The frame LED is
 * always neon: an element, or light running round it, would repaint the whole edge every step of
 * every blink at the LED's low frame rate, and lag.
 */
internal fun ledMaterial(settings: GlowSettings, shaders: Boolean): LedMaterial = when {
    settings.ledOnEdge || settings.style != GlowStyle.EDGE_FRAME || !settings.arrival.playsEffect -> LedMaterial.NEON
    else -> when (handOffToken(settings, shaders)) {
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
 * The LED in [material] and [color], sized by [light], built about the origin: the dot, the ring
 * round the lens, or the frame round the screen ([form]). [draw] puts it at a centre; everything
 * stays inside [LedLight.bloom], so [LedDot]'s layer holds it. [moves]: one step of its breath
 * changes more than its alpha. Each material is its own [MaterialLook], built only with what it
 * draws. The frame is the Edge Frame of [frameOf] waiting, always in plain light ([FrameLook]),
 * whatever [material] says, quieting down as [calm] goes from 0 to 1.
 *
 * [still]: on the always-on display, every waiting app's colour (newest first) at once, as the
 * panel holds one frame and can't be woken to show them in turn: the frame in their colours round
 * the edge, the dot or ring in plain light ([StillLook]).
 */
internal class LedLook(
    material: LedMaterial,
    color: Color,
    light: LedLight,
    form: LedForm,
    frameOf: GlowSettings? = null,
    calm: Float = 0f,
    still: List<Color>? = null,
) {
    private val look: MaterialLook = if (form == LedForm.FRAME) {
        val tone = if (still != null) stillTone(color) else color
        val apps = still?.takeIf { it.size > 1 }?.map(::stillTone)
        FrameLook(tone, light, calm, frameOf?.let { frameTint(it, tone) } ?: apps?.let { appsSweep(it, light.frame) }, still != null)
    } else if (still != null) {
        StillLook(still.ifEmpty { listOf(color) }.map(::stillTone), light, form == LedForm.RING)
    } else {
        val onCamera = form == LedForm.RING
        when (material) {
            LedMaterial.NEON -> NeonLook(color, light, onCamera)
            LedMaterial.COAL -> CoalLook(color, light, onCamera)
            LedMaterial.BEAD -> BeadLook(color, light, onCamera)
            LedMaterial.WISP -> WispLook(color, light, onCamera)
            LedMaterial.GEM -> GemLook(color, light, onCamera)
        }
    }

    val moves: Boolean = look.moves

    /** At [center] and [alpha], [ms] into its breath; [accents] off leaves out the one-off moments (sparks, ripples, glint). */
    fun draw(scope: DrawScope, center: Offset, alpha: Float, ms: Float, accents: Boolean = true) {
        if (alpha <= 0f) return
        scope.translate(center.x, center.y) { with(look) { draw(alpha, ms, accents) } }
    }

    /**
     * Part of the frame's line at [center] and [alpha]: [path] (about the origin, as the look is
     * built) stroked as the frame strokes its line and hot core, in its colours, without its glow.
     * The beacon's lights lay it round the edge ([LedFrameRun]). Nothing for the dot or the ring.
     */
    fun drawFrameLine(scope: DrawScope, center: Offset, path: Path, alpha: Float) {
        val frame = look as? FrameLook ?: return
        if (alpha <= 0f) return
        scope.translate(center.x, center.y) { with(frame) { strokeLine(path, alpha) } }
    }
}

/**
 * The frame LED's colours round the screen in [settings]' Edge Frame colour ([edgeTintStops]),
 * turned as the effect's come to rest ([edgeTintRestDegrees]), so the Edge Frame settles into the
 * same colours; null for the app's [color]. About the origin, where [LedLook] builds.
 */
internal fun frameTint(settings: GlowSettings, color: Color): Brush? =
    edgeTintStops(settings.edgeColor, color)?.let { stops ->
        movingSweep(Offset.Zero, *stops).apply { rotateTo(edgeTintRestDegrees(settings.edgeColor), Offset.Zero) }
    }

/**
 * The colours the still LED on the always-on display is drawn from, out of [palette] at step
 * [cycle] ([LedLook]): the frame in the Edge Frame's Duo or Spectrum takes only that step's colour,
 * the rest every waiting app's.
 */
internal fun stillColors(settings: GlowSettings, palette: List<Int>, cycle: Int): List<Int> =
    if (settings.ledForm == LedForm.FRAME && settings.edgeColor != EdgeColor.APP) listOf(palette[cycle % palette.size]) else palette

/**
 * [colors] round a [frame] about the origin, each on an equal length of its edge, the first from
 * the top's middle, clockwise, edge to edge. By equal angles a tall screen's long sides took more
 * than their share: on the S23, of three apps, the one across the bottom had 38 % more edge.
 */
private fun appsSweep(colors: List<Color>, frame: Size): Brush {
    val turns = List(colors.size + 1) { edgeTurnAt(it.toFloat() / colors.size, frame) }
    val stops = colors.flatMapIndexed { i, c -> listOf(turns[i] to c, turns[i + 1] to c) }
    return movingSweep(Offset.Zero, *stops.toTypedArray()).apply { rotateTo(STILL_FROM_DEGREES, Offset.Zero) }
}

/**
 * Where [share] of the way round a [frame]'s edge (its corners taken as square), from the top's
 * middle, clockwise, lies as seen from its centre: a share of a turn from the top, clockwise.
 */
internal fun edgeTurnAt(share: Float, frame: Size): Float {
    val w = frame.width
    val h = frame.height
    if (share <= 0f) return 0f
    if (share >= 1f || w <= 0f || h <= 0f) return share.coerceAtMost(1f)
    val left = -w / 2f
    val top = -h / 2f
    // Walked round from the top's middle, the corners at these lengths: x right, y down, as the screen has them.
    val s = share * 2f * (w + h)
    val c1 = w / 2f
    val c2 = c1 + h
    val c3 = c2 + w
    val c4 = c3 + h
    val (x, y) = when {
        s <= c1 -> s to top
        s <= c2 -> -left to top + (s - c1)
        s <= c3 -> -left - (s - c2) to -top
        s <= c4 -> left to -top - (s - c3)
        else -> left + (s - c4) to top
    }
    val turn = (atan2(y, x) / (2f * PI.toFloat()) + 0.25f).mod(1f)
    // The top's middle again, just short of the whole way round.
    return if (turn == 0f) 1f else turn
}

/** What every material is built from: the light's size, the app's colour and its tones, and neon's halo. */
private abstract class MaterialLook(protected val color: Color, light: LedLight, protected val onCamera: Boolean) {
    protected val core = light.core
    protected val line = light.line
    protected val ring = light.ring
    protected val bloom = light.bloom
    private val lens = light.lens
    protected val tones by lazy(LazyThreadSafetyMode.NONE) { Tones(color) }
    protected val clear = color.copy(alpha = 0f)
    protected val ringStroke = Stroke(line)
    protected val hotStroke = Stroke(line * 0.4f)
    protected val ringBox = Offset(-ring, -ring)
    protected val ringSpan = Size(2f * ring, 2f * ring)

    /** Neon's halo, which the coal's ring, the bead and the gem glow in too. */
    protected val halo: Brush by lazy(LazyThreadSafetyMode.NONE) { haloOf(color) }

    /** The halo in [tint]. */
    protected fun haloOf(tint: Color): Brush {
        val clear = tint.copy(alpha = 0f)
        return if (onCamera) {
            Brush.radialGradient(
                0f to clear,
                lens / bloom to clear,
                ring / bloom to tint.copy(alpha = 0.65f),
                (ring + (bloom - ring) * 0.35f) / bloom to tint.copy(alpha = 0.22f),
                1f to clear,
                center = Offset.Zero,
                radius = bloom,
            )
        } else {
            Brush.radialGradient(
                0f to tint.copy(alpha = 0.65f),
                0.35f to tint.copy(alpha = 0.22f),
                1f to clear,
                center = Offset.Zero,
                radius = bloom,
            )
        }
    }

    /** It changes more than its alpha through a breath, so it is drawn every step, not once. */
    open val moves: Boolean get() = true

    /** About the origin, [ms] into the breath, at [alpha]. */
    abstract fun DrawScope.draw(alpha: Float, ms: Float, accents: Boolean)
}

/** A plain light: a bright core (or ring) with a hot centre, in a soft bloom. */
private class NeonLook(color: Color, light: LedLight, onCamera: Boolean) : MaterialLook(color, light, onCamera) {
    private val hot = lerp(color, Color.White, 0.45f)

    override val moves: Boolean get() = false

    override fun DrawScope.draw(alpha: Float, ms: Float, accents: Boolean) {
        drawCircle(halo, bloom, Offset.Zero, alpha = alpha)
        if (onCamera) {
            drawCircle(color, ring, Offset.Zero, alpha = alpha, style = ringStroke)
            drawCircle(hot, ring, Offset.Zero, alpha = alpha, style = hotStroke)
        } else {
            drawCircle(color, core, Offset.Zero, alpha = alpha)
            drawCircle(hot, core * 0.5f, Offset.Zero, alpha = alpha)
        }
    }
}

/**
 * The dot or ring on the always-on display, where One UI runs the panel at ~10 nits (measured on
 * the S23, 2026-10-18) and the LED is one still frame: plain light whatever the material, its core
 * whiter than the lit LED's, each of [colors] a slice of the dot or an arc of the ring, the first
 * from the top, clockwise, their halo one light blending from each into the next. It is drawn
 * larger too ([AOD_CORE_EXTRA], [LedSurface.show]).
 */
private class StillLook(private val colors: List<Color>, light: LedLight, onCamera: Boolean) : MaterialLook(colors.first(), light, onCamera) {
    private val sweep = 360f / colors.size
    private val stillHalo: Brush = if (colors.size == 1) halo else blendedHalo()
    private val hots = colors.map { lerp(it, Color.White, STILL_HOT_WHITE) }
    private val stillHotStroke = Stroke(line * STILL_HOT_LINE)
    private val coreBox = Offset(-core, -core)
    private val coreSpan = Size(2f * core, 2f * core)
    private val hotRadius = core * STILL_HOT_CORE
    private val hotBox = Offset(-hotRadius, -hotRadius)
    private val hotSpan = Size(2f * hotRadius, 2f * hotRadius)

    override val moves: Boolean get() = false

    override fun DrawScope.draw(alpha: Float, ms: Float, accents: Boolean) {
        drawCircle(stillHalo, bloom, Offset.Zero, alpha = alpha)
        for (i in colors.indices) {
            val from = STILL_FROM_DEGREES + i * sweep
            if (onCamera) {
                drawArc(colors[i], from, sweep, useCenter = false, topLeft = ringBox, size = ringSpan, alpha = alpha, style = ringStroke)
                drawArc(hots[i], from, sweep, useCenter = false, topLeft = ringBox, size = ringSpan, alpha = alpha, style = stillHotStroke)
            } else {
                drawArc(colors[i], from, sweep, useCenter = true, topLeft = coreBox, size = coreSpan, alpha = alpha)
                drawArc(hots[i], from, sweep, useCenter = true, topLeft = hotBox, size = hotSpan, alpha = alpha)
            }
        }
    }

    /** Each colour at the middle of its arc, blending into the next, under the halo's falloff ([haloOf] in white). */
    private fun blendedHalo(): Brush {
        val n = colors.size
        val seam = lerp(colors.last(), colors.first(), 0.5f)
        val stops = listOf(0f to seam) + colors.mapIndexed { i, c -> (i + 0.5f) / n to c } + listOf(1f to seam)
        val hues = movingSweep(Offset.Zero, *stops.toTypedArray()).apply { rotateTo(STILL_FROM_DEGREES, Offset.Zero) }
        val falloff = haloOf(Color.White) as ShaderBrush
        return object : ShaderBrush() {
            override fun createShader(size: Size): Shader =
                android.graphics.ComposeShader(hues.createShader(size), falloff.createShader(size), android.graphics.PorterDuff.Mode.DST_IN)
        }
    }
}

/**
 * [color] on the always-on display, where One UI runs the panel at ~10 nits: at least
 * [STILL_MIN_LIGHTNESS] light, its hue kept and as much of its chroma as the screen shows. A deep
 * blue, as an app paints it, barely showed there; a light colour stays as it is.
 */
internal fun stillTone(color: Color): Color {
    val lch = toOklch(color)
    return if (lch[0] >= STILL_MIN_LIGHTNESS) color else oklch(STILL_MIN_LIGHTNESS, lch[1], lch[2])
}

private const val STILL_MIN_LIGHTNESS = 0.78f

/** The always-on display's dot and ring: this much larger than the lit LED. */
internal val AOD_CORE_EXTRA = 2.5.dp

/** Where the still LED's first colour starts: the top (a sweep's 0 is 3 o'clock). */
private const val STILL_FROM_DEGREES = -90f

/** The still LED's hot core: its colour under this much white (the lit LED's 0.45), its share of the ring's line and of the dot. */
private const val STILL_HOT_WHITE = 0.7f
private const val STILL_HOT_LINE = 0.55f
private const val STILL_HOT_CORE = 0.6f

/** The frame's hot core on the always-on display, under full light (the lit frame's 0.5). */
private const val STILL_FRAME_HOT_ALPHA = 0.9f

// ---------------------------------------------------------------------------------------------
// The frame LED: the Edge Frame waiting, in plain light. A line round the whole screen is a lot of
// light next to a 3 dp dot, so it is kept quiet: a soft line, a faint hot core and a short glow,
// in the Edge Frame's colours. It is most vivid as a message arrives and quiets down over the
// bright phase ([BrightStreak.settledAt], as [calm] goes from 0 to 1). It is drawn once a blink
// and breathed by its surface's alpha, as the dot is: an element, or light running round it,
// would repaint the whole edge every step at the LED's low frame rate, and lag.
// ---------------------------------------------------------------------------------------------

/** How bright the frame is at its quietest, as a share of a new message's ([calm] 1). */
private const val FRAME_CALM_LEVEL = 0.55f

/** The line and its hot core, under full light, so the edge glows rather than shines. */
private const val FRAME_LINE_ALPHA = 0.85f
private const val FRAME_HOT_ALPHA = 0.5f

/** The frame's glow: at least this many rings, at most this many, and how bright it is at the line. */
private const val FRAME_GLOW_MIN_RINGS = 3
private const val FRAME_GLOW_MAX_RINGS = 16
private const val FRAME_GLOW_PEAK = 0.3f

/** With Duo or Spectrum, the hot core is its colours under this much white. */
private const val FRAME_HOT_WHITE = 0.45f

/**
 * The frame: a line of light round the screen's edge with a hot core along it, its glow spilling
 * inward ([EdgeGlowRings], as the Edge Frame effect's glow is, so it reads as the same light); in
 * the Edge Frame's colours ([tint]: Duo, Spectrum), or the app's.
 */
private class FrameLook(color: Color, light: LedLight, calm: Float, private val tint: Brush?, still: Boolean) : MaterialLook(color, light, onCamera = false) {
    // On the always-on display (~10 nits) at full light, its core whiter, as the dot and ring are there ([StillLook]).
    private val level = if (still) 1f else 1f - (1f - FRAME_CALM_LEVEL) * calm
    private val lineAlpha = if (still) 1f else FRAME_LINE_ALPHA
    private val hotAlpha = if (still) STILL_FRAME_HOT_ALPHA else FRAME_HOT_ALPHA
    private val hotWhite = if (still) STILL_HOT_WHITE else FRAME_HOT_WHITE
    private val hotLine = if (still) Stroke(line * STILL_HOT_LINE) else hotStroke
    private val paint: Brush = tint ?: SolidColor(color)
    private val hot = lerp(color, Color.White, hotWhite)
    private val frame = light.frame
    private val topLeft = Offset(-frame.width / 2f, -frame.height / 2f)
    private val corner = CornerRadius(light.corner)

    // The glow's rings, from the line's inner edge in to [bloom]; with the glow off, none.
    private val rings = run {
        val count = if (bloom <= 0f) 0 else max(FRAME_GLOW_MIN_RINGS, ceil(bloom / max(1f, line / 2f)).toInt()).coerceAtMost(FRAME_GLOW_MAX_RINGS)
        EdgeGlowRings(topLeft, frame, corner.x, from = line / 2f, step = if (count == 0) 1f else bloom / count, count = count)
    }
    private val ringAlpha = FloatArray(rings.count) { i -> FRAME_GLOW_PEAK * rings.falloff(i) }

    override val moves: Boolean get() = false

    override fun DrawScope.draw(alpha: Float, ms: Float, accents: Boolean) {
        val a = alpha * level
        for (i in 0 until rings.count) {
            drawRoundRect(paint, rings.topLeft[i], rings.size[i], rings.corner[i], style = rings.stroke, alpha = a * ringAlpha[i])
        }
        drawRoundRect(paint, topLeft, frame, corner, style = ringStroke, alpha = a * lineAlpha)
        if (tint == null) {
            drawRoundRect(hot, topLeft, frame, corner, style = hotLine, alpha = a * hotAlpha)
        } else {
            // The hot core a whiter light of its own colours.
            drawRoundRect(tint, topLeft, frame, corner, style = hotLine, alpha = a * hotAlpha)
            drawRoundRect(Color.White, topLeft, frame, corner, style = hotLine, alpha = a * hotAlpha * hotWhite)
        }
    }

    /** [path] as the line and its hot core are drawn, at [alpha]: what the frame is once its glow comes in. */
    fun DrawScope.strokeLine(path: Path, alpha: Float) {
        val a = alpha * level
        drawPath(path, paint, alpha = a * lineAlpha, style = ringStroke)
        if (tint == null) {
            drawPath(path, hot, alpha = a * hotAlpha, style = hotLine)
        } else {
            drawPath(path, tint, alpha = a * hotAlpha, style = hotLine)
            drawPath(path, Color.White, alpha = a * hotAlpha * hotWhite, style = hotLine)
        }
    }
}

/**
 * Fire's coal: lit from inside and above, its glow wavering and rising off it, the odd spark.
 * Round the camera it is warmest across the top, and a flame runs round it once a breath.
 */
private class CoalLook(color: Color, light: LedLight, onCamera: Boolean) : MaterialLook(color, light, onCamera) {
    private val glow = tones.at(0.16f, 1.1f)
    private val heart = tones.pale(0.95f, 0.35f)
    private val sparkHot = tones.pale(0.93f, 0.5f)
    private val sparkGlowRadius = 0.5f * core

    // A spark: a hot point in a glow of the coal's own light, scaled as it burns down.
    private val sparkGlow = Brush.radialGradient(
        0f to glow.copy(alpha = 0.85f),
        0.35f to glow.copy(alpha = 0.35f),
        1f to glow.copy(alpha = 0f),
        center = Offset.Zero,
        radius = sparkGlowRadius,
    )

    // The dot: its glow sits a little high, as heat rises (centred above it, and no bigger for it),
    // over a body lit from inside and above with a crust below.
    private val rise = 0.3f * core
    private val dotHalo by lazy(LazyThreadSafetyMode.NONE) {
        Brush.radialGradient(
            0f to glow.copy(alpha = 0.6f),
            0.3f to color.copy(alpha = 0.26f),
            1f to clear,
            center = Offset(0f, -rise),
            radius = bloom - rise,
        )
    }
    private val dotBody by lazy(LazyThreadSafetyMode.NONE) {
        Brush.radialGradient(
            0f to glow,
            0.4f to color,
            1f to tones.at(-0.24f, 0.9f),
            center = Offset(0f, -0.35f * core),
            radius = 1.25f * core,
        )
    }

    // The ring: the glow lifting off its top (only outside it: neon's halo leaves the lens dark
    // too); warmest across the top (a sweep starts at 3 o'clock and runs clockwise, so three
    // quarters in is the top); and the running flame, a head of heat at a quarter round (the
    // bottom, unturned) with its glow trailing behind it, the way it runs.
    private val plume by lazy(LazyThreadSafetyMode.NONE) {
        Brush.radialGradient(0f to glow.copy(alpha = 0.35f), 1f to glow.copy(alpha = 0f), center = Offset(0f, -ring), radius = 2.2f * core)
    }
    private val outsideRing by lazy(LazyThreadSafetyMode.NONE) {
        Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, bloom))
            addOval(Rect(Offset.Zero, ring))
        }
    }
    private val warm by lazy(LazyThreadSafetyMode.NONE) {
        Brush.sweepGradient(0f to color, 0.25f to color, 0.5f to color, 0.75f to glow, 1f to color, center = Offset.Zero)
    }
    private val flame by lazy(LazyThreadSafetyMode.NONE) {
        Brush.sweepGradient(
            0f to glow.copy(alpha = 0f),
            0.12f to glow.copy(alpha = 0.35f),
            0.24f to glow,
            0.27f to glow.copy(alpha = 0f),
            1f to glow.copy(alpha = 0f),
            center = Offset.Zero,
        )
    }
    private val flameHot by lazy(LazyThreadSafetyMode.NONE) {
        Brush.sweepGradient(
            0f to heart.copy(alpha = 0f),
            0.18f to heart.copy(alpha = 0.3f),
            0.245f to heart,
            0.265f to heart.copy(alpha = 0f),
            1f to heart.copy(alpha = 0f),
            center = Offset.Zero,
        )
    }

    override fun DrawScope.draw(alpha: Float, ms: Float, accents: Boolean) {
        val glowing = coalGlowAt(ms)
        if (onCamera) {
            drawCircle(halo, bloom, Offset.Zero, alpha = alpha * glowing)
            clipPath(outsideRing) { drawCircle(plume, 2.2f * core, Offset(0f, -ring), alpha = alpha * glowing) }
            drawCircle(warm, ring, Offset.Zero, alpha = alpha, style = ringStroke)
            rotate(coalRunAt(ms), Offset.Zero) {
                drawCircle(flame, ring, Offset.Zero, alpha = alpha * glowing, style = ringStroke)
                drawCircle(flameHot, ring, Offset.Zero, alpha = alpha * (0.6f + 0.4f * glowing), style = hotStroke)
            }
        } else {
            drawCircle(dotHalo, bloom - rise, Offset(0f, -rise), alpha = alpha * glowing)
            drawCircle(dotBody, core, Offset.Zero, alpha = alpha)
            // Its heart wanders inside it, slowly, as the hottest spot in an ember does.
            val at = Offset(0.16f * core * sin(ms * 0.0042f), -0.3f * core + 0.1f * core * cos(ms * 0.0057f))
            drawCircle(heart, 0.32f * core, at, alpha = alpha * (0.7f + 0.3f * glowing))
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
        val climb: Float
        if (onCamera) {
            x0 = COAL_SPARK_X[i] * 0.9f * ring
            y0 = -sqrt(ring * ring - x0 * x0) - line / 2f
            climb = 1.7f * core
        } else {
            x0 = COAL_SPARK_X[i] * core
            y0 = -0.8f * core
            climb = 2f * core
        }
        val q = 1f - p
        val x = x0 + 0.3f * core * p * sin(PI.toFloat() * 1.6f * p + 1.7f * i)
        val y = y0 - climb * (1f - q * q)
        val size = 1f - 0.65f * p
        val a = alpha * q * sqrt(q) * (p / 0.1f).coerceAtMost(1f)
        translate(x, y) {
            scale(size, Offset.Zero) { drawCircle(sparkGlow, sparkGlowRadius, Offset.Zero, alpha = a) }
            drawCircle(sparkHot, 0.13f * core * size, Offset.Zero, alpha = a)
        }
    }
}

/** Water's bead: glass lit from the top left, a highlight, light focused on its far side; it fills, wobbles and ripples. */
private class BeadLook(color: Color, light: LedLight, onCamera: Boolean) : MaterialLook(color, light, onCamera) {
    private val lit = tones.at(0.16f, 0.55f)
    private val specular = tones.pale(0.97f, 0.15f)
    private val rippleStroke = Stroke(max(1f, 0.2f * core))
    private val specularStroke = Stroke(line * 0.42f, cap = StrokeCap.Round)
    private val causticStroke = Stroke(if (onCamera) line * 0.3f else 0.22f * core, cap = StrokeCap.Round)
    private val body: Brush = if (onCamera) {
        Brush.linearGradient(0f to lit, 0.5f to color, 1f to tones.at(-0.12f), start = Offset(-ring, -ring), end = Offset(ring, ring))
    } else {
        Brush.radialGradient(
            0f to lit,
            0.5f to color,
            1f to tones.at(-0.12f),
            center = Offset(-0.35f * core, -0.35f * core),
            radius = 1.5f * core,
        )
    }

    override fun DrawScope.draw(alpha: Float, ms: Float, accents: Boolean) {
        drawCircle(halo, bloom, Offset.Zero, alpha = alpha)
        val swell = beadSwellAt(ms)
        val from: Float
        if (onCamera) {
            drawCircle(body, ring, Offset.Zero, alpha = alpha, style = ringStroke)
            // The highlight high on the left, and the light it focuses low on the right, sloshing
            // round the ring as the water in it settles.
            val slosh = 16f * swell
            drawArc(specular, 195f + slosh, 50f, false, ringBox, ringSpan, alpha = 0.9f * alpha, style = specularStroke)
            drawArc(lit, 15f + slosh, 50f, false, ringBox, ringSpan, alpha = 0.55f * alpha, style = causticStroke)
            from = ring + line / 2f
        } else {
            // It fills and wobbles: wider as it is shorter and back, as a drop holds its volume.
            scale(1f + 0.09f * swell, 1f - 0.07f * swell, Offset.Zero) {
                drawCircle(body, core, Offset.Zero, alpha = alpha)
                val inner = 0.68f * core
                drawArc(lit, 20f, 60f, false, Offset(-inner, -inner), Size(2f * inner, 2f * inner), alpha = 0.55f * alpha, style = causticStroke)
                drawCircle(specular, 0.26f * core, Offset(-0.36f * core, -0.38f * core), alpha = 0.95f * alpha)
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
            drawCircle(color, r, Offset.Zero, alpha = alpha * BEAD_RIPPLE_STRENGTH[i] * q * q * (p / 0.12f).coerceAtMost(1f), style = rippleStroke)
        }
    }
}

/**
 * Air's wisp: no hard edge, a soft glow drawn off to one side and a curl of wind round it that
 * unfurls and spins with the breath. Round the camera, gusts along the ring and wind lines outside it.
 */
private class WispLook(color: Color, light: LedLight, onCamera: Boolean) : MaterialLook(color, light, onCamera) {
    private val body = tones.at(0.06f, 0.85f)
    private val pale = tones.pale(0.96f, 0.3f)
    private val wispHalo: Brush = if (onCamera) {
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
        Brush.radialGradient(0f to color.copy(alpha = 0.5f), 0.4f to color.copy(alpha = 0.18f), 1f to clear, center = Offset.Zero, radius = bloom)
    }

    // The dot: a core with a soft rim instead of an edge (as much light as neon's), the glow drawn
    // off to one side as wind carries it, and one arm of curl opening out fast, clockwise as it
    // turns anticlockwise so its outer end trails (under a turn: more reads as an orbit).
    private val dotCore by lazy(LazyThreadSafetyMode.NONE) {
        Brush.radialGradient(0f to pale, 0.4f to color, 0.78f to color, 1f to clear, center = Offset.Zero, radius = 1.15f * core)
    }
    private val lobe by lazy(LazyThreadSafetyMode.NONE) {
        Brush.radialGradient(0f to body.copy(alpha = 0.42f), 1f to body.copy(alpha = 0f), center = Offset(1.35f * core, 0f), radius = 1.5f * core)
    }
    private val curlBrush by lazy(LazyThreadSafetyMode.NONE) {
        Brush.radialGradient(0f to color, 0.4f to color, 1f to color.copy(alpha = 0f), center = Offset.Zero, radius = 2.6f * core)
    }
    private val curlMeasure by lazy(LazyThreadSafetyMode.NONE) {
        val curl = Path()
        val steps = 28
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val r = (1.05f + 1.5f * t * t) * core
            val a = 1.6f * PI.toFloat() * t
            if (i == 0) curl.moveTo(r * cos(a), r * sin(a)) else curl.lineTo(r * cos(a), r * sin(a))
        }
        PathMeasure().apply { setPath(curl, false) }
    }
    private val curlOut = Path()
    private val curlStroke = Stroke(max(1f, 0.16f * core), cap = StrokeCap.Round)

    // The ring: gusts along it, brighter and fainter, that the turn carries round; and the wind
    // lines outside it, coming out of nothing and thinning away again, as a gust does.
    private val gusts by lazy(LazyThreadSafetyMode.NONE) {
        Brush.sweepGradient(
            0f to color, 0.25f to color.copy(alpha = 0.5f), 0.5f to color.copy(alpha = 0.9f), 0.75f to color.copy(alpha = 0.5f), 1f to color,
            center = Offset.Zero,
        )
    }
    private val gustsHot by lazy(LazyThreadSafetyMode.NONE) {
        Brush.sweepGradient(
            0f to pale, 0.14f to pale.copy(alpha = 0f), 0.36f to pale.copy(alpha = 0f), 0.5f to pale.copy(alpha = 0.7f),
            0.64f to pale.copy(alpha = 0f), 0.86f to pale.copy(alpha = 0f), 1f to pale,
            center = Offset.Zero,
        )
    }
    private val wind by lazy(LazyThreadSafetyMode.NONE) {
        Brush.sweepGradient(
            0f to body.copy(alpha = 0f), WISP_WIND_SWEEP / 720f to body.copy(alpha = 0.6f), WISP_WIND_SWEEP / 360f to body.copy(alpha = 0f),
            1f to body.copy(alpha = 0f),
            center = Offset.Zero,
        )
    }
    private val windStroke = Stroke(line * 0.35f, cap = StrokeCap.Round)

    override fun DrawScope.draw(alpha: Float, ms: Float, accents: Boolean) {
        drawCircle(wispHalo, bloom, Offset.Zero, alpha = alpha)
        // Without the one-off moments (a hand-off landing on it), all of it is out already.
        val unfurl = if (accents) wispUnfurlAt(ms) else 1f
        rotate(wispTurnAt(ms), Offset.Zero) {
            if (onCamera) {
                drawCircle(gusts, ring, Offset.Zero, alpha = alpha, style = ringStroke)
                drawCircle(gustsHot, ring, Offset.Zero, alpha = alpha, style = hotStroke)
                // Two wind lines just outside the ring, the far one closer in and fainter, drawn
                // out from their tails as the gust gets up.
                val sweep = WISP_WIND_SWEEP * unfurl
                val r = ring + 1.6f * line
                drawArc(wind, 0f, sweep, false, Offset(-r, -r), Size(2f * r, 2f * r), alpha = alpha, style = windStroke)
                val near = ring + 1.15f * line
                rotate(180f, Offset.Zero) {
                    drawArc(wind, 0f, sweep, false, Offset(-near, -near), Size(2f * near, 2f * near), alpha = 0.6f * alpha, style = windStroke)
                }
            } else {
                drawCircle(lobe, 1.5f * core, Offset(1.35f * core, 0f), alpha = alpha)
                // The curl unfurls from the light; a shorter, fainter arm opposite makes it a swirl.
                curlOut.reset()
                curlMeasure.getSegment(0f, curlMeasure.length * unfurl, curlOut, true)
                drawPath(curlOut, curlBrush, alpha = alpha, style = curlStroke)
                rotate(180f, Offset.Zero) {
                    scale(0.72f, Offset.Zero) { drawPath(curlOut, curlBrush, alpha = 0.5f * alpha, style = curlStroke) }
                }
            }
        }
        if (!onCamera) drawCircle(dotCore, 1.15f * core, Offset.Zero, alpha = alpha)
    }
}

/**
 * Earth's stone: the hand-off's cut crystal (or a ring cut into facets), each facet lit by how
 * squarely it faces the light, with two little stones orbiting it, passing behind it and in
 * front, and a glint at the breath's peak.
 */
private class GemLook(color: Color, light: LedLight, onCamera: Boolean) : MaterialLook(color, light, onCamera) {
    private val pale = tones.pale(0.98f, 0.1f)
    private val glintWidth = max(1f, 0.13f * core)
    private val facets: List<Path>
    private val facetColors: List<Color>
    private val facetHotAlpha: List<Float>
    private val table: Offset
    private val glintSpot: Offset

    // The moonlets: an irregular chip lit from the top left, on an orbit seen a little from above.
    private val moonLit = Path()
    private val moonShade = Path()
    private val moonLight = tones.at(0.1f)
    private val moonDeep = tones.at(-0.18f)
    private val orbitX = if (onCamera) ring + 1.05f * core else 1.9f * core
    private val orbitY = GEM_ORBIT_FLAT * orbitX
    private val tiltCos = cos(GEM_ORBIT_TILT * (PI.toFloat() / 180f))
    private val tiltSin = sin(GEM_ORBIT_TILT * (PI.toFloat() / 180f))

    init {
        val light = LIGHT_FROM / LIGHT_FROM.getDistance()
        if (onCamera) {
            val facing = List(GEM_RING_FACETS) { i ->
                val a = (i + 0.5f) * 2f * PI.toFloat() / GEM_RING_FACETS
                cos(a) * light.x + sin(a) * light.y
            }
            facets = emptyList()
            // Every other facet a touch brighter, so the cut shows where the light is even.
            facetColors = facing.mapIndexed { i, f -> tones.at(facetLight(f) + if (i % 2 == 0) 0.02f else -0.02f) }
            facetHotAlpha = facing.map { 0.25f + 0.5f * max(0f, it) }
            table = Offset.Zero
            val g = -PI.toFloat() / 4f
            glintSpot = Offset(ring * cos(g), ring * sin(g))
        } else {
            // The hand-off's crystal, its box centred on the LED and as tall as the dot is wide.
            val u = core / 1.25f
            val drop = 0.175f * u
            val points = listOf(
                Offset(0f, -1.6f * u + drop), Offset(1.1f * u, -0.5f * u + drop), Offset(0.75f * u, 1.25f * u + drop),
                Offset(-0.75f * u, 1.25f * u + drop), Offset(-1.1f * u, -0.5f * u + drop),
            )
            table = Offset(0f, -0.15f * u + drop)
            glintSpot = points[1]
            facets = points.indices.map { i ->
                val a = points[i]
                val b = points[(i + 1) % points.size]
                Path().apply {
                    moveTo(table.x, table.y)
                    lineTo(a.x, a.y)
                    lineTo(b.x, b.y)
                    close()
                }
            }
            facetColors = points.indices.map { i ->
                val out = (points[i] + points[(i + 1) % points.size]) / 2f - table
                tones.at(facetLight((out.x * light.x + out.y * light.y) / out.getDistance()))
            }
            facetHotAlpha = emptyList()
        }
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

    override fun DrawScope.draw(alpha: Float, ms: Float, accents: Boolean) {
        drawCircle(halo, bloom, Offset.Zero, alpha = alpha)
        // The moonlets on the far side of their orbit pass behind the stone.
        for (i in 0 until GEM_MOONLETS) drawMoonlet(i, ms, alpha, front = false)
        if (onCamera) {
            val sweep = 360f / GEM_RING_FACETS
            for (i in 0 until GEM_RING_FACETS) {
                drawArc(facetColors[i], i * sweep, sweep, false, ringBox, ringSpan, alpha = alpha, style = ringStroke)
                drawArc(pale, i * sweep, sweep, false, ringBox, ringSpan, alpha = alpha * facetHotAlpha[i], style = hotStroke)
            }
        } else {
            for (i in facets.indices) drawPath(facets[i], facetColors[i], alpha = alpha)
            drawCircle(pale, 0.28f * core, table, alpha = 0.75f * alpha)
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
        val x = orbitX * cos(a)
        val y = orbitY * depth
        val near = 0.5f + 0.5f * depth
        val shade = alpha * (0.45f + 0.55f * near)
        translate(x * tiltCos - y * tiltSin, x * tiltSin + y * tiltCos) {
            rotate(70f * ms / 1000f + 140f * i, Offset.Zero) {
                scale(0.75f + 0.25f * near, Offset.Zero) {
                    drawPath(moonShade, moonDeep, alpha = shade)
                    drawPath(moonLit, moonLight, alpha = shade)
                }
            }
        }
    }

    /** A four-point star of arms [s] at [at]: a pinpoint of light caught by the stone. */
    private fun DrawScope.twinkle(at: Offset, s: Float, alpha: Float) {
        drawLine(pale, Offset(at.x - s, at.y), Offset(at.x + s, at.y), glintWidth, StrokeCap.Round, alpha = alpha)
        drawLine(pale, Offset(at.x, at.y - s), Offset(at.x, at.y + s), glintWidth, StrokeCap.Round, alpha = alpha)
        drawCircle(pale, 0.16f * s, at, alpha = alpha)
    }
}
