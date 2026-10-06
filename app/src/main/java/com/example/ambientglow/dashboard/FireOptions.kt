package com.example.ambientglow.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ambientglow.FireFlames
import com.example.ambientglow.FireSparks
import com.example.ambientglow.FirePalette
import com.example.ambientglow.FireWake
import com.example.ambientglow.GlowSettings
import com.example.ambientglow.R
import com.example.ambientglow.firePalette
import com.example.ambientglow.ui.components.OptionBody
import com.example.ambientglow.ui.components.OptionGroup

// ---------------------------------------------------------------------------------------------
// Fire's options, under the element picker. Every choice is a picture: the flames as a fire that
// grows tile by tile, the sparks as the app's sparkle, none to a shower, and what the front leaves
// as a burnt page, coals or a lone flame. The pictures burn in the message's fire, as Water's
// light in its colour. There is no colour to choose: the fire is the app's, as all the glow is.
// ---------------------------------------------------------------------------------------------

/**
 * Fire: the flames, the sparks and what the front leaves behind it, the pictures burning as a
 * message in [accent] would. What the front leaves isn't plain from its name: what the picked
 * one does, under it.
 */
@Composable
internal fun FireOptions(settings: GlowSettings, accent: Color, onEffect: (GlowSettings) -> Unit) {
    val fire = remember(accent) { FireInk(firePalette(accent)) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OptionGroup(stringResource(R.string.fire_flames)) {
            GlyphTiles(FireFlames.entries, settings.fireFlames, { onEffect(settings.copy(fireFlames = it)) }, ::FireLines) { flames, lit, lines ->
                flamesGlyph(flames, fire, lit, lines)
            }
        }
        OptionGroup(stringResource(R.string.fire_sparks)) {
            GlyphTiles(FireSparks.entries, settings.fireSparks, { onEffect(settings.copy(fireSparks = it)) }, ::FireLines) { sparks, lit, lines ->
                sparksGlyph(sparks, fire, lit, lines)
            }
        }
        OptionGroup(stringResource(R.string.fire_wake)) {
            GlyphTiles(FireWake.entries, settings.fireWake, { onEffect(settings.copy(fireWake = it)) }, ::FireLines) { wake, lit, lines ->
                wakeGlyph(wake, fire, lit, lines)
            }
            // On black there is no screen under the char: say what Burn comes to there.
            val onBlack = settings.arrival.onBlack
            OptionBody(settings.fireWake) { wake ->
                val body = stringResource(wake.body)
                if (wake == FireWake.BURN && onBlack) body + " " + stringResource(R.string.fire_burn_on_black) else body
            }
        }
    }
}

/** What the fire options are set to, in one sentence; sparks only when there are some. */
internal fun firePhrasing(settings: GlowSettings): Phrasing {
    // On a black screen Burn has no char to open, only its smouldering edge.
    val wake = if (settings.fireWake == FireWake.BURN && settings.arrival.onBlack) {
        R.string.fire_wake_burn_black_phrase
    } else {
        settings.fireWake.phrase
    }
    val parts = listOf(settings.fireFlames.phrase, wake)
    return if (settings.fireSparks == FireSparks.OFF) {
        Phrasing(R.string.fire_summary, parts)
    } else {
        Phrasing(R.string.fire_summary_sparks, parts + settings.fireSparks.phrase)
    }
}

/** The message's fire as the glyphs draw it: a flame's fill, hottest at its base, and its colours. */
@Immutable
private class FireInk(val palette: FirePalette) {
    /** On the flame's grid, tip to base. */
    val fill = palette.fill()
}

/** A flame's colours climbing from its base, on the glyph grid where the flame stands (y 2.5..21). */
private fun FirePalette.fill(): Brush = Brush.verticalGradient(
    0f to tip,
    0.3f to flare,
    0.55f to body,
    0.8f to hot,
    1f to core,
    startY = 2.5f,
    endY = 21f,
)

