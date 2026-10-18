package app.lumement.dashboard

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.lumement.DOT_HALO_FACTOR
import app.lumement.DotSize
import app.lumement.EdgeColor
import app.lumement.EdgeGlow
import app.lumement.EdgeWidth
import app.lumement.GlowForm
import app.lumement.GlowGraphic
import app.lumement.GlowMetrics
import app.lumement.GlowSettings
import app.lumement.GlowStyle
import app.lumement.LedDot
import app.lumement.LedForm
import app.lumement.R
import app.lumement.dotCenter
import app.lumement.dotFraction
import app.lumement.ui.components.BladeChip
import app.lumement.ui.components.CardDivider
import app.lumement.ui.components.ChipLabel
import app.lumement.ui.components.ChipRow
import app.lumement.ui.components.Disclosure
import app.lumement.ui.components.Fold
import app.lumement.ui.components.OptionBody
import app.lumement.ui.components.OptionGroup
import app.lumement.ui.components.Readout
import app.lumement.ui.components.SectionLabel
import app.lumement.ui.components.SelectionRow
import app.lumement.ui.components.StepButton
import app.lumement.ui.components.glowCard
import app.lumement.ui.components.glowSliderColors
import app.lumement.ui.theme.GlowMotion
import app.lumement.ui.theme.GlowPalette
import app.lumement.ui.theme.GlowShapes
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// The LED tab: its shape (a dot, a ring round the camera, or a frame round the screen's edge),
// where the dot sits (drag, spots, then folded: sliders and steps) and the ring's camera fit, and
// its look: the dot's or the ring's size, or the frame's colour, width and glow.
// ---------------------------------------------------------------------------------------------

private val PanelHeight: Dp = 280.dp
private val FormTileHeight: Dp = 60.dp

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
 * The [camera] spot is the lens itself: the LED there lights as a ring round it. [atLens] marks
 * the lens centre: the ring, or on a screen without a camera the dot that stands in for it.
 * [label] names it on its chip (the ring has none: it is picked as a shape), [phrase] in a
 * sentence ("Just left of the camera").
 */
@Immutable
internal data class DotSpot(
    @param:StringRes val label: Int,
    @param:StringRes val phrase: Int,
    val x: Float,
    val y: Float,
    val onCameraLine: Boolean,
    val camera: Boolean = false,
    val atLens: Boolean = false,
) {
    fun matches(x: Float, y: Float) = abs(this.x - x) < 0.001f && abs(this.y - y) < 0.001f
}

private val SPOT_EDGE = 18.dp
private val SPOT_CAMERA_GAP = 10.dp

/**
 * LED spots built from this phone's real camera, where a notification LED sat in the bezel: the
 * lens itself (the ring) and four on the camera's horizontal line (screen edges and either side
 * of the lens). Without a punch-hole the line is the status bar's, between clock and battery:
 * the edges and the centre. Positions are converted with the same margin the full-screen LED
 * uses, so a spot picked here lands exactly there.
 */
internal fun dotSpots(camera: ScreenCamera, window: IntSize, dotSize: DotSize, density: Density): List<DotSpot> = with(density) {
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
    val edgeLeft = DotSpot(R.string.dot_spot_edge_left, R.string.led_place_top_left, fx(edge), line, onCameraLine = true)
    val edgeRight = DotSpot(R.string.dot_spot_edge_right, R.string.led_place_top_right, fx(w - edge), line, onCameraLine = true)
    if (!camera.present) {
        val centre = DotSpot(R.string.dot_spot_centre, R.string.led_place_top_centre, fx(camX), line, onCameraLine = true, atLens = true)
        return listOf(edgeLeft, centre, edgeRight)
    }
    listOf(
        DotSpot(R.string.led_form_ring, R.string.led_place_ring, fx(camX), line, onCameraLine = false, camera = true, atLens = true),
        edgeLeft,
        DotSpot(R.string.dot_spot_cam_left, R.string.led_place_cam_left, fx(camX - clear), line, onCameraLine = true),
        DotSpot(R.string.dot_spot_cam_right, R.string.led_place_cam_right, fx(camX + clear), line, onCameraLine = true),
        edgeRight,
    )
}

