package app.lumement.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lumement.Labeled
import app.lumement.ui.components.ChipLabel
import app.lumement.ui.components.SelectionRow
import app.lumement.ui.theme.GlowPalette

// ---------------------------------------------------------------------------------------------
// Options as pictures: a row of tiles, each a glyph over its name, on the element glyphs' 24-unit
// grid and line weight. A glyph lights as the blade arrives under it and swells a little as it
// passes. The elements' options (Water's glass, Fire's flames) are built from these.
// ---------------------------------------------------------------------------------------------

private val TILE_HEIGHT = 60.dp
private val GLYPH_SIZE = 24.dp

/** The glyphs' line: 1.6 dp, as the element glyphs'. */
private val GLYPH_LINE = 1.6.dp

/** How much a glyph swells as the blade passes under it, at the middle of the slide. */
private const val POP = 0.14f

/**
 * One tile per option: its glyph over its name. [lines] builds the strokes a set of glyphs uses,
 * once per size, from a grid unit and the line weight in px; [glyph] draws one option's picture on
 * the 24-unit grid, [lit] 0..1 as the blade arrives.
 */
@Composable
internal fun <T : Labeled, L> GlyphTiles(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    lines: (unit: Float, line: Float) -> L,
    glyph: DrawScope.(option: T, lit: Float, lines: L) -> Unit,
) {
    SelectionRow(
        count = options.size,
        selected = options.indexOf(selected),
        onSelect = { onSelect(options[it]) },
        height = TILE_HEIGHT,
        inset = 2.dp,
    ) { index, lit ->
        val option = options[index]
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TileGlyph(lit, lines) { l, built -> glyph(option, l, built) }
            ChipLabel(stringResource(option.label), lit, compact = true)
        }
    }
}

/** A glyph that pops as [lit] passes; [lit] is read in the layer and draw phases, so a slide never recomposes. */
@Composable
private fun <L> TileGlyph(lit: () -> Float, lines: (unit: Float, line: Float) -> L, draw: DrawScope.(lit: Float, lines: L) -> Unit) {
    Spacer(
        Modifier
            .size(GLYPH_SIZE)
            .pop(lit)
            .drawWithCache {
                val unit = size.minDimension / 24f
                val built = lines(unit, GLYPH_LINE.toPx())
                onDrawBehind {
                    scale(unit, pivot = Offset.Zero) { draw(lit(), built) }
                }
            },
    )
}

/** Swells a little as [lit] passes from 0 to 1 (or back), at most at the middle, and settles. */
internal fun Modifier.pop(lit: () -> Float): Modifier = graphicsLayer {
    val l = lit()
    val pop = 1f + POP * 4f * l * (1f - l)
    scaleX = pop
    scaleY = pop
}

/** A round-capped glyph stroke [width] px wide, on a grid of [unit] px. */
internal fun glyphStroke(unit: Float, width: Float) = Stroke(width = width / unit, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** A glyph's colour: muted at rest, [color] once chosen. */
internal fun ink(lit: Float, color: Color) = lerp(GlowPalette.TextMuted, color, lit)
