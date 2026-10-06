package com.example.ambientglow.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ambientglow.AirCarry
import com.example.ambientglow.AirColor
import com.example.ambientglow.AirFlow
import com.example.ambientglow.AirGust
import com.example.ambientglow.AirPalette
import com.example.ambientglow.GlassBlur
import com.example.ambientglow.GlowSettings
import com.example.ambientglow.R
import com.example.ambientglow.airPalette
import com.example.ambientglow.ui.components.ChipLabel
import com.example.ambientglow.ui.components.Disclosure
import com.example.ambientglow.ui.components.OptionBody
import com.example.ambientglow.ui.components.OptionGroup
import com.example.ambientglow.ui.components.SelectionRow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Air's options, under the element picker, as pictures like Fire's: the strength as more and
// longer lines of wind, the lines as speed lines, a curl or a whirl, the colours as chips each
// holding a curl of that wind, what it carries as dust, petals or leaves, and the blur under it
// as Water's is drawn. The pictures blow in the wind chosen, and pop as the blade passes under them.
// ---------------------------------------------------------------------------------------------

private val SWATCH_SIZE = 14.dp

/** How far an unchosen colour's swatch fades, so the chosen wind is the one that shows. */
private const val SWATCH_REST = 0.5f

/**
 * Air: the gust's strength, its lines, their colour, what it carries and how it blurs the screen
 * it passes. The colour chips show each wind as a message in [accent] would blow it, and the
 * other pictures blow in the wind chosen. Lines and colour aren't plain from their names: what
 * the picked one does, under it. On a black screen there is nothing to blur, so the blur isn't
 * offered there.
 */
@Composable
internal fun AirOptions(settings: GlowSettings, accent: Color, onEffect: (GlowSettings) -> Unit) {
    val wind = remember(settings.airColor, accent) { airPalette(settings.airColor, accent) }
    // Each group carries its gap above it, so the blur's comes and goes with it.
    Column {
        OptionGroup(stringResource(R.string.air_gust)) {
            GlyphTiles(AirGust.entries, settings.airGust, { onEffect(settings.copy(airGust = it)) }, ::AirLines) { gust, lit, lines ->
                gustGlyph(gust, wind, lit, lines)
            }
        }
        Spacer(Modifier.height(GROUP_GAP))
        OptionGroup(stringResource(R.string.air_flow)) {
            GlyphTiles(AirFlow.entries, settings.airFlow, { onEffect(settings.copy(airFlow = it)) }, ::AirLines) { flow, lit, lines ->
                flowGlyph(flow, wind, lit, lines)
            }
            OptionBody(settings.airFlow) { stringResource(it.body) }
        }
        Spacer(Modifier.height(GROUP_GAP))
        OptionGroup(stringResource(R.string.air_color)) {
            AirColorPicker(settings.airColor, accent) { onEffect(settings.copy(airColor = it)) }
            OptionBody(settings.airColor) { stringResource(it.body) }
        }
        Spacer(Modifier.height(GROUP_GAP))
        OptionGroup(stringResource(R.string.air_carry)) {
            GlyphTiles(AirCarry.entries, settings.airCarry, { onEffect(settings.copy(airCarry = it)) }, ::AirLines) { carry, lit, lines ->
                carryGlyph(carry, wind, lit, lines)
            }
        }
        Disclosure(visible = !settings.arrival.onBlack) {
            Box(Modifier.padding(top = GROUP_GAP)) {
                OptionGroup(stringResource(R.string.air_blur)) {
                    GlyphTiles(GlassBlur.entries, settings.airBlur, { onEffect(settings.copy(airBlur = it)) }, ::AirLines) { blur, lit, lines ->
                        blurGlyph(blur, ink(lit, wind.glow), lines.plain)
                    }
                    OptionBody(settings.airBlur) { blur ->
                        stringResource(if (blur == GlassBlur.OFF) R.string.air_blur_off_body else R.string.air_blur_body)
                    }
                }
            }
        }
    }
}

