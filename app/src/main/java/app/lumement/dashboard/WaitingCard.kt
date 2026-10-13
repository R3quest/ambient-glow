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
 * Where the LED waits, by One UI's always-on display ([aod]; the card is left out on other
 * phones): each of its ways in one line, what it is set to for it under that, and the phone's
 * own selected. None is a wrong one, so none is pushed: the one that uses least battery says so,
 * quietly. Apps can't switch it (Samsung's own AOD page, where its ways are, needs a signature
 * permission), so a tap on any of its ways leads to the Settings page where it is changed. The
 * LED is drawn on the display in Lumement effects' window ([shieldOn]), which the screen card
 * asks for while it is off; till then the display's ways say they need it.
 */
@Composable
internal fun WaitingCard(aod: AodMode, shieldOn: Boolean, onAod: () -> Unit) {
    // Laid out as the screen card is: its label, then its choices.
    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(stringResource(R.string.waiting_label), GlowPalette.TextMuted)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WAYS.forEach { way ->
                WaitingWay(
                    way = way,
                    now = aod == way,
                    // The phone's own, but the LED can't go on the display without the window.
                    needsShield = aod == way && way != AodMode.OFF && !shieldOn,
                    onClick = onAod,
                )
            }
        }
    }
}

/**
 * From the most screen to the least: kept on, the display always on, the display only while a
 * message is new.
 */
private val WAYS = listOf(AodMode.OFF, AodMode.ON, AodMode.NEW_NOTIFICATIONS)

/**
 * One way the LED can wait ([way]): where, and what the display is set to for it, picked as the
 * screen card's are, the phone's own ([now]) selected; a tap on any leads to Settings
 * ([onClick]), where it is changed. The one that uses least battery says so, picked or not. In
 * line with the card's label: the row's highlight reaches into the card's padding.
 */
@Composable
private fun WaitingWay(way: AodMode, now: Boolean, needsShield: Boolean, onClick: () -> Unit) {
    val (title, setting) = when (way) {
        AodMode.OFF -> R.string.waiting_screen_title to R.string.waiting_screen_setting
        AodMode.ON -> R.string.waiting_aod_title to R.string.waiting_aod_setting
        AodMode.NEW_NOTIFICATIONS -> R.string.waiting_new_title to R.string.waiting_new_setting
    }
    RadioRow(
        title = stringResource(title),
        body = stringResource(if (needsShield) R.string.waiting_needs_shield else setting),
        selected = now,
        onClick = onClick,
        modifier = Modifier.rowBleed(),
    ) {
        if (way == AodMode.NEW_NOTIFICATIONS) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(12.dp)) {
                    val line = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    battery(GlowPalette.TextMuted, line)
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
