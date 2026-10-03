package com.example.ambientglow

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
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.ambientglow.ui.theme.AmbientGlowTheme
import com.example.ambientglow.ui.theme.GlowBrushes
import com.example.ambientglow.ui.theme.GlowPalette
import com.example.ambientglow.ui.theme.GlowShapes
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Real punch-hole position, so LED presets and mock-ups line up with this phone's camera.
    private val geometry = mutableStateOf(ScreenGeometry.Unknown)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        GlowLauncher.ensureChannel(this)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            geometry.value = ScreenGeometry.from(insets)
            ViewCompat.onApplyWindowInsets(view, insets)
        }
        setContent {
            AmbientGlowTheme {
                Dashboard(geometry.value)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Access state
// ---------------------------------------------------------------------------------------------

@Immutable
private data class AccessState(
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

// ---------------------------------------------------------------------------------------------
// Dashboard shell: header, tab bar, swipeable pages, pinned test dock
// ---------------------------------------------------------------------------------------------

private enum class DashboardTab(val label: Int) {
    ACCESS(R.string.tab_access),
    STYLE(R.string.tab_style),
}

/** Outer margins and the gap between sections: generous, so each block reads on its own. */
private val PageGutter = 24.dp
private val SectionGap = 28.dp

@Composable
private fun Dashboard(geometry: ScreenGeometry) {
    DashboardContent(geometry)
}

// Ignoring visibility is the long-standing (still experimental-marked) way to keep bar padding.
@OptIn(ExperimentalLayoutApi::class)
private val DashboardInsets: WindowInsets
    @Composable get() = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)

@Composable
private fun DashboardContent(reported: ScreenGeometry) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(GlowPrefs.load(context)) }
    // The camera as the user fitted it: what every mock-up and real-size preview lines up with.
    val density = LocalDensity.current.density
    val geometry = remember(reported, settings.lensOffsetXDp, settings.lensOffsetDp, settings.lensGrowDp, density) {
        reported.fitted(settings, density)
    }
    var access by remember { mutableStateOf(AccessState.read(context)) }
    var accessOpen by remember { mutableStateOf(false) }
    val refresh = { access = AccessState.read(context) }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    val pager = rememberPagerState(
        initialPage = DashboardTab.ACCESS.ordinal,
        pageCount = { DashboardTab.entries.size },
    )
    val scope = rememberCoroutineScope()
    val actions = rememberAccessActions(onRefresh = refresh)

    // The preview colour, and the real-size previews every change starts: the effect, or the
    // LED on a darkened screen. One at a time.
    var sample by rememberSaveable { mutableIntStateOf(0) }
    var showcaseRun by remember { mutableIntStateOf(0) }
    var showcasing by remember { mutableStateOf(false) }
    var ledRun by remember { mutableIntStateOf(0) }
    var ledHolding by remember { mutableStateOf(false) }
    var ledShowing by remember { mutableStateOf(false) }
    val showcase = {
        ledShowing = false
        showcaseRun++
        showcasing = true
    }
    val showLed = { holding: Boolean ->
        showcasing = false
        if (!holding) ledRun++
        ledHolding = holding
        ledShowing = true
    }
    val stylePage: @Composable () -> Unit = {
        StylePage(
            settings = settings,
            sample = sample,
            shieldOn = access.shield,
            onShield = actions.shield,
            onChange = { settings = it },
            onSample = { sample = it },
            onShowcase = showcase,
            onLed = showLed,
        )
    }

    CompositionLocalProvider(LocalCamera provides rememberCamera(geometry)) {
        Box(Modifier.fillMaxSize().background(GlowPalette.Void)) {
            // Padded for the bars even while hidden, so the LED preview hiding them moves nothing.
            Column(Modifier.fillMaxSize().windowInsetsPadding(DashboardInsets)) {
                TopBar(
                    access = access,
                    accessOpen = accessOpen,
                    onStatusClick = {
                        if (access.armed) {
                            accessOpen = !accessOpen
                        } else {
                            scope.launch { pager.animateScrollToPage(DashboardTab.ACCESS.ordinal) }
                        }
                    },
                )

                if (access.armed) {
                    // Everything granted: setup is done, so the app is just its styling page.
                    // Access lives behind the ARMED pill.
                    PageColumn(Modifier.weight(1f)) {
                        AnimatedVisibility(
                            visible = accessOpen,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            AccessSummary(access, actions)
                        }
                        stylePage()
                    }
                } else {
                    Box(Modifier.padding(horizontal = PageGutter)) {
                        GlowTabBar(
                            pager = pager,
                            pendingAccess = access.required - access.granted,
                            onSelect = { tab -> scope.launch { pager.animateScrollToPage(tab.ordinal) } },
                        )
                    }
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        PageColumn(Modifier.fillMaxSize()) {
                            when (DashboardTab.entries[page]) {
                                DashboardTab.ACCESS -> AccessPage(access, actions)
                                DashboardTab.STYLE -> stylePage()
                            }
                        }
                    }
                }

                TestDock()
            }

            // Each effect change plays once at real size over the whole screen, as a real message
            // would. It only draws, so taps go through to the options underneath while it plays.
            if (showcasing) {
                key(showcaseRun) {
                    ArrivalEffect(
                        settings = settings,
                        color = SAMPLE_COLORS[sample].color.toArgb(),
                        geometry = geometry,
                        onDone = { showcasing = false },
                    )
                }
            }
            if (ledShowing) {
                LedShowcase(
                    settings = settings,
                    color = SAMPLE_COLORS[sample].color,
                    geometry = geometry,
                    run = ledRun,
                    holding = ledHolding,
                    onDone = { ledShowing = false },
                )
            }
        }
    }
}

@Composable
private fun PageColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PageGutter, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        content()
        Spacer(Modifier.height(4.dp))
    }
}

/** What each access row's button does; shared by the setup cards and the compact summary. */
private class AccessActions(
    val listener: () -> Unit,
    val bridge: () -> Unit,
    val fullScreen: () -> Unit,
    val shield: () -> Unit,
)

@Composable
private fun rememberAccessActions(onRefresh: () -> Unit): AccessActions {
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
private fun AccessPage(access: AccessState, actions: AccessActions) {
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
        Text(
            text = stringResource(R.string.footer_privacy),
            style = MaterialTheme.typography.labelSmall,
            color = GlowPalette.TextFaint,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/** Armed state: one quiet card with a row per permission, opened from the ARMED pill. */
@Composable
private fun AccessSummary(access: AccessState, actions: AccessActions) {
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
        Text(
            text = stringResource(R.string.footer_privacy),
            style = MaterialTheme.typography.labelSmall,
            color = GlowPalette.TextFaint,
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun AccessRow(title: String, active: Boolean, onManage: () -> Unit) {
    val tint = if (active) GlowPalette.Lime else GlowPalette.Amber
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlowShapes.Pill)
            .clickable(role = Role.Button, onClick = onManage)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(7.dp)) { drawCircle(tint) }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = GlowPalette.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.access_manage),
            style = MaterialTheme.typography.labelSmall,
            color = GlowPalette.Cyan,
        )
    }
}

/**
 * Two groups, in the order things happen: what plays when a message arrives, then the LED that
 * waits until it is read. Each change is saved the moment it is made.
 */
