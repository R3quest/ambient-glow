package com.example.ambientglow.dashboard

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ambientglow.DEFAULT_GLOW_COLOR
import com.example.ambientglow.EarthColor
import com.example.ambientglow.EdgeColor
import com.example.ambientglow.EdgeGlow
import com.example.ambientglow.EdgeMotion
import com.example.ambientglow.EdgeWidth
import com.example.ambientglow.GlowForm
import com.example.ambientglow.GlowGraphic
import com.example.ambientglow.GlowMaterial
import com.example.ambientglow.GlowMetrics
import com.example.ambientglow.GlowSettings
import com.example.ambientglow.GlowStyle
import com.example.ambientglow.R
import com.example.ambientglow.SpawnElement
import com.example.ambientglow.elementFramesSupported
import com.example.ambientglow.ui.components.ChipRow
import com.example.ambientglow.ui.components.Disclosure
import com.example.ambientglow.ui.components.Fold
import com.example.ambientglow.ui.components.GhostButton
import com.example.ambientglow.ui.components.OptionBody
import com.example.ambientglow.ui.components.OptionGroup
import com.example.ambientglow.ui.components.SectionLabel
import com.example.ambientglow.ui.components.glowCard
import com.example.ambientglow.ui.components.selectionSurface
import com.example.ambientglow.ui.theme.GlowMotion
import com.example.ambientglow.ui.theme.GlowPalette
import com.example.ambientglow.ui.theme.GlowShapes
import kotlin.math.ceil

// ---------------------------------------------------------------------------------------------
// The EFFECT tab: the look (live preview, style, material, try-out colour), Edge Frame's options, and the
// card that stands in for them all with Just the LED. The spawn wave and its element are in ElementCard.
// ---------------------------------------------------------------------------------------------

private val PickerPhoneHeight: Dp = 46.dp
private val PickerPhoneCorner: Dp = 6.dp

