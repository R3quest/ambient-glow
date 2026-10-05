package com.example.ambientglow.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.example.ambientglow.DOT_HALO_FACTOR
import com.example.ambientglow.DotSize
import com.example.ambientglow.GlowGraphic
import com.example.ambientglow.GlowMetrics
import com.example.ambientglow.GlowSettings
import com.example.ambientglow.LedBrightness
import com.example.ambientglow.R
import com.example.ambientglow.dotCenter
import com.example.ambientglow.dotFraction
import com.example.ambientglow.ui.components.BladeChip
import com.example.ambientglow.ui.components.CardDivider
import com.example.ambientglow.ui.components.ChipLabel
import com.example.ambientglow.ui.components.Disclosure
import com.example.ambientglow.ui.components.Fold
import com.example.ambientglow.ui.components.OptionGroup
import com.example.ambientglow.ui.components.Readout
import com.example.ambientglow.ui.components.SectionLabel
import com.example.ambientglow.ui.components.SelectionRow
import com.example.ambientglow.ui.components.StepButton
import com.example.ambientglow.ui.components.glowCard
import com.example.ambientglow.ui.components.glowSliderColors
import com.example.ambientglow.ui.theme.GlowPalette
import com.example.ambientglow.ui.theme.GlowShapes
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// The LED tab: where it sits (drag, spots, then folded: the camera fit, sliders and steps), and
// its size and brightness.
// ---------------------------------------------------------------------------------------------

private val PanelHeight: Dp = 280.dp

private const val SNAP_PRESET = 0.03f
private const val SNAP_RULER = 0.012f
private val RULER_MAJORS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
private const val RULER_STEPS = 20

/** A dot position after magnetic snapping. [target] identifies what it snapped to, for haptics. */
internal data class Snapped(val x: Float, val y: Float, val target: Any?)

internal fun snapDot(x: Float, y: Float, spots: List<DotSpot>): Snapped {
    spots
        .firstOrNull { abs(it.x - x) < SNAP_PRESET && abs(it.y - y) < SNAP_PRESET }
        ?.let { return Snapped(it.x, it.y, it) }
    val sx = RULER_MAJORS.firstOrNull { abs(it - x) < SNAP_RULER }
    val sy = RULER_MAJORS.firstOrNull { abs(it - y) < SNAP_RULER }
    val target = if (sx != null || sy != null) sx to sy else null
    return Snapped(sx ?: x, sy ?: y, target)
}

/**
 * A one-tap LED position; [onCameraLine] spots sit at the exact height of the lens centre.
 * The [camera] spot is the lens itself: the LED there lights as a ring around it.
 */
@Immutable
internal data class DotSpot(
    @param:StringRes val label: Int,
    val x: Float,
    val y: Float,
    val onCameraLine: Boolean,
    val camera: Boolean = false,
) {
    fun matches(x: Float, y: Float) = abs(this.x - x) < 0.001f && abs(this.y - y) < 0.001f
}

private val SPOT_EDGE = 18.dp
private val SPOT_CAMERA_GAP = 10.dp

/**
 * LED spots built from this phone's real camera: the lens itself (the ring), four on the
 * camera's horizontal line (screen edges and either side of the lens), one straight below it,
 * plus the classic corners and bottom. Positions are converted with the same margin the full-screen LED uses, so a spot
 * picked here lands exactly there.
 */