@Composable
private fun StylePage(
    settings: GlowSettings,
    sample: Int,
    shieldOn: Boolean,
    onShield: () -> Unit,
    onChange: (GlowSettings) -> Unit,
    onSample: (Int) -> Unit,
    onShowcase: () -> Unit,
    onLed: (holding: Boolean) -> Unit,
) {
    val context = LocalContext.current
    // Latest value for slider commit callbacks, which fire after several onMove updates.
    var latest by remember { mutableStateOf(settings) }
    latest = settings

    SettingsGroup(index = "01", title = R.string.group_arrival_title, body = R.string.group_arrival_body) {
        EffectCard(
            settings = settings,
            sample = sample,
            onStyle = { style ->
                onChange(settings.copy(style = style))
                GlowPrefs.saveStyle(context, style)
                onShowcase()
            },
            onEffect = { next ->
                onChange(next)
                GlowPrefs.saveEffect(context, next)
                onShowcase()
            },
            onSample = { index ->
                onSample(index)
                onShowcase()
            },
        )
        ArrivalModeCard(
            selected = settings.arrival,
            shieldOn = shieldOn,
            onShield = onShield,
            onSelect = { mode ->
                onChange(settings.copy(arrival = mode))
                GlowPrefs.saveArrival(context, mode)
            },
        )
    }

    // The waiting LED is always the dot, whatever arrival style is chosen.
    SettingsGroup(index = "02", title = R.string.group_led_title, body = R.string.group_led_body) {
        LedCard(
            settings = settings,
            // While it moves, the real LED follows on screen; it blinks once when let go.
            onMove = { x, y, onCamera ->
                latest = latest.copy(dotX = x, dotY = y, ledOnCamera = onCamera)
                onChange(latest)
                onLed(true)
            },
            onCommit = {
                GlowPrefs.saveDot(context, latest.dotX, latest.dotY, latest.ledOnCamera)
                onLed(false)
            },
            onSize = { size ->
                latest = latest.copy(dotSize = size)
                onChange(latest)
                GlowPrefs.saveDotSize(context, size)
                onLed(false)
            },
            onBrightness = { level ->
                latest = latest.copy(ledBrightness = level)
                onChange(latest)
                GlowPrefs.saveLedBrightness(context, level)
                onLed(false)
            },
            onLensFit = { fit ->
                latest = fit(latest)
                onChange(latest)
                onLed(true)
            },
            onLensFitDone = {
                GlowPrefs.saveLensFit(context, latest.lensOffsetXDp, latest.lensOffsetDp, latest.lensGrowDp)
                onLed(false)
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Settings building blocks: group heading, card, divider, labelled option, toggle, radio
// ---------------------------------------------------------------------------------------------

/** A numbered heading with one line of context, then its cards. */
@Composable
private fun SettingsGroup(
    index: String,
    @StringRes title: Int,
    @StringRes body: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row {
            Text(
                text = index,
                style = MaterialTheme.typography.labelMedium,
                color = GlowPalette.TextFaint,
                modifier = Modifier.padding(top = 3.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    color = GlowPalette.TextPrimary,
                )
                Text(
                    text = stringResource(body),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlowPalette.TextMuted,
                )
            }
        }
        content()
    }
}

private fun Modifier.glowCard(): Modifier = this
    .fillMaxWidth()
    .clip(GlowShapes.Card)
    .background(GlowPalette.Surface)
    .border(1.dp, GlowPalette.OutlineSoft, GlowShapes.Card)
    .padding(20.dp)

@Composable
private fun CardDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(1.dp)
            .background(GlowPalette.OutlineSoft),
    )
}

/** A small caps label with its control right under it. */
@Composable
private fun OptionGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        content()
    }
}

/** The whole row toggles; the switch only shows the state. */
@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlowShapes.Tile)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = GlowPalette.TextPrimary,
            )
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = GlowPalette.TextMuted)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GlowPalette.Void,
                checkedTrackColor = GlowPalette.Cyan,
                checkedBorderColor = GlowPalette.Cyan,
                uncheckedThumbColor = GlowPalette.TextMuted,
                uncheckedTrackColor = GlowPalette.SurfaceHigh,
                uncheckedBorderColor = GlowPalette.Outline,
            ),
        )
    }
}

/** One choice of a list, with a line saying what it does. */
@Composable
private fun RadioRow(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val ring by animateColorAsState(if (selected) GlowPalette.Cyan else GlowPalette.Outline, label = "radio")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlowShapes.Tile)
            .background(if (selected) GlowPalette.SurfaceRaised else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Canvas(Modifier.padding(top = 2.dp).size(16.dp)) {
            val stroke = 1.5.dp.toPx()
            drawCircle(ring, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
            if (selected) drawCircle(GlowPalette.Cyan, radius = size.minDimension / 4f)
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = if (selected) GlowPalette.TextPrimary else GlowPalette.TextMuted,
            )
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = GlowPalette.TextMuted)
        }
    }
}

/** An amber heads-up with one fix-it action. */
@Composable
private fun NoticeRow(text: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlowShapes.Tile)
            .background(GlowPalette.Amber.copy(alpha = 0.08f))
            .border(1.dp, GlowPalette.Amber.copy(alpha = 0.4f), GlowShapes.Tile)
            .clickable(role = Role.Button, onClick = onAction)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = action.uppercase(), style = MaterialTheme.typography.labelMedium, color = GlowPalette.Amber)
    }
}

// ---------------------------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------------------------

/** One compact line: mark, name, and a status pill that opens access (or jumps to setup). */
@Composable
private fun TopBar(access: AccessState, accessOpen: Boolean, onStatusClick: () -> Unit) {
    val chevronTurn by animateFloatAsState(if (accessOpen) 180f else 0f, label = "chevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PageGutter, end = PageGutter - 4.dp, top = 14.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowEmblem(Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = GlowPalette.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        StatusPill(
            text = if (access.armed) {
                stringResource(R.string.status_armed)
            } else {
                stringResource(R.string.status_setup, access.granted, access.required)
            },
            tint = if (access.armed) GlowPalette.Lime else GlowPalette.Amber,
            onClick = onStatusClick,
        ) {
            if (access.armed) {
                Spacer(Modifier.width(6.dp))
                Chevron(
                    tint = GlowPalette.Lime,
                    modifier = Modifier.size(8.dp).graphicsLayer { rotationZ = chevronTurn },
                )
            }
        }
    }
}

@Composable
private fun Chevron(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        drawLine(tint, Offset(0f, size.height * 0.3f), Offset(size.width / 2f, size.height * 0.75f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(size.width / 2f, size.height * 0.75f), Offset(size.width, size.height * 0.3f), stroke, StrokeCap.Round)
    }
}

/** Brand mark: a cyan→magenta ring with a lime signal dot riding its edge. */
@Composable
private fun GlowEmblem(modifier: Modifier = Modifier) {
    val ringBrush = remember {
        Brush.sweepGradient(listOf(GlowPalette.Cyan, GlowPalette.Magenta, GlowPalette.Cyan))
    }
    Canvas(modifier) {
        val strokePx = size.minDimension * 0.12f
        val radius = size.minDimension / 2f - strokePx * 1.4f
        drawCircle(brush = ringBrush, radius = radius, style = Stroke(width = strokePx, cap = StrokeCap.Round))
        val angle = Math.toRadians(-50.0)
        val dot = Offset(
            x = center.x + radius * kotlin.math.cos(angle).toFloat(),
            y = center.y + radius * kotlin.math.sin(angle).toFloat(),
        )
        drawCircle(color = GlowPalette.Void, radius = strokePx * 1.5f, center = dot)
        drawCircle(color = GlowPalette.Lime, radius = strokePx * 1.05f, center = dot)
    }
}

