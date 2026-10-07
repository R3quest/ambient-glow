package com.example.ambientglow.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ambientglow.EarthColor
import com.example.ambientglow.EarthDebris
import com.example.ambientglow.EarthForce
import com.example.ambientglow.EarthForm
import com.example.ambientglow.EarthPalette
import com.example.ambientglow.GlowSettings
import com.example.ambientglow.R
import com.example.ambientglow.earthPalette
import com.example.ambientglow.ui.components.ChipLabel
import com.example.ambientglow.ui.components.OptionBody
import com.example.ambientglow.ui.components.OptionGroup
import com.example.ambientglow.ui.components.SelectionRow

// ---------------------------------------------------------------------------------------------
// Earth's options, under the element picker, as pictures like Fire's: the force as a seismograph
// that grows wilder tile by tile, the ground as glowing cracks, a mosaic of slabs or spires of
// rock, the colours as chips each holding a faceted stone split by its light, and what is thrown
// up as smoke or rubble. The pictures take the earth chosen, and pop as the blade passes under them.
// ---------------------------------------------------------------------------------------------

private val SWATCH_SIZE = 16.dp

/** How far an unchosen colour's swatch fades, so the chosen earth is the one that shows. */
private const val SWATCH_REST = 0.5f

/**
 * Earth: the force, what the ground breaks into, its colour and what it throws up. The colour
 * chips show each earth as a message in [accent] would break it, and the other pictures take the
 * earth chosen. The ground and colour aren't plain from their names: what the picked one does, under it.
 */
@Composable
internal fun EarthOptions(settings: GlowSettings, accent: Color, onEffect: (GlowSettings) -> Unit) {
    val earth = remember(settings.earthColor, accent) { earthPalette(settings.earthColor, accent) }
    val smoke = remember(earth) { earth.smoke() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OptionGroup(stringResource(R.string.earth_force)) {
            GlyphTiles(EarthForce.entries, settings.earthForce, { onEffect(settings.copy(earthForce = it)) }, ::EarthLines) { force, lit, lines ->
                forceGlyph(force, earth, lit, lines)
            }
        }
        OptionGroup(stringResource(R.string.earth_form)) {
            GlyphTiles(EarthForm.entries, settings.earthForm, { onEffect(settings.copy(earthForm = it)) }, ::EarthLines) { form, lit, lines ->
                formGlyph(form, earth, lit, lines)
            }
            OptionBody(settings.earthForm) { stringResource(it.body) }
        }
        OptionGroup(stringResource(R.string.earth_color)) {
            EarthColorPicker(settings.earthColor, accent) { onEffect(settings.copy(earthColor = it)) }
            OptionBody(settings.earthColor) { stringResource(it.body) }
        }
        OptionGroup(stringResource(R.string.earth_debris)) {
            GlyphTiles(EarthDebris.entries, settings.earthDebris, { onEffect(settings.copy(earthDebris = it)) }, ::EarthLines) { debris, lit, lines ->
                debrisGlyph(debris, earth, smoke, lit, lines)
            }
        }
    }
}

/** What the earth options are set to, in one sentence; what it throws up only when it throws something. */
internal fun earthPhrasing(settings: GlowSettings): Phrasing {
    val parts = listOf(settings.earthForce.phrase, settings.earthForm.phrase, settings.earthColor.phrase)
    return if (settings.earthDebris == EarthDebris.NONE) {
        Phrasing(R.string.earth_summary, parts)
    } else {
        Phrasing(R.string.earth_summary_debris, parts + settings.earthDebris.phrase)
    }
}

/** Earth's glyph strokes, built once per size: the plain line, and the light round a glowing crack. */
private class EarthLines(unit: Float, line: Float) {
    val plain = glyphStroke(unit, line)
    val halo = glyphStroke(unit, line * 2.8f)
}

private fun path(data: String): Path = PathParser().parsePathString(data).toPath()

/** The force as a seismograph: a flutter, a quake, a trace thrown off the scale. */
private val TREMOR = path("M2.5 12 L7.5 12 L9 10 L10.5 14 L12 9.5 L13.5 14.5 L15 11 L16.5 12 L21.5 12")
private val QUAKE = path("M2.5 12 L6.5 12 L8 9 L9.5 15 L11 6.5 L13 17.5 L14.5 8.5 L16 15 L17.5 12 L21.5 12")
private val UPHEAVAL = path("M2.5 12 L5 12 L6.5 8 L8 16 L10 3.5 L12 20.5 L14 5.5 L15.5 18 L17 9 L18.5 12 L21.5 12")