/** The screen in thirds, top row first, for an LED that sits on no spot. */
private val PLACE_GRID = arrayOf(
    intArrayOf(R.string.led_place_top_left, R.string.led_place_top_centre, R.string.led_place_top_right),
    intArrayOf(R.string.led_place_middle_left, R.string.led_place_middle, R.string.led_place_middle_right),
    intArrayOf(R.string.led_place_bottom_left, R.string.led_place_bottom_centre, R.string.led_place_bottom_right),
)

/**
 * Where the LED waits, in words: its spot's phrase, or else the third of the screen it is in
 * ("Bottom right"), so other tabs can say where it is without a picture. The frame is round the
 * screen's edge, wherever the dot was left.
 */
@StringRes
internal fun ledPlace(dotX: Float, dotY: Float, form: LedForm, spots: List<DotSpot>): Int {
    if (form == LedForm.FRAME) return R.string.led_place_frame
    val onCamera = form == LedForm.RING
    val spot = if (onCamera) spots.firstOrNull { it.camera } else spots.firstOrNull { !it.camera && it.matches(dotX, dotY) }
    if (spot != null) return spot.phrase
    if (onCamera) return R.string.led_place_ring
    fun third(f: Float) = (f * 3f).toInt().coerceIn(0, 2)
    return PLACE_GRID[third(dotY)][third(dotX)]
}

/** The LED as a 14 dp glyph: a ring round a lens, a dot in its glow, or a phone's edge lit all round. */
@Composable
internal fun LedGlyph(form: LedForm, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val r = size.minDimension / 2f
        when (form) {
            LedForm.RING -> {
                val line = 2.dp.toPx()
                drawCircle(GlowPalette.SurfaceHighest, radius = r - line * 1.5f)
                drawCircle(GlowPalette.Cyan, radius = r - line / 2f, style = Stroke(line))
            }
            LedForm.DOT -> {
                drawCircle(GlowPalette.Cyan, radius = r, alpha = 0.18f)
                drawCircle(GlowPalette.Cyan, radius = r * 0.4f)
            }
            LedForm.FRAME -> {
                val line = 1.5.dp.toPx()
                val phone = Size(size.width * 0.68f, size.height)
                val at = Offset((size.width - phone.width) / 2f, 0f)
                val corner = CornerRadius(3.dp.toPx())
                drawRoundRect(GlowPalette.Cyan, at, phone, corner, alpha = 0.14f)
                drawRoundRect(
                    GlowPalette.Cyan,
                    at + Offset(line / 2f, line / 2f),
                    Size(phone.width - line, phone.height - line),
                    CornerRadius(corner.x - line / 2f),
                    style = Stroke(line),
                )
            }
        }
    }
}

/** [ledPlace] for these settings, against this phone's spots. */
@Composable
@StringRes
internal fun ledPlaceOf(settings: GlowSettings): Int {
    val spots = rememberDotSpots(settings.dotSize)
    return ledPlace(settings.dotX, settings.dotY, settings.ledForm, spots)
}

@Composable
private fun rememberDotSpots(dotSize: DotSize): List<DotSpot> {
    val camera = LocalCamera.current
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(camera, window, dotSize, density) { dotSpots(camera, window, dotSize, density) }
}

/**
 * The LED's shape and place. [onMove] takes it to a spot in a form: a pick (a chip, a tap) is
 * followed by [onCommit], a drag or a run of steps by [onCommit] once it settles.
 */
