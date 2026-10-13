package app.lumement.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lumement.AodMode
import app.lumement.R
import app.lumement.ui.components.CardDivider
import app.lumement.ui.components.Disclosure
import app.lumement.ui.components.LinkRow
import app.lumement.ui.components.glowCard
import app.lumement.ui.theme.GlowPalette
import app.lumement.ui.theme.GlowShapes

// ---------------------------------------------------------------------------------------------
// The SCREEN tab's second section: what the phone lets the LED use while a message waits.
// ---------------------------------------------------------------------------------------------

/**
 * The two system settings the LED depends on, and how each is set now: the always-on display
 * ([aod]; left out where it can't be read), which the LED glows on instead of keeping the screen
 * on, and Lumement effects ([shieldOn]), whose window the LED is drawn in over the lock screen and
 * the always-on display. Each row is lit when on, says what that means for the LED, and opens the
 * Settings page where it is changed: apps can't switch either, so no switch that wouldn't flip.
 * While the always-on display is off on One UI ([aodForNew]), a quiet line under it says which mode
 * saves battery.
 */
@Composable
internal fun WaitingCard(aod: AodMode?, aodForNew: Boolean, shieldOn: Boolean, onAod: () -> Unit, onShield: () -> Unit) {
    Column(Modifier.glowCard(vertical = 10.dp)) {
        if (aod != null) {
            val aodOn = aod != AodMode.OFF
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
                action = stringResource(R.string.setup_aod_grant),
                onClick = onAod,
                leading = { StatusTile(aodOn) { tint, line -> battery(tint, line) } },
            )
            Disclosure(visible = !aodOn && aodForNew) {
                Row(
                    Modifier.padding(start = TILE + TILE_GAP, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(Modifier.size(12.dp)) {
                        battery(GlowPalette.Cyan, Stroke(1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.waiting_aod_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = GlowPalette.Cyan,
                    )
                }
            }
            CardDivider()
        }
        LinkRow(
            title = stringResource(R.string.waiting_shield_title),
            summary = stringResource(if (shieldOn) R.string.waiting_shield_on else R.string.waiting_shield_off),
            action = stringResource(if (shieldOn) R.string.setup_aod_grant else R.string.access_shield_grant),
            onClick = onShield,
            leading = { StatusTile(shieldOn) { tint, line -> spark(tint, line) } },
        )
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
