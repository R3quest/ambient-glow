package app.lumement.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lumement.Labeled
import app.lumement.ui.theme.GlowMotion
import app.lumement.ui.theme.GlowPalette
import app.lumement.ui.theme.GlowShapes
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Chips: equal-width rows with one sliding blade, and single chips that crossfade.
// ---------------------------------------------------------------------------------------------

/** One option per chip, equal widths. */
@Composable
internal fun <T : Labeled> ChipRow(options: List<T>, selected: T, onSelect: (T) -> Unit) {
    SelectionRow(
        count = options.size,
        selected = options.indexOf(selected),
        onSelect = { onSelect(options[it]) },
    ) { index, lit ->
        ChipLabel(stringResource(options[index].label), lit, compact = true)
    }
}

private val CHIP_HEIGHT = 40.dp
private val CHIP_GAP = 6.dp

/**
 * Equal-width chips with one blade that slides to the chosen one, as the tab bar's does. The
 * wells, blade and strokes are built once; a slide moves the blade in layout and tints the
 * labels in draw, so it doesn't recompose. [chip] draws a chip's content; [lit] says how much
 * of the blade is under it (1 when it rests there), read in draw, so a label lights as the
 * blade arrives rather than as the pick is made. Taller rows ([height]) hold tiles: an icon over
 * a label, with less [inset] so the label has the width. Not [enabled], no chip can be picked;
 * whoever holds the row dims it.
 */
@Composable
internal fun SelectionRow(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    height: Dp = CHIP_HEIGHT,
    inset: Dp = 8.dp,
    enabled: Boolean = true,
    chip: @Composable RowScope.(index: Int, lit: () -> Float) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // Starts in place; a new pick slides there, and a quick re-pick turns it mid-way.
    val slot = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) { slot.animateTo(selected.toFloat(), GlowMotion.Slide) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).selectableGroup()) {
        val slotWidth = (maxWidth - CHIP_GAP * (count - 1)) / count
        // The empty wells, inset like Modifier.border.
        Spacer(
            Modifier
                .matchParentSize()
                .drawWithCache {
                    val s = 1.dp.toPx()
                    val gap = CHIP_GAP.toPx()
                    val w = (size.width - gap * (count - 1)) / count
                    val h = size.height
                    val fill = GlowShapes.Pill.createOutline(Size(w, h), layoutDirection, this)
                    val edge = GlowShapes.Pill.createOutline(Size(w - s, h - s), layoutDirection, this)
                    val stroke = Stroke(s)
                    onDrawBehind {
                        repeat(count) { i ->
                            translate(left = i * (w + gap)) {
                                drawOutline(fill, GlowPalette.Void)
                                translate(s / 2f, s / 2f) { drawOutline(edge, GlowPalette.OutlineSoft, style = stroke) }
                            }
                        }
                    }
                },
        )
        Box(
            Modifier
                .width(slotWidth)
                .fillMaxHeight()
                .offset { IntOffset(((slotWidth + CHIP_GAP).toPx() * slot.value).roundToInt(), 0) }
                .clip(GlowShapes.Pill)
                .background(GlowPalette.SurfaceRaised)
                .border(1.dp, GlowPalette.Cyan, GlowShapes.Pill),
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
            repeat(count) { index ->
                val on = index == selected
                val lit = remember(slot, index) { { (1f - abs(slot.value - index)).coerceIn(0f, 1f) } }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(GlowShapes.Pill)
                        .selectable(selected = on, enabled = enabled, role = Role.RadioButton) {
                            if (!on) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(index)
                        }
                        .padding(horizontal = inset),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    chip(index, lit)
                }
            }
        }
    }
}

@Composable
private fun chipTextStyle(compact: Boolean): TextStyle = if (compact) {
    MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
} else {
    MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
}

/** A chip's label, tinted from muted to primary as [lit] goes 0 → 1; read in draw. */
@Composable
internal fun ChipLabel(text: String, lit: () -> Float, compact: Boolean = false) {
    BasicText(
        text = text,
        style = chipTextStyle(compact),
        color = { lerp(GlowPalette.TextMuted, GlowPalette.TextPrimary, lit()) },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A chip or tile's fill and edge, crossfading to the chosen look as [on] goes 0 → 1: the raised
 * fill and a [selectedStroke] cyan edge. Outlines and strokes are built once per size; [on]
 * is read in draw. For selections that can't slide (rows of unequal height, or no match).
 */
internal fun Modifier.selectionSurface(shape: Shape, on: State<Float>, selectedStroke: Dp): Modifier = drawWithCache {
    val thin = 1.dp.toPx()
    val thick = selectedStroke.toPx()
    val fill = shape.createOutline(size, layoutDirection, this)
    val soft = shape.createOutline(Size(size.width - thin, size.height - thin), layoutDirection, this)
    val chosen = shape.createOutline(Size(size.width - thick, size.height - thick), layoutDirection, this)
    val thinStroke = Stroke(thin)
    val thickStroke = Stroke(thick)
    onDrawBehind {
        val t = on.value
        drawOutline(fill, GlowPalette.Void)
        if (t > 0f) drawOutline(fill, GlowPalette.SurfaceRaised, alpha = t)
        if (t < 1f) {
            translate(thin / 2f, thin / 2f) { drawOutline(soft, GlowPalette.OutlineSoft, alpha = 1f - t, style = thinStroke) }
        }
        if (t > 0f) {
            translate(thick / 2f, thick / 2f) { drawOutline(chosen, GlowPalette.Cyan, alpha = t, style = thickStroke) }
        }
    }
}

/** A single chip that crossfades when chosen; equal-width rows use [SelectionRow] instead. */
@Composable
internal fun BladeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    leading: @Composable () -> Unit = {},
) {
    val on = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = GlowMotion.stateChange(),
        label = "chip",
    )
    Row(
        modifier = modifier
            .height(CHIP_HEIGHT)
            .clip(GlowShapes.Pill)
            .selectionSurface(GlowShapes.Pill, on, selectedStroke = 1.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        ChipLabel(label, { on.value }, compact)
    }
}
