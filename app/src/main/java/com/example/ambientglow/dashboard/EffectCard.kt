package com.example.ambientglow.dashboard

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ambientglow.ArrivalEffect
import com.example.ambientglow.ArrivalMode
import com.example.ambientglow.DEFAULT_GLOW_COLOR
import com.example.ambientglow.EdgeColor
import com.example.ambientglow.EdgeGlow
import com.example.ambientglow.EdgeMotion
import com.example.ambientglow.EdgeWidth
import com.example.ambientglow.GlassArea
import com.example.ambientglow.GlassBlur
import com.example.ambientglow.GlassFrost
import com.example.ambientglow.GlassHaze
import com.example.ambientglow.GlowGraphic
import com.example.ambientglow.GlowMetrics
import com.example.ambientglow.GlowSettings
import com.example.ambientglow.GlowStyle
import com.example.ambientglow.LED_BREATH_MS
import com.example.ambientglow.LedDot
import com.example.ambientglow.R
import com.example.ambientglow.RealTimeMotion
import com.example.ambientglow.SpawnElement
import com.example.ambientglow.forPreview
import com.example.ambientglow.glassHaze
import com.example.ambientglow.ledBreathAt
import com.example.ambientglow.ui.components.CardDivider
import com.example.ambientglow.ui.components.Chevron
import com.example.ambientglow.ui.components.ChipRow
import com.example.ambientglow.ui.components.Disclosure
import com.example.ambientglow.ui.components.Fold
import com.example.ambientglow.ui.components.NoticeRow
import com.example.ambientglow.ui.components.OptionBody
import com.example.ambientglow.ui.components.OptionGroup
import com.example.ambientglow.ui.components.OptionNote
import com.example.ambientglow.ui.components.RadioRow
import com.example.ambientglow.ui.components.SectionLabel
import com.example.ambientglow.ui.components.ToggleRow
import com.example.ambientglow.ui.components.glowCard
import com.example.ambientglow.ui.components.selectionSurface
import com.example.ambientglow.ui.theme.GlowMotion
import com.example.ambientglow.ui.theme.GlowPalette
import com.example.ambientglow.ui.theme.GlowShapes
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// The EFFECT and SCREEN tabs: the effect (live preview, style, try-out colour, Edge Frame options,
// spawn and its element), and where it plays.
// ---------------------------------------------------------------------------------------------

private val StudioPreviewHeight: Dp = 250.dp
private val PickerPhoneHeight: Dp = 46.dp
private val PickerPhoneCorner: Dp = 6.dp

/** Matches the corner of [GlowShapes.Phone], so the previewed frame hugs the mock-up's edge. */
private val PhoneCorner: Dp = 14.dp

private const val PREVIEW_LOOP_GAP_MS = 700L

/** Black panel before the dot's first breath, as the LED takes over. */
private const val PREVIEW_LED_DELAY_MS = 350L

/** How fast the mock-up's dot dissolves when the effect restarts or the preview waits. */
private const val PREVIEW_LED_OUT_MS = 120

/** A colour to try the effect in. Real messages use the colour of the app that sent them. */
@Immutable
internal data class SampleColor(@param:StringRes val name: Int, val color: Color)

internal val SAMPLE_COLORS = listOf(
    SampleColor(R.string.sample_cyan, Color(DEFAULT_GLOW_COLOR)),
    SampleColor(R.string.sample_green, Color(0xFF25D366)),
    SampleColor(R.string.sample_blue, Color(0xFF1E88E5)),
    SampleColor(R.string.sample_violet, Color(0xFF8B5CF6)),
    SampleColor(R.string.sample_pink, GlowPalette.Magenta),
    SampleColor(R.string.sample_red, Color(0xFFFF4B33)),
    SampleColor(R.string.sample_yellow, Color(0xFFFFD60A)),
)

/**
 * The look: the live preview beside the style list, and the colour to try it in.
 * [previewHeld]: the step a real-size preview is playing (or about to), so the inline one waits.
 * [loop] is off with Remove animations: the inline preview then plays each change once, and the
 * hint says that changes no longer play at full size.
 */
