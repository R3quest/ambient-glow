package app.lumement.dashboard

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
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import app.lumement.GlowLauncher
import app.lumement.GlowLog
import app.lumement.GlowPrefs
import app.lumement.GlowShield
import app.lumement.AodMode
import app.lumement.aodForNewNotificationsAvailable
import app.lumement.aodMode
import app.lumement.MainActivity
import app.lumement.NotificationWakerService
import app.lumement.R
import app.lumement.ui.components.Disclosure
import app.lumement.ui.components.NoticeRow
import app.lumement.ui.components.PrimaryButton
import app.lumement.ui.theme.GlowMotion
import app.lumement.ui.theme.GlowPalette
import app.lumement.ui.theme.GlowShapes

// ---------------------------------------------------------------------------------------------
// Access: what the app needs granted, the Settings screens that grant it, and setup.
// ---------------------------------------------------------------------------------------------

@Immutable
internal data class AccessState(
    val listener: Boolean,
    val bridge: Boolean,
    val fullScreen: Boolean,
    val fullScreenApplies: Boolean,
    /** Optional: plays the effect over the lock screen and covers the bars for the LED (GlowShield). Not counted in [ready]. */
    val shield: Boolean,
    /** The always-on display's mode ([aodMode]), null where it can't be read. */
    val aod: AodMode? = null,
    /** One UI's "Show for new notifications" exists here ([aodForNewNotificationsAvailable]). */
    val aodForNew: Boolean = false,
) {
    /**
     * The always-on display's mode where the dashboard shows it: on One UI only, where it was
     * measured (its switches read right, its Settings row, Show for new notifications). Elsewhere
     * Android's switch may not be the one the phone's own always-on display uses, and a wrong mode
     * is worse than none.
     */
    val oneUiAod: AodMode? get() = aod?.takeIf { aodForNew }

    /**
     * Optional, One UI: its always-on display is off, and could show for new notifications only, for
     * the LED to glow on instead of keeping the screen on.
     */
    val aodOff: Boolean get() = oneUiAod == AodMode.OFF

    fun has(step: AccessStep): Boolean = when (step) {
        AccessStep.LISTENER -> listener
        AccessStep.BRIDGE -> bridge
        AccessStep.FULL_SCREEN -> fullScreen || !fullScreenApplies
    }

    /** The steps setup shows, in order: full-screen wake only where Android asks for it. */
    val steps: List<AccessStep> get() = AccessStep.entries.filter { it != AccessStep.FULL_SCREEN || fullScreenApplies }

    /** The first required grant still missing, in step order; null once ready. */
    val nextStep: AccessStep? get() = steps.firstOrNull { !has(it) }

    val ready: Boolean get() = nextStep == null

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
                aod = aodMode(context.contentResolver),
                aodForNew = aodForNewNotificationsAvailable(context.contentResolver),
            )
        }
    }
}

/**
 * The required grants, in setup order. Alerts come first: Android asks for them in a dialog over
 * the app, a quick yes before the trips to Settings. The shield is optional, so it is not one of
 * them: setup offers it after these, once (see [SetupFlow]).
 */
internal enum class AccessStep(@StringRes val title: Int, @StringRes val body: Int, @StringRes val grant: Int) {
    BRIDGE(R.string.access_post_title, R.string.access_post_body, R.string.access_post_grant),
    LISTENER(R.string.access_listener_title, R.string.access_listener_body, R.string.access_listener_grant),
    FULL_SCREEN(R.string.access_fsi_title, R.string.access_fsi_body, R.string.access_fsi_grant),
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
        } catch (_: SecurityException) {
            // Or guard them with a permission of their own (Samsung's AOD settings): the same.
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
// opens the list, with the row to tap highlighted where Settings supports the fragment-args key:
// our service itself on stock Android, which lists services on that page; on One UI, which lists
// them a page further in, the Installed apps row that leads there (seen on the S23, One UI 8.5).
private fun Context.openShieldSettings() {
    val row = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
        ONE_UI_INSTALLED_SERVICES
    } else {
        ComponentName(this, GlowShield::class.java).flattenToString()
    }
    launchFirstAvailable(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", row)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", row) }),
    )
}

