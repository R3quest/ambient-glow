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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.ambientglow.ui.theme.AmbientGlowTheme
import com.example.ambientglow.ui.theme.GlowBrushes
import com.example.ambientglow.ui.theme.GlowPalette
import com.example.ambientglow.ui.theme.GlowShapes
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        GlowLauncher.ensureChannel(this)
        setContent {
            AmbientGlowTheme {
                Dashboard()
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
            )
        }
    }
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
// Dashboard
// ---------------------------------------------------------------------------------------------

@Composable
private fun Dashboard() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(GlowPrefs.load(context)) }
    var access by remember { mutableStateOf(AccessState.read(context)) }

    LifecycleResumeEffect(Unit) {
        access = AccessState.read(context)
        onPauseOrDispose { }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        access = AccessState.read(context)
        if (!granted || !access.bridge) context.openBridgeSettings()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GlowPalette.Void)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(access)

        SectionLabel(stringResource(R.string.section_access))
        AccessCard(
            index = "01",
            title = stringResource(R.string.access_listener_title),
            body = stringResource(R.string.access_listener_body),
            active = access.listener,
            grantLabel = stringResource(R.string.access_listener_grant),
            manageLabel = stringResource(R.string.access_listener_manage),
            onAction = context::openListenerSettings,
        )
        AccessCard(
            index = "02",
            title = stringResource(R.string.access_post_title),
            body = stringResource(R.string.access_post_body),
            active = access.bridge,
            grantLabel = stringResource(R.string.access_post_grant),
            manageLabel = stringResource(R.string.access_post_manage),
            onAction = {
                val needsRuntimeGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !NotificationManagerCompat.from(context).areNotificationsEnabled()
                if (needsRuntimeGrant) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.openBridgeSettings()
                }
            },
        )
        if (access.fullScreenApplies) {
            AccessCard(
                index = "03",
                title = stringResource(R.string.access_fsi_title),
                body = stringResource(R.string.access_fsi_body),
                active = access.fullScreen,
                grantLabel = stringResource(R.string.access_fsi_grant),
                manageLabel = stringResource(R.string.access_fsi_manage),
                onAction = context::openFullScreenIntentSettings,
            )
        }

        Spacer(Modifier.height(6.dp))
        SectionLabel(stringResource(R.string.section_style))
        StyleSelector(
            settings = settings,
            onSelect = { style ->
                settings = settings.copy(style = style)
                GlowPrefs.saveStyle(context, style)
            },
        )

        AnimatedVisibility(
            visible = settings.style == GlowStyle.CUSTOM_DOT,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            DotPositionPanel(
                dotX = settings.dotX,
                dotY = settings.dotY,
                onMove = { x, y -> settings = settings.copy(dotX = x, dotY = y) },
                onCommit = { GlowPrefs.saveDot(context, settings.dotX, settings.dotY) },
            )
        }

        Spacer(Modifier.height(6.dp))
        TestPreviewButton(
            onClick = { GlowLauncher.launchPreview(context, GlowRequest.of(settings, DEFAULT_GLOW_COLOR)) },
        )
        Text(
            text = stringResource(R.string.test_preview_hint),
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextMuted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.footer_privacy),
            style = MaterialTheme.typography.labelSmall,
            color = GlowPalette.TextFaint,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------------------------

@Composable
private fun Header(access: AccessState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowEmblem(Modifier.size(56.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = GlowPalette.TextPrimary,
            )
            Text(
                text = stringResource(R.string.header_tagline),
                style = MaterialTheme.typography.labelMedium,
                color = GlowPalette.TextMuted,
            )
        }
    }
    StatusPill(
        text = if (access.armed) {
            stringResource(R.string.status_armed)
        } else {
            stringResource(R.string.status_setup, access.granted, access.required)
        },
        tint = if (access.armed) GlowPalette.Lime else GlowPalette.Amber,
    )
}

