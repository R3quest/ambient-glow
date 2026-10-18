package app.lumement

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

// One LED breath: a soft rise, then a long exhale into the dark, like a notification LED. One
// clock (ms into the breath) drives it; the dashboard previews breathe with the same function.
internal const val LED_RISE_MS = 560f
internal const val LED_FALL_MS = 1_300f
internal const val LED_BREATH_MS = LED_RISE_MS + LED_FALL_MS // 1860

/**
 * The rate the LED breathes at. The S23's panel offers 10/24/30/48/60/96/120 Hz: 30 is three times
 * smoother than the 10 Hz idle at half the cost of 60.
 */
internal const val LED_FADE_HZ = 30f

/**
 * The breath's rate is asked for this long before it lights: one 10 Hz relayout vsync plus
 * SurfaceFlinger's switch at its next vsync, with margin; taken from the dark gap, so the period
 * is unchanged.
 */
internal const val LED_RATE_PREROLL_MS = 200L

/** Dark between breaths; with the breath it keeps a ~3.37 s period. */
internal const val LED_DARK_MS = 1_510L

/** Dark between two apps' breaths within one round; the round ends with the full [LED_DARK_MS]. */
internal const val LED_GAP_MS = 700L

/** The dark after breath [cycle] of a round of [count] colours: the gap to the next app's, or the round's end. */
internal fun ledPauseAfter(cycle: Int, count: Int): Long =
    if (count <= 1 || cycle % count == count - 1) LED_DARK_MS else LED_GAP_MS

/** Burn-in guard: the glow steps through a 2 px square, one corner per breath. */
internal val PIXEL_SHIFTS = listOf(Offset(0f, 0f), Offset(2f, 0f), Offset(2f, 2f), Offset(0f, 2f))

/** The ring's burn-in guard: it breathes 1 px in and out instead, so it stays centred on the lens. */
internal val RING_SHIFTS = listOf(0f, 1f, 0f, -1f)

/** The frame's burn-in guard: it steps up to 2 px in from the screen's edge and back, as it can't go out past it. */
internal val FRAME_SHIFTS = listOf(0f, 1f, 2f, 1f)

/**
 * On the always-on display the LED holds one frame for hours, every night, wider and brighter
 * there, so its burn-in step is a share of its own line rather than a pixel or two: each pixel
 * under it lit at most 3 steps in 4, its white core on other pixels at every step. The frame steps
 * in from the edge, the ring out from the lens ([stillStepPx]); the dot round a square ([stillShiftAt]).
 */
internal val STILL_SHIFTS = listOf(0f, 0.5f, 1f, 0.5f)

/** The still frame's inset or ring's growth at step [cycle], in px, for a line [line] px wide. */
internal fun stillStepPx(cycle: Int, line: Float): Float = STILL_SHIFTS[cycle % STILL_SHIFTS.size] * line

/** The still dot's offset at step [cycle]: round a square half its [core] radius wide ([PIXEL_SHIFTS], scaled). */
internal fun stillShiftAt(cycle: Int, core: Float): Offset = PIXEL_SHIFTS[cycle % PIXEL_SHIFTS.size] * (core / 4f)

/** The burn-in step for breath [cycle] of [form]: the dot's offset, the ring's growth, or the frame's inset ([ledStepPx]). */
internal fun ledShiftAt(form: LedForm, cycle: Int): Offset =
    if (form == LedForm.DOT) PIXEL_SHIFTS[cycle % PIXEL_SHIFTS.size] else Offset.Zero

/** The ring's growth or the frame's inset at breath [cycle], in px; nothing for the dot, which moves instead ([ledShiftAt]). */
internal fun ledStepPx(form: LedForm, cycle: Int): Float = when (form) {
    LedForm.DOT -> 0f
    LedForm.RING -> RING_SHIFTS[cycle % RING_SHIFTS.size]
    LedForm.FRAME -> FRAME_SHIFTS[cycle % FRAME_SHIFTS.size]
}

private val LedRise = CubicBezierEasing(0.45f, 0f, 0.3f, 1f)
private val LedFall = CubicBezierEasing(0.45f, 0f, 0.35f, 1f)

/**
 * LED level at [ms] into one breath. Both curves start and end at zero velocity, so it leaves the
 * dark, turns at the peak and settles back with no knee, and no frame jumps far even at 30 Hz.
 */
internal fun ledBreathAt(ms: Float): Float = when {
    ms <= 0f -> 0f
    ms < LED_RISE_MS -> LedRise.transform(ms / LED_RISE_MS)
    ms < LED_BREATH_MS -> 1f - LedFall.transform((ms - LED_RISE_MS) / LED_FALL_MS)
    else -> 0f
}

/** LED bloom radius as a multiple of its core. */
internal const val LED_HALO_FACTOR = 3.2f

internal object RealTimeMotion : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/**
 * Ring LED: line thickness as a share of the dot radius (a frame's core is sized back from its
 * line by it), and its clearance from the lens.
 */
internal const val LED_RING_STROKE_FACTOR = 0.6f
internal val LED_RING_GAP = 1.5.dp