/** One UI's key for the accessibility page's Installed apps row (from its Settings layout). */
private const val ONE_UI_INSTALLED_SERVICES = "accessibility_installed_services"

/**
 * Android's lock-screen settings, which One UI titles Lock screen and AOD, where the always-on
 * display is set (Samsung's own AOD page needs a permission of Samsung's), with its Always On
 * Display row highlighted (seen on the S23, One UI 8.5); else the display settings.
 */
private fun Context.openAodSettings() {
    launchFirstAvailable(
        Intent(ACTION_LOCK_SCREEN_SETTINGS)
            .putExtra(":settings:fragment_args_key", AOD_PREFERENCE)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", AOD_PREFERENCE) }),
        Intent(Settings.ACTION_DISPLAY_SETTINGS),
    )
}

/** Settings.ACTION_LOCKSCREEN_SETTINGS (hidden), the lock-screen page. */
private const val ACTION_LOCK_SCREEN_SETTINGS = "android.settings.LOCK_SCREEN_SETTINGS"

/** One UI's key for the Always On Display row there (it shows in Settings' change history). */
private const val AOD_PREFERENCE = "always_on_screen"

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

/** What each grant's button does: the dialog or Settings screen that grants it. */
internal class AccessActions(
    val listener: () -> Unit,
    val bridge: () -> Unit,
    val fullScreen: () -> Unit,
    val shield: () -> Unit,
    val aod: () -> Unit,
) {
    fun open(step: AccessStep) = when (step) {
        AccessStep.LISTENER -> listener()
        AccessStep.BRIDGE -> bridge()
        AccessStep.FULL_SCREEN -> fullScreen()
    }
}

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
            listener = {
                GrantReturn.await(GrantReturn.Grant.LISTENER)
                context.openListenerSettings()
            },
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
            shield = {
                GrantReturn.await(GrantReturn.Grant.SHIELD)
                context.openShieldSettings()
            },
            aod = context::openAodSettings,
        )
    }
}

/**
 * The grant the app just sent the user to Settings for, so the service it turns on can bring them
 * back the moment it connects, instead of them backing out through Settings screen by screen
 * (three on Samsung's accessibility pages). Cleared when the user comes back by themselves (the
 * dashboard's resume), so a service turned on some other time never pulls the app forward. In
 * memory only: a process restarted meanwhile has nothing waiting.
 */
internal object GrantReturn {
    enum class Grant { LISTENER, SHIELD }

    @Volatile private var awaiting: Grant? = null

    fun await(grant: Grant) {
        awaiting = grant
    }

    fun clear() {
        awaiting = null
    }

    /** From the service [grant] turns on, as it connects: back to the app, Settings closed over it. */
    fun granted(context: Context, grant: Grant) {
        if (awaiting != grant) return
        awaiting = null
        val back = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
        // A start Android blocks from the background is dropped silently: the user then backs out
        // of Settings as before. The catch is for builds that throw instead.
        try {
            context.startActivity(back)
        } catch (e: RuntimeException) {
            GlowLog.d { "setup return refused: $e" }
        }
    }
}

/** What a setup page asks for: a required grant, the optional shield, or nothing: all set. */
private sealed interface SetupPage {
    @get:StringRes val title: Int

    @get:StringRes val action: Int

    data class Grant(val step: AccessStep) : SetupPage {
        override val title get() = step.title
        override val action get() = step.grant
    }

    data object Shield : SetupPage {
        override val title get() = R.string.setup_shield_title
        override val action get() = R.string.access_shield_grant
    }

