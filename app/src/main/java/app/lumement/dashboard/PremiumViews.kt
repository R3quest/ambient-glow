package app.lumement.dashboard

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lumement.PremiumState
import app.lumement.R
import app.lumement.TRIAL_DAYS
import app.lumement.ui.components.GhostButton
import app.lumement.ui.components.PrimaryButton
import app.lumement.ui.components.glowCard
import app.lumement.ui.theme.GlowPalette
import app.lumement.ui.theme.GlowShapes

// ---------------------------------------------------------------------------------------------
// Premium on the dashboard: the line under a premium choice (where its trial stands, and a way to
// buy it), and the one card that says the trial is over.
// ---------------------------------------------------------------------------------------------

/** Premium's mark: the brand magenta, kept apart from the amber the app warns in. */
internal val PremiumTint = GlowPalette.Magenta

private val SPARKLE_SIZE = 9.dp

/**
 * Says a choice is premium and where its trial stands, with Play's price to unlock it once Play
 * has said it. Not yet tried, it says when the week starts. Nothing to say once it is owned.
 */
@Composable
internal fun PremiumLine(premium: PremiumModel, modifier: Modifier = Modifier) {
    val state = premium.state
    if (state == PremiumState.Owned) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PremiumStatus(state, Modifier.weight(1f))
            val price = premium.price
            if (price != null) {
                Spacer(Modifier.width(10.dp))
                UnlockPill(price, premium)
            }
        }
        if (state == PremiumState.Untried) {
            Text(
                text = stringResource(R.string.premium_untried_body),
                style = MaterialTheme.typography.bodySmall,
                color = GlowPalette.TextMuted,
            )
        }
    }
}

/** Premium's sparkle and where its trial stands, in its tint. */
@Composable
internal fun PremiumStatus(state: PremiumState, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Sparkle(PremiumTint, Modifier.size(SPARKLE_SIZE))
        Spacer(Modifier.width(8.dp))
        Text(text = premiumLabel(state), style = MaterialTheme.typography.labelSmall, color = PremiumTint)
    }
}

@Composable
private fun premiumLabel(state: PremiumState): String = when (state) {
    is PremiumState.Trial -> pluralStringResource(R.plurals.premium_days_left, state.daysLeft, state.daysLeft)
    PremiumState.Over -> stringResource(R.string.premium_over)
    else -> pluralStringResource(R.plurals.premium_untried, TRIAL_DAYS, TRIAL_DAYS)
}

@Composable
private fun UnlockPill(price: String, premium: PremiumModel) {
    val activity = LocalActivity.current
    val description = stringResource(R.string.premium_unlock_action, price)
    Text(
        text = stringResource(R.string.premium_unlock, price),
        style = MaterialTheme.typography.labelSmall,
        color = PremiumTint,
        maxLines = 1,
        modifier = Modifier
            .clip(GlowShapes.Pill)
            .background(PremiumTint.copy(alpha = 0.08f))
            .border(1.dp, PremiumTint.copy(alpha = 0.6f), GlowShapes.Pill)
            .clickable(role = Role.Button) { activity?.let(premium::unlock) }
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * Said once, the first time the dashboard opens after the trial: what changed on the lock screen,
 * that the choices are kept, and the way back. Dismissed, or bought, it goes for good.
 */
@Composable
internal fun PremiumOverCard(premium: PremiumModel, modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    val price = premium.price
    Column(modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Sparkle(PremiumTint, Modifier.size(12.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.premium_over_title),
                style = MaterialTheme.typography.titleMedium,
                color = GlowPalette.TextPrimary,
            )
        }
        Text(
            text = stringResource(R.string.premium_over_body),
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextMuted,
        )
        Spacer(Modifier.height(6.dp))
        if (price != null) {
            PrimaryButton(
                text = stringResource(R.string.premium_over_buy, price),
                onClick = {
                    premium.markOverSeen()
                    activity?.let(premium::unlock)
                },
            )
        }
        GhostButton(
            text = stringResource(R.string.premium_over_dismiss),
            emphasized = false,
            onClick = premium::markOverSeen,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
