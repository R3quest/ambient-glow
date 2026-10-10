package app.lumement

import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// What the glow screen draws ([WakeScreenActivity] runs the lock flow): black and the LED round
// while it covers the lock screen, the effect or the system's pop-up of the message on a black
// arrival, and nothing at all otherwise.
// ---------------------------------------------------------------------------------------------

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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        SurfaceLedLayer(settings, colors, geometry, onBlink, onFade, stopping)
    } else {
        ComposeLedLayer(settings, colors, geometry, onBlink, onFade, stopping)
    }
}

/**
 * [LedLayer] in the LED's own surface ([LedSurface]): the same round, its light drawn and breathed
 * without a window redraw, so a breath costs the app's threads next to nothing.
 */
@RequiresApi(Build.VERSION_CODES.Q)
@Composable
private fun SurfaceLedLayer(
    settings: GlowSettings,
    colors: List<Int>,
    geometry: ScreenGeometry,
    onBlink: () -> Unit,
    onFade: (fast: Boolean) -> Unit,
    stopping: Boolean,
) {
    val context = LocalContext.current
    val surface = remember { LedSurface(context) }
    val blink by rememberUpdatedState(onBlink)
    val fade by rememberUpdatedState(onFade)
    val stop by rememberUpdatedState(stopping)
    val palette by rememberUpdatedState(colors)
    val look by rememberUpdatedState(settings)
    val lens by rememberUpdatedState(geometry)
    DisposableEffect(Unit) {
        onDispose {
            surface.stop()
            fade(false)
        }
    }
    LaunchedEffect(Unit) {
        suspendCancellableCoroutine { ready -> surface.whenReady { ready.resume(Unit) } }
        var cycle = 0
        while (true) {
            if (stop) break
            fade(true)
            delay(LED_RATE_PREROLL_MS) // dark: the new rate is in place before the first lit frame
            if (stop) {
                fade(false)
                break
            }
            surface.show(look, lens, palette[cycle % palette.size], cycle)
            suspendCancellableCoroutine { out ->
                surface.breathe { out.resume(Unit) }
                out.invokeOnCancellation { surface.stop() }
            }
            fade(false)
            delay(ledPauseAfter(cycle, palette.size) - LED_RATE_PREROLL_MS)
            cycle++
            blink()
        }
    }
    AndroidView(factory = { surface.view }, modifier = Modifier.fillMaxSize())
}

/** [LedLayer] drawn by Compose, below Android 10 (no surface alpha there). */
@Composable
private fun ComposeLedLayer(
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
                delay(ledPauseAfter(cycle, palette.size) - LED_RATE_PREROLL_MS)
                cycle++
                blink()
            }
        }
    }
    RoundDot(settings, shown, cycle, geometry) { clock.value }
}

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