@Composable
private fun rememberDotSpots(dotSize: DotSize): List<DotSpot> {
    val camera = LocalCamera.current
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(camera, window, dotSize, density) {
        with(density) {
            val w = window.width.toFloat().coerceAtLeast(1f)
            val h = window.height.toFloat().coerceAtLeast(1f)
            val dot = dotSize.radius.toPx()
            val margin = dot * DOT_HALO_FACTOR
            // Screen px to the 0..1 fractions the LED is stored in, as a drag on the mock-up converts them.
            val screen = Size(w, h)
            fun fx(px: Float) = dotFraction(Offset(px, 0f), screen, margin).x
            fun fy(py: Float) = dotFraction(Offset(0f, py), screen, margin).y

            val camX = camera.x * w
            val camY = camera.y * h
            val clear = camera.radius * w + SPOT_CAMERA_GAP.toPx() + dot
            val edge = SPOT_EDGE.toPx()
            val line = fy(camY)
            listOf(
                DotSpot(R.string.dot_spot_ring, fx(camX), line, onCameraLine = false, camera = true),
                DotSpot(R.string.dot_spot_edge_left, fx(edge), line, onCameraLine = true),
                DotSpot(R.string.dot_spot_cam_left, fx(camX - clear), line, onCameraLine = true),
                DotSpot(R.string.dot_spot_cam_right, fx(camX + clear), line, onCameraLine = true),
                DotSpot(R.string.dot_spot_edge_right, fx(w - edge), line, onCameraLine = true),
                DotSpot(R.string.dot_spot_below_cam, fx(camX), fy(camY + clear), onCameraLine = false),
                DotSpot(R.string.dot_spot_corner_left, 0.06f, 0.008f, onCameraLine = false),
                DotSpot(R.string.dot_spot_corner_right, 0.94f, 0.008f, onCameraLine = false),
                DotSpot(R.string.dot_spot_bottom, 0.5f, 0.992f, onCameraLine = false),
            )
        }
    }
}

