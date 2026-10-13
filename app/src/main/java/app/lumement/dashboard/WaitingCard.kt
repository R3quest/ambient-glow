package app.lumement.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lumement.AodMode
import app.lumement.R
import app.lumement.ui.components.RadioRow
import app.lumement.ui.components.SectionLabel
import app.lumement.ui.components.glowCard
import app.lumement.ui.components.rowBleed
import app.lumement.ui.theme.GlowPalette

// ---------------------------------------------------------------------------------------------
// The SCREEN tab's second section: what the phone lets the LED use while a message waits.
// ---------------------------------------------------------------------------------------------

/**
 * Where the LED waits, by the always-on display ([aod]; the card is left out where it can't be
 * read): each of its ways in one line, what to set for it under that, and the phone's own
 * selected. None is a wrong one, so none is pushed: the one that uses least battery says so,
 * quietly. Apps can't switch it (Samsung's own AOD page, where its ways are, needs a signature
 * permission), so a tap on any of its ways leads to the Settings page where it is
 * changed. "Show for new notifications" is One UI's ([aodForNew]), listed only there. The LED is
 * drawn on the display in Lumement effects' window ([shieldOn]), which the screen card asks for
 * while it is off; till then the display's ways say they need it.
 */
@Composable
internal fun WaitingCard(aod: AodMode, aodForNew: Boolean, shieldOn: Boolean, onAod: () -> Unit) {
    // Laid out as the screen card is: its label, then its choices.
    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(stringResource(R.string.waiting_label), GlowPalette.TextMuted)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WaitingWay(
                title = stringResource(R.string.waiting_screen_title),
                setting = stringResource(R.string.waiting_screen_setting),
                now = aod == AodMode.OFF,
                onClick = onAod,
            )
            WaitingWay(
                title = stringResource(R.string.waiting_aod_title),
                setting = stringResource(if (aod == AodMode.ON && !shieldOn) R.string.waiting_needs_shield else R.string.waiting_aod_setting),
                now = aod == AodMode.ON,
                onClick = onAod,
            )
            if (aodForNew) {
                WaitingWay(
                    title = stringResource(R.string.waiting_new_title),
                    setting = stringResource(if (aod == AodMode.NEW_NOTIFICATIONS && !shieldOn) R.string.waiting_needs_shield else R.string.waiting_new_setting),
                    now = aod == AodMode.NEW_NOTIFICATIONS,
                    onClick = onAod,
                    leastBattery = true,
                )
            }
        }
    }
}

/**
 * One way the LED can wait: where, and what to set for it, picked as the screen card's are, the
 * phone's own ([now]) selected; a tap on any leads to Settings ([onClick]), where it is changed.
 * The one that uses least battery says so, picked or not. In line with the
 * card's label: the row's highlight reaches into the card's padding.
 */
@Composable
private fun WaitingWay(title: String, setting: String, now: Boolean, onClick: () -> Unit, leastBattery: Boolean = false) {
    RadioRow(
        title = title,
        body = setting,
        selected = now,
        onClick = onClick,
        modifier = Modifier.rowBleed(),
    ) {
        if (leastBattery) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(12.dp)) {
                    battery(GlowPalette.TextMuted, Stroke(1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.waiting_least_battery),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlowPalette.TextMuted,
                )
            }
        }
    }
}
