package com.example.ambientglow.dashboard

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import com.example.ambientglow.GlowSettings
import com.example.ambientglow.LED_BREATH_MS
import com.example.ambientglow.LED_RISE_MS
import com.example.ambientglow.LedDot
import com.example.ambientglow.RealTimeMotion
import com.example.ambientglow.ScreenGeometry
import com.example.ambientglow.hideBarsOnBlack
import com.example.ambientglow.ledBreathAt
import com.example.ambientglow.showBars
import com.example.ambientglow.solveRising
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// The real LED at full size over the darkened dashboard.
// ---------------------------------------------------------------------------------------------

/** How dark the dashboard goes behind the LED preview: nearly the LED's black panel, controls still visible. */
private const val LED_SCRIM = 0.85f
private const val LED_SCRIM_IN_MS = 200
private const val LED_SCRIM_OUT_MS = 320
private const val LED_FOLLOW_IN_MS = 150

/** Handing over to a real-size effect: the dot goes first, the dashboard comes back under it. */
private const val LED_LEAVE_GLOW_MS = 120
private const val LED_LEAVE_SCRIM_MS = 220

/** Where on the breath's rise the LED is at [level], so a new breath picks up without a jump. */
private fun riseMsAt(level: Float): Float = solveRising(level, 0f, LED_RISE_MS, steps = 12, f = ::ledBreathAt)

/**
 * The real LED over the darkened dashboard: its own drawing ([LedDot]) at its real size and
 * position, at the system brightness as the real one lights. While [holding] (the
 * dot is being moved) it stays lit and follows; otherwise it breathes once with the LED's curve
 * and fades away. A new [run] breathes again; [leaving] hands the screen to a real-size effect.
 *
 * The bars return as the scrim starts to lift. Only draws, so the controls underneath keep working.
 */
@Composable
internal fun LedShowcase(
    settings: GlowSettings,
    color: Color,
    geometry: ScreenGeometry,
    run: Int,
    holding: Boolean,
    leaving: Boolean,
    onDone: () -> Unit,
) {
    val scrim = remember { Animatable(0f) }
    // Held lit while dragging, and the breath's clock (ms into it).
    val glow = remember { Animatable(0f) }
    val clock = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    // Hidden bars follow this: two changes per run, not per frame.
    var dark by remember { mutableStateOf(true) }
    // Real time, as the LED screen keeps it: a breath shortened by the animator scale is not the LED's.
    LaunchedEffect(run, holding, leaving) {
        withContext(RealTimeMotion) {
            // Already dark: return at once (a tween to the value it holds still runs its full length).
            suspend fun darken() {
                if (scrim.value < LED_SCRIM) scrim.animateTo(LED_SCRIM, tween(LED_SCRIM_IN_MS, easing = FastOutSlowInEasing))
            }
            // Whatever is showing now, as the held level, so the next step starts where the dot is.
            val shown = maxOf(glow.value, ledBreathAt(clock.value))
            if (leaving) {
                dark = false
                glow.snapTo(shown)
                clock.snapTo(0f)
                coroutineScope {
                    launch { scrim.animateTo(0f, tween(LED_LEAVE_SCRIM_MS, easing = LinearOutSlowInEasing)) }
                    glow.animateTo(0f, tween(LED_LEAVE_GLOW_MS, easing = FastOutLinearInEasing))
                }
                done()
                return@withContext
            }
            dark = true
            if (holding) {
                glow.snapTo(shown)
                clock.snapTo(0f)
                // Lights up only over the dark scrim; at once when re-grabbed there.
                darken()
                glow.animateTo(1f, tween(LED_FOLLOW_IN_MS))
                return@withContext
            }
            darken()
            // From the crest when it was held lit; part-way up the rise if it was still breathing.
            clock.snapTo(riseMsAt(shown))
            glow.snapTo(0f)
            clock.animateTo(LED_BREATH_MS, tween(LED_BREATH_MS.toInt(), easing = LinearEasing))
            dark = false
            scrim.animateTo(0f, tween(LED_SCRIM_OUT_MS, easing = LinearOutSlowInEasing))
            done()
        }
    }
    val window = LocalActivity.current?.window
    // The clock, battery and gesture handle draw above the app, so hide them as the LED screen
    // does; dark while they slide out, so they vanish into the black at once.
    DisposableEffect(window, dark) {
        val hidden = dark
        if (hidden) window?.hideBarsOnBlack()
        onDispose { if (hidden) window?.showBars() }
    }
    Spacer(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = scrim.value) })
    LedDot(
        color = color,
        alpha = { maxOf(glow.value, ledBreathAt(clock.value)) },
        dotX = settings.dotX,
        dotY = settings.dotY,
        radius = settings.dotSize.radius,
        onCamera = settings.ledOnCamera,
        geometry = geometry,
        modifier = Modifier.fillMaxSize(),
    )
}
