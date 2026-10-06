package com.example.ambientglow.dashboard

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.example.ambientglow.GlowLauncher
import com.example.ambientglow.GlowShield
import com.example.ambientglow.NotificationWakerService
import com.example.ambientglow.R
import com.example.ambientglow.ui.components.Chevron
import com.example.ambientglow.ui.components.GhostButton
import com.example.ambientglow.ui.components.NoticeRow
import com.example.ambientglow.ui.components.StatusPill
import com.example.ambientglow.ui.theme.GlowBrushes
import com.example.ambientglow.ui.theme.GlowPalette
import com.example.ambientglow.ui.theme.GlowShapes

// ---------------------------------------------------------------------------------------------
// Access: what the app needs granted, the Settings screens that grant it, and its cards.
// ---------------------------------------------------------------------------------------------

@Immutable
internal data class AccessState(
    val listener: Boolean,
    val bridge: Boolean,
    val fullScreen: Boolean,
    val fullScreenApplies: Boolean,
    /** Optional: covers the system bars while the LED takes over (GlowShield). Not counted in [armed]. */
    val shield: Boolean,
) {
    val required: Int get() = if (fullScreenApplies) 3 else 2
    val granted: Int get() = listOf(listener, bridge, fullScreen && fullScreenApplies).count { it }
    val armed: Boolean get() = granted == required

    companion object {
        fun read(context: Context): AccessState {
            val fullScreenApplies = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            val fullScreen = if (fullScreenApplies) {
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            } else {
                true
            }
            return AccessState(
                listener = NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName),
                bridge = GlowLauncher.canPostBridge(context),
                fullScreen = fullScreen,
                fullScreenApplies = fullScreenApplies,
                shield = isShieldEnabled(context),
            )
        }
    }
}

private fun isShieldEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val shield = ComponentName(context, GlowShield::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == shield }
}

// Android 13+ blocks notification access and accessibility ("Restricted setting") for apps
// installed from an APK file, until the user allows it from the app's info page. Store and adb
// installs are exempt, so only file and download installs need the hint.
private fun isRestrictedInstall(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val source = try {
        context.packageManager.getInstallSourceInfo(context.packageName).packageSource
    } catch (_: Exception) {
        return false
    }
    return source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
        source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
}

private fun Context.openAppInfo() {
    launchFirstAvailable(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
}

private fun Context.launchFirstAvailable(vararg intents: Intent) {
    for (intent in intents) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // OEM builds omit some deep links; fall through to the next, broader screen.
        }
    }
}

private fun Context.openListenerSettings() {
    val component = ComponentName(this, NotificationWakerService::class.java)
    val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
    } else {
        null
    }
    launchFirstAvailable(
        *listOfNotNull(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)).toTypedArray(),
    )
}

// The per-service page (ACCESSIBILITY_DETAILS_SETTINGS) needs a system-only permission, so this
// opens the list; Settings highlights our entry where it supports the fragment-args key.
private fun Context.openShieldSettings() {
    val shield = ComponentName(this, GlowShield::class.java).flattenToString()
    launchFirstAvailable(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", shield)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", shield) }),
    )
}

private fun Context.openBridgeSettings() {
    launchFirstAvailable(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, GlowLauncher.CHANNEL_ID),
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    )
}

private fun Context.openFullScreenIntentSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    launchFirstAvailable(
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:$packageName".toUri()),
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    )
}

/** What each access row's button does; shared by the setup cards and the compact summary. */
internal class AccessActions(
    val listener: () -> Unit,
    val bridge: () -> Unit,
    val fullScreen: () -> Unit,
    val shield: () -> Unit,
)

@Composable
internal fun rememberAccessActions(onRefresh: () -> Unit): AccessActions {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onRefresh()
        if (!granted || !GlowLauncher.canPostBridge(context)) context.openBridgeSettings()
    }
    return remember(context, notificationPermission) {
        AccessActions(
            listener = context::openListenerSettings,
            bridge = {
                val needsRuntimeGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !NotificationManagerCompat.from(context).areNotificationsEnabled()
                if (needsRuntimeGrant) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.openBridgeSettings()
                }
            },
            fullScreen = context::openFullScreenIntentSettings,
            shield = context::openShieldSettings,
        )
    }
}

