package com.example.ambientglow.dashboard

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ambientglow.PremiumState
import com.example.ambientglow.R
import com.example.ambientglow.SpawnElement
import com.example.ambientglow.ui.components.CardDivider
import com.example.ambientglow.ui.components.OptionNote
import com.example.ambientglow.ui.components.PrimaryButton
import com.example.ambientglow.ui.components.glowCard
import com.example.ambientglow.ui.theme.GlowPalette

// ---------------------------------------------------------------------------------------------
// Premium, opened from the header's sparkle: what it adds, that it is paid once, what stays free,
// and the one way to buy it. A page rather than Play's sheet straight away, so a tap on a sparkle
// shows what it is before anything asks for money.
// ---------------------------------------------------------------------------------------------

/**
 * Where the trial stands, what premium adds, then Play's price to unlock it, or why it can't be
 * bought right now (no Play Store, not signed in). What stays free is said under it, so buying
 * is never mistaken for needing to.
 */
@Composable
internal fun PremiumScreen(premium: PremiumModel, onBack: () -> Unit) {
    val activity = LocalActivity.current
    val price = premium.price
    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(R.string.premium_title), onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = PageGutter, end = PageGutter, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.premium_intro),
                style = MaterialTheme.typography.bodySmall,
                color = GlowPalette.TextMuted,
            )
            Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (premium.state != PremiumState.Owned) PremiumStatus(premium.state)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (element in listOf(SpawnElement.FIRE, SpawnElement.AIR, SpawnElement.EARTH)) {
                        ElementGlyph(element, tint = { element.accent })
                    }
                }
                PremiumItem(R.string.premium_elements_title, R.string.premium_elements_body)
                CardDivider()
                PremiumItem(R.string.premium_colours_title, R.string.premium_colours_body)
                CardDivider()
                PremiumItem(R.string.premium_once_title, R.string.premium_once_body)
                Spacer(Modifier.height(2.dp))
                when {
                    premium.state == PremiumState.Owned -> OptionNote(stringResource(R.string.premium_owned))
                    price != null && activity != null -> PrimaryButton(
                        text = stringResource(R.string.premium_over_buy, price),
                        onClick = { premium.unlock(activity) },
                    )
                    else -> OptionNote(stringResource(R.string.premium_unavailable))
                }
            }
            OptionNote(stringResource(R.string.premium_free))
        }
    }
}

@Composable
private fun PremiumItem(title: Int, body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = GlowPalette.TextPrimary,
        )
        Text(text = stringResource(body), style = MaterialTheme.typography.bodySmall, color = GlowPalette.TextMuted)
    }
}

/** The header's way to premium: its sparkle. Gone once premium is owned. */
@Composable
internal fun PremiumButton(onClick: () -> Unit) {
    HeaderButton(stringResource(R.string.premium_open), onClick) {
        Sparkle(PremiumTint, Modifier.size(16.dp))
    }
}