@Composable
internal fun LedCard(
    settings: GlowSettings,
    onMove: (x: Float, y: Float, form: LedForm) -> Unit,
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
        onMove(snapped.x, snapped.y, if ((snapped.target as? DotSpot)?.camera == true) LedForm.RING else LedForm.DOT)
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
        onMove((dotX + dx).coerceIn(0f, 1f), (dotY + dy).coerceIn(0f, 1f), LedForm.DOT)
        settleSteps()
    }

    // Scale the real dot into the mock-up so LED vs L reads honestly.
    val screenHeightDp = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val previewScale = (PanelHeight / screenHeightDp.coerceAtLeast(PanelHeight)).coerceIn(0.1f, 1f)
    val previewRadius = previewDotRadius(settings.dotSize, previewScale)

    val pick: (DotSpot) -> Unit = { spot ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        lastSnap = spot
        onMove(spot.x, spot.y, if (spot.camera) LedForm.RING else LedForm.DOT)
        onCommit()
    }
    val ring = spots.firstOrNull { it.camera }
    val frame = settings.ledOnEdge

    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(stringResource(R.string.section_led), GlowPalette.TextMuted)

        // Tiles, a glyph over its name, so "Edge Frame" has the width.
        OptionGroup(stringResource(R.string.led_form)) {
            val forms = ledForms(camera = ring != null)
            SelectionRow(
                count = forms.size,
                selected = forms.indexOf(settings.ledForm).coerceAtLeast(0),
                height = FormTileHeight,
                inset = 2.dp,
                onSelect = { index ->
                    val (x, y, form) = ledFormMove(forms[index], dotX, dotY, spots) ?: return@SelectionRow
                    // Onto a spot (the ring, or beside the lens) it snaps as a drag would; else only its shape changes.
                    val moved = form == LedForm.RING || x != dotX || y != dotY
                    val spot = if (moved) spots.firstOrNull { it.matches(x, y) && it.camera == (form == LedForm.RING) } else null
                    if (spot != null) pick(spot) else {
                        onMove(x, y, form)
                        onCommit()
                    }
                },
            ) { index, lit ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LedGlyph(forms[index])
                    ChipLabel(stringResource(forms[index].label), lit, compact = true)
                }
            }
        }

        Column {
            // Dragging or tapping the mock-up is the main way; the readouts and how-to sit beside it.
            // The frame has no place to choose: the mock-up only shows it.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val previewLabel = stringResource(if (frame) R.string.led_frame_preview_label else R.string.dot_preview_label)
                PhoneMock(
                    modifier = Modifier
                        .height(PanelHeight)
                        .aspectRatio(0.48f)
                        .semantics { contentDescription = previewLabel },
                ) { mockGeometry ->
                    // A new shape takes over from the old in place, as the real one does over the
                    // dashboard; the ruler goes with the dot and the ring, which it places.
                    val shapes = ledFormLevels(settings.ledForm)
                    val frameShape = shapes.first { it.first == LedForm.FRAME }.second
                    DotRuler(
                        dotX = dotX,
                        dotY = dotY,
                        dotRadius = previewRadius,
                        spots = spots,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - frameShape.level },
                    )
                    for ((form, shape) in shapes) {
                        if (!shape.shows) continue
                        key(form) {
                            if (form == LedForm.FRAME) {
                                // The real frame scaled down: the Edge Frame's line and colours, waiting.
                                val corner = with(density) { PhoneCorner.toPx() }
                                LedDot(
                                    color = GlowPalette.Cyan,
                                    alpha = { shape.level },
                                    dotX = dotX,
                                    dotY = dotY,
                                    radius = previewRadius,
                                    form = LedForm.FRAME,
                                    geometry = mockGeometry.copy(cornerRadiusPx = corner),
                                    frameOf = settings,
                                    scale = previewScale,
                                    modifier = Modifier.fillMaxSize().frameOpening(form) { shape.level },
                                )
                            } else {
                                GlowGraphic(
                                    form = if (form == LedForm.RING) GlowForm.RING else GlowForm.DOT,
                                    color = GlowPalette.Cyan,
                                    alpha = { shape.level },
                                    dotX = dotX,
                                    dotY = dotY,
                                    metrics = GlowMetrics.Panel,
                                    geometry = mockGeometry,
                                    dotRadius = previewRadius,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    val margin = with(density) { previewRadius.toPx() * DOT_HALO_FACTOR }
                    // The gestures outlive a recomposition: they read the newest snap spots (a lens fit
                    // moves them) rather than those of the first touch.
                    val currentMove by rememberUpdatedState(move)
                    val currentCommit by rememberUpdatedState(onCommit)
                    if (!frame) Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(margin) {
                                detectTapGestures { position ->
                                    val f = dotFraction(position, size.toSize(), margin)
                                    currentMove(f.x, f.y)
                                    currentCommit()
                                }
                            }
                            .pointerInput(margin) {
                                detectDragGestures(onDragEnd = { currentCommit() }, onDragCancel = { currentCommit() }) { change, _ ->
                                    change.consume()
                                    val f = dotFraction(change.position, size.toSize(), margin)
                                    currentMove(f.x, f.y)
                                }
                            },
                    )
                }
                Spacer(Modifier.width(16.dp))
                // The words change with the frame: the old ones go, the new fade in where they were.
                AnimatedContent(
                    targetState = frame,
                    transitionSpec = { GlowMotion.swap() },
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.weight(1f),
                    label = "led-form-words",
                ) { framed ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!framed) {
                            Readout(stringResource(R.string.dot_readout_x, (dotX * 100).roundToInt()))
                            Readout(stringResource(R.string.dot_readout_y, (dotY * 100).roundToInt()))
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            text = stringResource(if (framed) R.string.led_frame_body else R.string.dot_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = GlowPalette.TextMuted,
                        )
                        // With the beacon, moving the LED moves how a message arrives too: say so here.
                        // Round the frame it lights at the camera, or at the top without one.
                        if (settings.style == GlowStyle.BEACON && settings.arrival.playsEffect) {
                            val note = when {
                                !framed -> R.string.dot_beacon_note
                                LocalCamera.current.present -> R.string.led_frame_beacon_note
                                else -> R.string.led_frame_beacon_note_no_camera
                            }
                            Text(
                                text = stringResource(note),
                                style = MaterialTheme.typography.bodySmall,
                                color = GlowPalette.TextMuted,
                            )
                        }
                    }
                }
            }

            // The ring's camera fit; no camera, no ring. Each carries its own gap, so nothing is left
            // behind as they fold away.
            if (ring != null) {
                Disclosure(visible = settings.ledOnCamera) {
                    val adjusted = settings.lensOffsetXDp != 0f || settings.lensOffsetDp != 0f || settings.lensGrowDp != 0f
                    Box(Modifier.padding(top = 16.dp)) {
                        Fold(
                            title = stringResource(R.string.fold_lens),
                            summary = stringResource(if (adjusted) R.string.fold_lens_adjusted else R.string.fold_lens_reported),
                        ) {
                            LensFit(settings, onLensFit, onLensFitDone)
                        }
                    }
                }
            }
            // Where the dot goes: a spot picked here from the ring makes it a dot again.
            Disclosure(visible = !frame) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OptionGroup(stringResource(if (LocalCamera.current.present) R.string.dot_spots_camera_line else R.string.dot_spots_top_line)) {
                        SpotRow(spots.filter { it.onCameraLine }, dotX, dotY, pick)
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
        }
    }
}

