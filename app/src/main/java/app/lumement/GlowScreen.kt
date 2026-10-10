package app.lumement

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// What the glow screen draws ([WakeScreenActivity] runs the lock flow): black and the LED round
// while it covers the lock screen, the effect or the system's pop-up of the message on a black
// arrival, and nothing at all otherwise.
// ---------------------------------------------------------------------------------------------

/** Dark between breaths; with the breath it keeps a ~3.37 s period. */
private const val LED_DARK_MS = 1_510L

/** Dark between two apps' breaths within one round; the round ends with the full [LED_DARK_MS]. */
private const val LED_GAP_MS = 700L

/**
 * One 10 Hz relayout vsync plus SurfaceFlinger's switch at its next vsync, with margin; taken
 * from the dark gap, so the period is unchanged.
 */
private const val LED_RATE_PREROLL_MS = 200L

/** Burn-in guard: the glow steps through a 2 px square, one corner per breath. */
private val PIXEL_SHIFTS = listOf(Offset(0f, 0f), Offset(2f, 0f), Offset(2f, 2f), Offset(0f, 2f))

/** The ring's burn-in guard: it breathes 1 px in and out instead, so it stays centred on the lens. */
private val RING_SHIFTS = listOf(0f, 1f, 0f, -1f)

@Composable
internal fun GlowScreen(
    face: Face,
    ending: Boolean,
    settling: Boolean,
    covered: Boolean,
    arriving: Boolean,
    arrivalSeq: Int,
    settings: GlowSettings,
    geometry: ScreenGeometry,
    onTap: () -> Unit,
    onTouch: (down: Boolean) -> Unit,
    onArrivalDone: () -> Unit,
    onShowMessage: () -> Unit,
    onRetractMessage: () -> Unit,
    onBlink: () -> Unit,
    onLedFade: (fast: Boolean) -> Unit,
) {
    // Only the LED face draws anything. The others stay fully transparent, so the lock screen
    // (or, right after an unlock, the home screen) is what the user sees.
    if (face != Face.LED) return
    val colors = GlowPending.colors().ifEmpty { listOf(DEFAULT_GLOW_COLOR) }
    val tap by rememberUpdatedState(onTap)
    val touch by rememberUpdatedState(onTouch)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallow both taps of a double tap, so the second one can't reach the lock screen
            // (where One UI's double-tap-to-sleep would turn the screen off again). Act on the
            // second press, not its release: its lift still comes to this window, and the lock
            // screen starts showing one press-length sooner.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    touch(true)
                    if (waitForUpOrCancellation()?.consume() == null) {
                        touch(false)
                        return@awaitEachGesture
                    }
                    withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }?.consume()
                    tap()
                }
            },
    ) {
        // Keyed per announced message, so a new one restarts the effect. Newest colour first.
        // Settling: nothing until the bars are ours. Ending (read elsewhere): an arrival vanishes
        // (the activity ends it too), the dot finishes the breath it is in and lights no more.
        // Covered (pocket, face down): the round pauses with the panel off and starts over, newest
        // colour first, when it is uncovered; an arrival still runs out, so it hands over as ever.
        if (!settling) {
            key(arrivalSeq) {
                if (arriving && !ending) {
                    if (settings.arrival == ArrivalMode.MESSAGE) {
                        // The effect plays once around the system's pop-up; the pop-up sets the length.
                        ArrivalEffect(settings, colors.first(), geometry, onDone = {}, overBlack = true)
                        MessagePopUp(onShow = onShowMessage, onRetract = onRetractMessage, onDone = onArrivalDone)
                    } else {
                        ArrivalEffect(settings, colors.first(), geometry, onDone = onArrivalDone, overBlack = true)
                    }
                } else if (!arriving && !covered) {
                    LedLayer(settings, colors, geometry, onBlink, onFade = onLedFade, stopping = ending)
                }
            }
        }
    }
}

/** How long the black panel holds the system's message pop-up before retracting it. */
private const val MESSAGE_HOLD_MS = 6_000L

/** The heads-up's slide-out before the rate drops and the dot starts; tune 350-450 against One UI. */
private const val MESSAGE_RETRACT_MS = 400L

/**
 * [ArrivalMode.MESSAGE]: the system's pop-up is the message; this only raises it, times it and
 * retracts it, so the card is gone before the dot takes over.
 */
@Composable
private fun MessagePopUp(onShow: () -> Unit, onRetract: () -> Unit, onDone: () -> Unit) {
    val show by rememberUpdatedState(onShow)
    val retract by rememberUpdatedState(onRetract)
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        show()
        withContext(RealTimeMotion) {
            delay(MESSAGE_HOLD_MS)
            retract()
            delay(MESSAGE_RETRACT_MS)
        }
        done()
    }
}