@Composable
internal fun EffectCard(
    settings: GlowSettings,
    sample: Int,
    previewHeld: PreviewPhase?,
    loop: Boolean,
    onStyle: (GlowStyle) -> Unit,
    onSample: (Int) -> Unit,
) {
    val color = SAMPLE_COLORS[sample].color
    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(stringResource(R.string.section_effect), GlowPalette.Cyan)
        // Preview beside the style list: what you pick is what plays, without scrolling.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EffectPreview(settings = settings, color = color, heldBy = previewHeld, loop = loop)
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionLabel(stringResource(R.string.effect_style))
                StylePicker(settings, color, onStyle)
            }
        }
        OptionGroup(stringResource(R.string.effect_sample_color)) {
            SampleColorRow(selected = sample, onSelect = onSample)
            Text(
                text = stringResource(if (loop) R.string.effect_showcase_hint else R.string.effect_showcase_hint_reduced),
                style = MaterialTheme.typography.bodySmall,
                color = GlowPalette.TextFaint,
            )
        }
    }
}

/** Edge Frame's own options, folded under what they are set to; the other styles have none. */
@Composable
internal fun EdgeFrameCard(settings: GlowSettings, onEffect: (GlowSettings) -> Unit) {
    val summary = sentence(
        stringResource(
            R.string.edge_summary,
            stringResource(settings.edgeMotion.phrase),
            stringResource(settings.edgeColor.phrase),
            stringResource(settings.edgeWidth.phrase),
            stringResource(settings.edgeGlow.phrase),
        ),
    )
    Column(Modifier.glowCard(vertical = 10.dp)) {
        Fold(title = stringResource(R.string.fold_edge), summary = summary) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Motion and colour aren't plain from their names: what the picked one does, under it.
                OptionGroup(stringResource(R.string.edge_motion)) {
                    ChipRow(EdgeMotion.entries, settings.edgeMotion) { onEffect(settings.copy(edgeMotion = it)) }
                    OptionBody(settings.edgeMotion) { stringResource(it.body) }
                }
                OptionGroup(stringResource(R.string.edge_color)) {
                    ChipRow(EdgeColor.entries, settings.edgeColor) { onEffect(settings.copy(edgeColor = it)) }
                    OptionBody(settings.edgeColor) { stringResource(it.body) }
                }
                OptionGroup(stringResource(R.string.edge_width)) {
                    ChipRow(EdgeWidth.entries, settings.edgeWidth) { onEffect(settings.copy(edgeWidth = it)) }
                }
                OptionGroup(stringResource(R.string.edge_glow)) {
                    ChipRow(EdgeGlow.entries, settings.edgeGlow) { onEffect(settings.copy(edgeGlow = it)) }
                }
            }
        }
    }
}

/**
 * The element the effect takes after, under the preview that shows it. Each element folds its
 * own options under the picker, so adding one adds a fold, not a wall of chips. Elements play in
 * the spawn wave: picking one turns the wave on, and with it off the card says so.
 */
@Composable
internal fun ElementCard(settings: GlowSettings, onEffect: (GlowSettings) -> Unit) {
    val element = settings.element
    // Each disclosure carries its own gap, so the card doesn't jump as it opens or closes.
    Column(Modifier.glowCard()) {
        SectionLabel(stringResource(R.string.element), GlowPalette.Cyan)
        Spacer(Modifier.height(16.dp))
        ElementPicker(element) { picked ->
            onEffect(settings.copy(element = picked, spawn = settings.spawn || picked != SpawnElement.NONE))
        }
        Spacer(Modifier.height(10.dp))
        OptionBody(element) { shown ->
            val body = stringResource(shown.body)
            if (shown.ready) body else body + " " + stringResource(R.string.element_soon)
        }
        Disclosure(visible = element.premium) {
            Box(Modifier.padding(top = 10.dp)) { PremiumLine() }
        }
        Disclosure(visible = !settings.spawn && element != SpawnElement.NONE) {
            Box(Modifier.padding(top = 14.dp)) {
                NoticeRow(
                    text = stringResource(R.string.element_needs_spawn),
                    action = stringResource(R.string.element_spawn_on),
                    onAction = { onEffect(settings.copy(spawn = true)) },
                )
            }
        }
        // Only Water has options so far; the other elements get a fold here as they get a look.
        Disclosure(visible = element == SpawnElement.WATER) {
            Column(Modifier.padding(top = 12.dp)) {
                CardDivider()
                Spacer(Modifier.height(10.dp))
                Fold(title = stringResource(R.string.fold_glass), summary = glassSummary(settings)) {
                    GlassOptions(settings, onEffect)
                }
            }
        }
    }
}

