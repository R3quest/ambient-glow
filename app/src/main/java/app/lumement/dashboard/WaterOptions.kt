package app.lumement.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lumement.GlassArea
import app.lumement.GlassBlur
import app.lumement.GlassFrost
import app.lumement.GlowSettings
import app.lumement.R
import app.lumement.ui.components.Disclosure
import app.lumement.ui.components.OptionBody
import app.lumement.ui.components.OptionGroup
import app.lumement.ui.components.OptionNote
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Water's options, under the element picker, as pictures like Fire's: the blur as a point of
// light spreading into a field of dots, the frost as condensation gathering on a pane, and where
// it all is as a phone with the wave's crest and the frost it leaves. They light in the message's
// colour, as the glass wave does.
// ---------------------------------------------------------------------------------------------

/**
 * Glass wave: how much the screen under it blurs, how frosted, and where. The area shapes both,
 * so it shows whenever either is on. On a black screen there is nothing to blur and only one way
 * for frost to show, so neither blur nor area is offered there. The pictures light in [accent].
 */
@Composable
internal fun WaterOptions(settings: GlowSettings, accent: Color, onEffect: (GlowSettings) -> Unit) {
    val onBlack = settings.arrival.onBlack
    Column {
        Disclosure(visible = !onBlack) {
            Box(Modifier.padding(bottom = 14.dp)) {
                OptionGroup(stringResource(R.string.glass_blur)) {
                    GlyphTiles(GlassBlur.entries, settings.glassBlur, { onEffect(settings.copy(glassBlur = it)) }, ::WaterLines) { blur, lit, lines ->
                        blurGlyph(blur, ink(lit, accent), lines.plain)
                    }
                }
            }
        }
        OptionGroup(stringResource(R.string.glass_frost)) {
            GlyphTiles(GlassFrost.entries, settings.glassFrost, { onEffect(settings.copy(glassFrost = it)) }, ::WaterLines) { frost, lit, lines ->
                frostGlyph(frost, ink(lit, accent), lines)
            }
        }
        Disclosure(visible = onBlack) {
            Box(Modifier.padding(top = 14.dp)) {
                OptionNote(stringResource(R.string.glass_on_black))
            }
        }
        Disclosure(visible = !onBlack && (settings.glassBlur != GlassBlur.OFF || settings.glassFrost != GlassFrost.OFF)) {
            Box(Modifier.padding(top = 14.dp)) {
                OptionGroup(stringResource(R.string.glass_area)) {
                    GlyphTiles(GlassArea.entries, settings.glassArea, { onEffect(settings.copy(glassArea = it)) }, ::WaterLines) { area, lit, lines ->
                        areaGlyph(area, ink(lit, accent), lines)
                    }
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

/** Water's glyph strokes, built once per size. The frost bands are in grid units, as wide as the band they paint. */
private class WaterLines(unit: Float, line: Float) {
    val plain = glyphStroke(unit, line)
    val bold = glyphStroke(unit, line * 1.35f)
    val band = Stroke(width = WAVE_BAND)
    val beyond = Stroke(width = 2f * BEYOND)
}

private val CENTRE = Offset(12f, 12f)

/** How faint the frost a glyph paints is, against its lines. */
private const val FROST_TINT = 0.35f

/** A line struck through a glyph: the option is off. */
private fun DrawScope.strike(color: Color, plain: Stroke) {
    drawLine(color, Offset(5f, 19f), Offset(19f, 5f), strokeWidth = plain.width, cap = StrokeCap.Round)
}

/**
 * The blur: a crisp point of light, struck through for none, spreading into rings of dots, more
 * and bigger the stronger it is, as a sharp light goes soft out of focus. [plain] is the glyphs'
 * line; Air's blur draws it too.
 */
internal fun DrawScope.blurGlyph(blur: GlassBlur, color: Color, plain: Stroke) {
    when (blur) {
        GlassBlur.OFF -> {
            drawCircle(color, 4.5f, CENTRE, style = plain)
            strike(color, plain)
        }
        GlassBlur.LIGHT -> {
            drawCircle(color, 4f, CENTRE)
            dots(color, 6.6f, 8, 1.1f, 0.85f, 0f)
        }
        GlassBlur.MEDIUM -> {
            drawCircle(color, 3.6f, CENTRE)
            dots(color, 6.4f, 8, 1.35f, 0.9f, 0f)
            dots(color, 9.6f, 12, 0.8f, 0.6f, 0.5f)
        }
        GlassBlur.STRONG -> {
            drawCircle(color, 3.2f, CENTRE, alpha = 0.9f)
            dots(color, 6.2f, 8, 1.6f, 0.9f, 0f)
            dots(color, 9.2f, 12, 1.1f, 0.7f, 0.5f)
            dots(color, 11.4f, 16, 0.6f, 0.4f, 0f)
        }
    }
}

/** [count] dots of [size] round the centre at [radius], turned [turn] of a step. */
private fun DrawScope.dots(color: Color, radius: Float, count: Int, size: Float, alpha: Float, turn: Float) {
    for (i in 0 until count) {
        val a = 2f * PI.toFloat() * (i + turn) / count
        drawCircle(color, size, Offset(CENTRE.x + radius * cos(a), CENTRE.y + radius * sin(a)), alpha = alpha)
    }
}

private val PANE_AT = Offset(4.5f, 4.5f)
private val PANE = Size(15f, 15f)
private val PANE_CORNER = CornerRadius(3f)

/** Drops on the pane: x, y and radius; Soft shows the first ones, Milky all. */
private val DROPS = floatArrayOf(
    8.5f, 9f, 1.1f,
    14.5f, 8f, 0.8f,
    11.5f, 13.5f, 1.3f,
    15.5f, 15.5f, 0.9f,
    8f, 16f, 0.7f,
    16f, 11.8f, 0.6f,
    11f, 9.5f, 0.55f,
)
private const val SOFT_DROPS = 4

/** The frost: a clear pane with its glints for none; misted with a few drops; milky and beaded. */
private fun DrawScope.frostGlyph(frost: GlassFrost, color: Color, lines: WaterLines) {
    when (frost) {
        GlassFrost.OFF -> {
            drawLine(color, Offset(7.8f, 12f), Offset(12f, 7.8f), strokeWidth = lines.plain.width, cap = StrokeCap.Round)
            drawLine(color, Offset(10.5f, 15f), Offset(15f, 10.5f), strokeWidth = lines.plain.width, cap = StrokeCap.Round)
        }
        GlassFrost.SOFT -> {
            drawRoundRect(color, PANE_AT, PANE, PANE_CORNER, alpha = FROST_TINT * 0.7f)
            drops(color, SOFT_DROPS)
        }
        GlassFrost.MILKY -> {
            drawRoundRect(color, PANE_AT, PANE, PANE_CORNER, alpha = FROST_TINT * 1.7f)
            drops(color, DROPS.size / 3)
        }
    }
    drawRoundRect(color, PANE_AT, PANE, PANE_CORNER, style = lines.plain)
}

private fun DrawScope.drops(color: Color, count: Int) {
    for (i in 0 until count) drawCircle(color, DROPS[3 * i + 2], Offset(DROPS[3 * i], DROPS[3 * i + 1]))
}

private val PHONE_AT = Offset(6f, 2.5f)
private val PHONE = Size(12f, 19f)
private val PHONE_CORNER = CornerRadius(2.5f)
private val CAMERA = Offset(12f, 5f)

/** The wave's crest in the area glyphs, round the camera, and the band of frost riding under it. */
private const val CREST = 6f
private const val WAVE_BAND = 3f

/** Past the crest, out to beyond the phone's corners. */
private const val BEYOND = 12f

/**
 * Where the glass is, on a phone with the wave rolling out of its camera: frost still ahead of
 * the crest and a notification already sharp behind it (swim out); a frosted band riding under
 * it (wave); the whole screen frosted (screen).
 */
private fun DrawScope.areaGlyph(area: GlassArea, color: Color, lines: WaterLines) {
    // Inside the phone's outline, clear of its line.
    clipRect(PHONE_AT.x + 0.8f, PHONE_AT.y + 0.8f, PHONE_AT.x + PHONE.width - 0.8f, PHONE_AT.y + PHONE.height - 0.8f) {
        when (area) {
            GlassArea.REVEAL -> {
                drawCircle(color, CREST + BEYOND, CAMERA, alpha = FROST_TINT, style = lines.beyond)
                drawLine(color, Offset(10f, 7.6f), Offset(14f, 7.6f), strokeWidth = lines.plain.width, cap = StrokeCap.Round)
            }
            GlassArea.WAVE -> drawCircle(color, CREST - WAVE_BAND / 2f, CAMERA, alpha = FROST_TINT * 1.4f, style = lines.band)
            GlassArea.SCREEN -> drawRect(color, PHONE_AT, PHONE, alpha = FROST_TINT)
        }
        drawCircle(color, CREST, CAMERA, style = lines.bold)
    }
    drawRoundRect(color, PHONE_AT, PHONE, PHONE_CORNER, style = lines.plain)
    drawCircle(color, 0.9f, CAMERA)
}