/** Fire's glyph strokes, built once per size: the plain line, and the same weight on flames drawn smaller. */
private class FireLines(unit: Float, line: Float) {
    val plain = glyphStroke(unit, line)
    val bold = glyphStroke(unit, line * 1.35f)
    val gentle = glyphStroke(unit, line / GENTLE_FLAME)
    val tall = glyphStroke(unit, line / TALL_FLAME)
    val small = glyphStroke(unit, line / SMALL_FLAME)
    val lone = glyphStroke(unit, line / LONE_FLAME)
    val struck = glyphStroke(unit, line / STRUCK_SPARKLE)
    val burn = glyphStroke(unit, line / BURN_FLAME)
}

private val FLAME = PathParser().parsePathString(FLAME_OUTLINE).toPath()
private val FLAME_INNER = PathParser().parsePathString(FLAME_CORE).toPath()
private val SPARKLE_PATH = PathParser().parsePathString(SPARKLE).toPath()

/** Where the flame glyphs stand on their 24-unit grid: the base they grow from. */
private val FLAME_BASE = Offset(12f, 21f)

private const val GENTLE_FLAME = 0.62f
private const val TALL_FLAME = 0.92f
private const val SMALL_FLAME = 0.42f
private const val SIDE_FLAME_OFFSET = 9.3f
private const val LONE_FLAME = 0.62f
private const val STRUCK_SPARKLE = 0.62f
private const val BURN_FLAME = 0.36f

/** Where the burning page's flame stands: on its edge's highest point. */
private val BURN_FLAME_AT = Offset(11.5f, 8.6f)

/**
 * The flame heights: a small flame, a tall one with its core, and the tall one between two small
 * ones. Chosen, they fill with the fire and take its outline.
 */
private fun DrawScope.flamesGlyph(flames: FireFlames, fire: FireInk, lit: Float, lines: FireLines) {
    val line = ink(lit, fire.palette.flare)
    when (flames) {
        FireFlames.GENTLE -> flame(GENTLE_FLAME, 0f, fire, lit, line, lines.gentle, core = false)
        FireFlames.BLAZE -> flame(TALL_FLAME, 0f, fire, lit, line, lines.tall, core = true)
        FireFlames.INFERNO -> {
            flame(SMALL_FLAME, -SIDE_FLAME_OFFSET, fire, lit, line, lines.small, core = false)
            flame(SMALL_FLAME, SIDE_FLAME_OFFSET, fire, lit, line, lines.small, core = false)
            flame(TALL_FLAME, 0f, fire, lit, line, lines.tall, core = true)
        }
    }
}

/** One flame at [k] of full size, standing on the base, moved [dx] grid units across: filled with the fire as [lit]. */
private fun DrawScope.flame(k: Float, dx: Float, fire: FireInk, lit: Float, line: Color, stroke: Stroke, core: Boolean) {
    translate(left = dx) {
        scale(k, pivot = FLAME_BASE) {
            if (lit > 0f) drawPath(FLAME, fire.fill, alpha = lit)
            drawPath(FLAME, line, style = stroke)
            if (core) drawPath(FLAME_INNER, line, style = stroke)
        }
    }
}

/** Sparkles round the grid for Few and Shower: centre x, y and size (of the 24-unit sparkle). */
private val FEW_SPARKS = floatArrayOf(9f, 14.5f, 0.5f, 16.5f, 7.5f, 0.32f)
private val SHOWER_SPARKS = floatArrayOf(
    6.5f, 15.5f, 0.4f,
    16.5f, 17f, 0.28f,
    16f, 7f, 0.42f,
    7f, 6f, 0.24f,
    11.5f, 11f, 0.18f,
)

/**
 * The sparks as the app's own sparkle, as the fire throws them: one struck through for none, a
 * pair, a shower. Chosen, they light in the fire's hottest colours.
 */