@Composable
internal fun AccessPage(access: AccessState, actions: AccessActions) {
    val context = LocalContext.current
    val restricted = remember(context) { isRestrictedInstall(context) }
    val restrictedNotice: @Composable () -> Unit = {
        NoticeRow(
            text = stringResource(R.string.access_restricted_body),
            action = stringResource(R.string.access_restricted_action),
            onAction = context::openAppInfo,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AccessCard(
            index = "01",
            title = stringResource(R.string.access_listener_title),
            body = stringResource(R.string.access_listener_body),
            active = access.listener,
            grantLabel = stringResource(R.string.access_listener_grant),
            manageLabel = stringResource(R.string.access_listener_manage),
            onAction = actions.listener,
            notice = restrictedNotice.takeIf { restricted && !access.listener },
        )
        AccessCard(
            index = "02",
            title = stringResource(R.string.access_post_title),
            body = stringResource(R.string.access_post_body),
            active = access.bridge,
            grantLabel = stringResource(R.string.access_post_grant),
            manageLabel = stringResource(R.string.access_post_manage),
            onAction = actions.bridge,
        )
        if (access.fullScreenApplies) {
            AccessCard(
                index = "03",
                title = stringResource(R.string.access_fsi_title),
                body = stringResource(R.string.access_fsi_body),
                active = access.fullScreen,
                grantLabel = stringResource(R.string.access_fsi_grant),
                manageLabel = stringResource(R.string.access_fsi_manage),
                onAction = actions.fullScreen,
            )
        }
        AccessCard(
            index = if (access.fullScreenApplies) "04" else "03",
            title = stringResource(R.string.access_shield_title),
            body = stringResource(R.string.access_shield_body),
            active = access.shield,
            grantLabel = stringResource(R.string.access_shield_grant),
            manageLabel = stringResource(R.string.access_shield_manage),
            onAction = actions.shield,
            notice = restrictedNotice.takeIf { restricted && !access.shield },
        )
        PrivacyNote(Modifier.padding(top = 6.dp))
    }
}

/** Armed state: one quiet card with a row per permission, opened from the ARMED pill. */
@Composable
internal fun AccessSummary(access: AccessState, actions: AccessActions) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlowShapes.Card)
            .background(GlowPalette.Surface)
            .border(1.dp, GlowPalette.OutlineSoft, GlowShapes.Card)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        AccessRow(stringResource(R.string.access_listener_title), access.listener, actions.listener)
        AccessRow(stringResource(R.string.access_post_title), access.bridge, actions.bridge)
        if (access.fullScreenApplies) {
            AccessRow(stringResource(R.string.access_fsi_title), access.fullScreen, actions.fullScreen)
        }
        AccessRow(stringResource(R.string.access_shield_title), access.shield, actions.shield)
        Spacer(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(GlowPalette.OutlineSoft),
        )
        PrivacyNote(Modifier.padding(vertical = 12.dp))
    }
}

/**
 * One short sentence in body type, not mono caps: in the label style it sat under the status rows
 * and read as a state ("OFFLINE"), not as a promise. True because the app has no INTERNET permission.
 */
@Composable
private fun PrivacyNote(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.footer_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = GlowPalette.TextMuted,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun AccessRow(title: String, active: Boolean, onManage: () -> Unit) {
    val tint = if (active) GlowPalette.Lime else GlowPalette.Amber
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlowShapes.Pill)
            // The chevron replaces a MANAGE label; TalkBack still hears it as the action.
            .clickable(onClickLabel = stringResource(R.string.access_manage), role = Role.Button, onClick = onManage)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Settings-style: title over state, a chevron for the tap. No caps labels on the row.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = GlowPalette.TextPrimary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(6.dp)) { drawCircle(tint) }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(if (active) R.string.access_on else R.string.access_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlowPalette.TextMuted,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Chevron(
            tint = GlowPalette.TextMuted,
            modifier = Modifier.size(10.dp).graphicsLayer { rotationZ = -90f },
        )
    }
}

@Composable
private fun AccessCard(
    index: String,
    title: String,
    body: String,
    active: Boolean,
    grantLabel: String,
    manageLabel: String,
    onAction: () -> Unit,
    notice: (@Composable () -> Unit)? = null,
) {
    val borderBrush: Brush = if (active) SolidColor(GlowPalette.OutlineSoft) else GlowBrushes.Warning
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlowShapes.Card)
            .background(GlowPalette.Surface)
            .border(1.dp, borderBrush, GlowShapes.Card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = index, style = MaterialTheme.typography.labelMedium, color = GlowPalette.TextFaint)
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = GlowPalette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            StatusPill(
                text = stringResource(if (active) R.string.state_active else R.string.state_pending),
                tint = if (active) GlowPalette.Lime else GlowPalette.Amber,
            )
        }
        Text(text = body, style = MaterialTheme.typography.bodySmall, color = GlowPalette.TextMuted)
        Spacer(Modifier.height(2.dp))
        GhostButton(
            text = if (active) manageLabel else grantLabel,
            emphasized = !active,
            onClick = onAction,
        )
        notice?.invoke()
    }
}
