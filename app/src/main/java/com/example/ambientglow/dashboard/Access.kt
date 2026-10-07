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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.example.ambientglow.GlowLauncher
import com.example.ambientglow.GlowLog
import com.example.ambientglow.GlowPrefs
import com.example.ambientglow.GlowShield
import com.example.ambientglow.MainActivity
import com.example.ambientglow.NotificationWakerService
import com.example.ambientglow.R
import com.example.ambientglow.ui.components.Disclosure
import com.example.ambientglow.ui.components.NoticeRow
import com.example.ambientglow.ui.components.PrimaryButton
import com.example.ambientglow.ui.theme.GlowBrushes
import com.example.ambientglow.ui.theme.GlowMotion
import com.example.ambientglow.ui.theme.GlowPalette
import com.example.ambientglow.ui.theme.GlowShapes

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
) {
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

/** What each grant's button does: the dialog or Settings screen that grants it. */
internal class AccessActions(
    val listener: () -> Unit,
    val bridge: () -> Unit,
    val fullScreen: () -> Unit,
    val shield: () -> Unit,
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
 * After the required steps comes the shield, the one optional grant, with a way past it. It is
 * offered once: passed over (or turned on), later setups leave it out, and the Screen tab keeps
 * offering it where it matters. Then an all-set page, left with [onDone].
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
    val startedReady = rememberSaveable { access.ready }
    var shieldPassed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(access.shield) { if (access.shield && offerShield) GlowPrefs.markShieldOffered(context) }
    val page = access.nextStep?.let { SetupPage.Grant(it) } ?: when {
        offerShield && !access.shield && !shieldPassed -> SetupPage.Shield
        else -> SetupPage.Done
    }
    val total = plan.size + if (offerShield) 1 else 0
    val done = plan.count(access::has) + if (offerShield && page == SetupPage.Done) 1 else 0
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
            Column {
                StepGlyph(shown, Modifier.size(72.dp))
                Spacer(Modifier.height(28.dp))
                if (shown != SetupPage.Done && total > 1) {
                    Text(
                        text = when (shown) {
                            is SetupPage.Grant -> stringResource(R.string.setup_step, plan.indexOf(shown.step) + 1, total)
                            else -> stringResource(R.string.setup_step_optional, total, total)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = GlowPalette.Cyan,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    text = stringResource(shown.title),
                    style = MaterialTheme.typography.displaySmall,
                    color = GlowPalette.TextPrimary,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(
                        when (shown) {
                            is SetupPage.Grant -> shown.step.body
                            SetupPage.Shield -> R.string.setup_shield_body
                            SetupPage.Done -> if (access.shield) R.string.access_done_body else R.string.access_done_body_no_shield
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlowPalette.TextMuted,
                )
                if (shown == SetupPage.Shield) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.setup_shield_where),
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
                    SetupPage.Done -> onDone()
                }
            },
        )
        // The way past the optional step: quiet, under the button, so turning it on stays the lead.
        Disclosure(visible = page == SetupPage.Shield) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(GlowShapes.Button)
                    .clickable(role = Role.Button) {
                        GlowPrefs.markShieldOffered(context)
                        shieldPassed = true
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

/** One segment per step of this run: lime once granted, cyan for the one showing, faint to come. */
@Composable
private fun SetupProgress(done: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val color by animateColorAsState(
                targetValue = when {
                    i < done -> GlowPalette.Lime
                    i == done -> GlowPalette.Cyan
                    else -> GlowPalette.SurfaceHighest
                },
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
 * The page's mark in a tile edged in the signature gradient: a bell, a message, a phone waking,
 * a spark for the effects, or a check once all is set.
 */
@Composable
private fun StepGlyph(page: SetupPage, modifier: Modifier = Modifier) {
    val glow by animateColorAsState(
        targetValue = if (page == SetupPage.Done) GlowPalette.Lime else GlowPalette.Cyan,
        animationSpec = GlowMotion.stateChange(),
        label = "glyph-glow",
    )
    Box(
        modifier
            // A soft halo past the tile's edge, the app's glow in miniature.
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(glow.copy(alpha = 0.22f), Color.Transparent), center, size.maxDimension),
                    radius = size.maxDimension,
                )
            }
            .clip(GlowShapes.Tile)
            .background(GlowPalette.Surface)
            .border(1.dp, GlowBrushes.Signature, GlowShapes.Tile),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            val line = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val tint = if (page == SetupPage.Done) GlowPalette.Lime else GlowPalette.Cyan
            when (page) {
                is SetupPage.Grant -> when (page.step) {
                    AccessStep.BRIDGE -> bell(tint, line)
                    AccessStep.LISTENER -> bubble(tint, line)
                    AccessStep.FULL_SCREEN -> phone(tint, line)
                }
                SetupPage.Shield -> spark(tint, line)
                SetupPage.Done -> check(tint, line)
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