    data object Aod : SetupPage {
        override val title get() = R.string.setup_aod_title
        override val action get() = R.string.setup_aod_grant
    }

    data object Done : SetupPage {
        override val title get() = R.string.access_done_title
        override val action get() = R.string.access_done_action
    }
}

/**
 * Setup, full screen, one grant per page: why it is needed, then one button to the Settings
 * screen (or dialog) that grants it. Back from Settings the next page slides in, but nothing
 * opens on its own: each page is what prepares the user for the system's own wording, which for
 * notification access sounds far worse than what the app does. Steps granted before setup
 * started (full-screen wake usually is) are neither shown nor counted.
 *
 * After the required steps come the optional ones, each with a way past it and offered once:
 * passed over (or done), later setups leave it out. The shield (the Screen tab keeps offering it
 * where it matters), then, on One UI with its always-on display off and the shield on (the LED on
 * the always-on display is drawn in its window), the always-on display shown for new
 * notifications: the LED glows on it for less battery than the lit screen. Then an all-set page,
 * left with [onDone].
 */
@Composable
internal fun SetupFlow(access: AccessState, actions: AccessActions, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val restricted = remember(context) { isRestrictedInstall(context) }
    // What this run asks for, decided as it starts and kept through a recreated activity, so the
    // count never shifts under the user. A step lost meanwhile (withdrawn mid-setup) joins it.
    val startPlan = rememberSaveable { access.steps.filterNot(access::has).map { it.name } }
    val plan = access.steps.filter { it.name in startPlan || !access.has(it) }
    val offerShield = rememberSaveable { !access.shield && !GlowPrefs.shieldOffered(context) }
    val offerAod = rememberSaveable { access.aodOff && !GlowPrefs.aodOffered(context) }
    val startedReady = rememberSaveable { access.ready }
    var shieldPassed by rememberSaveable { mutableStateOf(false) }
    var aodPassed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(access.shield) { if (access.shield && offerShield) GlowPrefs.markShieldOffered(context) }
    LaunchedEffect(access.aodOff) { if (!access.aodOff && offerAod) GlowPrefs.markAodOffered(context) }
    val page = access.nextStep?.let { SetupPage.Grant(it) } ?: when {
        offerShield && !access.shield && !shieldPassed -> SetupPage.Shield
        offerAod && access.shield && access.aodOff && !aodPassed -> SetupPage.Aod
        else -> SetupPage.Done
    }
    val total = plan.size + (if (offerShield) 1 else 0) + (if (offerAod) 1 else 0)
    val done = plan.count(access::has) +
        (if (offerShield && (page == SetupPage.Aod || page == SetupPage.Done)) 1 else 0) +
        (if (offerAod && page == SetupPage.Done) 1 else 0)
    // Once, as the last grant lands: not again for the same all-set page redrawn.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(access.ready) { if (access.ready && !startedReady) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    // The header sits exactly where the dashboard's does, so handing over to it moves nothing.
    Column(modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 20.dp)) {
        BrandHeader()
        if (total > 1) {
            Spacer(Modifier.height(20.dp))
            SetupProgress(done = done, total = total)
        }
        Spacer(Modifier.weight(1f))
        // A step slides in from the right as the one before it is granted, the way a pager turns.
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
            },
            label = "setup-step",
        ) { shown ->
            // Each step is a pane of its own: TalkBack says its title as it slides in.
            val title = stringResource(shown.title)
            Column(Modifier.semantics { paneTitle = title }) {
                StepGlyph(shown, Modifier.size(72.dp))
                Spacer(Modifier.height(28.dp))
                if (shown != SetupPage.Done && total > 1) {
                    Text(
                        text = when (shown) {
                            is SetupPage.Grant -> stringResource(R.string.setup_step, plan.indexOf(shown.step) + 1, total)
                            SetupPage.Shield -> stringResource(R.string.setup_step_optional, plan.size + 1, total)
                            else -> stringResource(R.string.setup_step_optional, total, total)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = GlowPalette.Cyan,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    color = GlowPalette.TextPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(
                        when (shown) {
                            is SetupPage.Grant -> shown.step.body
                            SetupPage.Shield -> R.string.setup_shield_body
                            SetupPage.Aod -> R.string.setup_aod_body
                            SetupPage.Done -> if (access.shield) R.string.access_done_body else R.string.access_done_body_no_shield
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlowPalette.TextMuted,
                )
                if (shown == SetupPage.Shield || shown == SetupPage.Aod) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(if (shown == SetupPage.Aod) R.string.setup_aod_where else R.string.setup_shield_where),
                        style = MaterialTheme.typography.bodySmall,
                        color = GlowPalette.TextMuted,
                    )
                }
                val needsUnlock = shown == SetupPage.Shield || (shown as? SetupPage.Grant)?.step == AccessStep.LISTENER
                if (restricted && needsUnlock) {
                    Spacer(Modifier.height(18.dp))
                    NoticeRow(
                        text = stringResource(R.string.access_restricted_body),
                        action = stringResource(R.string.access_restricted_action),
                        onAction = context::openAppInfo,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1.4f))
        PrimaryButton(
            text = stringResource(page.action),
            onClick = {
                when (page) {
                    is SetupPage.Grant -> actions.open(page.step)
                    SetupPage.Shield -> actions.shield()
                    SetupPage.Aod -> actions.aod()
                    SetupPage.Done -> onDone()
                }
            },
        )
        // The way past an optional step: quiet, under the button, so taking it stays the lead.
        Disclosure(visible = page == SetupPage.Shield || page == SetupPage.Aod) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(GlowShapes.Button)
                    .clickable(role = Role.Button) {
                        if (page == SetupPage.Aod) {
                            GlowPrefs.markAodOffered(context)
                            aodPassed = true
                        } else {
                            GlowPrefs.markShieldOffered(context)
                            shieldPassed = true
                        }
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.setup_skip).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = GlowPalette.TextMuted,
                )
            }
        }
        PrivacyNote(Modifier.padding(top = 14.dp))
    }
}

