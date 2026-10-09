package app.lumement.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumement.ArrivalEffect
import app.lumement.ArrivalMode
import app.lumement.GlassHaze
import app.lumement.GlowSettings
import app.lumement.LED_BREATH_MS
import app.lumement.LedDot
import app.lumement.elementFramesSupported
import app.lumement.R
import app.lumement.RealTimeMotion
import app.lumement.forPreview
import app.lumement.glassHaze
import app.lumement.ledBreathAt
import app.lumement.ledMaterial
import app.lumement.ledRingGapAt
import app.lumement.ui.components.Chevron
import app.lumement.ui.theme.GlowPalette
import app.lumement.ui.theme.GlowShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// The inline preview: a phone mock-up playing a message's whole story, the screen it lights, the
// real effect scaled to it, then the LED.
// ---------------------------------------------------------------------------------------------

private val StudioPreviewHeight: Dp = 250.dp

/** Matches the corner of [GlowShapes.Phone], so the previewed frame hugs the mock-up's edge. */
private val PhoneCorner: Dp = 14.dp

private const val PREVIEW_LOOP_GAP_MS = 700L

/** Black panel before the dot's first breath, as the LED takes over. */
private const val PREVIEW_LED_DELAY_MS = 350L

/** How fast the mock-up's dot dissolves when the effect restarts or the preview waits. */
private const val PREVIEW_LED_OUT_MS = 120

/** Where the inline preview is in the story a message tells: its effect, then the LED. */
internal enum class PreviewPhase { EFFECT, LED, REST }

/**
 * The whole arrival in a phone mock-up, looped: the screen the effect plays on (lock screen,
 * black, or black with the message pop-up, as chosen under Where it plays), the real
 * [ArrivalEffect] scaled to it, then the black panel and one breath of the real [LedDot]. Any
 * change the effect shows, or a tap, restarts it from the effect's first frame; moving or sizing
 * the LED does not. Waits while a real-size preview plays ([heldBy] is the step it shows), so
 * only one effect moves at a time, then picks the story up after that step; a tap still plays
 * it. Parks too while less than a fifth of it is on screen (another tab, scrolled away), so it
 * draws no frames there. Without [loop] (Remove animations) it plays each change once. Without
 * [effect] (Just the LED) the story is the LED alone.
 */