private val GROUP_GAP = 14.dp

/**
 * What the air options are set to, in one sentence: what it carries only when it carries
 * something, and the blur only when there is one and a screen under it to blur.
 */
internal fun airPhrasing(settings: GlowSettings): Phrasing {
    val parts = listOf(settings.airGust.phrase, settings.airFlow.phrase, settings.airColor.phrase)
    val carry = settings.airCarry.phrase.takeIf { settings.airCarry != AirCarry.NONE }
    val blur = settings.airBlur.phrase.takeIf { settings.airBlur != GlassBlur.OFF && !settings.arrival.onBlack }
    return when {
        carry != null && blur != null -> Phrasing(R.string.air_summary_carry_blur, parts + carry + blur)
        carry != null -> Phrasing(R.string.air_summary_carry, parts + carry)
        blur != null -> Phrasing(R.string.air_summary_blur, parts + blur)
        else -> Phrasing(R.string.air_summary, parts)
    }
}

/** Air's glyph strokes, built once per size. */
private class AirLines(unit: Float, line: Float) {
    val plain = glyphStroke(unit, line)
    val petal = glyphStroke(unit, line / PETAL_SCALE)
    val small = glyphStroke(unit, line / SMALL_PETAL_SCALE)
    val leaf = glyphStroke(unit, line / LEAF_SCALE)
}

/**
 * A line of wind on the glyph grid: straight from [x0] to [x1] along [y], then curling up (or
 * down) over a half turn of [rho] and a tighter turn inside it, as the effect's lines curl.
 */
private fun curlLine(x0: Float, x1: Float, y: Float, rho: Float, up: Boolean = true): Path = Path().apply {
    val inner = rho * 0.55f
    moveTo(x0, y)
    lineTo(x1, y)
    if (up) {
        arcTo(Rect(x1 - rho, y - 2f * rho, x1 + rho, y), 90f, -180f, false)
        arcTo(Rect(x1 - inner, y - 2f * rho, x1 + inner, y - 2f * rho + 2f * inner), 270f, -160f, false)
    } else {
        arcTo(Rect(x1 - rho, y, x1 + rho, y + 2f * rho), 270f, 180f, false)
        arcTo(Rect(x1 - inner, y + 2f * rho - 2f * inner, x1 + inner, y + 2f * rho), 90f, 160f, false)
    }
}

private fun line(x0: Float, x1: Float, y: Float): Path = Path().apply {
    moveTo(x0, y)
    lineTo(x1, y)
}

/** The strengths: a short curl; a curl and a line; a long curl, a longer line and a curl turning down. */
private val BREEZE = listOf(curlLine(5f, 13f, 15f, 2.8f))
private val GUST = listOf(curlLine(3.5f, 13f, 11.5f, 3.2f), line(6.5f, 18f, 16.5f))
private val GALE = listOf(
    curlLine(3f, 14f, 8.5f, 3f),
    line(3f, 20.5f, 13f),
    curlLine(6f, 15.5f, 17.5f, 2.4f, up = false),
)

/** Speed lines bursting out of a camera at the top: long ones down, short ones to the sides. */
private val CAMERA = Offset(12f, 4.5f)
private val STREAKS = listOf(
    Offset(12f, 8f) to Offset(12f, 20.5f),
    Offset(9.4f, 7.6f) to Offset(5f, 18f),
    Offset(14.6f, 7.6f) to Offset(19f, 18f),
    Offset(8.4f, 5.6f) to Offset(4f, 8.6f),
    Offset(15.6f, 5.6f) to Offset(20f, 8.6f),
)

/** One line sweeping into a big curl, and a small one curling the other way under it, as wind is drawn. */
private val CURLS = listOf(curlLine(3f, 11f, 15f, 5f), curlLine(8f, 17f, 19f, 1.8f, up = false))

