package com.example.ambientglow.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ambientglow.R
import com.example.ambientglow.ui.theme.GlowMotion
import com.example.ambientglow.ui.theme.GlowPalette
import com.example.ambientglow.ui.theme.GlowShapes

// ---------------------------------------------------------------------------------------------
// Dashboard building blocks: headings, cards, rows, folds, buttons, steppers, sliders.
// ---------------------------------------------------------------------------------------------

/** A numbered heading with one line of context, then its cards. */
@Composable
internal fun SettingsGroup(
    index: String,
    @StringRes title: Int,
    @StringRes body: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row {
            Text(
                text = index,
                style = MaterialTheme.typography.labelMedium,
                color = GlowPalette.TextFaint,
                modifier = Modifier.padding(top = 3.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    color = GlowPalette.TextPrimary,
                )
                Text(
                    text = stringResource(body),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlowPalette.TextMuted,
                )
            }
        }
        content()
    }
}

/** [vertical] is less for a card that is one row (a fold), so it sits as tall as a row. */
internal fun Modifier.glowCard(vertical: Dp = 20.dp): Modifier = this
    .fillMaxWidth()
    .clip(GlowShapes.Card)
    .background(GlowPalette.Surface)
    .border(1.dp, GlowPalette.OutlineSoft, GlowShapes.Card)
    .padding(horizontal = 20.dp, vertical = vertical)

/** How far a tappable row's highlight reaches past its text, into the card's padding. */
private val RowBleed = 12.dp

/**
 * Widens a full-width row by [RowBleed] each side while its content stays in line with the
 * card's: the tile's rounded corners, and the press highlight, then sit clear of the text.
 * Applied before the row's clip; the row pads its content back in by [RowBleed].
 */
private fun Modifier.rowBleed(): Modifier = layout { measurable, constraints ->
    val bleed = RowBleed.roundToPx()
    val wide = constraints.copy(
        minWidth = constraints.minWidth + bleed * 2,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + bleed * 2 else constraints.maxWidth,
    )
    val placeable = measurable.measure(wide)
    layout(placeable.width - bleed * 2, placeable.height) { placeable.place(-bleed, 0) }
}

@Composable
internal fun CardDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(1.dp)
            .background(GlowPalette.OutlineSoft),
    )
}

/** A line of explanation where options are left out, in the options' own small type. */
@Composable
internal fun OptionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = GlowPalette.TextFaint,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A small caps label with its control right under it. */
@Composable
internal fun OptionGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        content()
    }
}

/** The whole row toggles; the switch only shows the state. */
@Composable
internal fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .rowBleed()
            .clip(GlowShapes.Tile)
            .toggleable(value = checked, role = Role.Switch) {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onChange(it)
            }
            .padding(horizontal = RowBleed, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = GlowPalette.TextPrimary,
            )
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = GlowPalette.TextMuted)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GlowPalette.Void,
                checkedTrackColor = GlowPalette.Cyan,
                checkedBorderColor = GlowPalette.Cyan,
                uncheckedThumbColor = GlowPalette.TextMuted,
                uncheckedTrackColor = GlowPalette.SurfaceHigh,
                uncheckedBorderColor = GlowPalette.Outline,
            ),
        )
    }
}

/** Half the height of a capital in the app's sans-serif (Roboto's is 0.71 em). */
private const val CAP_MIDDLE_EM = 0.355f

/**
 * One choice of a list, with a line saying what it does (or none, when that is said below the
 * list). One eased value turns the fill, ring, dot and title together; it is read in draw, so a
 * pick doesn't recompose the row. The ring sits on the title's first line, centred on its
 * capitals: centred on the line box it reads low, since that box keeps room for descenders.
 */
