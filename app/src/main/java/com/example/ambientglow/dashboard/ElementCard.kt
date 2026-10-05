package com.example.ambientglow.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ambientglow.GlassArea
import com.example.ambientglow.GlassBlur
import com.example.ambientglow.GlassFrost
import com.example.ambientglow.GlowSettings
import com.example.ambientglow.R
import com.example.ambientglow.SpawnElement
import com.example.ambientglow.ui.components.CardDivider
import com.example.ambientglow.ui.components.ChipLabel
import com.example.ambientglow.ui.components.ChipRow
import com.example.ambientglow.ui.components.Disclosure
import com.example.ambientglow.ui.components.Fold
import com.example.ambientglow.ui.components.OptionBody
import com.example.ambientglow.ui.components.OptionGroup
import com.example.ambientglow.ui.components.OptionNote
import com.example.ambientglow.ui.components.SectionLabel
import com.example.ambientglow.ui.components.SelectionRow
import com.example.ambientglow.ui.components.ToggleRow
import com.example.ambientglow.ui.components.glowCard
import com.example.ambientglow.ui.theme.GlowMotion
import com.example.ambientglow.ui.theme.GlowPalette

// ---------------------------------------------------------------------------------------------
// The spawn wave's switch, then the element it takes after: a row of tiles, each a line-drawn
// glyph over its name (a sparkle on the premium ones), what the chosen one does, and its own
// options (Water's glass).
// ---------------------------------------------------------------------------------------------

private val TILE_HEIGHT = 64.dp
private val GLYPH_SIZE = 22.dp
private val SPARKLE_SIZE = 9.dp

/** How far the elements fade while the spawn wave they play in is off. */
private const val DIMMED = 0.45f

/** Premium's mark: the brand magenta, kept apart from the amber the app warns in. */
private val PremiumTint = GlowPalette.Magenta

/**
 * The spawn wave and the element it takes after, under the preview that shows it. The wave's
 * switch heads the card since every element is a look of it; with it off the elements and their
 * options dim and can't be changed. Each element folds its own options under the picker, so
 * adding one adds a fold, not a wall of chips.
 */
@Composable
internal fun ElementCard(settings: GlowSettings, onEffect: (GlowSettings) -> Unit) {
    val element = settings.element
    val presence = animateFloatAsState(if (settings.spawn) 1f else DIMMED, GlowMotion.stateChange(), label = "dim")
    // Each disclosure carries its own gap, so the card doesn't jump as it opens or closes.
    Column(Modifier.glowCard()) {
        SectionLabel(stringResource(R.string.element), GlowPalette.Cyan)
        Spacer(Modifier.height(12.dp))
        ToggleRow(
            title = stringResource(R.string.effect_spawn_title),
            body = stringResource(R.string.effect_spawn_body),
            checked = settings.spawn,
            onChange = { onEffect(settings.copy(spawn = it)) },
        )
        Spacer(Modifier.height(12.dp))
        CardDivider()
        Spacer(Modifier.height(16.dp))
        Column(Modifier.graphicsLayer { alpha = presence.value }) {
            ElementPicker(element, enabled = settings.spawn) { picked ->
                onEffect(settings.copy(element = picked))
            }
            Spacer(Modifier.height(10.dp))
            OptionBody(element) { shown ->
                val body = stringResource(shown.body)
                if (shown.ready) body else body + " " + stringResource(R.string.element_soon)
            }
            Disclosure(visible = element.premium) {
                Box(Modifier.padding(top = 10.dp)) { PremiumLine() }
            }
            // Only Water has options so far; the other elements get a fold here as they get a look.
            Disclosure(visible = element == SpawnElement.WATER) {
                Column(Modifier.padding(top = 12.dp)) {
                    CardDivider()
                    Spacer(Modifier.height(10.dp))
                    Fold(
                        title = stringResource(R.string.fold_glass),
                        summary = glassPhrasing(settings).text(),
                        enabled = settings.spawn,
                    ) {
                        GlassOptions(settings, onEffect)
                    }
                }
            }
        }
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
internal fun glassPhrasing(settings: GlowSettings): Phrasing {
    val onBlack = settings.arrival.onBlack
    // On a black screen there is nothing to blur, so only the frost is said.
    val parts = listOfNotNull(
        settings.glassBlur.phrase.takeIf { !onBlack && settings.glassBlur != GlassBlur.OFF },
        settings.glassFrost.phrase.takeIf { settings.glassFrost != GlassFrost.OFF },
    )
    val area = settings.glassArea.phrase
    return when {
        parts.isEmpty() -> Phrasing(R.string.glass_summary_none)
        onBlack -> Phrasing(R.string.glass_summary_black, parts)
        parts.size == 2 -> Phrasing(R.string.glass_summary_both, parts + area)
        else -> Phrasing(R.string.glass_summary_one, parts + area)
    }
}

/** One tile per element; the blade slides to the chosen one and its glyph lights in its colour. */
@Composable
private fun ElementPicker(selected: SpawnElement, enabled: Boolean, onSelect: (SpawnElement) -> Unit) {
    SelectionRow(
        count = SpawnElement.entries.size,
        selected = selected.ordinal,
        onSelect = { onSelect(SpawnElement.entries[it]) },
        height = TILE_HEIGHT,
        inset = 2.dp,
        enabled = enabled,
    ) { index, lit ->
        val element = SpawnElement.entries[index]
        // The tile's top end is its square corner: the sparkle sits there, clear of the glyph.
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ElementGlyph(element, tint = { lerp(GlowPalette.TextMuted, element.accent, lit()) })
                ChipLabel(stringResource(element.label), lit, compact = true)
            }
            if (element.premium) {
                val mark = stringResource(R.string.element_premium_mark)
                Sparkle(
                    PremiumTint,
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 5.dp, end = 3.dp)
                        .size(SPARKLE_SIZE)
                        .semantics { contentDescription = mark },
                )
            }
        }
    }
}