@Composable
internal fun LedCard(
    settings: GlowSettings,
    onMove: (x: Float, y: Float, onCamera: Boolean) -> Unit,
    onCommit: () -> Unit,
    onLensFit: (fit: (GlowSettings) -> GlowSettings) -> Unit,
    onLensFitDone: () -> Unit,
) {
    val dotX = settings.dotX
    val dotY = settings.dotY
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var lastSnap by remember { mutableStateOf<Any?>(null) }
    val spots = rememberDotSpots(settings.dotSize)

    // Every input path (drag, tap, both sliders) goes through the same magnetic snap. Snapping
    // onto the lens turns the LED into the ring; moving off it makes it a dot again.
    val move: (Float, Float) -> Unit = { x, y ->
        val snapped = snapDot(x, y, spots)
        if (snapped.target != null && snapped.target != lastSnap) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        lastSnap = snapped.target
        onMove(snapped.x, snapped.y, (snapped.target as? DotSpot)?.camera == true)
    }

    // The − / + steps: 1 dp per tap, past the snap so small steps are not pulled back. The LED
    // stays lit while tapping and settles once the taps stop.
    val window = LocalWindowInfo.current.containerSize
    val margin = with(density) { settings.dotSize.radius.toPx() } * DOT_HALO_FACTOR
    val stepX = density.density / (window.width - margin * 2f).coerceAtLeast(1f)
    val stepY = density.density / (window.height - margin * 2f).coerceAtLeast(1f)
    val settleSteps = rememberSettle(STEP_SETTLE_MS, onCommit)
    val step: (Float, Float) -> Unit = { dx, dy ->
        lastSnap = null
        onMove((dotX + dx).coerceIn(0f, 1f), (dotY + dy).coerceIn(0f, 1f), false)
        settleSteps()
    }

    // Scale the real dot into the mock-up so LED vs L reads honestly.
    val screenHeightDp = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val previewScale = (PanelHeight / screenHeightDp.coerceAtLeast(PanelHeight)).coerceIn(0.1f, 1f)
    val previewRadius = previewDotRadius(settings.dotSize, previewScale)

    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(stringResource(R.string.section_dot), GlowPalette.Cyan)

        // Dragging or tapping the mock-up is the main way; the readouts and how-to sit beside it.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val previewLabel = stringResource(R.string.dot_preview_label)
            PhoneMock(
                modifier = Modifier
                    .height(PanelHeight)
                    .aspectRatio(0.48f)
                    .semantics { contentDescription = previewLabel },
            ) { mockGeometry ->
                DotRuler(
                    dotX = dotX,
                    dotY = dotY,
                    dotRadius = previewRadius,
                    spots = spots,
                    modifier = Modifier.fillMaxSize(),
                )
                GlowGraphic(
                    style = settings.ledStyle,
                    color = GlowPalette.Cyan,
                    alpha = { 1f },
                    dotX = dotX,
                    dotY = dotY,
                    metrics = GlowMetrics.Panel,
                    geometry = mockGeometry,
                    dotRadius = previewRadius,
                    modifier = Modifier.fillMaxSize(),
                )
                val margin = with(density) { previewRadius.toPx() * DOT_HALO_FACTOR }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(margin) {
                            detectTapGestures { position ->
                                val f = dotFraction(position, size.toSize(), margin)
                                move(f.x, f.y)
                                onCommit()
                            }
                        }
                        .pointerInput(margin) {
                            detectDragGestures(onDragEnd = onCommit, onDragCancel = onCommit) { change, _ ->
                                change.consume()
                                val f = dotFraction(change.position, size.toSize(), margin)
                                move(f.x, f.y)
                            }
                        },
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Readout(stringResource(R.string.dot_readout_x, (dotX * 100).roundToInt()))
                Readout(stringResource(R.string.dot_readout_y, (dotY * 100).roundToInt()))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.dot_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlowPalette.TextMuted,
                )
            }
        }

        val pick: (DotSpot) -> Unit = { spot ->
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastSnap = spot
            onMove(spot.x, spot.y, spot.camera)
            onCommit()
        }
        // The camera fit unfolds under the lens chip, carrying its own gap; no camera, no gap.
        spots.firstOrNull { it.camera }?.let { ring ->
            Column {
                OptionGroup(stringResource(R.string.dot_spots_lens)) {
                    BladeChip(
                        label = stringResource(ring.label),
                        selected = settings.ledOnCamera,
                        onClick = { pick(ring) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Canvas(Modifier.size(14.dp)) {
                            val line = 2.dp.toPx()
                            drawCircle(GlowPalette.SurfaceHighest, radius = size.minDimension / 2f - line * 1.5f)
                            drawCircle(GlowPalette.Cyan, radius = size.minDimension / 2f - line / 2f, style = Stroke(line))
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                }
                Disclosure(visible = settings.ledOnCamera) {
                    val adjusted = settings.lensOffsetXDp != 0f || settings.lensOffsetDp != 0f || settings.lensGrowDp != 0f
                    Box(Modifier.padding(top = 10.dp)) {
                        Fold(
                            title = stringResource(R.string.fold_lens),
                            summary = stringResource(if (adjusted) R.string.fold_lens_adjusted else R.string.fold_lens_reported),
                        ) {
                            LensFit(settings, onLensFit, onLensFitDone)
                        }
                    }
                }
            }
        }
        OptionGroup(stringResource(R.string.dot_spots_camera_line)) {
            SpotRow(spots.filter { it.onCameraLine }, dotX, dotY, pick)
        }
        OptionGroup(stringResource(R.string.dot_spots_other)) {
            SpotRow(spots.filterNot { it.onCameraLine || it.camera }, dotX, dotY, pick)
        }

        CardDivider()
        Fold(title = stringResource(R.string.fold_precise), summary = stringResource(R.string.fold_precise_summary)) {
            val sliderColors = glowSliderColors()
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AxisRow(
                    label = stringResource(R.string.dot_horizontal),
                    value = dotX,
                    onValue = { move(it, dotY) },
                    onDone = onCommit,
                    minus = stringResource(R.string.dot_step_left),
                    plus = stringResource(R.string.dot_step_right),
                    onStep = { step(it * stepX, 0f) },
                    colors = sliderColors,
                )
                // Left to right is top to bottom, as the Y readout counts.
                AxisRow(
                    label = stringResource(R.string.dot_vertical),
                    value = dotY,
                    onValue = { move(dotX, it) },
                    onDone = onCommit,
                    minus = stringResource(R.string.dot_step_up),
                    plus = stringResource(R.string.dot_step_down),
                    onStep = { step(0f, it * stepY) },
                    colors = sliderColors,
                )
            }
        }
    }
}