@Composable
private fun SectionLabel(text: String, color: Color = GlowPalette.TextFaint) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun StatusPill(
    text: String,
    tint: Color,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .clip(GlowShapes.Pill)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.55f), GlowShapes.Pill)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(6.dp)) { drawCircle(tint) }
        Spacer(Modifier.width(7.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = tint)
        trailing()
    }
}

// ---------------------------------------------------------------------------------------------
// Tab bar
// ---------------------------------------------------------------------------------------------

/**
 * Segmented blade control. The highlighted blade tracks the pager's scroll position as you
 * swipe; that is read in the layout phase, so swiping does not recompose the bar.
 */
@Composable
private fun GlowTabBar(pager: PagerState, pendingAccess: Int, onSelect: (DashboardTab) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(GlowShapes.Tile)
            .background(GlowPalette.Surface)
            .border(1.dp, GlowPalette.OutlineSoft, GlowShapes.Tile)
            .padding(4.dp),
    ) {
        val tabWidth = maxWidth / DashboardTab.entries.size
        Box(
            modifier = Modifier
                .width(tabWidth)
                .fillMaxHeight()
                .offset {
                    val position = pager.currentPage + pager.currentPageOffsetFraction
                    IntOffset((tabWidth.toPx() * position).roundToInt(), 0)
                }
                .clip(GlowShapes.Tile)
                .background(GlowPalette.SurfaceRaised)
                .border(1.dp, GlowBrushes.Signature, GlowShapes.Tile),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            DashboardTab.entries.forEach { tab ->
                val selected = pager.currentPage == tab.ordinal
                val tint by animateColorAsState(
                    if (selected) GlowPalette.TextPrimary else GlowPalette.TextFaint,
                    label = "tab-tint",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(GlowShapes.Tile)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(tab.label),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = tint,
                    )
                    if (tab == DashboardTab.ACCESS && pendingAccess > 0) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(GlowShapes.Pill)
                                .background(GlowPalette.Amber)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = pendingAccess.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = GlowPalette.Void,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Access cards
// ---------------------------------------------------------------------------------------------

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

@Composable
private fun GhostButton(text: String, emphasized: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (emphasized) GlowPalette.Cyan else GlowPalette.TextMuted
    Box(
        modifier = modifier
            .clip(GlowShapes.GhostButton)
            .background(if (emphasized) GlowPalette.Cyan.copy(alpha = 0.08f) else GlowPalette.SurfaceRaised)
            .border(1.dp, tint.copy(alpha = if (emphasized) 0.7f else 0.25f), GlowShapes.GhostButton)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text.uppercase(), style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// 01 New message: the effect (live preview, style, try-out, spawn, Edge Frame options), where it plays
// ---------------------------------------------------------------------------------------------

private val StudioPreviewHeight: Dp = 250.dp
private val PickerPhoneHeight: Dp = 46.dp
private val PickerPhoneCorner: Dp = 6.dp

/** Matches the corner of [GlowShapes.Phone], so the previewed frame hugs the mock-up's edge. */
private val PhoneCorner: Dp = 14.dp

private const val PREVIEW_LOOP_GAP_MS = 700L

/** Black panel before the dot's first blink, as the LED takes over. */
private const val PREVIEW_LED_DELAY_MS = 350L

/** A colour to try the effect in. Real messages use the colour of the app that sent them. */
@Immutable
private data class SampleColor(@param:StringRes val name: Int, val color: Color)

private val SAMPLE_COLORS = listOf(
    SampleColor(R.string.sample_cyan, Color(DEFAULT_GLOW_COLOR)),
    SampleColor(R.string.sample_green, Color(0xFF25D366)),
    SampleColor(R.string.sample_blue, Color(0xFF1E88E5)),
    SampleColor(R.string.sample_violet, Color(0xFF8B5CF6)),
    SampleColor(R.string.sample_pink, GlowPalette.Magenta),
    SampleColor(R.string.sample_red, Color(0xFFFF4B33)),
    SampleColor(R.string.sample_yellow, Color(0xFFFFD60A)),
)

@Composable
private fun EffectCard(
    settings: GlowSettings,
    sample: Int,
    onStyle: (GlowStyle) -> Unit,
    onEffect: (GlowSettings) -> Unit,
    onSample: (Int) -> Unit,
) {
    val color = SAMPLE_COLORS[sample].color
    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(stringResource(R.string.section_effect), GlowPalette.Cyan)
        // Preview beside the style list: what you pick is what plays, without scrolling.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EffectPreview(settings = settings, color = color)
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionLabel(stringResource(R.string.effect_style))
                StylePicker(settings, color, onStyle)
            }
        }
        OptionGroup(stringResource(R.string.effect_sample_color)) {
            SampleColorRow(selected = sample, onSelect = onSample)
            Text(
                text = stringResource(R.string.effect_showcase_hint),
                style = MaterialTheme.typography.bodySmall,
                color = GlowPalette.TextFaint,
            )
        }
        CardDivider()
        ToggleRow(
            title = stringResource(R.string.effect_spawn_title),
            body = stringResource(R.string.effect_spawn_body),
            checked = settings.spawn,
            onChange = { onEffect(settings.copy(spawn = it)) },
        )
        // The other styles have nothing to tune here; the dot is placed in group 02.
        AnimatedVisibility(
            visible = settings.style == GlowStyle.EDGE_FRAME,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CardDivider()
                SectionLabel(stringResource(R.string.section_edge), GlowPalette.Cyan)
                OptionGroup(stringResource(R.string.edge_motion)) {
                    ChipRow(EdgeMotion.entries, settings.edgeMotion, { it.label }) { onEffect(settings.copy(edgeMotion = it)) }
                }
                OptionGroup(stringResource(R.string.edge_color)) {
                    ChipRow(EdgeColor.entries, settings.edgeColor, { it.label }) { onEffect(settings.copy(edgeColor = it)) }
                }
                OptionGroup(stringResource(R.string.edge_width)) {
                    ChipRow(EdgeWidth.entries, settings.edgeWidth, { it.label }) { onEffect(settings.copy(edgeWidth = it)) }
                }
                OptionGroup(stringResource(R.string.edge_glow)) {
                    ChipRow(EdgeGlow.entries, settings.edgeGlow, { it.label }) { onEffect(settings.copy(edgeGlow = it)) }
                }
            }
        }
    }
}

/** The three styles as rows: a mini phone showing the style in [accent], and its name. */
@Composable
private fun StylePicker(settings: GlowSettings, accent: Color, onSelect: (GlowStyle) -> Unit) {
    val corner = with(LocalDensity.current) { PickerPhoneCorner.toPx() }
    Column(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlowStyle.entries.forEach { style ->
            val selected = settings.style == style
            val border: Brush = if (selected) GlowBrushes.Signature else SolidColor(GlowPalette.OutlineSoft)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(GlowShapes.Tile)
                    .background(if (selected) GlowPalette.SurfaceRaised else GlowPalette.Void)
                    .border(if (selected) 1.5.dp else 1.dp, border, GlowShapes.Tile)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(style) })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhoneMock(
                    modifier = Modifier.height(PickerPhoneHeight).aspectRatio(0.55f),
                    shape = RoundedCornerShape(PickerPhoneCorner),
                ) { mockGeometry ->
                    GlowGraphic(
                        style = if (style == GlowStyle.CUSTOM_DOT) settings.ledStyle else style,
                        color = if (selected) accent else GlowPalette.TextFaint,
                        alpha = { 1f },
                        dotX = settings.dotX,
                        dotY = settings.dotY,
                        metrics = GlowMetrics.Tile,
                        geometry = mockGeometry.copy(cornerRadiusPx = corner),
                        dotRadius = previewDotRadius(settings.dotSize, TILE_PREVIEW_SCALE),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(style.title),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = if (selected) GlowPalette.TextPrimary else GlowPalette.TextMuted,
                    maxLines = 2,
                )
            }
        }
    }
}