@Composable
internal fun RadioRow(title: String, body: String?, selected: Boolean, onClick: () -> Unit) {
    val on = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = GlowMotion.stateChange(),
        label = "radio",
    )
    val haptics = LocalHapticFeedback.current
    val titleStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
    // From the ring's centre down to the title's baseline: the capitals' middle sits that far up.
    val capMiddle = with(LocalDensity.current) { (titleStyle.fontSize * CAP_MIDDLE_EM).roundToPx() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlowShapes.Tile)
            .drawBehind { if (on.value > 0f) drawRect(GlowPalette.SurfaceRaised, alpha = on.value) }
            .selectable(selected = selected, role = Role.RadioButton) {
                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Spacer(
            Modifier
                .alignBy { it.measuredHeight / 2 + capMiddle }
                .size(16.dp)
                .drawWithCache {
                    val stroke = Stroke(1.5.dp.toPx())
                    val ringRadius = size.minDimension / 2f - stroke.width / 2f
                    val dotRadius = size.minDimension / 4f
                    onDrawBehind {
                        val t = on.value
                        drawCircle(lerp(GlowPalette.Outline, GlowPalette.Cyan, t), radius = ringRadius, style = stroke)
                        if (t > 0f) drawCircle(GlowPalette.Cyan, radius = dotRadius * t)
                    }
                },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.alignBy(FirstBaseline), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                text = title,
                style = titleStyle,
                color = { lerp(GlowPalette.TextMuted, GlowPalette.TextPrimary, on.value) },
            )
            if (body != null) Text(text = body, style = MaterialTheme.typography.bodySmall, color = GlowPalette.TextMuted)
        }
    }
}

/** An amber heads-up with one fix-it action. */
@Composable
internal fun NoticeRow(text: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlowShapes.Tile)
            .background(GlowPalette.Amber.copy(alpha = 0.08f))
            .border(1.dp, GlowPalette.Amber.copy(alpha = 0.4f), GlowShapes.Tile)
            .clickable(role = Role.Button, onClick = onAction)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = action.uppercase(), style = MaterialTheme.typography.labelMedium, color = GlowPalette.Amber)
    }
}

@Composable
internal fun Chevron(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        drawLine(tint, Offset(0f, size.height * 0.3f), Offset(size.width / 2f, size.height * 0.75f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(size.width / 2f, size.height * 0.75f), Offset(size.width, size.height * 0.3f), stroke, StrokeCap.Round)
    }
}

@Composable
internal fun SectionLabel(text: String, color: Color = GlowPalette.TextFaint) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
}

/**
 * A status in [tint]. A new one turns the colour (read in draw), swaps the text in place and
 * eases the pill to its new width.
 */
@Composable
internal fun StatusPill(
    text: String,
    tint: Color,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val color = animateColorAsState(
        targetValue = tint,
        animationSpec = GlowMotion.stateChange(),
        label = "status",
    )
    Row(
        modifier = Modifier
            .clip(GlowShapes.Pill)
            .drawWithCache {
                val s = 1.dp.toPx()
                val fill = GlowShapes.Pill.createOutline(size, layoutDirection, this)
                val edge = GlowShapes.Pill.createOutline(Size(size.width - s, size.height - s), layoutDirection, this)
                val stroke = Stroke(s)
                onDrawBehind {
                    drawOutline(fill, color.value, alpha = 0.10f)
                    translate(s / 2f, s / 2f) { drawOutline(edge, color.value, alpha = 0.55f, style = stroke) }
                }
            }
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(6.dp)) { drawCircle(color.value) }
        Spacer(Modifier.width(7.dp))
        AnimatedContent(
            targetState = text,
            transitionSpec = { GlowMotion.swap() },
            contentAlignment = Alignment.CenterStart,
            label = "status-text",
        ) { shown ->
            BasicText(text = shown, style = MaterialTheme.typography.labelSmall, color = { color.value })
        }
        trailing()
    }
}