/** How the LED looks wherever it sits: its size (on the camera, the ring's thickness) and brightness. */
@Composable
internal fun LedLookCard(settings: GlowSettings, onSize: (DotSize) -> Unit, onBrightness: (LedBrightness) -> Unit) {
    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(stringResource(R.string.section_led_look), GlowPalette.Cyan)
        OptionGroup(stringResource(if (settings.ledOnCamera) R.string.dot_ring_thickness else R.string.dot_size)) {
            SelectionRow(
                count = DotSize.entries.size,
                selected = settings.dotSize.ordinal,
                onSelect = { onSize(DotSize.entries[it]) },
            ) { index, lit ->
                val size = DotSize.entries[index]
                Canvas(Modifier.size(size.radius * 2)) { drawCircle(GlowPalette.Cyan) }
                Spacer(Modifier.width(8.dp))
                ChipLabel(stringResource(size.label), lit)
            }
        }
        OptionGroup(stringResource(R.string.led_brightness)) {
            SelectionRow(
                count = LedBrightness.entries.size,
                selected = settings.ledBrightness.ordinal,
                onSelect = { onBrightness(LedBrightness.entries[it]) },
            ) { index, lit ->
                val level = LedBrightness.entries[index]
                Canvas(Modifier.size(8.dp)) { drawCircle(GlowPalette.Cyan.copy(alpha = level.level)) }
                Spacer(Modifier.width(8.dp))
                ChipLabel(stringResource(level.label), lit)
            }
        }
    }
}

/** One axis of the precise position: a slider between its − / + steps. [onStep] gets −1 or +1. */
@Composable
private fun AxisRow(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
    onDone: () -> Unit,
    minus: String,
    plus: String,
    onStep: (Float) -> Unit,
    colors: SliderColors,
) {
    OptionGroup(label) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepButton(plus = false, description = minus, onStep = { onStep(-1f) })
            Slider(
                value = value,
                onValueChange = onValue,
                onValueChangeFinished = onDone,
                colors = colors,
                modifier = Modifier.weight(1f).semantics { contentDescription = label },
            )
            StepButton(plus = true, description = plus, onStep = { onStep(1f) })
        }
    }
}

/** After the last − / + tap, how long the LED stays lit before it is saved and breathes out. */
private const val STEP_SETTLE_MS = 1_200L

/**
 * For a run of − / + taps: the returned poke restarts a [delayMs] wait, and [onSettle] runs once
 * it ends. If the card leaves first (back, leaving the app), it runs at once, so no tap is lost.
 */
@Composable
private fun rememberSettle(delayMs: Long, onSettle: () -> Unit): () -> Unit {
    val settle by rememberUpdatedState(onSettle)
    var pokes by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(pokes) {
        if (!pending) return@LaunchedEffect
        delay(delayMs)
        pending = false
        settle()
    }
    DisposableEffect(Unit) {
        onDispose { if (pending) settle() }
    }
    return remember {
        {
            pending = true
            pokes++
        }
    }
}

/** How far the camera fit can go either way, in physical pixels. */
private const val LENS_FIT_MAX_PX = 40

/**
 * Lines the ring up with the real lens, one physical pixel per tap: the cutout some phones
 * report is only a rectangle from the top edge. The ring stays lit on screen while tapping.
 */
@Composable
private fun LensFit(settings: GlowSettings, onFit: (fit: (GlowSettings) -> GlowSettings) -> Unit, onDone: () -> Unit) {
    val density = LocalDensity.current.density
    fun px(dp: Float) = (dp * density).roundToInt()
    fun dp(px: Int) = px.coerceIn(-LENS_FIT_MAX_PX, LENS_FIT_MAX_PX) / density
    val offsetXPx = px(settings.lensOffsetXDp)
    val offsetPx = px(settings.lensOffsetDp)
    val growPx = px(settings.lensGrowDp)
    val settleSteps = rememberSettle(STEP_SETTLE_MS, onDone)
    val fit: (Int, Int, Int) -> Unit = { x, y, grow ->
        onFit { it.copy(lensOffsetXDp = dp(x), lensOffsetDp = dp(y), lensGrowDp = dp(grow)) }
        settleSteps()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlowShapes.Tile)
            .background(GlowPalette.SurfaceRaised)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = stringResource(R.string.lens_fit_body),
                style = MaterialTheme.typography.bodySmall,
                color = GlowPalette.TextMuted,
                modifier = Modifier.weight(1f),
            )
            if (offsetXPx != 0 || offsetPx != 0 || growPx != 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.lens_fit_reset),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlowPalette.Cyan,
                    modifier = Modifier
                        .clip(GlowShapes.Pill)
                        .clickable(role = Role.Button) { fit(0, 0, 0) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        FitRow(
            label = stringResource(R.string.lens_fit_height),
            valuePx = offsetPx,
            minus = stringResource(R.string.lens_fit_up),
            plus = stringResource(R.string.lens_fit_down),
            onStep = { fit(offsetXPx, offsetPx + it, growPx) },
        )
        FitRow(
            label = stringResource(R.string.lens_fit_side),
            valuePx = offsetXPx,
            minus = stringResource(R.string.lens_fit_left),
            plus = stringResource(R.string.lens_fit_right),
            onStep = { fit(offsetXPx + it, offsetPx, growPx) },
        )
        FitRow(
            label = stringResource(R.string.lens_fit_radius),
            valuePx = growPx,
            minus = stringResource(R.string.lens_fit_smaller),
            plus = stringResource(R.string.lens_fit_bigger),
            onStep = { fit(offsetXPx, offsetPx, growPx + it) },
        )
    }
}