/** One swatch per sample colour, equal widths. */
@Composable
private fun SampleColorRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SAMPLE_COLORS.forEachIndexed { index, sample ->
            val isSelected = index == selected
            val ring by animateColorAsState(
                if (isSelected) GlowPalette.TextPrimary else Color.Transparent,
                label = "swatch",
            )
            val name = stringResource(sample.name)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(GlowShapes.Pill)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(index) })
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(28.dp)) {
                    val stroke = 1.5.dp.toPx()
                    drawCircle(ring, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
                    drawCircle(sample.color, radius = size.minDimension / 2f - stroke * 2.5f)
                }
            }
        }
    }
}

/** Where the inline preview is in the story a message tells: its effect, then the LED. */
private enum class PreviewPhase { EFFECT, LED, REST }

/**
 * The whole arrival in a phone mock-up, looped: the screen the effect plays on (lock screen,
 * black, or black with the message pop-up, as chosen under Where it plays), the real
 * [ArrivalEffect] scaled to it, then the black panel and one blink of the LED dot. Any change,
 * or a tap, restarts it from the effect's first frame.
 */
@Composable
private fun EffectPreview(settings: GlowSettings, color: Color) {
    var run by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(PreviewPhase.EFFECT) }
    val led = remember { Animatable(0f) }
    LaunchedEffect(settings, color) { phase = PreviewPhase.EFFECT }
    LaunchedEffect(phase) {
        when (phase) {
            PreviewPhase.EFFECT -> led.snapTo(0f)
            PreviewPhase.LED -> {
                delay(PREVIEW_LED_DELAY_MS)
                led.animateTo(1f, tween(LED_FADE_IN_MS, easing = FastOutSlowInEasing))
                delay(LED_HOLD_MS)
                led.animateTo(0f, tween(LED_FADE_OUT_MS, easing = LinearOutSlowInEasing))
                phase = PreviewPhase.REST
            }
            PreviewPhase.REST -> {
                delay(PREVIEW_LOOP_GAP_MS)
                run++
                phase = PreviewPhase.EFFECT
            }
        }
    }
    val playing = phase == PreviewPhase.EFFECT
    // The screen behind the effect lights with it and goes dark as the LED takes over.
    val lockScreen = animateFloatAsState(
        targetValue = if (playing && settings.arrival == ArrivalMode.LOCK_SCREEN) 1f else 0f,
        animationSpec = tween(if (playing) 200 else 450),
        label = "lock-screen",
    )
    val popUp = animateFloatAsState(
        targetValue = if (playing && settings.arrival == ArrivalMode.MESSAGE) 1f else 0f,
        animationSpec = tween(if (playing) 300 else 450),
        label = "pop-up",
    )

    val density = LocalDensity.current
    val screenHeight = LocalWindowInfo.current.containerSize.height.toFloat()
    val scale = with(density) { StudioPreviewHeight.toPx() / screenHeight.coerceAtLeast(1f) }.coerceIn(0.1f, 1f)
    val corner = with(density) { PhoneCorner.toPx() }
    val previewLabel = stringResource(R.string.effect_preview_label)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhoneMock(
            Modifier
                .height(StudioPreviewHeight)
                .aspectRatio(0.48f)
                .clip(GlowShapes.Phone)
                .clickable(role = Role.Button) {
                    run++
                    phase = PreviewPhase.EFFECT
                }
                .semantics { contentDescription = previewLabel },
        ) { mockGeometry ->
            MockLockScreen(accent = color, alpha = { lockScreen.value })
            MockPopUp(accent = color, alpha = { popUp.value })
            if (playing) {
                key(run, settings, color) {
                    ArrivalEffect(
                        settings = settings,
                        color = color.toArgb(),
                        geometry = mockGeometry.copy(cornerRadiusPx = corner),
                        onDone = { phase = PreviewPhase.LED },
                        scale = scale,
                    )
                }
            }
            GlowGraphic(
                style = settings.ledStyle,
                color = color,
                alpha = { led.value },
                dotX = settings.dotX,
                dotY = settings.dotY,
                metrics = GlowMetrics.Panel,
                geometry = mockGeometry,
                dotRadius = previewDotRadius(settings.dotSize, scale),
                modifier = Modifier.fillMaxSize(),
            )
        }
        PreviewSteps(ledActive = !playing)
    }
}

/** "EFFECT → LED", the current step lit. */
@Composable
private fun PreviewSteps(ledActive: Boolean) {
    val effectTint by animateColorAsState(if (ledActive) GlowPalette.TextFaint else GlowPalette.Cyan, label = "step-effect")
    val ledTint by animateColorAsState(if (ledActive) GlowPalette.Cyan else GlowPalette.TextFaint, label = "step-led")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.preview_step_effect), style = MaterialTheme.typography.labelSmall, color = effectTint)
        Chevron(
            tint = GlowPalette.TextFaint,
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .size(7.dp)
                .graphicsLayer { rotationZ = -90f },
        )
        Text(stringResource(R.string.preview_step_led), style = MaterialTheme.typography.labelSmall, color = ledTint)
    }
}

/** Notification cards of the mock-ups: an app icon in [icon] and two lines of text. */
private fun DrawScope.mockCard(top: Float, height: Float, icon: Color) {
    val side = size.width * 0.06f
    val cardWidth = size.width - side * 2f
    drawRoundRect(
        color = GlowPalette.SurfaceHigh,
        topLeft = Offset(side, top),
        size = Size(cardWidth, height),
        cornerRadius = CornerRadius(height * 0.3f),
    )
    val iconRadius = height * 0.17f
    val iconX = side + height * 0.38f
    drawCircle(icon, iconRadius, Offset(iconX, top + height / 2f))
    val textX = iconX + iconRadius * 2.2f
    val line = height * 0.1f
    val textWidth = side + cardWidth - textX - height * 0.3f
    val lines = CornerRadius(line / 2f)
    drawRoundRect(GlowPalette.Outline, Offset(textX, top + height * 0.34f), Size(textWidth * 0.55f, line), lines)
    drawRoundRect(GlowPalette.Outline, Offset(textX, top + height * 0.58f), Size(textWidth * 0.85f, line), lines)
}