/** A whirl: a spiral running out from the centre two and a quarter turns, anticlockwise as the wind turns. */
private val VORTEX = Path().apply {
    val steps = 54
    for (j in 0..steps) {
        val t = j / steps.toFloat()
        val a = -2.25f * 2f * PI.toFloat() * t
        val r = 1f + 8.4f * t
        val x = 12f + r * cos(a)
        val y = 12f + r * sin(a)
        if (j == 0) moveTo(x, y) else lineTo(x, y)
    }
}

/** The strength as more and longer lines of wind; chosen, they take the wind's glow. */
private fun DrawScope.gustGlyph(gust: AirGust, wind: AirPalette, lit: Float, lines: AirLines) {
    val ink = ink(lit, wind.glow)
    val paths = when (gust) {
        AirGust.BREEZE -> BREEZE
        AirGust.GUST -> GUST
        AirGust.GALE -> GALE
    }
    paths.forEach { drawPath(it, ink, style = lines.plain) }
}

/** The lines: speed lines out of a camera, a curl, a whirl. */
private fun DrawScope.flowGlyph(flow: AirFlow, wind: AirPalette, lit: Float, lines: AirLines) {
    val ink = ink(lit, wind.glow)
    when (flow) {
        AirFlow.STREAKS -> {
            drawCircle(ink, 1.3f, CAMERA)
            STREAKS.forEach { (from, to) -> drawLine(ink, from, to, strokeWidth = lines.plain.width, cap = StrokeCap.Round) }
        }
        AirFlow.CURLS -> CURLS.forEach { drawPath(it, ink, style = lines.plain) }
        AirFlow.VORTEX -> drawPath(VORTEX, ink, style = lines.plain)
    }
}

/** A petal on the grid: an oval narrowing to its base at the bottom, notched at its tip. */
private val PETAL = PathParser()
    .parsePathString("M12 20.5 C7.6 17.4 7.2 10.8 9.8 6.6 L12 8.6 L14.2 6.6 C16.8 10.8 16.4 17.4 12 20.5 Z").toPath()

/** A leaf on the grid, pointed at both ends, and its rib. */
private val LEAF = PathParser().parsePathString("M12 3 C17.2 7 17.2 15.5 12 21 C6.8 15.5 6.8 7 12 3 Z").toPath()
private val LEAF_RIB = PathParser().parsePathString("M12 6.5 L12 21").toPath()

/** Where the petals and leaves lie, how big and how turned. */
private const val PETAL_SCALE = 0.58f
private const val SMALL_PETAL_SCALE = 0.42f
private const val LEAF_SCALE = 0.62f
private val PETAL_AT = Offset(8.5f, 13.5f)
private val SMALL_PETAL_AT = Offset(16.5f, 8f)
private val LEAF_AT = Offset(11f, 12.5f)
private val CENTRE = Offset(12f, 12f)

/** Dust as glints of different sizes along a drift: x, y and radius. */
private val DUST = floatArrayOf(
    5f, 17.5f, 1.4f,
    9.5f, 13f, 0.9f,
    13.5f, 16f, 1.2f,
    16f, 9f, 1.5f,
    19.5f, 13.5f, 0.8f,
    10f, 7f, 0.7f,
)

/**
 * What the gust carries: a petal struck through for none; dust; petals; a leaf. Chosen, they
 * fill with their colours in the wind chosen.
 */