/** The force: the trace in the cracks' light, glowing once chosen. */
private fun DrawScope.forceGlyph(force: EarthForce, earth: EarthPalette, lit: Float, lines: EarthLines) {
    val trace = when (force) {
        EarthForce.TREMOR -> TREMOR
        EarthForce.QUAKE -> QUAKE
        EarthForce.UPHEAVAL -> UPHEAVAL
    }
    glowing(trace, earth, lit, lines)
}

/** A line of light: a soft halo once chosen, under the line itself. */
private fun DrawScope.glowing(path: Path, earth: EarthPalette, lit: Float, lines: EarthLines) {
    if (lit > 0f) drawPath(path, earth.glow, alpha = 0.3f * lit, style = lines.halo)
    drawPath(path, ink(lit, earth.glow), style = lines.plain)
}

/** Cracks: one running the height of the tile, forking off to either side. */
private val CRACKS = path(
    "M12 2.5 L10.4 7.5 L13.2 11 L10.8 15.2 L12.6 21.5 " +
        "M13.2 11 L17.4 12.6 L20.5 11.2 M10.4 7.5 L6.2 9 L3.8 7.6 M10.8 15.2 L6.8 17.4",
)

/** Slabs: four plates of stone, a gap of light between them; two catch the light, two don't. */
private val SLABS = listOf(
    path("M3 3.5 L10.6 3 L10 10.6 L3.4 11.2 Z") to true,
    path("M13 3.2 L20.8 4.4 L20.4 11 L12.6 10.4 Z") to false,
    path("M3.4 13.6 L10.2 13 L11.4 20.8 L3.8 20.6 Z") to false,
    path("M12.6 12.8 L20.4 13.4 L20.8 20.4 L13.8 20.8 Z") to true,
)

/** Spires: three shards out of the ground, the middle one tallest and in front, each a lit face and a face in shade. */
@Immutable
private class Spire(left: Offset, tip: Offset, right: Offset) {
    private val mid = Offset((left.x + right.x) / 2f, left.y)
    val outline = Path().apply {
        moveTo(left.x, left.y)
        lineTo(tip.x, tip.y)
        lineTo(right.x, right.y)
        close()
    }
    val litFace = Path().apply {
        moveTo(left.x, left.y)
        lineTo(tip.x, tip.y)
        lineTo(mid.x, mid.y)
        close()
    }
    val shadeFace = Path().apply {
        moveTo(mid.x, mid.y)
        lineTo(tip.x, tip.y)
        lineTo(right.x, right.y)
        close()
    }
}

private val SPIRES = listOf(
    Spire(Offset(4.5f, 20f), Offset(7.5f, 10f), Offset(10.5f, 20f)),
    Spire(Offset(14f, 20f), Offset(17.5f, 11.5f), Offset(20f, 20f)),
    Spire(Offset(8.5f, 20f), Offset(12.5f, 3.5f), Offset(16f, 20f)),
)

/**
 * What the ground breaks into: cracks of light; a mosaic of slabs with light in the gaps; spires
 * of rock on the ground. Chosen, the stone fills in its own tones.
 */
private fun DrawScope.formGlyph(form: EarthForm, earth: EarthPalette, lit: Float, lines: EarthLines) {
    when (form) {
        EarthForm.FAULTS -> glowing(CRACKS, earth, lit, lines)
        EarthForm.SLABS -> {
            val line = ink(lit, earth.glow)
            SLABS.forEach { (plate, sunny) ->
                if (lit > 0f) drawPath(plate, if (sunny) earth.light else earth.stone, alpha = lit)
                drawPath(plate, line, style = lines.plain)
            }
        }
        EarthForm.SPIRES -> {
            val line = ink(lit, earth.light)
            SPIRES.forEach { spire ->
                if (lit > 0f) {
                    drawPath(spire.litFace, earth.light, alpha = lit)
                    drawPath(spire.shadeFace, earth.shade, alpha = lit)
                }
                drawPath(spire.outline, line, style = lines.plain)
            }
            drawLine(line, Offset(3f, 20f), Offset(21f, 20f), strokeWidth = lines.plain.width, cap = StrokeCap.Round)
        }
    }
}

/** Smoke rising off the ground: a billowing bank, and a wisp breaking off above it. */
private val SMOKE = path(
    "M4.5 20 C2.4 18.6 3 15.6 5.6 15.4 C5.6 12.2 9.4 11 11.4 13.2 C12.2 10 17.4 10 17.8 13.6 " +
        "C20.6 13.8 21.6 17.8 19.2 20 Z",
)
private val WISP = path("M8.6 9.4 C7.2 7.6 9.2 5.6 11 6.8 C11.8 4.6 15.6 4.8 15.6 7.6")

/** The smoke's light on the glyph grid: lit at its crown, shading into its base. Built once per palette. */
private fun EarthPalette.smoke(): Brush = Brush.verticalGradient(0f to dustLit, 0.45f to dustLit, 1f to dustShade, startY = 10.5f, endY = 20f)

