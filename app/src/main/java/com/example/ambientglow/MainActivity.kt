package com.example.ambientglow

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
    CompositionLocalProvider(LocalCamera provides rememberCamera(geometry)) {
        DashboardContent()
    }
}

@Composable
private fun DashboardContent() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(GlowPrefs.load(context)) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GlowPalette.Void)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
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
                StylePage(settings, onChange = { settings = it })
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
                        DashboardTab.STYLE -> StylePage(settings, onChange = { settings = it })
                    }
                }
            }
        }

        TestDock()
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AccessCard(
            index = "01",
            title = stringResource(R.string.access_listener_title),
            body = stringResource(R.string.access_listener_body),
            active = access.listener,
            grantLabel = stringResource(R.string.access_listener_grant),
            manageLabel = stringResource(R.string.access_listener_manage),
            onAction = actions.listener,
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

@Composable
private fun StylePage(settings: GlowSettings, onChange: (GlowSettings) -> Unit) {
    val context = LocalContext.current
    // Latest value for slider commit callbacks, which fire after several onMove updates.
    var latest by remember { mutableStateOf(settings) }
    latest = settings

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(stringResource(R.string.section_led_style))
        Text(
            text = stringResource(R.string.led_style_body),
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextMuted,
        )
        Spacer(Modifier.height(2.dp))
        StyleSelector(
            settings = settings,
            onSelect = { style ->
                onChange(settings.copy(style = style))
                GlowPrefs.saveStyle(context, style)
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(stringResource(R.string.arrival_mode))
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ArrivalMode.entries.forEach { mode ->
                BladeChip(
                    label = stringResource(mode.label),
                    selected = settings.arrival == mode,
                    onClick = {
                        onChange(settings.copy(arrival = mode))
                        GlowPrefs.saveArrival(context, mode)
                    },
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
            }
        }
        Text(
            text = stringResource(settings.arrival.body),
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextMuted,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(stringResource(R.string.led_brightness))
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LedBrightness.entries.forEach { level ->
                BladeChip(
                    label = stringResource(level.label),
                    selected = settings.ledBrightness == level,
                    onClick = {
                        onChange(settings.copy(ledBrightness = level))
                        GlowPrefs.saveLedBrightness(context, level)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Canvas(Modifier.size(8.dp)) { drawCircle(GlowPalette.Cyan.copy(alpha = level.level)) }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }

    // The waiting LED is always the dot, whatever arrival style is chosen.
    DotPositionPanel(
        settings = settings,
        onMove = { x, y ->
            latest = latest.copy(dotX = x, dotY = y)
            onChange(latest)
        },
        onCommit = { GlowPrefs.saveDot(context, latest.dotX, latest.dotY) },
        onSize = { size ->
            latest = latest.copy(dotSize = size)
            onChange(latest)
            GlowPrefs.saveDotSize(context, size)
        },
    )
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
// Style selector
// ---------------------------------------------------------------------------------------------

@Composable
private fun StyleSelector(settings: GlowSettings, onSelect: (GlowStyle) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlowStyle.entries.forEach { style ->
            StyleTile(
                style = style,
                selected = settings.style == style,
                settings = settings,
                onSelect = { onSelect(style) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StyleTile(
    style: GlowStyle,
    selected: Boolean,
    settings: GlowSettings,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border: Brush = if (selected) GlowBrushes.Signature else SolidColor(GlowPalette.OutlineSoft)
    Column(
        modifier = modifier
            .clip(GlowShapes.Tile)
            .background(if (selected) GlowPalette.SurfaceRaised else GlowPalette.Surface)
            .border(if (selected) 1.5.dp else 1.dp, border, GlowShapes.Tile)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhoneMock(modifier = Modifier.fillMaxWidth(0.82f).aspectRatio(0.5f)) { mockGeometry ->
            GlowGraphic(
                style = style,
                color = if (selected) GlowPalette.Cyan else GlowPalette.TextFaint,
                alpha = { 1f },
                dotX = settings.dotX,
                dotY = settings.dotY,
                metrics = GlowMetrics.Tile,
                geometry = mockGeometry,
                dotRadius = previewDotRadius(settings.dotSize, TILE_PREVIEW_SCALE),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(style.title),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (selected) GlowPalette.TextPrimary else GlowPalette.TextMuted,
            maxLines = 1,
        )
        Text(
            text = stringResource(style.caption),
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextFaint,
            maxLines = 2,
        )
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
    content: @Composable BoxScope.(ScreenGeometry) -> Unit,
) {
    val camera = LocalCamera.current
    val minRadius = with(LocalDensity.current) { 1.5.dp.toPx() }
    BoxWithConstraints(
        modifier = modifier
            .clip(GlowShapes.Phone)
            .background(GlowPalette.Void)
            .border(1.dp, GlowPalette.Outline, GlowShapes.Phone),
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

/** A one-tap LED position; [onCameraLine] spots sit at the exact height of the lens centre. */
@Immutable
private data class DotSpot(@param:StringRes val label: Int, val x: Float, val y: Float, val onCameraLine: Boolean) {
    fun matches(x: Float, y: Float) = abs(this.x - x) < 0.001f && abs(this.y - y) < 0.001f
}

private val SPOT_EDGE = 18.dp
private val SPOT_CAMERA_GAP = 10.dp

/**
 * LED spots built from this phone's real camera: four on the camera's horizontal line (screen
 * edges and either side of the lens), one straight below it, plus the classic corners and
 * bottom. Positions are converted with the same margin the full-screen LED uses, so a spot
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

@Composable
private fun DotPositionPanel(
    settings: GlowSettings,
    onMove: (Float, Float) -> Unit,
    onCommit: () -> Unit,
    onSize: (DotSize) -> Unit,
) {
    val dotX = settings.dotX
    val dotY = settings.dotY
    val sliderColors = glowSliderColors()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var lastSnap by remember { mutableStateOf<Any?>(null) }
    val spots = rememberDotSpots(settings.dotSize)

    // Every input path (drag, tap, both sliders) goes through the same magnetic snap.
    val move: (Float, Float) -> Unit = { x, y ->
        val snapped = snapDot(x, y, spots)
        if (snapped.target != null && snapped.target != lastSnap) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        lastSnap = snapped.target
        onMove(snapped.x, snapped.y)
    }

    // Scale the real dot into the mock-up so LED vs L reads honestly.
    val screenHeightDp = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val previewScale = (PanelHeight / screenHeightDp.coerceAtLeast(PanelHeight)).coerceIn(0.1f, 1f)
    val previewRadius = previewDotRadius(settings.dotSize, previewScale)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlowShapes.Card)
            .background(GlowPalette.Surface)
            .border(1.dp, GlowPalette.OutlineSoft, GlowShapes.Card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
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
            ) {
                DotRuler(
                    dotX = dotX,
                    dotY = dotY,
                    dotRadius = previewRadius,
                    spots = spots,
                    modifier = Modifier.fillMaxSize(),
                )
                GlowGraphic(
                    style = GlowStyle.CUSTOM_DOT,
                    color = GlowPalette.Cyan,
                    alpha = { 1f },
                    dotX = dotX,
                    dotY = dotY,
                    metrics = GlowMetrics.Panel,
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
            // Rotated so the top of the slider is the top of the screen.
            VerticalSlider(
                value = 1f - dotY,
                onValueChange = { move(dotX, 1f - it) },
                onValueChangeFinished = onCommit,
                colors = sliderColors,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(44.dp)
                    .semantics { contentDescription = verticalLabel },
            )
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Readout(stringResource(R.string.dot_readout_x, (dotX * 100).roundToInt()))
                Readout(stringResource(R.string.dot_readout_y, (dotY * 100).roundToInt()))
            }
        }

        SectionLabel(stringResource(R.string.dot_horizontal))
        val horizontalLabel = stringResource(R.string.dot_horizontal)
        Slider(
            value = dotX,
            onValueChange = { move(it, dotY) },
            onValueChangeFinished = onCommit,
            colors = sliderColors,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = horizontalLabel },
        )

        SectionLabel(stringResource(R.string.dot_size))
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

        val pick: (DotSpot) -> Unit = { spot ->
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastSnap = spot
            onMove(spot.x, spot.y)
            onCommit()
        }
        SectionLabel(stringResource(R.string.dot_spots_camera_line))
        SpotRow(spots.filter { it.onCameraLine }, dotX, dotY, pick)
        SectionLabel(stringResource(R.string.dot_spots_other))
        SpotRow(spots.filterNot { it.onCameraLine }, dotX, dotY, pick)
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
// Test dock (pinned under both tabs)
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .clip(GlowShapes.Button)
                    .background(GlowBrushes.SignatureHorizontal)
                    .clickable(role = Role.Button) { GlowLauncher.launchPreview(context) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.test_preview),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = GlowPalette.Void,
                )
            }
            GhostButton(
                text = if (countdown > 0) {
                    stringResource(R.string.test_locked_countdown, countdown)
                } else {
                    stringResource(R.string.test_locked)
                },
                emphasized = true,
                onClick = {
                    if (countdown > 0) return@GhostButton
                    blocked = !GlowLauncher.scheduleTestNotification(context)
                    if (!blocked) runs++
                },
                modifier = Modifier.weight(1f).height(50.dp),
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