/** A lock screen in miniature: clock, the new message (in [accent]) over an older one, shortcuts. */
@Composable
private fun MockLockScreen(accent: Color, alpha: () -> Float) {
    // Sized in dp, not sp: it is part of the drawing, so it must not grow with the font scale.
    val clockSize = with(LocalDensity.current) { 26.dp.toSp() }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha() }) {
        Text(
            text = stringResource(R.string.preview_clock),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Light,
                fontSize = clockSize,
                lineHeight = clockSize,
            ),
            color = GlowPalette.TextPrimary.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp),
        )
        Canvas(Modifier.fillMaxSize()) {
            val dateWidth = size.width * 0.36f
            drawRoundRect(
                color = GlowPalette.Outline,
                topLeft = Offset((size.width - dateWidth) / 2f, size.height * 0.27f),
                size = Size(dateWidth, 2.5.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
            val cardHeight = size.height * 0.11f
            mockCard(size.height * 0.42f, cardHeight, accent)
            mockCard(size.height * 0.42f + cardHeight + 4.dp.toPx(), cardHeight, GlowPalette.TextFaint)
            val shortcut = size.width * 0.07f
            val shortcutY = size.height - shortcut * 2.2f
            drawCircle(GlowPalette.SurfaceHigh, shortcut, Offset(shortcut * 2.2f, shortcutY))
            drawCircle(GlowPalette.SurfaceHigh, shortcut, Offset(size.width - shortcut * 2.2f, shortcutY))
        }
    }
}

/** The system's pop-up of only the new message, sliding in under the camera. */
@Composable
private fun MockPopUp(accent: Color, alpha: () -> Float) {
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val shown = alpha()
                this.alpha = shown
                translationY = -(1f - shown) * 6.dp.toPx()
            },
    ) {
        mockCard(size.height * 0.08f, size.height * 0.11f, accent)
    }
}

@Composable
private fun ArrivalModeCard(
    selected: ArrivalMode,
    shieldOn: Boolean,
    onShield: () -> Unit,
    onSelect: (ArrivalMode) -> Unit,
) {
    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(stringResource(R.string.arrival_mode), GlowPalette.Cyan)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ArrivalMode.entries.forEach { mode ->
                RadioRow(
                    title = stringResource(mode.label),
                    body = stringResource(mode.body),
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                )
            }
        }
        // Without the shield, Lock screen only lights the screen: say so where it is chosen.
        AnimatedVisibility(
            visible = selected == ArrivalMode.LOCK_SCREEN && !shieldOn,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            NoticeRow(
                text = stringResource(R.string.arrival_needs_shield),
                action = stringResource(R.string.access_shield_grant),
                onAction = onShield,
            )
        }
    }
}

/** One option per chip, equal widths. */
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> Int, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            BladeChip(
                label = stringResource(label(option)),
                selected = option == selected,
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
                compact = true,
            )
        }
    }
}

/** Camera position as screen fractions (radius as a fraction of screen width). */
@Immutable
private data class ScreenCamera(val x: Float, val y: Float, val radius: Float)

private val LocalCamera = staticCompositionLocalOf { ScreenCamera(x = 0.5f, y = 0.03f, radius = 0.03f) }

@Composable
private fun rememberCamera(geometry: ScreenGeometry): ScreenCamera {
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(geometry, window, density) {
        val w = window.width.toFloat().coerceAtLeast(1f)
        val h = window.height.toFloat().coerceAtLeast(1f)
        val spot = geometry.cutout
        if (spot != null) {
            ScreenCamera(x = spot.centerX / w, y = spot.centerY / h, radius = spot.radius / w)
        } else {
            with(density) {
                ScreenCamera(
                    x = 0.5f,
                    y = GlowMetrics.FullScreen.fallbackCameraCenterY.toPx() / h,
                    radius = GlowMetrics.FullScreen.fallbackCameraRadius.toPx() / w,
                )
            }
        }
    }
}

/**
 * A minimal handset silhouette: black panel, hairline bezel and this phone's punch-hole
 * camera drawn where it really is. [content] receives that camera scaled into the mock-up,
 * so Camera Ring previews circle the drawn lens.
 */
@Composable
private fun PhoneMock(
    modifier: Modifier = Modifier,
    shape: Shape = GlowShapes.Phone,
    content: @Composable BoxScope.(ScreenGeometry) -> Unit,
) {
    val camera = LocalCamera.current
    val minRadius = with(LocalDensity.current) { 1.5.dp.toPx() }
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(GlowPalette.Void)
            .border(1.dp, GlowPalette.Outline, shape),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val lens = remember(camera, w, h) {
            CutoutSpot(centerX = camera.x * w, centerY = camera.y * h, radius = (camera.radius * w).coerceAtLeast(minRadius))
        }
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(GlowPalette.SurfaceHighest, radius = lens.radius, center = Offset(lens.centerX, lens.centerY))
        }
        content(remember(lens) { ScreenGeometry(cutout = lens, cornerRadiusPx = null) })
    }
}

// ---------------------------------------------------------------------------------------------
// Dot position
// ---------------------------------------------------------------------------------------------

private val PanelHeight: Dp = 280.dp
private const val TILE_PREVIEW_SCALE = 0.22f
private val MIN_PREVIEW_DOT = 1.4.dp

private const val SNAP_PRESET = 0.03f
private const val SNAP_RULER = 0.012f
private val RULER_MAJORS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
private const val RULER_STEPS = 20

/** The real dot scaled down to a mock-up, kept just large enough to see. */
private fun previewDotRadius(size: DotSize, scale: Float): Dp =
    (size.radius * scale).coerceAtLeast(MIN_PREVIEW_DOT)

/** A dot position after magnetic snapping. [target] identifies what it snapped to, for haptics. */
private data class Snapped(val x: Float, val y: Float, val target: Any?)

private fun snapDot(x: Float, y: Float, spots: List<DotSpot>): Snapped {
    spots
        .firstOrNull { abs(it.x - x) < SNAP_PRESET && abs(it.y - y) < SNAP_PRESET }
        ?.let { return Snapped(it.x, it.y, it) }
    val sx = RULER_MAJORS.firstOrNull { abs(it - x) < SNAP_RULER }
    val sy = RULER_MAJORS.firstOrNull { abs(it - y) < SNAP_RULER }
    val target = if (sx != null || sy != null) sx to sy else null
    return Snapped(sx ?: x, sy ?: y, target)
}

/**
 * A one-tap LED position; [onCameraLine] spots sit at the exact height of the lens centre.
 * The [camera] spot is the lens itself: the LED there lights as a ring around it.
 */
@Immutable
private data class DotSpot(
    @param:StringRes val label: Int,
    val x: Float,
    val y: Float,
    val onCameraLine: Boolean,
    val camera: Boolean = false,
) {
    fun matches(x: Float, y: Float) = abs(this.x - x) < 0.001f && abs(this.y - y) < 0.001f
}

private val SPOT_EDGE = 18.dp
private val SPOT_CAMERA_GAP = 10.dp

/**
 * LED spots built from this phone's real camera: the lens itself (the ring), four on the
 * camera's horizontal line (screen edges and either side of the lens), one straight below it,
 * plus the classic corners and bottom. Positions are converted with the same margin the full-screen LED uses, so a spot
 * picked here lands exactly there.
 */