@Composable
internal fun GhostButton(text: String, emphasized: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (emphasized) GlowPalette.Cyan else GlowPalette.TextMuted
    Box(
        modifier = modifier
            .clip(GlowShapes.GhostButton)
            .background(if (emphasized) GlowPalette.Cyan.copy(alpha = 0.08f) else GlowPalette.SurfaceRaised)
            .border(1.dp, tint.copy(alpha = if (emphasized) 0.7f else 0.25f), GlowShapes.GhostButton)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text.uppercase(), style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

/** Content that unfolds in place below what is already there, carrying its own gap (padding) so nothing jumps. */
@Composable
internal fun Disclosure(visible: Boolean, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = GlowMotion.DisclosureEnter,
        exit = GlowMotion.DisclosureExit,
        content = content,
    )
}

/**
 * Options folded away under a titled row until wanted; [summary] says in a sentence what they are
 * set to, so the closed row still reads. Unfolds like a [Disclosure], carrying its own gap, and remembers
 * being open while its page is away. Not [enabled], it shows shut and can't be opened; enabled
 * again, it is as it was left.
 */
@Composable
internal fun Fold(title: String, summary: String, enabled: Boolean = true, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, GlowMotion.chevronTurn(open), label = "fold")
    val state = stringResource(if (open) R.string.fold_open else R.string.fold_closed)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .rowBleed()
                .clip(GlowShapes.Tile)
                .clickable(enabled = enabled, role = Role.Button) { open = !open }
                .semantics { stateDescription = state }
                .padding(horizontal = RowBleed, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = GlowPalette.TextPrimary,
                )
                AnimatedContent(
                    targetState = summary,
                    transitionSpec = { GlowMotion.swap() },
                    contentAlignment = Alignment.TopStart,
                    label = "fold-summary",
                ) { shown ->
                    Text(text = shown, style = MaterialTheme.typography.bodySmall, color = GlowPalette.TextMuted)
                }
            }
            Spacer(Modifier.width(16.dp))
            Chevron(tint = GlowPalette.TextMuted, modifier = Modifier.size(10.dp).graphicsLayer { rotationZ = turn })
        }
        Disclosure(visible = open && enabled) {
            Box(Modifier.padding(top = 14.dp)) { content() }
        }
    }
}

/**
 * What the chip picked above does: only the selected option's text, crossfaded on a new pick
 * while the space eases to the new height, so the options don't have to be read as a list.
 */
@Composable
internal fun <T> OptionBody(selected: T, text: @Composable (T) -> String) {
    AnimatedContent(
        targetState = selected,
        transitionSpec = { GlowMotion.swap() },
        contentAlignment = Alignment.TopStart,
        label = "option-body",
    ) { option ->
        Text(
            text = text(option),
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextFaint,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val STEP_REPEAT_DELAY_MS = 400L
private const val STEP_REPEAT_MS = 60L

/** A − or + step; holding it repeats. It dips while pressed and ticks on every step. */
@Composable
internal fun StepButton(plus: Boolean, description: String, onStep: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val onStepNow by rememberUpdatedState(onStep)
    val step = {
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        onStepNow()
    }
    // The raw gesture below consumes the press, so it reports it here for the dip and ripple.
    val interaction = remember { MutableInteractionSource() }
    val down by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (down) 0.94f else 1f,
        animationSpec = if (down) tween(90, easing = FastOutSlowInEasing) else spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "step-press",
    )
    val edge by animateColorAsState(
        targetValue = if (down) GlowPalette.Cyan.copy(alpha = 0.6f) else GlowPalette.OutlineSoft,
        animationSpec = GlowMotion.stateChange(),
        label = "step-edge",
    )
    Box(
        modifier = modifier
            .size(36.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(GlowShapes.Pill)
            .background(GlowPalette.SurfaceRaised)
            .border(1.dp, edge, GlowShapes.Pill)
            .indication(interaction, ripple())
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick {
                    step()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown().also { it.consume() }
                    val press = PressInteraction.Press(first.position)
                    interaction.tryEmit(press)
                    var ended = false
                    try {
                        step()
                        var wait = STEP_REPEAT_DELAY_MS
                        while (true) {
                            // Null only on timeout: still held, so step again, faster.
                            val up = withTimeoutOrNull(wait) { waitForUpOrCancellation() != null }
                            if (up == null) {
                                step()
                                wait = STEP_REPEAT_MS
                                continue
                            }
                            interaction.tryEmit(if (up) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                            ended = true
                            break
                        }
                    } finally {
                        if (!ended) interaction.tryEmit(PressInteraction.Cancel(press))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val line = 1.5.dp.toPx()
            drawLine(GlowPalette.TextPrimary, Offset(0f, center.y), Offset(size.width, center.y), line, StrokeCap.Round)
            if (plus) drawLine(GlowPalette.TextPrimary, Offset(center.x, 0f), Offset(center.x, size.height), line, StrokeCap.Round)
        }
    }
}

@Composable
internal fun Readout(text: String) {
    Box(
        modifier = Modifier
            .clip(GlowShapes.Pill)
            .background(GlowPalette.SurfaceRaised)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = GlowPalette.TextPrimary)
    }
}

@Composable
internal fun glowSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = GlowPalette.Cyan,
    activeTrackColor = GlowPalette.Cyan,
    activeTickColor = GlowPalette.Void,
    inactiveTrackColor = GlowPalette.SurfaceHigh,
    inactiveTickColor = GlowPalette.Outline,
)
