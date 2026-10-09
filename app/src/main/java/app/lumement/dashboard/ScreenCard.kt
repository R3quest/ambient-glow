package app.lumement.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lumement.ArrivalMode
import app.lumement.GlowSettings
import app.lumement.R
import app.lumement.ui.components.Disclosure
import app.lumement.ui.components.NoticeRow
import app.lumement.ui.components.OptionBody
import app.lumement.ui.components.RadioRow
import app.lumement.ui.components.SectionLabel
import app.lumement.ui.components.glowCard
import app.lumement.ui.theme.GlowPalette

// ---------------------------------------------------------------------------------------------
// The SCREEN tab: what the screen shows when a message arrives, beside the preview that shows it.
// ---------------------------------------------------------------------------------------------

/**
 * Where the effect plays, beside the preview that shows it: the options by name, and what the
 * chosen one does under them, so they don't have to be read as a list.
 */
@Composable
internal fun ScreenCard(
    settings: GlowSettings,
    sample: Int,
    previewHeld: PreviewPhase?,
    loop: Boolean,
    shieldOn: Boolean,
    onShield: () -> Unit,
    onSelect: (ArrivalMode) -> Unit,
) {
    val selected = settings.arrival
    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(stringResource(R.string.arrival_mode), GlowPalette.Cyan)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EffectPreview(
                settings = settings,
                color = SAMPLE_COLORS[sample].color,
                heldBy = previewHeld,
                loop = loop,
                effect = selected.playsEffect,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ArrivalMode.entries.forEach { mode ->
                    RadioRow(
                        title = stringResource(mode.label),
                        body = null,
                        selected = selected == mode,
                        onClick = { onSelect(mode) },
                    )
                }
            }
        }
        // Each carries its own gap, so the card doesn't jump as the notice comes and goes.
        Column {
            OptionBody(selected) { mode -> stringResource(mode.body) }
            // Without the shield, Lock screen only lights the screen: say so where it is chosen.
            Disclosure(visible = selected == ArrivalMode.LOCK_SCREEN && !shieldOn) {
                Box(Modifier.padding(top = 12.dp)) {
                    NoticeRow(
                        text = stringResource(R.string.arrival_needs_shield),
                        action = stringResource(R.string.access_shield_grant),
                        onAction = onShield,
                    )
                }
            }
        }
    }
}