/** How the effect starts: an AirDrop-style wave from the camera, which the element rides. */
@Composable
internal fun SpawnCard(settings: GlowSettings, onEffect: (GlowSettings) -> Unit) {
    Column(Modifier.glowCard()) {
        ToggleRow(
            title = stringResource(R.string.effect_spawn_title),
            body = stringResource(R.string.effect_spawn_body),
            checked = settings.spawn,
            onChange = { onEffect(settings.copy(spawn = it)) },
        )
    }
}

/**
 * Glass wave: how much the screen under it blurs, how frosted, and where. The area shapes both,
 * so it shows whenever either is on. On a black screen there is nothing to blur and only one way
 * for frost to show, so neither blur nor area is offered there.
 */
@Composable
private fun GlassOptions(settings: GlowSettings, onEffect: (GlowSettings) -> Unit) {
    val onBlack = settings.arrival.onBlack
    Column {
        Disclosure(visible = !onBlack) {
            Box(Modifier.padding(bottom = 14.dp)) {
                OptionGroup(stringResource(R.string.glass_blur)) {
                    ChipRow(GlassBlur.entries, settings.glassBlur) { onEffect(settings.copy(glassBlur = it)) }
                }
            }
        }
        OptionGroup(stringResource(R.string.glass_frost)) {
            ChipRow(GlassFrost.entries, settings.glassFrost) { onEffect(settings.copy(glassFrost = it)) }
        }
        Disclosure(visible = onBlack) {
            Box(Modifier.padding(top = 14.dp)) {
                OptionNote(stringResource(R.string.glass_on_black))
            }
        }
        Disclosure(visible = !onBlack && (settings.glassBlur != GlassBlur.OFF || settings.glassFrost != GlassFrost.OFF)) {
            Box(Modifier.padding(top = 14.dp)) {
                OptionGroup(stringResource(R.string.glass_area)) {
                    ChipRow(GlassArea.entries, settings.glassArea) { onEffect(settings.copy(glassArea = it)) }
                    // The fallback only concerns a blur that follows the wave; frost is drawn, so it always can.
                    val fallback = settings.glassBlur != GlassBlur.OFF
                    OptionBody(settings.glassArea) { area ->
                        val body = stringResource(area.body)
                        if (area == GlassArea.SCREEN || !fallback) body else body + " " + stringResource(R.string.glass_area_fallback)
                    }
                }
            }
        }
    }
}

/** What the glass options are set to, in one sentence, leaving out what is off or not offered. */
@Composable
private fun glassSummary(settings: GlowSettings): String {
    val blur = stringResource(settings.glassBlur.phrase).takeIf { !settings.arrival.onBlack && settings.glassBlur != GlassBlur.OFF }
    val frost = stringResource(settings.glassFrost.phrase).takeIf { settings.glassFrost != GlassFrost.OFF }
    val area = stringResource(settings.glassArea.phrase)
    return sentence(
        when {
            // On a black screen there is nothing to blur, and frost covers it all.
            settings.arrival.onBlack && frost != null -> stringResource(R.string.glass_summary_black, frost)
            blur != null && frost != null -> stringResource(R.string.glass_summary_both, blur, frost, area)
            blur != null || frost != null -> stringResource(R.string.glass_summary_one, blur ?: frost.orEmpty(), area)
            else -> stringResource(R.string.glass_summary_none)
        },
    )
}