/**
 * How the LED looks, one card whatever its shape: the dot's size or the ring's thickness, or the
 * frame's colour, width and glow, open as soon as the frame is picked. The frame's are the Edge
 * Frame's own ([EdgeColor], [EdgeWidth], [EdgeGlow]), set here alone and the same whatever the
 * effect's style: with the Edge Frame effect its frame arrives in them too, as the effect's card
 * says ([EdgeFrameCard]); made of the element, in their width and glow only. It lights at the system
 * brightness, so the room's light and the system slider set how bright it is. While [reveal] (the
 * Edge Frame card's link, once the tab is in place) it scrolls into view, then [onRevealed] lets
 * it go, so coming back to the tab later doesn't scroll again.
 */
@Composable
internal fun LedLookCard(
    settings: GlowSettings,
    onLook: (GlowSettings) -> Unit,
    reveal: Boolean = false,
    onRevealed: () -> Unit = {},
) {
    val view = remember { BringIntoViewRequester() }
    val revealed by rememberUpdatedState(onRevealed)
    LaunchedEffect(reveal) {
        if (!reveal) return@LaunchedEffect
        try {
            view.bringIntoView()
        } finally {
            revealed()
        }
    }
    Column(Modifier.bringIntoViewRequester(view).glowCard()) {
        SectionLabel(stringResource(R.string.section_led_look), GlowPalette.TextMuted)
        // The dot's and the ring's size, or the frame's look; each carries its own gap.
        Disclosure(visible = !settings.ledOnEdge) {
            Box(Modifier.padding(top = 16.dp)) {
                OptionGroup(stringResource(if (settings.ledOnCamera) R.string.dot_ring_thickness else R.string.dot_size)) {
                    SelectionRow(
                        count = DotSize.entries.size,
                        selected = settings.dotSize.ordinal,
                        onSelect = { onLook(settings.copy(dotSize = DotSize.entries[it])) },
                    ) { index, lit ->
                        val size = DotSize.entries[index]
                        Canvas(Modifier.size(size.radius * 2)) { drawCircle(GlowPalette.Cyan) }
                        Spacer(Modifier.width(8.dp))
                        ChipLabel(stringResource(size.label), lit)
                    }
                }
            }
        }
        Disclosure(visible = settings.ledOnEdge) {
            // The same whatever the effect's style; the Edge Frame card says it shares them.
            Column(Modifier.padding(top = 16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OptionGroup(stringResource(R.string.edge_color)) {
                        ChipRow(EdgeColor.entries, settings.edgeColor) { onLook(settings.copy(edgeColor = it)) }
                        OptionBody(settings.edgeColor) { stringResource(frameLedColorBody(it)) }
                    }
                    OptionGroup(stringResource(R.string.edge_width)) {
                        ChipRow(EdgeWidth.entries, settings.edgeWidth) { onLook(settings.copy(edgeWidth = it)) }
                    }
                    OptionGroup(stringResource(R.string.edge_glow)) {
                        ChipRow(EdgeGlow.entries, settings.edgeGlow) { onLook(settings.copy(edgeGlow = it)) }
                    }
                }
            }
        }
    }
}