/** Brand mark: a cyan→magenta ring with a lime signal dot riding its edge. */
@Composable
private fun GlowEmblem(modifier: Modifier = Modifier) {
    val ringBrush = remember {
        Brush.sweepGradient(listOf(GlowPalette.Cyan, GlowPalette.Magenta, GlowPalette.Cyan))
    }
    Canvas(modifier) {
        val strokePx = size.minDimension * 0.11f
        val radius = size.minDimension / 2f - strokePx * 1.6f
        drawCircle(
            color = GlowPalette.Cyan.copy(alpha = 0.16f),
            radius = radius + strokePx * 1.1f,
            style = Stroke(width = strokePx * 1.4f),
        )
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
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = GlowPalette.TextFaint,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun StatusPill(text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .clip(GlowShapes.Pill)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.55f), GlowShapes.Pill)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(6.dp)) { drawCircle(tint) }
        Spacer(Modifier.width(7.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = tint)
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
            .padding(18.dp),
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
private fun GhostButton(text: String, emphasized: Boolean, onClick: () -> Unit) {
    val tint = if (emphasized) GlowPalette.Cyan else GlowPalette.TextMuted
    Box(
        modifier = Modifier
            .clip(GlowShapes.GhostButton)
            .background(if (emphasized) GlowPalette.Cyan.copy(alpha = 0.08f) else GlowPalette.SurfaceRaised)
            .border(1.dp, tint.copy(alpha = if (emphasized) 0.7f else 0.25f), GlowShapes.GhostButton)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text = text.uppercase(), style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

// ---------------------------------------------------------------------------------------------
// Style selector
// ---------------------------------------------------------------------------------------------

@Composable
private fun StyleSelector(settings: GlowSettings, onSelect: (GlowStyle) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
        PhoneMock(
            metrics = GlowMetrics.Tile,
            modifier = Modifier.fillMaxWidth(0.82f).aspectRatio(0.5f),
        ) {
            GlowGraphic(
                style = style,
                color = if (selected) GlowPalette.Cyan else GlowPalette.TextFaint,
                alpha = { 1f },
                dotX = settings.dotX,
                dotY = settings.dotY,
                metrics = GlowMetrics.Tile,
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

/** A minimal handset silhouette: black panel, hairline bezel and a punch-hole camera. */
@Composable
private fun PhoneMock(
    metrics: GlowMetrics,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(GlowShapes.Phone)
            .background(GlowPalette.Void)
            .border(1.dp, GlowPalette.Outline, GlowShapes.Phone),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = GlowPalette.SurfaceHighest,
                radius = metrics.fallbackCameraRadius.toPx(),
                center = Offset(size.width / 2f, metrics.fallbackCameraCenterY.toPx()),
            )
        }
        content()
    }
}

// ---------------------------------------------------------------------------------------------
// Dot position
// ---------------------------------------------------------------------------------------------

private val PanelHeight: Dp = 280.dp

@Composable
private fun DotPositionPanel(
    dotX: Float,
    dotY: Float,
    onMove: (Float, Float) -> Unit,
    onCommit: () -> Unit,
) {
    val sliderColors = glowSliderColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(GlowShapes.Card)
            .background(GlowPalette.Surface)
            .border(1.dp, GlowPalette.OutlineSoft, GlowShapes.Card)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.section_dot),
            style = MaterialTheme.typography.labelSmall,
            color = GlowPalette.Cyan,
        )
        Text(
            text = stringResource(R.string.dot_body),
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextMuted,
        )

        Row(
            modifier = Modifier.fillMaxWidth().height(PanelHeight).padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoneMock(
                metrics = GlowMetrics.Panel,
                modifier = Modifier.fillMaxHeight().aspectRatio(0.48f),
            ) {
                GlowGraphic(
                    style = GlowStyle.CUSTOM_DOT,
                    color = GlowPalette.Cyan,
                    alpha = { 1f },
                    dotX = dotX,
                    dotY = dotY,
                    metrics = GlowMetrics.Panel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(8.dp))
            val verticalLabel = stringResource(R.string.dot_vertical)
            // Rotated so the top of the slider is the top of the screen.
            VerticalSlider(
                value = 1f - dotY,
                onValueChange = { onMove(dotX, 1f - it) },
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

        Text(
            text = stringResource(R.string.dot_horizontal),
            style = MaterialTheme.typography.labelSmall,
            color = GlowPalette.TextFaint,
            modifier = Modifier.padding(top = 4.dp),
        )
        val horizontalLabel = stringResource(R.string.dot_horizontal)
        Slider(
            value = dotX,
            onValueChange = { onMove(it, dotY) },
            onValueChangeFinished = onCommit,
            colors = sliderColors,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = horizontalLabel },
        )
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
// Test preview
// ---------------------------------------------------------------------------------------------

@Composable
private fun TestPreviewButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(GlowShapes.Button)
            .background(GlowBrushes.SignatureHorizontal)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.test_preview),
            style = MaterialTheme.typography.labelLarge,
            color = GlowPalette.Void,
        )
    }
}