/** One segment per step of this run, filling in cyan up to the one showing; faint to come. */
@Composable
private fun SetupProgress(done: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val color by animateColorAsState(
                targetValue = if (i <= done) GlowPalette.Cyan else GlowPalette.SurfaceHighest,
                animationSpec = GlowMotion.stateChange(),
                label = "setup-segment",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(GlowShapes.Pill)
                    .background(color),
            )
        }
    }
}

/**
 * The page's mark in a tile edged in cyan: a bell, a message, a phone waking, a spark for the
 * effects, a battery for the always-on display, or a check once all is set.
 */
@Composable
private fun StepGlyph(page: SetupPage, modifier: Modifier = Modifier) {
    val accent = GlowPalette.Cyan
    Box(
        modifier
            // A soft halo past the tile's edge, the app's glow in miniature.
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent), center, size.maxDimension),
                    radius = size.maxDimension,
                )
            }
            .clip(GlowShapes.Tile)
            .background(GlowPalette.Surface)
            .border(1.dp, accent.copy(alpha = 0.6f), GlowShapes.Tile),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            val line = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (page) {
                is SetupPage.Grant -> when (page.step) {
                    AccessStep.BRIDGE -> bell(accent, line)
                    AccessStep.LISTENER -> bubble(accent, line)
                    AccessStep.FULL_SCREEN -> phone(accent, line)
                }
                SetupPage.Shield -> spark(accent, line)
                SetupPage.Aod -> battery(accent, line)
                SetupPage.Done -> check(accent, line)
            }
        }
    }
}