@Composable
private fun FitRow(label: String, valuePx: Int, minus: String, plus: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = GlowPalette.TextMuted,
            modifier = Modifier.weight(1f),
        )
        StepButton(plus = false, description = minus, onStep = { onStep(-1) })
        Text(
            text = stringResource(R.string.lens_fit_px, valuePx),
            style = MaterialTheme.typography.labelMedium,
            color = GlowPalette.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )
        StepButton(plus = true, description = plus, onStep = { onStep(1) })
    }
}

@Composable
private fun SpotRow(spots: List<DotSpot>, dotX: Float, dotY: Float, onPick: (DotSpot) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        spots.forEach { spot ->
            BladeChip(
                label = stringResource(spot.label),
                selected = spot.matches(dotX, dotY),
                onClick = { onPick(spot) },
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Graduated ruler for the dot mock-up: ticks every 5 % along the top and left edges, longer
 * marks at quarters, dashed crosshairs through the dot, the camera line, and a pin per spot.
 */
@Composable
private fun DotRuler(dotX: Float, dotY: Float, dotRadius: Dp, spots: List<DotSpot>, modifier: Modifier = Modifier) {
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) }
    Canvas(modifier) {
        val margin = dotRadius.toPx() * DOT_HALO_FACTOR
        val minor = 3.dp.toPx()
        val major = 7.dp.toPx()
        val hair = 1.dp.toPx()
        val spanX = size.width - margin * 2f
        val spanY = size.height - margin * 2f

        for (i in 0..RULER_STEPS) {
            val f = i / RULER_STEPS.toFloat()
            val isMajor = i % (RULER_STEPS / 4) == 0
            val length = if (isMajor) major else minor
            val color = if (isMajor) GlowPalette.TextFaint else GlowPalette.Outline
            val x = margin + f * spanX
            val y = margin + f * spanY
            drawLine(color, Offset(x, 0f), Offset(x, length), strokeWidth = hair)
            drawLine(color, Offset(0f, y), Offset(length, y), strokeWidth = hair)
        }

        val center = dotCenter(dotX, dotY, size, margin)
        val guide = GlowPalette.Cyan.copy(alpha = 0.22f)
        drawLine(guide, Offset(center.x, 0f), Offset(center.x, size.height), hair, pathEffect = dash)
        drawLine(guide, Offset(0f, center.y), Offset(size.width, center.y), hair, pathEffect = dash)
        drawLine(GlowPalette.Cyan, Offset(center.x, 0f), Offset(center.x, major), hair * 2f)
        drawLine(GlowPalette.Cyan, Offset(0f, center.y), Offset(major, center.y), hair * 2f)

        // The camera line: every spot on it shares the lens centre's height.
        spots.firstOrNull { it.onCameraLine }?.let { spot ->
            val y = dotCenter(0f, spot.y, size, margin).y
            drawLine(GlowPalette.Lime.copy(alpha = 0.28f), Offset(0f, y), Offset(size.width, y), hair, pathEffect = dash)
        }

        val pinRadius = 3.5.dp.toPx()
        spots.forEach { spot ->
            drawCircle(
                color = if (spot.matches(dotX, dotY)) GlowPalette.Lime else GlowPalette.TextFaint,
                radius = pinRadius,
                center = dotCenter(spot.x, spot.y, size, margin),
                style = Stroke(width = hair),
            )
        }
    }
}