@Composable
private fun rememberDotSpots(dotSize: DotSize): List<DotSpot> {
    val camera = LocalCamera.current
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(camera, window, dotSize, density) {
        with(density) {
            val w = window.width.toFloat().coerceAtLeast(1f)
            val h = window.height.toFloat().coerceAtLeast(1f)
            val dot = dotSize.radius.toPx()
            val margin = dot * DOT_HALO_FACTOR
            fun fx(px: Float) = ((px - margin) / (w - margin * 2f).coerceAtLeast(1f)).coerceIn(0f, 1f)
            fun fy(py: Float) = ((py - margin) / (h - margin * 2f).coerceAtLeast(1f)).coerceIn(0f, 1f)

            val camX = camera.x * w
            val camY = camera.y * h
            val clear = camera.radius * w + SPOT_CAMERA_GAP.toPx() + dot
            val edge = SPOT_EDGE.toPx()
            val line = fy(camY)
            listOf(
                DotSpot(R.string.dot_spot_ring, fx(camX), line, onCameraLine = false, camera = true),
                DotSpot(R.string.dot_spot_edge_left, fx(edge), line, onCameraLine = true),
                DotSpot(R.string.dot_spot_cam_left, fx(camX - clear), line, onCameraLine = true),
                DotSpot(R.string.dot_spot_cam_right, fx(camX + clear), line, onCameraLine = true),
                DotSpot(R.string.dot_spot_edge_right, fx(w - edge), line, onCameraLine = true),
                DotSpot(R.string.dot_spot_below_cam, fx(camX), fy(camY + clear), onCameraLine = false),
                DotSpot(R.string.dot_spot_corner_left, 0.06f, 0.008f, onCameraLine = false),
                DotSpot(R.string.dot_spot_corner_right, 0.94f, 0.008f, onCameraLine = false),
                DotSpot(R.string.dot_spot_bottom, 0.5f, 0.992f, onCameraLine = false),
            )
        }
    }
}

/** How dark the dashboard goes behind the LED preview: nearly the LED's black panel, controls still visible. */
private const val LED_SCRIM = 0.85f
private const val LED_SCRIM_IN_MS = 200
private const val LED_SCRIM_OUT_MS = 300
private const val LED_FOLLOW_IN_MS = 150

/**
 * The real LED over the darkened dashboard: its own drawing ([LedDot]) at its real size and
 * position, with the window at the chosen LED brightness, since on an AMOLED panel that is what
 * sets how bright the dot is. While [holding] (the dot is being moved) it stays lit and follows;
 * otherwise it blinks once with the LED's timing and fades away. A new [run] blinks again.
 * Only draws, so the controls underneath keep working.
 */