/** Bell: a dome on a flared rim, and its clapper. */
private fun DrawScope.bell(tint: Color, line: Stroke) {
    val (w, h) = size
    val path = Path().apply {
        moveTo(w * 0.12f, h * 0.76f)
        lineTo(w * 0.88f, h * 0.76f)
        moveTo(w * 0.2f, h * 0.76f)
        cubicTo(w * 0.28f, h * 0.62f, w * 0.22f, h * 0.12f, w * 0.5f, h * 0.12f)
        cubicTo(w * 0.78f, h * 0.12f, w * 0.72f, h * 0.62f, w * 0.8f, h * 0.76f)
    }
    drawPath(path, tint, style = line)
    drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.5f, h * 0.9f))
}

/** A message bubble with its tail, and two lines of text. */
private fun DrawScope.bubble(tint: Color, line: Stroke) {
    val (w, h) = size
    val path = Path().apply {
        addRoundRect(RoundRect(w * 0.06f, h * 0.12f, w * 0.94f, h * 0.72f, CornerRadius(w * 0.16f)))
        moveTo(w * 0.26f, h * 0.72f)
        lineTo(w * 0.22f, h * 0.92f)
        lineTo(w * 0.46f, h * 0.72f)
    }
    drawPath(path, tint, style = line)
    drawLine(tint, Offset(w * 0.26f, h * 0.34f), Offset(w * 0.74f, h * 0.34f), line.width, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.26f, h * 0.5f), Offset(w * 0.58f, h * 0.5f), line.width, StrokeCap.Round)
}

/** A phone, its screen lit. */
private fun DrawScope.phone(tint: Color, line: Stroke) {
    val (w, h) = size
    drawRoundRect(tint, Offset(w * 0.24f, h * 0.04f), Size(w * 0.52f, h * 0.92f), CornerRadius(w * 0.12f), style = line)
    drawRoundRect(tint.copy(alpha = 0.35f), Offset(w * 0.33f, h * 0.16f), Size(w * 0.34f, h * 0.62f), CornerRadius(w * 0.04f))
}

/** A four-pointed spark, pinched at its waist, and a small one beside it. */
private fun DrawScope.spark(tint: Color, line: Stroke) {
    val (w, h) = size
    val c = Offset(w * 0.44f, h * 0.56f)
    val r = w * 0.4f
    val waist = r * 0.22f
    val path = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x + waist, c.y - waist, c.x + r, c.y)
        quadraticTo(c.x + waist, c.y + waist, c.x, c.y + r)
        quadraticTo(c.x - waist, c.y + waist, c.x - r, c.y)
        quadraticTo(c.x - waist, c.y - waist, c.x, c.y - r)
        close()
    }
    drawPath(path, tint, style = line)
    drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.86f, h * 0.14f))
}

/** A battery standing up, its terminal on top, two thirds full. */
internal fun DrawScope.battery(tint: Color, line: Stroke) {
    val (w, h) = size
    drawRoundRect(tint, Offset(w * 0.27f, h * 0.14f), Size(w * 0.46f, h * 0.82f), CornerRadius(w * 0.1f), style = line)
    drawRoundRect(tint, Offset(w * 0.4f, h * 0.02f), Size(w * 0.2f, h * 0.08f), CornerRadius(w * 0.03f))
    drawRoundRect(tint.copy(alpha = 0.45f), Offset(w * 0.36f, h * 0.42f), Size(w * 0.28f, h * 0.46f), CornerRadius(w * 0.04f))
}

private fun DrawScope.check(tint: Color, line: Stroke) {
    val (w, h) = size
    val path = Path().apply {
        moveTo(w * 0.12f, h * 0.54f)
        lineTo(w * 0.4f, h * 0.8f)
        lineTo(w * 0.88f, h * 0.2f)
    }
    drawPath(path, tint, style = line)
}

/**
 * One short sentence in body type, not mono caps: in the label style it read as a state
 * ("OFFLINE"), not as a promise. True because the app has no INTERNET permission.
 */
@Composable
private fun PrivacyNote(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.footer_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = GlowPalette.TextMuted,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}