private fun DrawScope.sparksGlyph(sparks: FireSparks, fire: FireInk, lit: Float, lines: FireLines) {
    val glow = ink(lit, fire.palette.hot)
    when (sparks) {
        FireSparks.OFF -> {
            sparkle(12f, 12f, STRUCK_SPARKLE, glow, lines.struck)
            drawLine(ink(lit, fire.palette.flare), Offset(5f, 19f), Offset(19f, 5f), strokeWidth = lines.plain.width, cap = StrokeCap.Round)
        }
        FireSparks.FEW -> sparkles(FEW_SPARKS, glow)
        FireSparks.SHOWER -> sparkles(SHOWER_SPARKS, glow)
    }
}

private fun DrawScope.sparkles(spots: FloatArray, color: Color) {
    for (i in spots.indices step 3) sparkle(spots[i], spots[i + 1], spots[i + 2], color, null)
}

/** One sparkle centred on [x], [y] at [k] of the grid: filled, or outlined with [outline] (built for [k]). */
private fun DrawScope.sparkle(x: Float, y: Float, k: Float, color: Color, outline: Stroke?) {
    translate(x - 12f, y - 12f) {
        scale(k, pivot = Offset(12f, 12f)) {
            if (outline == null) drawPath(SPARKLE_PATH, color) else drawPath(SPARKLE_PATH, color, style = outline)
        }
    }
}

private val BURNT_PAGE = PathParser()
    .parsePathString("M6 21 L6 11 L8 9 L9.5 11 L11.5 8 L13 10.5 L15 8.5 L16.5 10.5 L18 9.5 L18 21 Z").toPath()
private val BURNT_EDGE = PathParser()
    .parsePathString("M6 11 L8 9 L9.5 11 L11.5 8 L13 10.5 L15 8.5 L16.5 10.5 L18 9.5").toPath()
private val COALS = PathParser()
    .parsePathString("M3 20 C3 16.5 5.5 14.5 8.5 15 C9.5 12 13.5 11 15.5 13.5 C18.5 12.8 21 15.5 21 20 Z").toPath()
private val COAL_CRACKS = PathParser()
    .parsePathString("M8.5 15 L10 17.3 L9 20 M15.5 13.5 L14 16.5 L15.3 20").toPath()
private val COAL_HEAT = PathParser()
    .parsePathString("M8 10 C9 8.8 7 7.4 8 5.8 M14.5 9.5 C15.5 8.3 13.5 6.9 14.5 5.3").toPath()

/**
 * What the front leaves: a page whose top has burned away over the notifications under it, its
 * edge glowing and a flame still licking at it; coals with glowing cracks, heat rising off them; a lone flame on clean ground.
 */
private fun DrawScope.wakeGlyph(wake: FireWake, fire: FireInk, lit: Float, lines: FireLines) {
    val line = ink(lit, fire.palette.flare)
    val glow = ink(lit, fire.palette.hot)
    when (wake) {
        FireWake.BURN -> {
            drawPath(BURNT_PAGE, line, style = lines.plain)
            drawLine(line, Offset(8.5f, 14.5f), Offset(15.5f, 14.5f), strokeWidth = lines.plain.width, cap = StrokeCap.Round)
            drawLine(line, Offset(8.5f, 17.5f), Offset(13f, 17.5f), strokeWidth = lines.plain.width, cap = StrokeCap.Round)
            drawPath(BURNT_EDGE, glow, style = lines.bold)
            // A flame still licking at its highest point.
            translate(left = BURN_FLAME_AT.x - FLAME_BASE.x, top = BURN_FLAME_AT.y - FLAME_BASE.y) {
                flame(BURN_FLAME, 0f, fire, lit, glow, lines.burn, core = false)
            }
        }
        FireWake.COALS -> {
            if (lit > 0f) drawPath(COALS, fire.palette.ember, alpha = 0.6f * lit)
            drawPath(COALS, line, style = lines.plain)
            drawPath(COAL_CRACKS, glow, style = lines.plain)
            drawPath(COAL_HEAT, line, alpha = 0.7f, style = lines.plain)
        }
        FireWake.CLEAN -> {
            flame(LONE_FLAME, 0f, fire, lit, line, lines.lone, core = false)
            drawLine(line, Offset(5f, 21f), Offset(19f, 21f), strokeWidth = lines.plain.width, cap = StrokeCap.Round)
        }
    }
}
