package app.lumement.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import app.lumement.ui.components.SectionLabel
import app.lumement.ui.components.glowCard
import app.lumement.ui.theme.GlowPalette
import app.lumement.ui.theme.GlowShapes

// ---------------------------------------------------------------------------------------------
// The SCREEN tab's second section: how long the LED holds the screen on, then where it goes.
// ---------------------------------------------------------------------------------------------

/**
 * Whether the LED can go onto One UI's always-on display once its bright minutes are over
 * ([AfterBright.AOD]), and if not, the one thing missing: first the always-on display itself, then
 * Lumement effects, whose window the LED is drawn in. On, but not shown for new notifications, it
 * works and only costs the always-on display's own battery while nothing waits.
 */
internal enum class AodReadiness(@StringRes val title: Int, @StringRes val body: Int, val ready: Boolean) {
    READY(R.string.then_ready_title, R.string.then_ready_body, ready = true),
    ALWAYS_ON(R.string.then_always_on_title, R.string.then_always_on_body, ready = true),
    AOD_OFF(R.string.then_aod_off_title, R.string.then_aod_off_body, ready = false),
    NEEDS_SHIELD(R.string.then_needs_shield_title, R.string.then_needs_shield_body, ready = false),
}

internal fun aodReadiness(aod: AodMode, shieldOn: Boolean): AodReadiness = when {
    aod == AodMode.OFF -> AodReadiness.AOD_OFF
    !shieldOn -> AodReadiness.NEEDS_SHIELD
    aod == AodMode.NEW_NOTIFICATIONS -> AodReadiness.READY
    else -> AodReadiness.ALWAYS_ON
}

/**
 * What the LED does while a message waits: first how long it holds the screen on after a new
 * message ([BrightFor]), the one choice every phone has. Then, on One UI ([aod]; elsewhere the LED
 * goes out), where it goes after that ([AfterBright]).
 */
@Composable
internal fun WaitingCard(
    settings: GlowSettings,
    aod: AodMode?,
    shieldOn: Boolean,
    onAod: () -> Unit,
    onShield: () -> Unit,
    onBrightFor: (BrightFor) -> Unit,
    onAfterBright: (AfterBright) -> Unit,
) {
    val noBright = settings.brightFor == BrightFor.OFF
    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.waiting_bright_label), GlowPalette.TextMuted, heading = true)
            ChipRow(BrightFor.entries, settings.brightFor, onBrightFor)
            OptionBody(noBright) { off ->
                stringResource(if (off) R.string.waiting_bright_off_body else R.string.waiting_bright_body)
            }
        }
        if (aod == null) {
            // Away from One UI the LED goes onto an always-on display where the phone shows one, as
            // on One UI, but how each phone's behaves is unmeasured: said for either way.
            OptionNote(stringResource(if (noBright) R.string.waiting_then_stock_never_body else R.string.waiting_then_stock_body))
        } else {
            CardDivider()
            ThenSection(settings.afterBright, noBright, aodReadiness(aod, shieldOn), onAod, onShield, onAfterBright)
        }
    }
}

/**
 * Where the LED goes once its bright minutes are over. On the always-on display, a row right under
 * the choice says whether it will work, lit in cyan when it will and amber when something is
 * missing, and leads to what's missing: One UI's Settings page (apps can't switch the always-on
 * display: Samsung's own page needs a signature permission) or Lumement effects. Off, a line says
 * the LED goes out, or that it never lights at all with no bright minutes either.
 */
@Composable
private fun ThenSection(
    after: AfterBright,
    noBright: Boolean,
    readiness: AodReadiness,
    onAod: () -> Unit,
    onShield: () -> Unit,
    onAfterBright: (AfterBright) -> Unit,
) {
    // Kept while the row folds away, so its text doesn't change before its space goes.
    var shown by remember { mutableStateOf(readiness) }
    SideEffect { if (after == AfterBright.AOD) shown = readiness }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.waiting_then_label), GlowPalette.TextMuted, heading = true)
        ChipRow(AfterBright.entries, after, onAfterBright)
        Disclosure(visible = after == AfterBright.AOD) {
            Box(Modifier.padding(top = 2.dp)) {
                LinkRow(
                    title = stringResource(shown.title),
                    summary = stringResource(shown.body),
                    action = stringResource(if (shown == AodReadiness.NEEDS_SHIELD) R.string.access_shield_grant else R.string.setup_aod_grant),
                    onClick = if (shown == AodReadiness.NEEDS_SHIELD) onShield else onAod,
                    leading = { StatusTile(shown.ready) { tint, line -> aodPhone(tint, line) } },
                )
            }
        }
        Disclosure(visible = after == AfterBright.OFF) {
            OptionNote(stringResource(outNote(noBright)))
        }
    }
}

/** Where the LED goes out after its bright minutes: or, with none, that it never lights at all. */
@StringRes
private fun outNote(noBright: Boolean): Int = if (noBright) R.string.waiting_then_never_body else R.string.waiting_then_off_body

private val TILE = 36.dp
private val TILE_GAP = 14.dp

/** A row's mark in a small tile: lit in cyan when [ready], in amber when something is missing. */
@Composable
private fun StatusTile(ready: Boolean, glyph: DrawScope.(tint: Color, line: Stroke) -> Unit) {
    val tint = if (ready) GlowPalette.Cyan else GlowPalette.Amber
    Box(
        Modifier
            .size(TILE)
            .clip(GlowShapes.Tile)
            .background(tint.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = 0.5f), GlowShapes.Tile),
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
