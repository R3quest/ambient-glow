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
import app.lumement.AfterBright
import app.lumement.AodMode
import app.lumement.BrightFor
import app.lumement.GlowSettings
import app.lumement.R
import app.lumement.ui.components.CardDivider
import app.lumement.ui.components.ChipRow
import app.lumement.ui.components.Disclosure
import app.lumement.ui.components.LinkRow
import app.lumement.ui.components.OptionBody
import app.lumement.ui.components.OptionNote
import app.lumement.ui.components.RowBleed
import app.lumement.ui.components.SectionLabel
import app.lumement.ui.components.rowBleed
import app.lumement.ui.components.glowCard
import app.lumement.ui.theme.GlowPalette
import app.lumement.ui.theme.GlowShapes

// ---------------------------------------------------------------------------------------------
// The SCREEN tab's second section: how long the LED holds the screen on, then where it goes.
// ---------------------------------------------------------------------------------------------

/**
 * What the LED does while a message waits: first how long it holds the screen on after a new
 * message ([BrightFor]), the one choice every phone has. Then, on One UI ([aod]; elsewhere the LED
 * goes out), where it goes after that ([AfterBright]), and the always-on display it goes onto.
 */
@Composable
internal fun WaitingCard(
    settings: GlowSettings,
    aod: AodMode?,
    shieldOn: Boolean,
    onAod: () -> Unit,
    onBrightFor: (BrightFor) -> Unit,
    onAfterBright: (AfterBright) -> Unit,
) {
    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.waiting_bright_label), GlowPalette.TextMuted)
            ChipRow(BrightFor.entries, settings.brightFor, onBrightFor)
            OptionBody(settings.brightFor == BrightFor.OFF) { off ->
                stringResource(if (off) R.string.waiting_bright_off_body else R.string.waiting_bright_body)
            }
        }
        if (aod == null) {
            // No always-on display the LED is known to go onto: it goes out.
            OptionNote(stringResource(R.string.waiting_then_off_body))
        } else {
            CardDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.waiting_then_label), GlowPalette.TextMuted)
                ChipRow(AfterBright.entries, settings.afterBright, onAfterBright)
                OptionBody(settings.afterBright) { after ->
                    stringResource(if (after == AfterBright.AOD) R.string.waiting_then_aod_body else R.string.waiting_then_off_body)
                }
            }
            CardDivider()
            AodRows(aod, shieldOn, onAod)
        }
    }
}

/**
 * One UI's always-on display, laid out as the dashboard's other cards are: its label, then a row
 * with its mode and what the LED does in it, lit in cyan when on, and a way to the Settings page
 * where it is changed. Apps can't switch it (Samsung's own AOD page needs a signature permission),
 * so no switch that wouldn't flip. The LED is drawn on it in Lumement effects' window
 * ([shieldOn]), which the screen card asks for while it is off.
 *
 * Under a divider, until it is set to Show for new notifications, what that mode would bring from
 * the one set now, all in quiet grey as it isn't needed: a tag that says it saves battery and is
 * optional, then the line, One UI's name for the mode a shade brighter, to look for there. It
 * leads to the same page.
 */
@Composable
private fun AodRows(aod: AodMode, shieldOn: Boolean, onAod: () -> Unit) {
    val aodOn = aod != AodMode.OFF
    val action = stringResource(R.string.setup_aod_grant)
    val tip = when (aod) {
        AodMode.OFF -> R.string.waiting_tip_off
        AodMode.ON -> R.string.waiting_tip_on
        AodMode.NEW_NOTIFICATIONS -> null
    }
    // Kept while the tip folds away, so its text doesn't go before its space does.
    var lastTip by remember { mutableIntStateOf(R.string.waiting_tip_off) }
    SideEffect { if (tip != null) lastTip = tip }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.waiting_aod_label), GlowPalette.TextMuted)
        LinkRow(
            title = stringResource(
                when (aod) {
                    AodMode.OFF -> R.string.waiting_aod_off
                    AodMode.ON -> R.string.waiting_aod_on
                    AodMode.NEW_NOTIFICATIONS -> R.string.waiting_aod_new
                },
            ),
            summary = stringResource(
                when {
                    !aodOn -> R.string.waiting_led_screen
                    !shieldOn -> R.string.waiting_led_needs_shield
                    else -> R.string.waiting_led_aod
                },
            ),
            action = action,
            onClick = onAod,
            leading = { StatusTile(aodOn) { tint, line -> aodPhone(tint, line) } },
        )
        Disclosure(visible = tip != null) {
            Column {
                CardDivider()
                Column(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .rowBleed()
                        .clip(GlowShapes.Tile)
                        .clickable(role = Role.Button, onClickLabel = action, onClick = onAod)
                        // None below: the card's own padding ends it, as it starts it above the label.
                        .padding(start = RowBleed, end = RowBleed, top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(12.dp)) {
                            val line = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            battery(GlowPalette.TextMuted, line)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.waiting_tip_tag),
                            style = MaterialTheme.typography.labelSmall,
                            color = GlowPalette.TextMuted,
                        )
                    }
                    Tip(tip ?: lastTip)
                }
            }
        }
    }
}

/** What the mode would bring, its One UI name a shade brighter. */
@Composable
private fun Tip(@StringRes text: Int) {
    val mode = stringResource(R.string.waiting_tip_mode)
    val tip = stringResource(text, mode)
    val styled = remember(tip, mode) {
        buildAnnotatedString {
            append(tip)
            val at = tip.indexOf(mode)
            if (at >= 0) addStyle(SpanStyle(color = GlowPalette.TextPrimary), at, at + mode.length)
        }
    }
    Text(text = styled, style = MaterialTheme.typography.bodySmall, color = GlowPalette.TextMuted)
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