/** The shapes the LED can take: the ring needs a [camera] to go round; without one, a dot or the frame. */
internal fun ledForms(camera: Boolean): List<LedForm> = if (camera) LedForm.entries else listOf(LedForm.DOT, LedForm.FRAME)

/**
 * Where the LED goes when its shape tile picks [form], from the dot's place [dotX], [dotY]: as
 * (x, y, form), or null when it can't take it (the ring, with no camera among [spots]). The ring
 * goes onto the lens. The dot keeps its place, unless that is the lens, which can't light: then
 * just beside it. The frame keeps the dot's place, for coming back to it.
 */
internal fun ledFormMove(form: LedForm, dotX: Float, dotY: Float, spots: List<DotSpot>): Triple<Float, Float, LedForm>? {
    val ring = spots.firstOrNull { it.camera }
    return when (form) {
        LedForm.RING -> ring?.let { Triple(it.x, it.y, LedForm.RING) }
        LedForm.DOT -> {
            val beside = spots.firstOrNull { it.label == R.string.dot_spot_cam_right }
            val inLens = ring != null && ring.matches(dotX, dotY)
            if (inLens && beside != null) Triple(beside.x, beside.y, LedForm.DOT) else Triple(dotX, dotY, LedForm.DOT)
        }
        LedForm.FRAME -> Triple(dotX, dotY, LedForm.FRAME)
    }
}

/** What [color] looks like on the waiting frame: drawn once per blink, its hues hold still round the edge. */
@StringRes
internal fun frameLedColorBody(color: EdgeColor): Int = when (color) {
    EdgeColor.APP -> color.body
    EdgeColor.DUO -> R.string.edge_color_duo_body_led
    EdgeColor.SPECTRUM -> R.string.edge_color_spectrum_body_led
}

/**
 * One axis of the precise position: its label and where the dot is on it, then a slider between
 * its − / + steps. [onStep] gets −1 or +1. The value has a decimal, since a step moves it less
 * than a whole percent and each tap should show.
 */
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(label)
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.dot_axis_value, value * 100f),
                style = MaterialTheme.typography.labelMedium,
                color = GlowPalette.TextPrimary,
            )
        }
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
 * All in the text greys, so only the dot (the LED itself) is in colour.
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
        val guide = GlowPalette.TextMuted.copy(alpha = 0.3f)
        drawLine(guide, Offset(center.x, 0f), Offset(center.x, size.height), hair, pathEffect = dash)
        drawLine(guide, Offset(0f, center.y), Offset(size.width, center.y), hair, pathEffect = dash)
        drawLine(GlowPalette.TextMuted, Offset(center.x, 0f), Offset(center.x, major), hair * 2f)
        drawLine(GlowPalette.TextMuted, Offset(0f, center.y), Offset(major, center.y), hair * 2f)

        // The camera line: every spot on it shares the lens centre's height.
        spots.firstOrNull { it.onCameraLine }?.let { spot ->
            val y = dotCenter(0f, spot.y, size, margin).y
            drawLine(GlowPalette.TextMuted.copy(alpha = 0.28f), Offset(0f, y), Offset(size.width, y), hair, pathEffect = dash)
        }

        val pinRadius = 3.5.dp.toPx()
        spots.forEach { spot ->
            drawCircle(
                color = if (spot.matches(dotX, dotY)) GlowPalette.TextMuted else GlowPalette.TextFaint,
                radius = pinRadius,
                center = dotCenter(spot.x, spot.y, size, margin),
                style = Stroke(width = hair),
            )
        }
    }
}