private fun DrawScope.carryGlyph(carry: AirCarry, wind: AirPalette, lit: Float, lines: AirLines) {
    when (carry) {
        AirCarry.NONE -> {
            val ink = ink(lit, wind.glow)
            item(PETAL, null, CENTRE, PETAL_SCALE, 0f, ink, lit, lines.petal)
            drawLine(ink, Offset(5f, 19f), Offset(19f, 5f), strokeWidth = lines.plain.width, cap = StrokeCap.Round)
        }
        AirCarry.DUST -> {
            val ink = ink(lit, wind.core)
            for (i in DUST.indices step 3) drawCircle(ink, DUST[i + 2], Offset(DUST[i], DUST[i + 1]))
        }
        AirCarry.PETALS -> {
            val ink = ink(lit, wind.petalShade)
            item(PETAL, wind.petal, PETAL_AT, PETAL_SCALE, -35f, ink, lit, lines.petal)
            item(PETAL, wind.petal, SMALL_PETAL_AT, SMALL_PETAL_SCALE, 50f, ink, lit, lines.small)
        }
        AirCarry.LEAVES -> {
            val ink = ink(lit, wind.leafShade)
            item(LEAF, wind.leaf, LEAF_AT, LEAF_SCALE, 40f, ink, lit, lines.leaf, rib = LEAF_RIB)
        }
    }
}

/** One petal or leaf centred on [at] at [k] of the grid, turned [degrees]: outlined, and filled with [fill] as [lit]. */
private fun DrawScope.item(
    shape: Path,
    fill: Color?,
    at: Offset,
    k: Float,
    degrees: Float,
    ink: Color,
    lit: Float,
    outline: Stroke,
    rib: Path? = null,
) {
    translate(at.x - CENTRE.x, at.y - CENTRE.y) {
        rotate(degrees, CENTRE) {
            scale(k, CENTRE) {
                if (fill != null && lit > 0f) drawPath(shape, fill, alpha = lit)
                drawPath(shape, ink, style = outline)
                if (rib != null) drawPath(rib, ink, style = outline)
            }
        }
    }
}

/** One chip per wind colour, each with a curl of that wind. */
@Composable
private fun AirColorPicker(selected: AirColor, accent: Color, onSelect: (AirColor) -> Unit) {
    val inks = remember(accent) { AirColor.entries.map { WindInk(airPalette(it, accent)) } }
    SelectionRow(
        count = AirColor.entries.size,
        selected = selected.ordinal,
        onSelect = { onSelect(AirColor.entries[it]) },
        inset = 6.dp,
    ) { index, lit ->
        CurlSwatch(inks[index], lit)
        Spacer(Modifier.width(6.dp))
        ChipLabel(stringResource(AirColor.entries[index].label), lit, compact = true)
    }
}

/** A wind as the swatch draws it: its curl, from its tail's colour to its white head. */
@Immutable
private class WindInk(palette: AirPalette) {
    val brush: Brush = Brush.horizontalGradient(
        0f to palette.shade,
        0.45f to palette.glow,
        0.8f to palette.body,
        1f to palette.core,
        startX = SWATCH_CURL_FROM,
        endX = SWATCH_CURL_TO,
    )
}

/** The swatch's curl on the grid, and the span of x it runs over (the gradient's). */
private const val SWATCH_CURL_FROM = 2f
private const val SWATCH_CURL_TO = 17f
private val SWATCH_CURL = curlLine(SWATCH_CURL_FROM, 13f, 17f, 4.2f)

/** A curl of wind, brightening to full and popping as [lit] goes 0 → 1; read in draw. */
@Composable
private fun CurlSwatch(ink: WindInk, lit: () -> Float) {
    Spacer(
        Modifier
            .size(SWATCH_SIZE)
            .pop(lit)
            .drawWithCache {
                // The curl spans x 2..17.2 and y 8.6..17 of its grid: fitted to the swatch, its line as thick as a glyph's.
                val k = size.width / 16f
                val stroke = glyphStroke(k, 2.dp.toPx())
                onDrawBehind {
                    translate(-1.6f * k, -4.8f * k) {
                        scale(k, pivot = Offset.Zero) {
                            drawPath(SWATCH_CURL, ink.brush, alpha = SWATCH_REST + (1f - SWATCH_REST) * lit(), style = stroke)
                        }
                    }
                }
            },
    )
}