/** A chunk on its own, in the middle of the tile, for none struck through. */
private val CHUNK = path("M7 13.5 L10.5 8.5 L15.5 9.2 L17.2 13.8 L14 17.4 L8.8 16.8 Z")

/** Rubble: three chunks, thrown up at different heights. */
private val RUBBLE = listOf(
    path("M3.5 15.5 L6.2 12.6 L9.6 13.6 L10 17.2 L7 19 L4.2 18 Z"),
    path("M12 7.8 L14.6 5.6 L17.4 7.2 L16.9 10.6 L13.6 11.2 Z"),
    path("M14.6 17 L16.8 15.2 L19.6 16.2 L19.2 19.2 L16 19.8 Z"),
)

/**
 * What the quake throws up: a chunk struck through for none; smoke rising off the ground; rubble.
 * Chosen, they fill with their colours.
 */
private fun DrawScope.debrisGlyph(debris: EarthDebris, earth: EarthPalette, smoke: Brush, lit: Float, lines: EarthLines) {
    when (debris) {
        EarthDebris.NONE -> {
            val line = ink(lit, earth.light)
            drawPath(CHUNK, line, style = lines.plain)
            drawLine(line, Offset(5f, 19f), Offset(19f, 5f), strokeWidth = lines.plain.width, cap = StrokeCap.Round)
        }
        EarthDebris.DUST -> {
            // Soft, as smoke is: once chosen the bank fills with its light and the outline gives way to it.
            if (lit > 0f) drawPath(SMOKE, smoke, alpha = lit)
            drawPath(SMOKE, ink(lit, earth.dustLit), alpha = 1f - 0.6f * lit, style = lines.plain)
            drawPath(WISP, ink(lit, earth.dustLit), alpha = 1f - 0.35f * lit, style = lines.plain)
        }
        EarthDebris.RUBBLE -> {
            val line = ink(lit, earth.light)
            RUBBLE.forEachIndexed { i, chunk ->
                if (lit > 0f) drawPath(chunk, if (i == 1) earth.light else earth.stone, alpha = lit)
                drawPath(chunk, line, style = lines.plain)
            }
        }
    }
}

/** One chip per earth colour, each with a stone split by that earth's light. */
@Composable
private fun EarthColorPicker(selected: EarthColor, accent: Color, onSelect: (EarthColor) -> Unit) {
    val palettes = remember(accent) { EarthColor.entries.map { earthPalette(it, accent) } }
    SelectionRow(
        count = EarthColor.entries.size,
        selected = selected.ordinal,
        onSelect = { onSelect(EarthColor.entries[it]) },
        inset = 6.dp,
    ) { index, lit ->
        StoneSwatch(palettes[index], lit)
        Spacer(Modifier.width(6.dp))
        ChipLabel(stringResource(EarthColor.entries[index].label), lit, compact = true)
    }
}

/** The swatch's stone on a 16-unit grid: its outline, a face catching the light, a face in shade, and the crack through it. */
private val SWATCH_STONE = path("M2 11 L4.5 4 L10 2 L14.5 5.5 L14 12 L9 14.5 L4 14 Z")
private val SWATCH_LIT = path("M2 11 L4.5 4 L10 2 L8 8 Z")
private val SWATCH_SHADE = path("M14.5 5.5 L14 12 L9 14.5 L8 8 Z")
private val SWATCH_CRACK = path("M10 2 L8 8 L9.8 10.6 L8.6 14.3")

/** A faceted stone split by its light, brightening to full and popping as [lit] goes 0 → 1; read in draw. */
@Composable
private fun StoneSwatch(earth: EarthPalette, lit: () -> Float) {
    Spacer(
        Modifier
            .size(SWATCH_SIZE)
            .pop(lit)
            .drawWithCache {
                val k = size.width / 16f
                // The crack carries the colour that tells the chips apart: bold, in a halo of its light.
                val crack = glyphStroke(k, 1.8.dp.toPx())
                val halo = glyphStroke(k, 4.dp.toPx())
                onDrawBehind {
                    val alpha = SWATCH_REST + (1f - SWATCH_REST) * lit()
                    scale(k, pivot = Offset.Zero) {
                        drawPath(SWATCH_STONE, earth.stone, alpha = alpha)
                        drawPath(SWATCH_LIT, earth.light, alpha = alpha)
                        drawPath(SWATCH_SHADE, earth.shade, alpha = alpha)
                        drawPath(SWATCH_CRACK, earth.glow, alpha = 0.4f * alpha, style = halo)
                        drawPath(SWATCH_CRACK, earth.glow, alpha = alpha, style = crack)
                    }
                }
            },
    )
}
