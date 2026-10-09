package com.example.ambientglow.dashboard

import android.content.Context
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.ambientglow.APP_COLORS
import com.example.ambientglow.AppColor
import com.example.ambientglow.BRAND_ICON_PX
import com.example.ambientglow.DEFAULT_GLOW_COLOR
import com.example.ambientglow.GlowApp
import com.example.ambientglow.R
import com.example.ambientglow.iconGlow
import com.example.ambientglow.ui.components.Disclosure
import com.example.ambientglow.ui.components.OptionBody
import com.example.ambientglow.ui.components.OptionGroup
import com.example.ambientglow.ui.components.RowBleed
import com.example.ambientglow.ui.components.glowSwitchColors
import com.example.ambientglow.ui.components.rowBleed
import com.example.ambientglow.ui.theme.GlowMotion
import com.example.ambientglow.ui.theme.GlowPalette
import com.example.ambientglow.ui.theme.GlowShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// One app on the Apps screen: its row, the colours that unfold under it, and its icon and icon
// colour, loaded off the main thread and kept for when the row scrolls back into view.
// ---------------------------------------------------------------------------------------------

private val AppIconSize = 36.dp
private val SwatchSize = 32.dp
private val SwatchRing = 1.5.dp
private val SwatchRingGap = 2.25.dp
private const val SWATCHES_PER_ROW = 5

/** How far a muted app's icon and name fade: still readable, clearly not lit. */
private const val MUTED_ALPHA = 0.4f

/** How far a colour fades that is premium's, after its trial. */
private const val LOCKED_ALPHA = 0.35f

/**
 * Icon, name and what sets it apart (muted, a look-alike, kept apart from one, or a colour of the
 * user's), the colour itself, and its switch. The switch is the only way to mute, so a tap meant for the colours
 * never silences an app; muted, the row dims and has no colours to open. The colour shows once it
 * is known ([colorReady]), so it doesn't flash the default first.
 */
@Composable
internal fun AppRow(
    app: GlowApp,
    icon: ImageBitmap?,
    colorReady: Boolean,
    new: Boolean,
    alike: GlowApp?,
    premium: PremiumModel,
    open: Boolean,
    onOpen: () -> Unit,
    onMute: (Boolean) -> Unit,
    onColor: (Int?) -> Unit,
) {
    val lit = !app.muted
    val shown = animateFloatAsState(if (lit) 1f else 0f, GlowMotion.stateChange(), label = "app-lit")
    val haptics = LocalHapticFeedback.current
    val glow = Color(app.glow)
    val state = stringResource(if (open) R.string.fold_open else R.string.fold_closed)
    val switchLabel = stringResource(R.string.apps_switch, app.label)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .rowBleed()
                .clip(GlowShapes.Tile)
                .clickable(
                    enabled = lit,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.apps_color_action),
                    onClick = onOpen,
                )
                .semantics { if (lit) stateDescription = state }
                .padding(horizontal = RowBleed, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                icon = icon,
                label = app.label,
                color = glow,
                modifier = Modifier
                    .size(AppIconSize)
                    .graphicsLayer { alpha = MUTED_ALPHA + (1f - MUTED_ALPHA) * shown.value },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (lit) GlowPalette.TextPrimary else GlowPalette.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (new) {
                        Spacer(Modifier.width(8.dp))
                        NewTag()
                    }
                }
                // Only what sets the app apart: its icon's colour is the usual case, and the dot shows it.
                val note = when {
                    !lit -> stringResource(R.string.apps_muted)
                    alike != null -> stringResource(R.string.apps_alike, alike.label)
                    app.color != null -> stringResource(R.string.apps_color_own)
                    app.apart != null -> stringResource(R.string.apps_apart, app.apart.from)
                    else -> null
                }
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lit && alike != null) GlowPalette.Amber else GlowPalette.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            // What it glows in, as a lit dot does; gone when the app is muted.
            Canvas(Modifier.size(20.dp).graphicsLayer { alpha = if (colorReady) shown.value else 0f }) {
                drawCircle(glow, radius = size.minDimension / 2f, alpha = 0.18f)
                drawCircle(glow, radius = size.minDimension / 4f)
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = lit,
                onCheckedChange = { on ->
                    haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    onMute(!on)
                },
                colors = glowSwitchColors(),
                modifier = Modifier.semantics { contentDescription = switchLabel },
            )
        }
        Disclosure(visible = open && lit) {
            Box(Modifier.padding(top = 8.dp, bottom = 14.dp)) {
                ColorPicker(app, icon, alike, premium, onColor)
            }
        }
    }
}

/** What an app's colours say under them. */
private enum class ColorNote { AUTO, APART, OWN, LOCKED }

/**
 * Its icon's colour first (the icon on it, so it reads as "the icon's"; the colour it is kept
 * apart in, if that is taken), then [APP_COLORS], which are [premium]'s: after its trial they
 * dim and only the first can be picked. What the pick means is said under the swatches, and a
 * look-alike is named with why it matters.
 */