/** A scaled-down LED (a mock-up's) is kept at least this big, and its ring at least this clear of the lens. */
private val MIN_SCALED_LED = 1.4.dp
private val MIN_SCALED_RING_GAP = 0.5.dp

/**
 * The LED's radius at [scale]: the real dot, or scaled down to a mock-up and kept just large
 * enough to see. Whatever draws the LED or lands on it (the effects' endings) uses this, so the
 * light lands exactly on the dot the mock-up then breathes.
 */
internal fun ledRadiusAt(size: DotSize, scale: Float): Dp = (size.radius * scale).coerceAtLeast(MIN_SCALED_LED)

/** The ring LED's clearance from the lens at [scale], as [ledRadiusAt] is its size. */
internal fun ledRingGapAt(scale: Float): Dp = (LED_RING_GAP * scale).coerceAtLeast(MIN_SCALED_RING_GAP)

/**
 * A bright core, a hot white centre and a soft radial bloom, or its element's material. Built
 * once per colour/size change in drawWithCache; each breath frame changes the layer alpha.
 * The dashboard draws its real-size LED preview and the phone mock-up's LED with this too.
 *
 * The layer is only as big as the light: a breath frame then redraws that square, not the whole
 * window (and its alpha needs an offscreen buffer only that big). It is centred on the light with
 * a sub-pixel translation, so the light lands exactly where a full-size layer drew it.
 *
 * [form]: the dot, or the same light as a ring hugging the punch-hole, whose pixels cannot light
 * ([radius] then sets the ring's thickness and [ringGap] its clearance from the lens, which the
 * mock-up scales with its lens), or as a frame round the screen's edge, whose layer is the whole
 * screen. [stepPx] is the ring's or the frame's burn-in step ([ledStepPx]).
 *
 * [material]: what it is made of ([LedLook]); one that moves reads [clock], ms into the breath. A
 * frame is the Edge Frame of [frameOf] waiting (its width, glow and colour), sized at [scale] (a
 * mock-up's), not by [radius]; it quiets down as [calm] goes from 0 to 1.
 */
@Composable
internal fun LedDot(
    color: Color,
    alpha: () -> Float,
    dotX: Float,
    dotY: Float,
    radius: Dp,
    modifier: Modifier = Modifier,
    form: LedForm = LedForm.DOT,
    geometry: ScreenGeometry = ScreenGeometry.Unknown,
    stepPx: Float = 0f,
    ringGap: Dp = LED_RING_GAP,
    material: LedMaterial = LedMaterial.NEON,
    clock: () -> Float = { 0f },
    scale: Float = 1f,
    frameOf: GlowSettings,
    calm: Float = 0f,
) {
    val density = LocalDensity.current
    // The frame's light depends on the screen's size too, so it is built with the layer below.
    val light = remember(density, radius, form, geometry, stepPx, ringGap) {
        if (form == LedForm.FRAME) {
            null
        } else {
            with(density) { ledLight(radius.toPx(), form == LedForm.RING, geometry.lensRadius(this.density), stepPx, ringGap.toPx()) }
        }
    }
    Spacer(
        modifier
            .layout { measurable, constraints ->
                val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
                val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
                if (light == null) {
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    return@layout layout(width, height) {
                        placeable.placeWithLayer(0, 0) { this.alpha = alpha() }
                    }
                }
                val center = ledCenter(form, geometry, dotX, dotY, light, Size(width.toFloat(), height.toFloat()), this.density)
                val side = light.side
                val placeable = measurable.measure(Constraints.fixed(side, side))
                layout(width, height) {
                    placeable.placeWithLayer(0, 0) {
                        translationX = center.x - side / 2f
                        translationY = center.y - side / 2f
                        this.alpha = alpha()
                    }
                }
            }
            .drawWithCache {
                val shown = light ?: ledFrameLight(
                    frameOf,
                    size,
                    geometry.frameCornerPx(this.density, scale),
                    stepPx,
                    this.density * scale,
                )
                val look = LedLook(material, color, shown, form, frameOf, calm)
                // Neon changes only its layer's alpha; a material that moves redraws its square.
                onDrawBehind { look.draw(this, size.center, 1f, if (look.moves) clock() else 0f) }
            },
    )
}

/**
 * The LED's light in px: the dot's [core] (sets the ring's thickness; a frame's is sized back from
 * its [line]), the [ring] of [line] width round a [lens] of that radius, and the [bloom] that
 * bounds all of it. A frame's [line] runs round a rounded rectangle [frame] across (through the
 * line's middle) with [corner] radius, centred on the screen; its [bloom] is how far its glow
 * reaches in from the line.
 */
@Immutable
internal class LedLight(
    val core: Float,
    val lens: Float,
    val line: Float,
    val ring: Float,
    val bloom: Float,
    val frame: Size = Size.Zero,
    val corner: Float = 0f,
) {
    /** The layer's side: the bloom plus room for antialiasing all round, even so the light sits at its centre. */
    val side: Int = 2 * ceil(bloom + LED_LAYER_MARGIN_PX).toInt()
}

private const val LED_LAYER_MARGIN_PX = 2f