/** A summary built from phrases, starting with a capital as a sentence does. */
private fun sentence(text: String): String = text.replaceFirstChar { it.titlecase() }

/**
 * The three styles as rows: a mini phone showing the style in [accent], and its name. A new
 * pick crossfades rather than slides, since rows can differ in height at large font sizes.
 */
@Composable
private fun StylePicker(settings: GlowSettings, accent: Color, onSelect: (GlowStyle) -> Unit) {
    val corner = with(LocalDensity.current) { PickerPhoneCorner.toPx() }
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlowStyle.entries.forEach { style ->
            val selected = settings.style == style
            val on = animateFloatAsState(
                targetValue = if (selected) 1f else 0f,
                animationSpec = GlowMotion.stateChange(),
                label = "style-tile",
            )
            val title = animateColorAsState(
                targetValue = if (selected) GlowPalette.TextPrimary else GlowPalette.TextMuted,
                animationSpec = GlowMotion.stateChange(),
                label = "style-title",
            )
            // Recomposes the glyph while it turns; only on a pick, and only on two tiles.
            val glyph by animateColorAsState(
                targetValue = if (selected) accent else GlowPalette.TextFaint,
                animationSpec = GlowMotion.stateChange(),
                label = "style-glyph",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(GlowShapes.Tile)
                    .selectionSurface(GlowShapes.Tile, on, selectedStroke = 1.5.dp)
                    .selectable(selected = selected, role = Role.RadioButton) {
                        if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(style)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhoneMock(
                    modifier = Modifier.height(PickerPhoneHeight).aspectRatio(0.55f),
                    shape = RoundedCornerShape(PickerPhoneCorner),
                ) { mockGeometry ->
                    GlowGraphic(
                        style = if (style == GlowStyle.CUSTOM_DOT) settings.ledStyle else style,
                        color = glyph,
                        alpha = { 1f },
                        dotX = settings.dotX,
                        dotY = settings.dotY,
                        metrics = GlowMetrics.Tile,
                        geometry = mockGeometry.copy(cornerRadiusPx = corner),
                        dotRadius = previewDotRadius(settings.dotSize, TILE_PREVIEW_SCALE),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(12.dp))
                BasicText(
                    text = stringResource(style.label),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = { title.value },
                    maxLines = 2,
                )
            }
        }
    }
}

private val SWATCH_GAP = 4.dp
private val SWATCH_SIZE = 28.dp
private val SWATCH_RING = 1.5.dp

/** Clear space between a swatch's colour and the ring around it. */
private val SWATCH_RING_GAP = 2.25.dp

/**
 * One swatch per sample colour, equal widths. One ring slides to the chosen one, between the
 * swatches' measured centres: weighted widths are whole pixels with the remainder spread over
 * some of them, so centres worked out from the row width drift off by a pixel or more.
 */
@Composable
private fun SampleColorRow(selected: Int, onSelect: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    // Starts in place; a new pick slides there, and a quick re-pick turns it mid-way.
    val slot = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) { slot.animateTo(selected.toFloat(), GlowMotion.Slide) }
    // Each swatch's centre in the row, from its placement; read in draw.
    val centers = remember { mutableStateListOf(*Array(SAMPLE_COLORS.size) { Offset.Unspecified }) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP),
        ) {
            SAMPLE_COLORS.forEachIndexed { index, sample ->
                val isSelected = index == selected
                val name = stringResource(sample.name)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(GlowShapes.Pill)
                        .selectable(selected = isSelected, role = Role.RadioButton) {
                            if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(index)
                        }
                        .semantics { contentDescription = name }
                        .onPlaced { centers[index] = it.boundsInParent().center },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(SWATCH_SIZE)) {
                        drawCircle(sample.color, radius = size.minDimension / 2f - (SWATCH_RING + SWATCH_RING_GAP).toPx())
                    }
                }
            }
        }
        // The ring, over the swatches: its position is read in draw.
        Spacer(
            Modifier
                .matchParentSize()
                .drawWithCache {
                    val stroke = Stroke(SWATCH_RING.toPx())
                    val radius = SWATCH_SIZE.toPx() / 2f - stroke.width / 2f
                    onDrawBehind {
                        // Between the two swatches either side of the slide (the row already mirrors in RTL).
                        val at = slot.value.coerceIn(0f, (centers.size - 1).toFloat())
                        val from = centers[at.toInt()]
                        val to = centers[ceil(at).toInt()]
                        if (from.isSpecified && to.isSpecified) {
                            val center = lerp(from, to, at - at.toInt())
                            drawCircle(GlowPalette.TextPrimary, radius = radius, center = center, style = stroke)
                        }
                    }
                },
        )
    }
}

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
 * draws no frames there. Without [loop] (Remove animations) it plays each change once.
 */