/** Says a premium element is one, and that it is free to try for now. */
@Composable
private fun PremiumLine() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Sparkle(PremiumTint, Modifier.size(SPARKLE_SIZE))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.element_premium),
            style = MaterialTheme.typography.labelSmall,
            color = PremiumTint,
        )
    }
}

private val SPARKLE = PathParser()
    .parsePathString("M12 1 C12.9 7.6 16.4 11.1 23 12 C16.4 12.9 12.9 16.4 12 23 C11.1 16.4 7.6 12.9 1 12 C7.6 11.1 11.1 7.6 12 1 Z")
    .toPath()

/** A four-point sparkle, filled, on the glyphs' 24-unit grid. */
@Composable
private fun Sparkle(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        scale(size.minDimension / 24f, pivot = Offset.Zero) { drawPath(SPARKLE, color) }
    }
}

/** The colour an element's glyph lights in when chosen. */
private val SpawnElement.accent: Color
    get() = when (this) {
        SpawnElement.FIRE -> Color(0xFFFF7A2F)
        SpawnElement.WATER -> GlowPalette.Cyan
        SpawnElement.AIR -> Color(0xFFB8D4E3)
        SpawnElement.EARTH -> Color(0xFF7BD66B)
    }

/** Glyph outlines on a 24-unit grid, stroked rather than filled, as the app's other marks are. */
private val GLYPHS: Map<SpawnElement, List<Path>> = mapOf(
    // A flame with a smaller one inside.
    SpawnElement.FIRE to listOf(
        "M12 2.5 C13 6.5 18 8.5 18 14.2 C18 18 15.3 21 12 21 C8.7 21 6 18 6 14.2 " +
            "C6 11.2 7.8 9.6 9 8 C9.4 9.9 10.3 11 11.4 11.3 C10.9 8.2 11.1 5.2 12 2.5 Z",
        "M12 21 C10.4 21 9.6 19.7 9.6 18.3 C9.6 16.5 11 15.4 12 13.6 C13 15.4 14.4 16.5 14.4 18.3 " +
            "C14.4 19.7 13.6 21 12 21",
    ),
    // A drop with a glint.
    SpawnElement.WATER to listOf(
        "M12 2.8 C12 2.8 5.6 10.2 5.6 14.6 C5.6 18.1 8.5 21 12 21 C15.5 21 18.4 18.1 18.4 14.6 " +
            "C18.4 10.2 12 2.8 12 2.8 Z",
        "M8.9 15 C8.9 16.8 10.2 18.1 12 18.1",
    ),
    // Air: three gusts, two curling back.
    SpawnElement.AIR to listOf(
        "M3 9 L14 9 C15.8 9 17 7.7 17 6.4 C17 5.1 16 4 14.7 4 C13.5 4 12.6 4.9 12.6 6",
        "M3 13 L18.6 13 C20.3 13 21.4 14.2 21.4 15.6 C21.4 17 20.3 18.1 18.9 18.1 C17.6 18.1 16.7 17.2 16.7 16",
        "M3 17 L11 17",
    ),
    // A sprout from the ground.
    SpawnElement.EARTH to listOf(
        "M12 20.5 L12 11.5",
        "M12 14 C12 10.2 9.4 7.8 5.2 7.8 C5.2 11.6 7.8 14 12 14 Z",
        "M12 11.5 C12 7.4 14.8 4.6 19.2 4.6 C19.2 8.8 16.4 11.5 12 11.5 Z",
        "M6.5 20.5 L17.5 20.5",
    ),
).mapValues { (_, paths) -> paths.map { PathParser().parsePathString(it).toPath() } }

@Composable
private fun ElementGlyph(element: SpawnElement, tint: () -> Color) {
    val paths = GLYPHS.getValue(element)
    Canvas(Modifier.size(GLYPH_SIZE)) {
        val unit = size.minDimension / 24f
        // Stroked in grid units, so the line is 1.6 dp at this size.
        val stroke = Stroke(width = 1.6.dp.toPx() / unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val color = tint()
        scale(unit, pivot = Offset.Zero) {
            paths.forEach { drawPath(it, color, style = stroke) }
        }
    }
}