internal fun ledLight(core: Float, onCamera: Boolean, lens: Float, ringGrowPx: Float, ringGap: Float): LedLight {
    if (!onCamera) return LedLight(core, lens = 0f, line = 0f, ring = 0f, bloom = core * LED_HALO_FACTOR)
    val line = core * LED_RING_STROKE_FACTOR
    val ring = lens + ringGap + line / 2f + ringGrowPx
    return LedLight(core, lens, line, ring, bloom = ring + line / 2f + core * (LED_HALO_FACTOR - 1f))
}

/**
 * The frame LED is the Edge Frame waiting: its line and glow are the Edge Frame's ([EdgeWidth],
 * [EdgeGlow]) at about half, a light that waits rather than one arriving. A Hairline frame waits
 * as a 1.5 dp line, a Heavy one as 5.6 dp; Off has no glow.
 */
private const val FRAME_LINE_SHARE = 0.45f
private val FRAME_LINE_FLOOR = 0.6.dp
private const val FRAME_GLOW_SHARE = 0.5f

/** The frame LED's line at full screen, from the Edge Frame's width. */
internal val EdgeWidth.ledLine: Dp get() = stroke * FRAME_LINE_SHARE + FRAME_LINE_FLOOR

/** How far the frame LED's glow reaches in at full screen, from the Edge Frame's glow. */
internal val EdgeGlow.ledReach: Dp get() = reach * FRAME_GLOW_SHARE

/**
 * The frame LED round a screen of [size] whose corners have [corner] radius, its line and glow
 * from [settings]' Edge Frame ([ledLine], [ledReach]; [full]: the effect's own line, for the
 * always-on display's ~10 nits) at [dp] px a dp; [inset] is its burn-in step in from the edge
 * ([FRAME_SHIFTS]). Its core is what a ring of that line would have, for whatever is sized by it.
 */
internal fun ledFrameLight(settings: GlowSettings, size: Size, corner: Float, inset: Float, dp: Float, full: Boolean = false): LedLight {
    val line = (if (full) settings.edgeWidth.stroke else settings.edgeWidth.ledLine).value * dp
    val edge = inset + line / 2f
    return LedLight(
        core = line / LED_RING_STROKE_FACTOR,
        lens = 0f,
        line = line,
        ring = 0f,
        bloom = settings.edgeGlow.ledReach.value * dp,
        frame = Size((size.width - 2f * edge).coerceAtLeast(0f), (size.height - 2f * edge).coerceAtLeast(0f)),
        corner = (corner - edge).coerceAtLeast(0f),
    )
}

/** The screen's corner radius in px, or the usual one where the display reports none (previews, older Android). */
internal fun ScreenGeometry.frameCornerPx(density: Float, scale: Float = 1f): Float =
    cornerRadiusPx ?: (GlowMetrics.FullScreen.fallbackCornerRadius.value * density * scale)

/** The LED's centre on a canvas of [size]: the lens for the ring, the screen's for the frame, else the dot's spot ([ledDotCenter]). */
internal fun ledCenter(form: LedForm, geometry: ScreenGeometry, dotX: Float, dotY: Float, light: LedLight, size: Size, density: Float): Offset = when (form) {
    LedForm.RING -> geometry.lens(size.width, density).center
    LedForm.FRAME -> size.center
    LedForm.DOT -> ledDotCenter(dotX, dotY, light.core, size)
}

/**
 * The dot LED's centre on a canvas of [size]: kept on screen by its halo, with the same margin as
 * [GlowGraphic], so the LED sits exactly where the dashboard showed it.
 */
internal fun ledDotCenter(dotX: Float, dotY: Float, core: Float, size: Size): Offset =
    dotCenter(dotX, dotY, size, core * DOT_HALO_FACTOR)

/** Where an effect lands: the LED's [light] and its [center] on the canvas. */
@Immutable
internal class LedLanding(val center: Offset, val light: LedLight)

/**
 * The LED as an effect at [scale] lands on it, round [lens] as that effect places the camera: lit
 * as [LedDot] lights its first breath (no burn-in shift yet). The beacon's ember and the Edge
 * Frame's hand-off both end in exactly this, so the LED that lights next doesn't jump. A frame
 * lands round the whole screen, its corners [corner] in radius ([frameCornerPx]), which every
 * frame landing passes ([frameLanding]); the dot and the ring don't use it.
 */
internal fun Density.ledLanding(settings: GlowSettings, lens: CutoutSpot, size: Size, scale: Float, corner: Float = 0f): LedLanding {
    if (settings.ledOnEdge) {
        return LedLanding(size.center, ledFrameLight(settings, size, corner, inset = 0f, dp = density * scale))
    }
    val core = ledRadiusAt(settings.dotSize, scale).toPx()
    val onCamera = settings.ledOnCamera
    val light = ledLight(core, onCamera, lens.radius, ringGrowPx = 0f, ringGap = ledRingGapAt(scale).toPx())
    val center = if (onCamera) lens.center else ledDotCenter(settings.dotX, settings.dotY, core, size)
    return LedLanding(center, light)
}