@Composable
private fun ColorPicker(app: GlowApp, icon: ImageBitmap?, alike: GlowApp?, premium: PremiumModel, onColor: (Int?) -> Unit) {
    val choices = remember { listOf<AppColor?>(null) + APP_COLORS }
    val unlocked = premium.state.unlocked
    OptionGroup(stringResource(R.string.apps_color)) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            choices.chunked(SWATCHES_PER_ROW).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { choice ->
                        Swatch(
                            color = Color(choice?.color ?: app.apart?.color ?: app.autoColor),
                            icon = if (choice == null) icon else null,
                            name = stringResource(choice?.name ?: R.string.apps_color_auto),
                            selected = app.color == choice?.color,
                            enabled = unlocked || choice == null,
                            onClick = { onColor(choice?.color) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        val note = when {
            !unlocked -> ColorNote.LOCKED
            app.color != null -> ColorNote.OWN
            app.apart != null -> ColorNote.APART
            else -> ColorNote.AUTO
        }
        OptionBody(note) { shown ->
            when (shown) {
                ColorNote.AUTO -> stringResource(R.string.apps_color_auto_body)
                ColorNote.APART -> stringResource(R.string.apps_color_apart_body, app.apart?.from.orEmpty())
                ColorNote.OWN -> stringResource(R.string.apps_color_own_body)
                ColorNote.LOCKED -> stringResource(R.string.apps_color_locked_body)
            }
        }
        PremiumLine(premium)
        Disclosure(visible = alike != null) {
            // Kept while it folds away, so the note doesn't empty before it goes.
            val last = remember { mutableStateOf(alike) }
            if (alike != null) last.value = alike
            Text(
                text = stringResource(R.string.apps_alike_body, last.value?.label.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = GlowPalette.Amber,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Swatch(
    color: Color,
    icon: ImageBitmap?,
    name: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val ring = animateFloatAsState(if (selected) 1f else 0f, GlowMotion.stateChange(), label = "swatch")
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(GlowShapes.Pill)
            .graphicsLayer { alpha = if (enabled) 1f else LOCKED_ALPHA }
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            }
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(SwatchSize)) {
            val stroke = SwatchRing.toPx()
            val disc = size.minDimension / 2f - stroke - SwatchRingGap.toPx()
            drawCircle(color, radius = disc)
            if (icon != null) {
                // The icon inside the disc, so the disc shows as its rim; scaled down smoothly.
                val side = (disc * 1.3f).toInt()
                val at = ((size.minDimension - side) / 2f).toInt()
                drawImage(icon, dstOffset = IntOffset(at, at), dstSize = IntSize(side, side), filterQuality = FilterQuality.Medium)
            }
            if (ring.value > 0f) {
                drawCircle(
                    GlowPalette.TextPrimary,
                    radius = size.minDimension / 2f - stroke / 2f,
                    alpha = ring.value,
                    style = Stroke(stroke),
                )
            }
        }
    }
}

@Composable
private fun NewTag() {
    Box(
        Modifier
            .clip(GlowShapes.Pill)
            .background(GlowPalette.Cyan.copy(alpha = 0.1f))
            .border(1.dp, GlowPalette.Cyan.copy(alpha = 0.5f), GlowShapes.Pill)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(stringResource(R.string.apps_new), style = MaterialTheme.typography.labelSmall, color = GlowPalette.Cyan)
    }
}

/** The app's icon; until it loads, or for an app this one can't see, its initial on its colour. */
@Composable
private fun AppIcon(icon: ImageBitmap?, label: String, color: Color, modifier: Modifier = Modifier) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = modifier)
        return
    }
    Box(
        modifier = modifier.clip(CircleShape).background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}

/**
 * An app's icon at the row's size ([bytes] of it), and the colour the icon gives ([iconGlow]), as
 * the listener will work it out when the app first posts. An app this one can't see has no icon.
 */
@Immutable
internal class AppLook(val icon: ImageBitmap?, val glow: Int, val bytes: Int)

/** Looks already loaded, so a row scrolled back into view shows at once: about ninety icons at 3x. */
private const val LOOK_CACHE_BYTES = 4 * 1024 * 1024

private val looks = object : LruCache<String, AppLook>(LOOK_CACHE_BYTES) {
    override fun sizeOf(key: String, value: AppLook) = value.bytes.coerceAtLeast(1)
}

/** [pkg]'s look, at once from the cache, else loaded off the main thread (null until then). */
@Composable
internal fun rememberAppLook(pkg: String): AppLook? {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { AppIconSize.roundToPx() }
    val key = "$pkg@$px"
    val look by produceState(looks[key], key) {
        if (value == null) value = withContext(Dispatchers.IO) { loadLook(context, pkg, px) }.also { looks.put(key, it) }
    }
    return look
}

private fun loadLook(context: Context, pkg: String, px: Int): AppLook {
    val drawable = try {
        context.packageManager.getApplicationIcon(pkg)
    } catch (_: PackageManager.NameNotFoundException) {
        return AppLook(icon = null, glow = DEFAULT_GLOW_COLOR, bytes = 1)
    }
    val icon = drawable.toBitmap(px, px)
    val glow = iconGlow(drawable.toBitmap(BRAND_ICON_PX, BRAND_ICON_PX)) ?: DEFAULT_GLOW_COLOR
    return AppLook(icon.asImageBitmap(), glow, icon.allocationByteCount)
}