@Composable
private fun LedShowcase(
    settings: GlowSettings,
    color: Color,
    geometry: ScreenGeometry,
    run: Int,
    holding: Boolean,
    onDone: () -> Unit,
) {
    val scrim = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(run, holding) {
        launch { scrim.animateTo(LED_SCRIM, tween(LED_SCRIM_IN_MS)) }
        if (holding) {
            glow.animateTo(1f, tween(LED_FOLLOW_IN_MS))
            return@LaunchedEffect
        }
        glow.animateTo(1f, tween(LED_FADE_IN_MS, easing = FastOutSlowInEasing))
        delay(LED_HOLD_MS)
        glow.animateTo(0f, tween(LED_FADE_OUT_MS, easing = LinearOutSlowInEasing))
        scrim.animateTo(0f, tween(LED_SCRIM_OUT_MS))
        done()
    }
    val window = LocalActivity.current?.window
    val level = settings.ledBrightness.level
    DisposableEffect(window, level) {
        window?.setBrightness(level)
        onDispose { window?.setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }
    // The clock, battery and gesture handle draw above the app, so hide them as the LED screen
    // does; dark while they slide out, so they vanish into the black at once.
    DisposableEffect(window) {
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        bars?.apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            bars?.apply {
                show(WindowInsetsCompat.Type.systemBars())
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    Spacer(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black, alpha = scrim.value) })
    LedDot(
        color = color,
        alpha = { glow.value },
        dotX = settings.dotX,
        dotY = settings.dotY,
        radius = settings.dotSize.radius,
        onCamera = settings.ledOnCamera,
        geometry = geometry,
        modifier = Modifier.fillMaxSize(),
    )
}

private fun Window.setBrightness(level: Float) {
    attributes = attributes.apply { screenBrightness = level }
}

@Composable
private fun LedCard(
    settings: GlowSettings,
    onMove: (x: Float, y: Float, onCamera: Boolean) -> Unit,
    onCommit: () -> Unit,
    onSize: (DotSize) -> Unit,
    onBrightness: (LedBrightness) -> Unit,
    onLensFit: (fit: (GlowSettings) -> GlowSettings) -> Unit,
    onLensFitDone: () -> Unit,
) {
    val dotX = settings.dotX
    val dotY = settings.dotY
    val sliderColors = glowSliderColors()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var lastSnap by remember { mutableStateOf<Any?>(null) }
    val spots = rememberDotSpots(settings.dotSize)

    // Every input path (drag, tap, both sliders) goes through the same magnetic snap. Snapping
    // onto the lens turns the LED into the ring; moving off it makes it a dot again.
    val move: (Float, Float) -> Unit = { x, y ->
        val snapped = snapDot(x, y, spots)
        if (snapped.target != null && snapped.target != lastSnap) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        lastSnap = snapped.target
        onMove(snapped.x, snapped.y, (snapped.target as? DotSpot)?.camera == true)
    }

    // The − / + steps: 1 dp per tap, past the snap so small steps are not pulled back. The LED
    // stays lit while tapping and settles once the taps stop.
    val window = LocalWindowInfo.current.containerSize
    val margin = with(density) { settings.dotSize.radius.toPx() } * DOT_HALO_FACTOR
    val stepX = density.density / (window.width - margin * 2f).coerceAtLeast(1f)
    val stepY = density.density / (window.height - margin * 2f).coerceAtLeast(1f)
    var dotSteps by remember { mutableIntStateOf(0) }
    val step: (Float, Float) -> Unit = { dx, dy ->
        lastSnap = null
        onMove((dotX + dx).coerceIn(0f, 1f), (dotY + dy).coerceIn(0f, 1f), false)
        dotSteps++
    }
    val commit by rememberUpdatedState(onCommit)
    LaunchedEffect(dotSteps) {
        if (dotSteps == 0) return@LaunchedEffect
        delay(STEP_SETTLE_MS)
        commit()
    }

    // Scale the real dot into the mock-up so LED vs L reads honestly.
    val screenHeightDp = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val previewScale = (PanelHeight / screenHeightDp.coerceAtLeast(PanelHeight)).coerceIn(0.1f, 1f)
    val previewRadius = previewDotRadius(settings.dotSize, previewScale)

    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionLabel(stringResource(R.string.section_dot), GlowPalette.Cyan)
        Text(
            text = stringResource(R.string.dot_body),
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextMuted,
        )

        Row(
            modifier = Modifier.fillMaxWidth().height(PanelHeight).padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val previewLabel = stringResource(R.string.dot_preview_label)
            PhoneMock(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(0.48f)
                    .semantics { contentDescription = previewLabel },
            ) { mockGeometry ->
                DotRuler(
                    dotX = dotX,
                    dotY = dotY,
                    dotRadius = previewRadius,
                    spots = spots,
                    modifier = Modifier.fillMaxSize(),
                )
                GlowGraphic(
                    style = settings.ledStyle,
                    color = GlowPalette.Cyan,
                    alpha = { 1f },
                    dotX = dotX,
                    dotY = dotY,
                    metrics = GlowMetrics.Panel,
                    geometry = mockGeometry,
                    dotRadius = previewRadius,
                    modifier = Modifier.fillMaxSize(),
                )
                val margin = with(density) { previewRadius.toPx() * DOT_HALO_FACTOR }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(margin) {
                            detectTapGestures { position ->
                                val f = dotFraction(position, size.toSize(), margin)
                                move(f.x, f.y)
                                onCommit()
                            }
                        }
                        .pointerInput(margin) {
                            detectDragGestures(onDragEnd = onCommit, onDragCancel = onCommit) { change, _ ->
                                change.consume()
                                val f = dotFraction(change.position, size.toSize(), margin)
                                move(f.x, f.y)
                            }
                        },
                )
            }
            Spacer(Modifier.width(8.dp))
            val verticalLabel = stringResource(R.string.dot_vertical)
            Column(
                modifier = Modifier.fillMaxHeight().width(44.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Same sense as the Y readout: − up, + down.
                StepButton(plus = false, description = stringResource(R.string.dot_step_up), onStep = { step(0f, -stepY) })
                // Rotated so the top of the slider is the top of the screen.
                VerticalSlider(
                    value = 1f - dotY,
                    onValueChange = { move(dotX, 1f - it) },
                    onValueChangeFinished = onCommit,
                    colors = sliderColors,
                    modifier = Modifier
                        .weight(1f)
                        .width(44.dp)
                        .semantics { contentDescription = verticalLabel },
                )
                StepButton(plus = true, description = stringResource(R.string.dot_step_down), onStep = { step(0f, stepY) })
            }
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Readout(stringResource(R.string.dot_readout_x, (dotX * 100).roundToInt()))
                Readout(stringResource(R.string.dot_readout_y, (dotY * 100).roundToInt()))
            }
        }

        val horizontalLabel = stringResource(R.string.dot_horizontal)
        OptionGroup(horizontalLabel) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StepButton(plus = false, description = stringResource(R.string.dot_step_left), onStep = { step(-stepX, 0f) })
                Slider(
                    value = dotX,
                    onValueChange = { move(it, dotY) },
                    onValueChangeFinished = onCommit,
                    colors = sliderColors,
                    modifier = Modifier.weight(1f).semantics { contentDescription = horizontalLabel },
                )
                StepButton(plus = true, description = stringResource(R.string.dot_step_right), onStep = { step(stepX, 0f) })
            }
        }

        val pick: (DotSpot) -> Unit = { spot ->
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastSnap = spot
            onMove(spot.x, spot.y, spot.camera)
            onCommit()
        }
        spots.firstOrNull { it.camera }?.let { ring ->
            OptionGroup(stringResource(R.string.dot_spots_lens)) {
                BladeChip(
                    label = stringResource(ring.label),
                    selected = settings.ledOnCamera,
                    onClick = { pick(ring) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Canvas(Modifier.size(14.dp)) {
                        val line = 2.dp.toPx()
                        drawCircle(GlowPalette.SurfaceHighest, radius = size.minDimension / 2f - line * 1.5f)
                        drawCircle(GlowPalette.Cyan, radius = size.minDimension / 2f - line / 2f, style = Stroke(line))
                    }
                    Spacer(Modifier.width(10.dp))
                }
            }
        }
        AnimatedVisibility(
            visible = settings.ledOnCamera,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            LensFit(settings, onLensFit, onLensFitDone)
        }
        OptionGroup(stringResource(R.string.dot_spots_camera_line)) {
            SpotRow(spots.filter { it.onCameraLine }, dotX, dotY, pick)
        }
        OptionGroup(stringResource(R.string.dot_spots_other)) {
            SpotRow(spots.filterNot { it.onCameraLine || it.camera }, dotX, dotY, pick)
        }

        CardDivider()
        // On the camera, size is how thick the ring is.
        OptionGroup(stringResource(if (settings.ledOnCamera) R.string.dot_ring_thickness else R.string.dot_size)) {
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DotSize.entries.forEach { size ->
                    BladeChip(
                        label = stringResource(size.label),
                        selected = settings.dotSize == size,
                        onClick = { onSize(size) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Canvas(Modifier.size(size.radius * 2)) { drawCircle(GlowPalette.Cyan) }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
        OptionGroup(stringResource(R.string.led_brightness)) {
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LedBrightness.entries.forEach { level ->
                    BladeChip(
                        label = stringResource(level.label),
                        selected = settings.ledBrightness == level,
                        onClick = { onBrightness(level) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Canvas(Modifier.size(8.dp)) { drawCircle(GlowPalette.Cyan.copy(alpha = level.level)) }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
    }
}

private const val STEP_REPEAT_DELAY_MS = 400L
private const val STEP_REPEAT_MS = 60L

/** After the last − / + tap, how long the LED stays lit before it is saved and blinks out. */
private const val STEP_SETTLE_MS = 1_200L

/** How far the camera fit can go either way, in physical pixels. */
private const val LENS_FIT_MAX_PX = 40

/** A − or + step; holding it repeats. */
@Composable
private fun StepButton(plus: Boolean, description: String, onStep: () -> Unit, modifier: Modifier = Modifier) {
    val step by rememberUpdatedState(onStep)
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(GlowShapes.Pill)
            .background(GlowPalette.SurfaceRaised)
            .border(1.dp, GlowPalette.OutlineSoft, GlowShapes.Pill)
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick {
                    step()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    step()
                    var wait = STEP_REPEAT_DELAY_MS
                    // Null only on timeout: still held, so step again, faster.
                    while (withTimeoutOrNull(wait) { waitForUpOrCancellation(); true } == null) {
                        step()
                        wait = STEP_REPEAT_MS
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val line = 1.5.dp.toPx()
            drawLine(GlowPalette.TextPrimary, Offset(0f, center.y), Offset(size.width, center.y), line, StrokeCap.Round)
            if (plus) drawLine(GlowPalette.TextPrimary, Offset(center.x, 0f), Offset(center.x, size.height), line, StrokeCap.Round)
        }
    }
}

/**
 * Lines the ring up with the real lens, one physical pixel per tap: the cutout some phones
 * report is only a rectangle from the top edge. The ring stays lit on screen while tapping.
 */
@Composable
private fun LensFit(settings: GlowSettings, onFit: (fit: (GlowSettings) -> GlowSettings) -> Unit, onDone: () -> Unit) {
    val density = LocalDensity.current.density
    fun px(dp: Float) = (dp * density).roundToInt()
    fun dp(px: Int) = px.coerceIn(-LENS_FIT_MAX_PX, LENS_FIT_MAX_PX) / density
    val offsetXPx = px(settings.lensOffsetXDp)
    val offsetPx = px(settings.lensOffsetDp)
    val growPx = px(settings.lensGrowDp)
    var steps by remember { mutableIntStateOf(0) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(steps) {
        if (steps == 0) return@LaunchedEffect
        delay(STEP_SETTLE_MS)
        done()
    }
    val fit: (Int, Int, Int) -> Unit = { x, y, grow ->
        onFit { it.copy(lensOffsetXDp = dp(x), lensOffsetDp = dp(y), lensGrowDp = dp(grow)) }
        steps++
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlowShapes.Tile)
            .background(GlowPalette.SurfaceRaised)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.lens_fit), GlowPalette.Cyan)
            Spacer(Modifier.weight(1f))
            if (offsetXPx != 0 || offsetPx != 0 || growPx != 0) {
                Text(
                    text = stringResource(R.string.lens_fit_reset),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlowPalette.Cyan,
                    modifier = Modifier
                        .clip(GlowShapes.Pill)
                        .clickable(role = Role.Button) { fit(0, 0, 0) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.lens_fit_body),
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextMuted,
        )
        FitRow(
            label = stringResource(R.string.lens_fit_height),
            valuePx = offsetPx,
            minus = stringResource(R.string.lens_fit_up),
            plus = stringResource(R.string.lens_fit_down),
            onStep = { fit(offsetXPx, offsetPx + it, growPx) },
        )
        FitRow(
            label = stringResource(R.string.lens_fit_side),
            valuePx = offsetXPx,
            minus = stringResource(R.string.lens_fit_left),
            plus = stringResource(R.string.lens_fit_right),
            onStep = { fit(offsetXPx + it, offsetPx, growPx) },
        )
        FitRow(
            label = stringResource(R.string.lens_fit_radius),
            valuePx = growPx,
            minus = stringResource(R.string.lens_fit_smaller),
            plus = stringResource(R.string.lens_fit_bigger),
            onStep = { fit(offsetXPx, offsetPx, growPx + it) },
        )
    }
}

@Composable
private fun FitRow(label: String, valuePx: Int, minus: String, plus: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = GlowPalette.TextMuted,
            modifier = Modifier.weight(1f),
        )
        StepButton(plus = false, description = minus, onStep = { onStep(-1) })
        Text(
            text = stringResource(R.string.lens_fit_px, valuePx),
            style = MaterialTheme.typography.labelMedium,
            color = GlowPalette.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )
        StepButton(plus = true, description = plus, onStep = { onStep(1) })
    }
}

@Composable
private fun SpotRow(spots: List<DotSpot>, dotX: Float, dotY: Float, onPick: (DotSpot) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        spots.forEach { spot ->
            BladeChip(
                label = stringResource(spot.label),
                selected = spot.matches(dotX, dotY),
                onClick = { onPick(spot) },
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Graduated ruler for the dot mock-up: ticks every 5 % along the top and left edges, longer
 * marks at quarters, dashed crosshairs through the dot, the camera line, and a pin per spot.
 */
@Composable
private fun DotRuler(dotX: Float, dotY: Float, dotRadius: Dp, spots: List<DotSpot>, modifier: Modifier = Modifier) {
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) }
    Canvas(modifier) {
        val margin = dotRadius.toPx() * DOT_HALO_FACTOR
        val minor = 3.dp.toPx()
        val major = 7.dp.toPx()
        val hair = 1.dp.toPx()
        val spanX = size.width - margin * 2f
        val spanY = size.height - margin * 2f

        for (i in 0..RULER_STEPS) {
            val f = i / RULER_STEPS.toFloat()
            val isMajor = i % (RULER_STEPS / 4) == 0
            val length = if (isMajor) major else minor
            val color = if (isMajor) GlowPalette.TextFaint else GlowPalette.Outline
            val x = margin + f * spanX
            val y = margin + f * spanY
            drawLine(color, Offset(x, 0f), Offset(x, length), strokeWidth = hair)
            drawLine(color, Offset(0f, y), Offset(length, y), strokeWidth = hair)
        }

        val center = dotCenter(dotX, dotY, size, margin)
        val guide = GlowPalette.Cyan.copy(alpha = 0.22f)
        drawLine(guide, Offset(center.x, 0f), Offset(center.x, size.height), hair, pathEffect = dash)
        drawLine(guide, Offset(0f, center.y), Offset(size.width, center.y), hair, pathEffect = dash)
        drawLine(GlowPalette.Cyan, Offset(center.x, 0f), Offset(center.x, major), hair * 2f)
        drawLine(GlowPalette.Cyan, Offset(0f, center.y), Offset(major, center.y), hair * 2f)

        // The camera line: every spot on it shares the lens centre's height.
        spots.firstOrNull { it.onCameraLine }?.let { spot ->
            val y = dotCenter(0f, spot.y, size, margin).y
            drawLine(GlowPalette.Lime.copy(alpha = 0.28f), Offset(0f, y), Offset(size.width, y), hair, pathEffect = dash)
        }

        val pinRadius = 3.5.dp.toPx()
        spots.forEach { spot ->
            drawCircle(
                color = if (spot.matches(dotX, dotY)) GlowPalette.Lime else GlowPalette.TextFaint,
                radius = pinRadius,
                center = dotCenter(spot.x, spot.y, size, margin),
                style = Stroke(width = hair),
            )
        }
    }
}

@Composable
private fun Readout(text: String) {
    Box(
        modifier = Modifier
            .clip(GlowShapes.Pill)
            .background(GlowPalette.SurfaceRaised)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = GlowPalette.TextPrimary)
    }
}

/** Material Slider rotated 270° with its measured width and height swapped. */
@Composable
private fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    colors: SliderColors,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        modifier = modifier
            .graphicsLayer {
                rotationZ = 270f
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth,
                    ),
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(-placeable.width, 0)
                }
            },
    )
}

