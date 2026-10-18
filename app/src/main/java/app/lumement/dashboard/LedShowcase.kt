package app.lumement.dashboard

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import app.lumement.GlowSettings
import app.lumement.LED_BREATH_MS
import app.lumement.LED_RISE_MS
import app.lumement.LedDot
import app.lumement.LedForm
import app.lumement.RealTimeMotion
import app.lumement.ScreenGeometry
import app.lumement.elementFramesSupported
import app.lumement.hideBarsOnBlack
import app.lumement.ledBreathAt
import app.lumement.ledMaterial
import app.lumement.showBars
import app.lumement.solveRising
import app.lumement.ui.theme.GlowMotion
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
    // A new shape takes over from the old in place, both on the same breath.
    for ((form, shape) in ledFormLevels(settings.ledForm)) {
        if (!shape.shows) continue
        key(form) {
            LedDot(
                color = color,
                alpha = { maxOf(glow.value, ledBreathAt(clock.value)) * shape.level },
                dotX = settings.dotX,
                dotY = settings.dotY,
                radius = settings.dotSize.radius,
                form = form,
                geometry = geometry,
                material = ledMaterial(settings.copy(ledForm = form), elementFramesSupported),
                clock = { clock.value },
                frameOf = settings,
                modifier = Modifier.fillMaxSize().frameOpening(form) { shape.level },
            )
        }
    }
}

/** How much of one LED shape shows, 0..1, read in draw; [shows] changes only as it appears or goes. */
@Stable
internal class FormLevel(private val animated: State<Float>) {
    val level: Float get() = animated.value
    val shows: Boolean by derivedStateOf { animated.value > 0f }
}

/**
 * Each LED shape's [FormLevel] as the shape turns to [form]: the chosen one rises, the one it
 * leaves dims, overlapping ([GlowMotion.lightIn], [GlowMotion.lightOut]). At first only [form] shows.
 */
@Composable
internal fun ledFormLevels(form: LedForm): List<Pair<LedForm, FormLevel>> = LedForm.entries.map { each ->
    val on = each == form
    val animated = animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = if (on) GlowMotion.lightIn() else GlowMotion.lightOut(),
        label = "led-form",
    )
    each to remember(animated) { FormLevel(animated) }
}

/** The frame's scale as it starts to rise. */
private const val FRAME_OPEN_FROM = 0.97f

/**
 * The frame opens out to the screen's edges as it rises, and draws back in as it goes; [level] is
 * read in the layer. The other shapes are left as they are.
 */
internal fun Modifier.frameOpening(form: LedForm, level: () -> Float): Modifier =
    if (form != LedForm.FRAME) {
        this
    } else {
        graphicsLayer {
            val s = FRAME_OPEN_FROM + (1f - FRAME_OPEN_FROM) * level()
            scaleX = s
            scaleY = s
        }
    }
