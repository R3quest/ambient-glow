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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.example.ambientglow.ui.theme.GlowMotion
import com.example.ambientglow.ui.theme.GlowPalette
import com.example.ambientglow.ui.theme.GlowShapes

// ---------------------------------------------------------------------------------------------
// Dashboard building blocks: headings, cards, rows, buttons, steppers, sliders.
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

internal fun Modifier.glowCard(): Modifier = this
    .fillMaxWidth()
    .clip(GlowShapes.Card)
    .background(GlowPalette.Surface)
    .border(1.dp, GlowPalette.OutlineSoft, GlowShapes.Card)
    .padding(20.dp)

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
            .clip(GlowShapes.Tile)
            .toggleable(value = checked, role = Role.Switch) {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onChange(it)
            }
            .padding(vertical = 6.dp),
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

/**
 * One choice of a list, with a line saying what it does. One eased value turns the fill, ring,
 * dot and title together; it is read in draw, so a pick doesn't recompose the row.
 */
@Composable
internal fun RadioRow(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val on = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = GlowMotion.stateChange(),
        label = "radio",
    )
    val haptics = LocalHapticFeedback.current
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
                .padding(top = 2.dp)
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
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = { lerp(GlowPalette.TextMuted, GlowPalette.TextPrimary, on.value) },
            )
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = GlowPalette.TextMuted)
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

/** Material Slider rotated 270° with its measured width and height swapped. */
@Composable
internal fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    colors: SliderColors,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        modifier = modifier
            .graphicsLayer {
                rotationZ = 270f
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth,
                    ),
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(-placeable.width, 0)
                }
            },
    )
}

@Composable
internal fun glowSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = GlowPalette.Cyan,
    activeTrackColor = GlowPalette.Cyan,
    activeTickColor = GlowPalette.Void,
    inactiveTrackColor = GlowPalette.SurfaceHigh,
    inactiveTickColor = GlowPalette.Outline,
)