/**
 * Old-school notification LED: a round of breaths, one per waiting app, newest first, then a
 * rest. Always the dot; the chosen style is only the new-message effect ([ArrivalEffect]).
 * [onFade] asks for the breath's frame rate while dark, before it lights and after it fades.
 * [stopping]: the breath in progress runs out on its own exhale and no new one starts.
 */
@Composable
private fun LedLayer(
    settings: GlowSettings,
    colors: List<Int>,
    geometry: ScreenGeometry,
    onBlink: () -> Unit,
    onFade: (fast: Boolean) -> Unit,
    stopping: Boolean,
) {
    val clock = remember { Animatable(0f) }
    val blink by rememberUpdatedState(onBlink)
    val fade by rememberUpdatedState(onFade)
    val stop by rememberUpdatedState(stopping)
    val palette by rememberUpdatedState(colors)
    var cycle by remember { mutableIntStateOf(0) }
    // Fixed per breath, so a palette change (or the default once all is read) waits for the dark.
    var shown by remember { mutableIntStateOf(colors.first()) }
    DisposableEffect(Unit) { onDispose { fade(false) } }
    // The breath is a signal, not decoration: keep its timing even when developer options
    // shorten or disable animations (animator duration scale).
    LaunchedEffect(Unit) {
        withContext(RealTimeMotion) {
            while (true) {
                if (stop) break
                fade(true)
                delay(LED_RATE_PREROLL_MS) // dark: the new rate is in place before the first lit frame
                if (stop) {
                    fade(false)
                    break
                }
                shown = palette[cycle % palette.size]
                clock.snapTo(0f)
                clock.animateTo(LED_BREATH_MS, tween(LED_BREATH_MS.toInt(), easing = LinearEasing))
                fade(false)
                val last = palette.size <= 1 || cycle % palette.size == palette.size - 1
                delay((if (last) LED_DARK_MS else LED_GAP_MS) - LED_RATE_PREROLL_MS)
                cycle++
                blink()
            }
        }
    }
    RoundDot(settings, shown, cycle, geometry) { clock.value }
}

/**
 * One breath in [GlowShield]'s blink window, once [running] (the panel is on), at the LED's own
 * pace and look; [round] is its place in the burn-in guard. [onDone] once it is dark again.
 *
 * Its light moves on the LED's own [LED_FADE_HZ] grid. One UI holds the panel at its top rate
 * after every wake, where the window can't ask for less (measured: 120 Hz on the S23), and a
 * light redrawn on every one of those frames costs the app and the compositor four times the work
 * for no smoother a light than the lit LED's. A frame with nothing new draws nothing.
 */
@Composable
internal fun BlinkBreath(settings: GlowSettings, color: Int, round: Int, geometry: ScreenGeometry, running: Boolean, onDone: () -> Unit) {
    var ms by remember { mutableFloatStateOf(0f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = withFrameNanos { it }
        do {
            val at = (withFrameNanos { it } - start) / 1_000_000f
            // Nearest step, not the one below: on a panel running at the grid's own rate, float error
            // would otherwise skip one now and then.
            ms = minOf((at / LED_FRAME_MS).roundToInt() * LED_FRAME_MS, LED_BREATH_MS)
        } while (at < LED_BREATH_MS)
        ms = LED_BREATH_MS
        done()
    }
    RoundDot(settings, color, round, geometry) { ms }
}

/** One frame of the LED's breath, in ms. */
private const val LED_FRAME_MS = 1_000f / LED_FADE_HZ

/** The round's dot in [color], [clock] ms into its breath, stepped through the burn-in guard by [cycle]. */
@Composable
private fun RoundDot(settings: GlowSettings, color: Int, cycle: Int, geometry: ScreenGeometry, clock: () -> Float) {
    val onCamera = settings.ledOnCamera
    val shift = if (onCamera) Offset.Zero else PIXEL_SHIFTS[cycle % PIXEL_SHIFTS.size]
    LedDot(
        color = Color(color),
        alpha = { ledBreathAt(clock()) },
        dotX = settings.dotX,
        dotY = settings.dotY,
        radius = settings.dotSize.radius,
        onCamera = onCamera,
        geometry = geometry,
        ringGrowPx = if (onCamera) RING_SHIFTS[cycle % RING_SHIFTS.size] else 0f,
        material = ledMaterial(settings, elementFramesSupported),
        clock = clock,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = shift.x
                translationY = shift.y
            },
    )
}