@Composable
private fun glowSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = GlowPalette.Cyan,
    activeTrackColor = GlowPalette.Cyan,
    activeTickColor = GlowPalette.Void,
    inactiveTrackColor = GlowPalette.SurfaceHigh,
    inactiveTickColor = GlowPalette.Outline,
)

// ---------------------------------------------------------------------------------------------
// Chips
// ---------------------------------------------------------------------------------------------

@Composable
private fun BladeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    leading: @Composable () -> Unit = {},
) {
    val border: Brush = if (selected) GlowBrushes.Signature else SolidColor(GlowPalette.OutlineSoft)
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(GlowShapes.Pill)
            .background(if (selected) GlowPalette.SurfaceRaised else GlowPalette.Void)
            .border(1.dp, border, GlowShapes.Pill)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Text(
            text = label,
            style = if (compact) {
                MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            } else {
                MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
            },
            color = if (selected) GlowPalette.TextPrimary else GlowPalette.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Test dock (pinned under every page)
// ---------------------------------------------------------------------------------------------

private const val COUNTDOWN_SECONDS = (GlowLauncher.TEST_DELAY_MS / 1_000L).toInt()

@Composable
private fun TestDock() {
    val context = LocalContext.current
    var countdown by remember { mutableIntStateOf(0) }
    var runs by remember { mutableIntStateOf(0) }
    var blocked by remember { mutableStateOf(false) }

    // Display-only countdown; the message itself is posted by GlowLauncher's single callback.
    LaunchedEffect(runs) {
        if (runs == 0) return@LaunchedEffect
        for (remaining in COUNTDOWN_SECONDS downTo 1) {
            countdown = remaining
            delay(1_000L)
        }
        countdown = 0
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .graphicsLayer { alpha = 0.3f }
            .background(GlowBrushes.SignatureHorizontal),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlowPalette.Void)
            .padding(horizontal = PageGutter, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The one end-to-end check: real messages through the listener, on the locked phone.
        // The effect alone replays at full size whenever it is changed.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .graphicsLayer { alpha = if (countdown > 0) 0.55f else 1f }
                .clip(GlowShapes.Button)
                .background(GlowBrushes.SignatureHorizontal)
                .clickable(role = Role.Button, enabled = countdown == 0) {
                    blocked = !GlowLauncher.scheduleTestNotification(context)
                    if (!blocked) runs++
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (countdown > 0) {
                    stringResource(R.string.test_locked_countdown, countdown)
                } else {
                    stringResource(R.string.test_locked)
                },
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = GlowPalette.Void,
            )
        }
        AnimatedVisibility(visible = blocked || countdown > 0) {
            Text(
                text = stringResource(if (blocked) R.string.test_locked_blocked else R.string.test_locked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (blocked) GlowPalette.Amber else GlowPalette.TextMuted,
            )
        }
    }
}