@Composable
internal fun EffectPreview(
    settings: GlowSettings,
    color: Color,
    heldBy: PreviewPhase?,
    loop: Boolean,
    effect: Boolean = true,
) {
    var run by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(PreviewPhase.EFFECT) }
    // The LED's breath clock (ms into it), and a fade so the dot dissolves rather than cuts out.
    val led = remember { Animatable(0f) }
    val ledOut = remember { Animatable(1f) }
    // The inline effect dissolves as a real-size run takes over, instead of cutting out.
    val effectOut = remember { Animatable(1f) }
    val effectShown by remember { derivedStateOf { effectOut.value > 0f } }
    val effectKey = remember(settings) { settings.forPreview() }
    val held = heldBy != null
    var tapped by remember { mutableStateOf(false) }
    var onScreen by remember { mutableStateOf(false) }
    // Picks up after the real-size preview: past the effect it just played, or past its LED.
    var wasHeld by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf(PreviewPhase.REST) }
    LaunchedEffect(heldBy) {
        if (heldBy != null) resume = if (heldBy == PreviewPhase.EFFECT) PreviewPhase.LED else PreviewPhase.REST
    }
    LaunchedEffect(held) { if (!held) tapped = false }
    val heldPark = held && !tapped
    val parked = heldPark || !onScreen
    LaunchedEffect(effectKey, color) { if (!parked) phase = PreviewPhase.EFFECT }
    LaunchedEffect(heldPark) {
        if (heldPark) {
            wasHeld = true
            if (phase == PreviewPhase.EFFECT && onScreen) {
                effectOut.animateTo(0f, tween(SHOWCASE_FADE_MS, easing = FastOutLinearInEasing))
            } else {
                effectOut.snapTo(0f)
            }
        } else {
            effectOut.snapTo(1f)
            if (wasHeld) {
                wasHeld = false
                if (onScreen) phase = resume
            }
        }
    }
    // Back in view: from the effect's first frame, unless a real-size preview still holds it.
    LaunchedEffect(onScreen) {
        if (onScreen && !heldPark) {
            run++
            phase = PreviewPhase.EFFECT
        }
    }
    // The LED is a signal, as on the real screen: its breath keeps its timing at any animator scale.
    LaunchedEffect(phase, parked, loop, effect) {
        withContext(RealTimeMotion) {
            if (parked || (phase == PreviewPhase.EFFECT && effect)) {
                if (ledBreathAt(led.value) * ledOut.value > 0f) {
                    ledOut.animateTo(0f, tween(PREVIEW_LED_OUT_MS, easing = FastOutLinearInEasing))
                }
                led.snapTo(0f)
                ledOut.snapTo(1f)
                return@withContext
            }
            when (phase) {
                // No effect to play: the story starts at the LED.
                PreviewPhase.EFFECT -> phase = PreviewPhase.LED
                PreviewPhase.LED -> {
                    ledOut.snapTo(1f)
                    delay(PREVIEW_LED_DELAY_MS)
                    led.snapTo(0f)
                    led.animateTo(LED_BREATH_MS, tween(LED_BREATH_MS.toInt(), easing = LinearEasing))
                    phase = PreviewPhase.REST
                }
                PreviewPhase.REST -> if (loop) {
                    delay(PREVIEW_LOOP_GAP_MS)
                    run++
                    phase = PreviewPhase.EFFECT
                }
            }
        }
    }
    // What the run on screen started with, so the pick that parks it doesn't restart it as it dissolves.
    var shownKey by remember { mutableStateOf(effectKey) }
    var shownColor by remember { mutableStateOf(color) }
    if (!parked) {
        shownKey = effectKey
        shownColor = color
    }
    val playing = phase == PreviewPhase.EFFECT && !parked && effect
    val dissolving = phase == PreviewPhase.EFFECT && heldPark && onScreen && effectShown
    // The screen behind the effect lights with it and goes dark as the LED takes over.
    val lockScreen = animateFloatAsState(
        targetValue = if (playing && settings.arrival == ArrivalMode.LOCK_SCREEN) 1f else 0f,
        animationSpec = tween(if (playing) 200 else 450),
        label = "lock-screen",
    )
    val popUp = animateFloatAsState(
        targetValue = if (playing && settings.arrival == ArrivalMode.MESSAGE) 1f else 0f,
        animationSpec = tween(if (playing) 300 else 450),
        label = "pop-up",
    )

    val density = LocalDensity.current
    val screenHeight = LocalWindowInfo.current.containerSize.height.toFloat()
    val scale = with(density) { StudioPreviewHeight.toPx() / screenHeight.coerceAtLeast(1f) }.coerceIn(0.1f, 1f)
    val corner = with(density) { PhoneCorner.toPx() }
    // The glass wave's blur of the screen under it, as the real lock screen gets it (Android 12+).
    val haze = remember { GlassHaze() }
    val previewLabel = stringResource(R.string.effect_preview_label)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhoneMock(
            Modifier
                .height(StudioPreviewHeight)
                .aspectRatio(0.48f)
                .clip(GlowShapes.Phone)
                .onVisibilityChanged(minFractionVisible = 0.2f) { onScreen = it }
                .clickable(role = Role.Button) {
                    run++
                    phase = PreviewPhase.EFFECT
                    // A play, not the end of a hold: nothing to resume once the real-size run ends.
                    wasHeld = false
                    tapped = held
                }
                .semantics { contentDescription = previewLabel },
        ) { mockGeometry ->
            val effectGeometry = mockGeometry.copy(cornerRadiusPx = corner)
            MockLockScreen(
                accent = color,
                alpha = { lockScreen.value },
                modifier = Modifier.glassHaze(haze, settings, effectGeometry, scale),
            )
            MockPopUp(accent = color, alpha = { popUp.value })
            if (playing || dissolving) {
                key(run, shownKey, shownColor) {
                    ArrivalEffect(
                        settings = shownKey,
                        color = shownColor.toArgb(),
                        geometry = effectGeometry,
                        onDone = { phase = PreviewPhase.LED },
                        modifier = Modifier.graphicsLayer {
                            alpha = effectOut.value
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                        scale = scale,
                        onBlurBehind = haze,
                        overBlack = shownKey.arrival.onBlack,
                    )
                }
            }
            // The LED's own light, scaled down: its ring gap shrinks with the mock-up's lens.
            LedDot(
                color = color,
                alpha = { ledBreathAt(led.value) * ledOut.value },
                dotX = settings.dotX,
                dotY = settings.dotY,
                radius = previewDotRadius(settings.dotSize, scale),
                onCamera = settings.ledOnCamera,
                geometry = mockGeometry,
                ringGap = ledRingGapAt(scale),
                material = ledMaterial(settings, elementFramesSupported),
                clock = { led.value },
                modifier = Modifier.fillMaxSize(),
            )
        }
        PreviewSteps(ledActive = phase != PreviewPhase.EFFECT, effect = effect)
    }
}