@Composable
private fun EffectPreview(settings: GlowSettings, color: Color, heldBy: PreviewPhase?, loop: Boolean) {
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
    LaunchedEffect(phase, parked, loop) {
        withContext(RealTimeMotion) {
            if (parked || phase == PreviewPhase.EFFECT) {
                if (ledBreathAt(led.value) * ledOut.value > 0f) {
                    ledOut.animateTo(0f, tween(PREVIEW_LED_OUT_MS, easing = FastOutLinearInEasing))
                }
                led.snapTo(0f)
                ledOut.snapTo(1f)
                return@withContext
            }
            when (phase) {
                PreviewPhase.EFFECT -> Unit
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
    val playing = phase == PreviewPhase.EFFECT && !parked
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
                ringGap = (1.5.dp * scale).coerceAtLeast(0.5.dp),
                modifier = Modifier.fillMaxSize(),
            )
        }
        PreviewSteps(ledActive = phase != PreviewPhase.EFFECT)
    }
}

/** "EFFECT → LED", the current step lit. */
@Composable
private fun PreviewSteps(ledActive: Boolean) {
    val effectTint by animateColorAsState(if (ledActive) GlowPalette.TextFaint else GlowPalette.Cyan, label = "step-effect")
    val ledTint by animateColorAsState(if (ledActive) GlowPalette.Cyan else GlowPalette.TextFaint, label = "step-led")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.preview_step_effect), style = MaterialTheme.typography.labelSmall, color = effectTint)
        Chevron(
            tint = GlowPalette.TextFaint,
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .size(7.dp)
                .graphicsLayer { rotationZ = -90f },
        )
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

/**
 * Where the effect plays, beside the preview that shows it: the options by name, and what the
 * chosen one does under them, so they don't have to be read as a list.
 */
@Composable
internal fun ScreenCard(
    settings: GlowSettings,
    sample: Int,
    previewHeld: PreviewPhase?,
    loop: Boolean,
    shieldOn: Boolean,
    onShield: () -> Unit,
    onSelect: (ArrivalMode) -> Unit,
) {
    val selected = settings.arrival
    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(stringResource(R.string.arrival_mode), GlowPalette.Cyan)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EffectPreview(settings = settings, color = SAMPLE_COLORS[sample].color, heldBy = previewHeld, loop = loop)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ArrivalMode.entries.forEach { mode ->
                    RadioRow(
                        title = stringResource(mode.label),
                        body = null,
                        selected = selected == mode,
                        onClick = { onSelect(mode) },
                    )
                }
            }
        }
        // Each carries its own gap, so the card doesn't jump as the notice comes and goes.
        Column {
            OptionBody(selected) { mode -> stringResource(mode.body) }
            // Without the shield, Lock screen only lights the screen: say so where it is chosen.
            Disclosure(visible = selected == ArrivalMode.LOCK_SCREEN && !shieldOn) {
                Box(Modifier.padding(top = 12.dp)) {
                    NoticeRow(
                        text = stringResource(R.string.arrival_needs_shield),
                        action = stringResource(R.string.access_shield_grant),
                        onAction = onShield,
                    )
                }
            }
        }
    }
}