/** Clear of the top for the ring: the lens, the gap and the line ([GlowMetrics.Tile]), and a little air. */
private val PickerCameraDrop: Dp = 7.dp

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
    onMaterial: (GlowMaterial) -> Unit,
    onSample: (Int) -> Unit,
) {
    val color = SAMPLE_COLORS[sample].color
    val shaders = elementFramesSupported
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
                OptionBody(settings.style) { stringResource(it.body) }
            }
        }
        // What either style is made of: under the style, as the two make the look together.
        OptionGroup(stringResource(R.string.edge_material)) {
            MaterialPicker(settings, onMaterial)
            OptionBody(materialBody(settings, shaders)) { body -> body.map { stringResource(it) }.joinToString(" ") }
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

/**
 * Edge Frame's own options, folded under what they are set to; the beacon has none. Made of the
 * element (the material, picked with the style), the frame takes the element's colours, so its
 * own colour row steps aside.
 */
@Composable
internal fun EdgeFrameCard(settings: GlowSettings, onEffect: (GlowSettings) -> Unit) {
    val shaders = elementFramesSupported
    Column(Modifier.glowCard(vertical = 10.dp)) {
        Fold(title = stringResource(R.string.fold_edge), summary = edgePhrasing(settings, shaders).text()) {
            Column {
                // Motion and colour aren't plain from their names: what the picked one does, under it.
                OptionGroup(stringResource(R.string.edge_motion)) {
                    MotionPicker(settings) { onEffect(settings.copy(edgeMotion = it)) }
                    OptionBody(settings.edgeMotion) { stringResource(it.body) }
                }
                // Carries its own gap, so nothing jumps as it comes and goes.
                Disclosure(visible = !(settings.elemental && shaders)) {
                    Box(Modifier.padding(top = 14.dp)) {
                        OptionGroup(stringResource(R.string.edge_color)) {
                            ChipRow(EdgeColor.entries, settings.edgeColor) { onEffect(settings.copy(edgeColor = it)) }
                            OptionBody(settings.edgeColor) { stringResource(it.body) }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                OptionGroup(stringResource(R.string.edge_width)) {
                    ChipRow(EdgeWidth.entries, settings.edgeWidth) { onEffect(settings.copy(edgeWidth = it)) }
                }
                Spacer(Modifier.height(14.dp))
                OptionGroup(stringResource(R.string.edge_glow)) {
                    ChipRow(EdgeGlow.entries, settings.edgeGlow) { onEffect(settings.copy(edgeGlow = it)) }
                }
            }
        }
    }
}

/**
 * In place of the effect's options while Just the LED is chosen: there is nothing to style, so
 * say why and lead back to where effects are turned on, rather than offer controls that do nothing.
 */
@Composable
internal fun EffectsOffCard(onChooseScreen: () -> Unit) {
    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.effects_off_title),
            style = MaterialTheme.typography.titleMedium,
            color = GlowPalette.TextPrimary,
        )
        Text(
            text = stringResource(R.string.effects_off_body),
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        GhostButton(text = stringResource(R.string.effects_off_action), emphasized = true, onClick = onChooseScreen)
    }
}

/** A sentence a fold's summary says: a string [template] and the phrases that fill it, in order. */
@Immutable
internal data class Phrasing(@param:StringRes val template: Int, val phrases: List<Int> = emptyList())

/** The phrasing read out, starting with a capital as a sentence does. */
@Composable
internal fun Phrasing.text(): String =
    sentence(stringResource(template, *phrases.map { stringResource(it) }.toTypedArray()))

internal fun sentence(text: String): String = text.replaceFirstChar { it.titlecase() }

/**
 * Edge Frame's options in one sentence: motion, what it is made of (or its colour, as neon),
 * width and glow. [shaders]: the element's frame can play here ([elementFramesSupported]).
 */
internal fun edgePhrasing(settings: GlowSettings, shaders: Boolean) = Phrasing(
    R.string.edge_summary,
    listOf(
        settings.edgeMotion.phrase,
        if (settings.elemental && shaders) elementFramePhrase(settings) else settings.edgeColor.phrase,
        settings.edgeWidth.phrase,
        settings.edgeGlow.phrase,
    ),
)

/** The element's frame in a phrase: "as a burning fuse". Earth is mended in gold only in its own stone. */
@StringRes
private fun elementFramePhrase(settings: GlowSettings): Int = when (settings.element) {
    SpawnElement.FIRE -> R.string.edge_material_fire_phrase
    SpawnElement.WATER -> R.string.edge_material_water_phrase
    SpawnElement.AIR -> R.string.edge_material_air_phrase
    SpawnElement.EARTH ->
        if (settings.earthColor == EarthColor.STONE) R.string.edge_material_earth_phrase else R.string.edge_material_earth_lit_phrase
}

/**
 * What the chosen material does in the chosen style, as the sentences to say: the element's
 * frame or beacon and where its colours come from, or why it plays as Neon for now.
 */
internal fun materialBody(settings: GlowSettings, shaders: Boolean): List<Int> {
    val beacon = settings.style == GlowStyle.BEACON
    return when {
        settings.material == GlowMaterial.NEON ->
            listOf(if (beacon) R.string.beacon_material_neon_body else R.string.edge_material_neon_body)
        !shaders -> listOf(R.string.edge_material_needs_shaders)
        !settings.spawn -> listOf(R.string.edge_material_needs_spawn)
        else -> {
            val look = when (settings.element) {
                SpawnElement.FIRE -> if (beacon) R.string.beacon_material_fire_body else R.string.edge_material_fire_body
                SpawnElement.WATER -> if (beacon) R.string.beacon_material_water_body else R.string.edge_material_water_body
                SpawnElement.AIR -> if (beacon) R.string.beacon_material_air_body else R.string.edge_material_air_body
                SpawnElement.EARTH -> if (beacon) R.string.beacon_material_earth_body else R.string.edge_material_earth_body
            }
            // Water's is the app's colour, as its wave is: it has no colours of its own to point to.
            if (settings.element == SpawnElement.WATER) listOf(look) else listOf(look, R.string.edge_material_element_colours)
        }
    }
}

/**
 * Neon or the element, as pictures: neon light in the style's shape (a phone's edge, or the
 * beacon's ring), and the chosen element's glyph in its own colour, so the tile says what the
 * effect will be made of.
 */
@Composable
private fun MaterialPicker(settings: GlowSettings, onSelect: (GlowMaterial) -> Unit) {
    val element = settings.element
    GlyphTiles(GlowMaterial.entries, settings.material, onSelect, ::glyphStroke) { material, lit, stroke ->
        when (material) {
            GlowMaterial.NEON -> {
                val color = ink(lit, GlowPalette.Cyan)
                // The glow first, then the line on it.
                val glow = Stroke(stroke.width * 2.6f)
                if (settings.style == GlowStyle.BEACON) {
                    drawCircle(color, NeonRingRadius, NeonRingCenter, alpha = 0.22f * lit, style = glow)
                    drawCircle(color, NeonRingRadius, NeonRingCenter, style = stroke)
                } else {
                    drawRoundRect(color, NeonGlyphAt, NeonGlyphSize, NeonGlyphCorner, alpha = 0.22f * lit, style = glow)
                    drawRoundRect(color, NeonGlyphAt, NeonGlyphSize, NeonGlyphCorner, style = stroke)
                }
            }
            GlowMaterial.ELEMENT -> {
                val color = ink(lit, element.accent)
                element.glyph.forEach { drawPath(it, color, style = stroke) }
            }
        }
    }
}

/** Neon's glyph: a phone's outline on the 24-unit grid, as tall as the element glyphs. */
private val NeonGlyphAt = Offset(6.5f, 2.5f)
private val NeonGlyphSize = Size(11f, 19f)
private val NeonGlyphCorner = CornerRadius(3f)

/** Neon's glyph for the beacon: a ring on the 24-unit grid. */
private val NeonRingCenter = Offset(12f, 12f)
private const val NeonRingRadius = 7f

/**
 * The motions as pictures on the same phone: Pulse its edge with a breath inside it, Comet the
 * left side lit up to a head at the camera, Twin both sides meeting there. They light in what the
 * frame is made of: the element's colour, or neon's cyan.
 */
@Composable
private fun MotionPicker(settings: GlowSettings, onSelect: (EdgeMotion) -> Unit) {
    val accent = if (settings.elemental && elementFramesSupported) settings.element.accent else GlowPalette.Cyan
    GlyphTiles(EdgeMotion.entries, settings.edgeMotion, onSelect, ::glyphStroke) { motion, lit, stroke ->
        val color = ink(lit, accent)
        if (motion == EdgeMotion.PULSE) {
            drawRoundRect(color, NeonGlyphAt, NeonGlyphSize, NeonGlyphCorner, style = stroke)
            drawRoundRect(color, PulseInnerAt, PulseInnerSize, PulseInnerCorner, alpha = 0.45f, style = stroke)
        } else {
            // The frame faint behind the light running round it, a fading tail and a head at the camera.
            drawRoundRect(color, NeonGlyphAt, NeonGlyphSize, NeonGlyphCorner, alpha = 0.3f, style = stroke)
            drawPath(CometTail, color, alpha = 0.55f, style = stroke)
            drawPath(CometHead, color, style = stroke)
            if (motion == EdgeMotion.TWIN) {
                scale(-1f, 1f, Offset(12f, 0f)) {
                    drawPath(CometTail, color, alpha = 0.55f, style = stroke)
                    drawPath(CometHead, color, style = stroke)
                }
            }
            drawCircle(color, 1.5f, Offset(12f, 2.5f))
        }
    }
}

private val PulseInnerAt = Offset(9f, 5f)
private val PulseInnerSize = Size(6f, 14f)
private val PulseInnerCorner = CornerRadius(1.5f)

/** Comet's light up the phone's left side: the tail low down, then up round the corner to the camera. */
private val CometTail = Path().apply {
    moveTo(6.5f, 17f)
    lineTo(6.5f, 11f)
}
private val CometHead = Path().apply {
    moveTo(6.5f, 11f)
    lineTo(6.5f, 5.5f)
    quadraticTo(6.5f, 2.5f, 9.5f, 2.5f)
    lineTo(12f, 2.5f)
}

/**
 * The styles as rows: a mini phone showing the style in [accent], and its name. The beacon's
 * phone shows the LED's form, a dot or the camera ring, as that is where it plays. A new pick
 * crossfades rather than slides, since rows can differ in height at large font sizes.
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
                    cameraDrop = PickerCameraDrop,
                ) { mockGeometry ->
                    GlowGraphic(
                        form = if (style == GlowStyle.EDGE_FRAME) GlowForm.EDGE else settings.ledForm,
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
