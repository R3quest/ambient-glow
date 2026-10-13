package app.lumement.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import app.lumement.AodMode
import app.lumement.R
import app.lumement.ui.components.Disclosure
import app.lumement.ui.components.LinkRow
import app.lumement.ui.components.RowBleed
import app.lumement.ui.components.rowBleed
import app.lumement.ui.components.glowCard
import app.lumement.ui.theme.GlowPalette
import app.lumement.ui.theme.GlowShapes

// ---------------------------------------------------------------------------------------------
// The SCREEN tab's second section: what the phone lets the LED use while a message waits.
// ---------------------------------------------------------------------------------------------

/**
 * One UI's always-on display ([aod]; the card is left out on other phones), the one system
 * setting here that is a choice: off, the LED keeps the screen on; on, the LED glows on it. Lit in
 * cyan when on, what that means for the LED, and a way to the Settings page where it is changed:
 * apps can't switch it (Samsung's own AOD page needs a signature permission), so no switch that
 * wouldn't flip. The LED is drawn on it in Lumement effects' window ([shieldOn]), which the screen
 * card asks for while it is off.
 *
 * Under the row, until it is set to Show for new notifications, a quiet footnote says what that
 * mode would bring from the one set now, on the row's grid so the two read as one. Grey, as it
 * isn't needed, with only One UI's name for the mode in cyan, to look for there; it leads to the
 * same page.
 */
@Composable
internal fun WaitingCard(aod: AodMode, shieldOn: Boolean, onAod: () -> Unit) {
    val aodOn = aod != AodMode.OFF
    val action = stringResource(R.string.setup_aod_grant)
    val tip = when (aod) {
        AodMode.OFF -> R.string.waiting_tip_off
        AodMode.ON -> R.string.waiting_tip_on
        AodMode.NEW_NOTIFICATIONS -> null
    }
    // Kept while the footnote folds away, so its text doesn't go before its space does.
    var lastTip by remember { mutableIntStateOf(R.string.waiting_tip_off) }
    SideEffect { if (tip != null) lastTip = tip }
    Column(Modifier.glowCard(vertical = 10.dp)) {
        LinkRow(
            title = stringResource(R.string.waiting_aod_title),
            summary = stringResource(
                when {
                    !aodOn -> R.string.waiting_aod_off
                    !shieldOn -> R.string.waiting_aod_needs_shield
                    aod == AodMode.NEW_NOTIFICATIONS -> R.string.waiting_aod_new
                    else -> R.string.waiting_aod_on
                },
            ),
            action = action,
            onClick = onAod,
            leading = { StatusTile(aodOn) { tint, line -> aodPhone(tint, line) } },
        )
        // On the row's grid: the divider from its text, the mark under its tile, the text in line.
        Disclosure(visible = tip != null) {
            Column {
                Box(
                    Modifier
                        .padding(start = TILE + TILE_GAP, top = 2.dp, bottom = 2.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(GlowPalette.OutlineSoft),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .rowBleed()
                        .clip(GlowShapes.Tile)
                        .clickable(role = Role.Button, onClickLabel = action, onClick = onAod)
                        .padding(horizontal = RowBleed, vertical = 10.dp),
                ) {
                    // On the first line's middle.
                    Box(Modifier.width(TILE).padding(top = 3.dp), contentAlignment = Alignment.TopCenter) {
                        Canvas(Modifier.size(12.dp)) {
                            val line = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            battery(GlowPalette.TextMuted, line)
                        }
                    }
                    Spacer(Modifier.width(TILE_GAP))
                    Tip(tip ?: lastTip)
                }
            }
        }
    }
}

/** The footnote: that it is optional, then what the mode would bring, its One UI name in cyan. */
@Composable
private fun Tip(@StringRes text: Int) {
    val mode = stringResource(R.string.waiting_tip_mode)
    val tip = stringResource(text, mode)
    val styled = remember(tip, mode) {
        buildAnnotatedString {
            append(tip)
            val at = tip.indexOf(mode)
            if (at >= 0) addStyle(SpanStyle(color = GlowPalette.Cyan), at, at + mode.length)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.waiting_tip_title),
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextMuted,
        )
        Text(text = styled, style = MaterialTheme.typography.bodySmall, color = GlowPalette.TextFaint)
    }
}

private val TILE = 36.dp
private val TILE_GAP = 14.dp

/** A row's mark in a small tile: lit in cyan, edged in it, when [on]; faint when off. */
@Composable
private fun StatusTile(on: Boolean, glyph: DrawScope.(tint: Color, line: Stroke) -> Unit) {
    val tint = if (on) GlowPalette.Cyan else GlowPalette.TextFaint
    Box(
        Modifier
            .size(TILE)
            .clip(GlowShapes.Tile)
            .background(if (on) GlowPalette.Cyan.copy(alpha = 0.08f) else GlowPalette.SurfaceRaised)
            .border(1.dp, if (on) GlowPalette.Cyan.copy(alpha = 0.5f) else GlowPalette.OutlineSoft, GlowShapes.Tile),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            glyph(tint, Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    Spacer(Modifier.width(TILE_GAP))
}

/** A phone dozing: the time across its dark screen, and the LED's dot above it. */
private fun DrawScope.aodPhone(tint: Color, line: Stroke) {
    val (w, h) = size
    drawRoundRect(tint, Offset(w * 0.24f, h * 0.04f), Size(w * 0.52f, h * 0.92f), CornerRadius(w * 0.12f), style = line)
    drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.5f, h * 0.2f))
    drawLine(tint, Offset(w * 0.38f, h * 0.5f), Offset(w * 0.62f, h * 0.5f), line.width, StrokeCap.Round)
    // The date under it, thinner and fainter.
    val date = h * 0.63f
    drawLine(
        tint.copy(alpha = 0.5f),
        Offset(w * 0.42f, date),
        Offset(w * 0.58f, date),
        line.width * 0.75f,
        StrokeCap.Round,
    )
}