/** "EFFECT → LED", the current step lit; just "LED" when there is no effect. */
@Composable
private fun PreviewSteps(ledActive: Boolean, effect: Boolean) {
    val effectTint by animateColorAsState(if (ledActive) GlowPalette.TextFaint else GlowPalette.Cyan, label = "step-effect")
    val ledTint by animateColorAsState(if (ledActive) GlowPalette.Cyan else GlowPalette.TextFaint, label = "step-led")
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (effect) {
            Text(stringResource(R.string.preview_step_effect), style = MaterialTheme.typography.labelSmall, color = effectTint)
            Chevron(
                tint = GlowPalette.TextFaint,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size(7.dp)
                    .graphicsLayer { rotationZ = -90f },
            )
        }
        Text(stringResource(R.string.preview_step_led), style = MaterialTheme.typography.labelSmall, color = ledTint)
    }
}

/** Notification cards of the mock-ups: an app icon in [icon] and two lines of text. */
private fun DrawScope.mockCard(top: Float, height: Float, icon: Color) {
    val side = size.width * 0.06f
    val cardWidth = size.width - side * 2f
    drawRoundRect(
        color = GlowPalette.SurfaceHigh,
        topLeft = Offset(side, top),
        size = Size(cardWidth, height),
        cornerRadius = CornerRadius(height * 0.3f),
    )
    val iconRadius = height * 0.17f
    val iconX = side + height * 0.38f
    drawCircle(icon, iconRadius, Offset(iconX, top + height / 2f))
    val textX = iconX + iconRadius * 2.2f
    val line = height * 0.1f
    val textWidth = side + cardWidth - textX - height * 0.3f
    val lines = CornerRadius(line / 2f)
    drawRoundRect(GlowPalette.Outline, Offset(textX, top + height * 0.34f), Size(textWidth * 0.55f, line), lines)
    drawRoundRect(GlowPalette.Outline, Offset(textX, top + height * 0.58f), Size(textWidth * 0.85f, line), lines)
}

/** A lock screen in miniature: clock, the new message (in [accent]) over an older one, shortcuts. */
@Composable
private fun MockLockScreen(accent: Color, alpha: () -> Float, modifier: Modifier = Modifier) {
    // Sized in dp, not sp: it is part of the drawing, so it must not grow with the font scale.
    val clockSize = with(LocalDensity.current) { 26.dp.toSp() }
    Box(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha() }) {
        Text(
            text = stringResource(R.string.preview_clock),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Light,
                fontSize = clockSize,
                lineHeight = clockSize,
            ),
            color = GlowPalette.TextPrimary.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp),
        )
        Canvas(Modifier.fillMaxSize()) {
            val dateWidth = size.width * 0.36f
            drawRoundRect(
                color = GlowPalette.Outline,
                topLeft = Offset((size.width - dateWidth) / 2f, size.height * 0.27f),
                size = Size(dateWidth, 2.5.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
            val cardHeight = size.height * 0.11f
            mockCard(size.height * 0.42f, cardHeight, accent)
            mockCard(size.height * 0.42f + cardHeight + 4.dp.toPx(), cardHeight, GlowPalette.TextFaint)
            val shortcut = size.width * 0.07f
            val shortcutY = size.height - shortcut * 2.2f
            drawCircle(GlowPalette.SurfaceHigh, shortcut, Offset(shortcut * 2.2f, shortcutY))
            drawCircle(GlowPalette.SurfaceHigh, shortcut, Offset(size.width - shortcut * 2.2f, shortcutY))
        }
    }
}

/** The system's pop-up of only the new message, sliding in under the camera. */
@Composable
private fun MockPopUp(accent: Color, alpha: () -> Float) {
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val shown = alpha()
                this.alpha = shown
                translationY = -(1f - shown) * 6.dp.toPx()
            },
    ) {
        mockCard(size.height * 0.08f, size.height * 0.11f, accent)
    }
}
