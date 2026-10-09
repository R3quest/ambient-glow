package com.example.ambientglow.dashboard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.ambientglow.DEFAULT_GLOW_COLOR
import com.example.ambientglow.GlowApp
import com.example.ambientglow.LauncherApp
import com.example.ambientglow.R
import com.example.ambientglow.appOrder
import com.example.ambientglow.inOrder
import com.example.ambientglow.isNewSince
import com.example.ambientglow.lookAlikesAmong
import com.example.ambientglow.settled
import com.example.ambientglow.matching
import com.example.ambientglow.otherApps
import com.example.ambientglow.toGlowApp
import com.example.ambientglow.ui.components.Chevron
import com.example.ambientglow.ui.components.FoldHeader
import com.example.ambientglow.ui.components.OptionNote
import com.example.ambientglow.ui.components.SectionLabel
import com.example.ambientglow.ui.components.glowCard
import com.example.ambientglow.ui.components.glowCardSlice
import com.example.ambientglow.ui.theme.GlowBrushes
import com.example.ambientglow.ui.theme.GlowPalette
import com.example.ambientglow.ui.theme.GlowShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// The Apps screen, opened from the header: every app that has sent a message, whether it lights
// up and the colour it glows in, and under them the phone's other apps, to set before they ever
// send one. Apps arrive on their own (GlowApps); nothing has to be set up before the first glow.
// Set once and rarely changed, so it is kept out of the style tabs. Rows are in AppRow.
// ---------------------------------------------------------------------------------------------

/**
 * The header the dashboard's has, with a way back in place of the mark ([onBack]; the system's
 * back does the same), then two lists: the apps that have sent a message, and folded under them
 * the phone's other apps, A to Z with a search. Both list the same rows, and one app's colours
 * are open at a time across them. A lazy list, so only the rows on screen are built and load
 * their icons. A picked colour shows on the real LED ([onTryColor]). Colours of one's own are
 * [premium]'s; every app is kept apart from look-alikes either way ([settled]).
 *
 * Leaving the screen, or the dashboard while on it, marks what was new as seen.
 */
@Composable
internal fun AppsScreen(model: AppsModel, premium: PremiumModel, onBack: () -> Unit, onTryColor: (Int) -> Unit) {
    val context = LocalContext.current
    val own = premium.state.unlocked
    val (stored, storedChoices) = model.current
    // As messages glow now: one's own colours only with premium, and look-alikes kept apart.
    val apps = remember(stored, own) { settled(stored, own) }
    val choices = remember(storedChoices, own) { if (own) storedChoices else storedChoices.copy(colors = emptyMap()) }
    // After the trial, the colours the user picked wait for premium: each such row says so.
    val saved = if (own) emptyMap() else storedChoices.colors
    // The heard apps' order, set as the screen opens and kept while it is up, so a row never
    // jumps from under a finger as it is switched (and not lost when its card scrolls away).
    val order = remember { appOrder(apps) }
    val heard = remember(apps, order) { inOrder(apps, order) }
    // Read once per visit, off the main thread: some hundred labels.
    val launcher by produceState<List<LauncherApp>?>(null) {
        value = withContext(Dispatchers.IO) { launcherApps(context) }
    }
    val others = remember(launcher, apps) { launcher?.let { list -> otherApps(list, apps.map { it.pkg }) } }
    val alike = remember(apps, others, choices) { lookAlikesAmong(apps, others.orEmpty(), choices) }
    var othersOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val found = remember(others, query) { others?.matching(query).orEmpty() }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    LifecycleResumeEffect(model) {
        onPauseOrDispose { model.markViewed() }
    }

    // What each row does, the same in both lists. A muted app's colours fold away with it.
    val row = @Composable { app: GlowApp, look: AppLook?, colorReady: Boolean, new: Boolean ->
        AppRow(
            app = app,
            icon = look?.icon,
            colorReady = colorReady,
            new = new,
            alike = alike[app.pkg],
            saved = saved[app.pkg],
            premium = premium,
            open = open == app.pkg,
            onOpen = { open = if (open == app.pkg) null else app.pkg },
            onMute = { muted ->
                if (muted && open == app.pkg) open = null
                model.mute(app.pkg, muted, own)
            },
            onColor = { color -> onTryColor(model.recolor(app, color)) },
        )
    }

    Column(Modifier.fillMaxSize()) {
        AppsHeader(onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().imePadding(),
            contentPadding = PaddingValues(start = PageGutter, end = PageGutter, top = 20.dp, bottom = 24.dp),
        ) {
            item(key = "intro") {
                Text(
                    text = stringResource(R.string.group_apps_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlowPalette.TextMuted,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            item(key = "heard") {
                if (heard.isEmpty()) {
                    AppsEmptyCard()
                } else {
                    HeardCard(heard) { app ->
                        // Its colour is the one the listener worked out and stored.
                        row(app, rememberAppLook(app.pkg), true, app.isNewSince(model.viewedAt))
                    }
                }
            }
            // Only once the phone's list is in, so the section never shows empty.
            if (!others.isNullOrEmpty()) {
                // One card in slices: its header (and search), then a row per app.
                item(key = "others") {
                    Column(
                        Modifier
                            .padding(top = 28.dp)
                            .glowCardSlice(top = true, bottom = !othersOpen)
                            .padding(top = 10.dp, bottom = if (othersOpen) 4.dp else 10.dp),
                    ) {
                        FoldHeader(
                            title = stringResource(R.string.apps_others),
                            summary = othersSummary(others, choices.muted),
                            open = othersOpen,
                        ) {
                            if (othersOpen) keyboard?.hide()
                            othersOpen = !othersOpen
                        }
                        if (othersOpen) {
                            Spacer(Modifier.height(10.dp))
                            SearchField(query, onQuery = { query = it }, onDone = { keyboard?.hide() })
                        }
                    }
                }
                // The last row closes the card: a foot of its own would be shorter than the card's
                // corner, and the row above it would draw the corner's start as a straight edge.
                if (othersOpen) {
                    itemsIndexed(found, key = { _, other -> "app:" + other.pkg }) { index, other ->
                        val last = index == found.lastIndex
                        val look = rememberAppLook(other.pkg)
                        val app = other.toGlowApp(look?.glow ?: DEFAULT_GLOW_COLOR, choices)
                        Box(Modifier.glowCardSlice(top = false, bottom = last).padding(bottom = if (last) 10.dp else 0.dp)) {
                            row(app, look, look != null || app.color != null, false)
                        }
                    }
                    if (found.isEmpty()) {
                        item(key = "none") {
                            Box(Modifier.glowCardSlice(top = false, bottom = true).padding(top = 10.dp, bottom = 20.dp)) {
                                OptionNote(stringResource(R.string.apps_search_none, query.trim()))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Placed as the dashboard's header is, with a way back where its mark is. */
@Composable
private fun AppsHeader(onBack: () -> Unit) {
    // The chevron points back, against the way the text reads.
    val turn = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -90f else 90f
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 14.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Centred on the title without making the header taller, as the dashboard's button is.
        Box(Modifier.height(0.dp).wrapContentHeight(unbounded = true)) {
            HeaderButton(stringResource(R.string.apps_back), onBack) {
                Chevron(GlowPalette.TextPrimary, Modifier.size(11.dp).graphicsLayer { rotationZ = turn })
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = stringResource(R.string.group_apps_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = GlowPalette.TextPrimary,
        )
    }
}

/** The apps that have sent a message, in [heard] order, under how many of them light up. */
@Composable
private fun HeardCard(heard: List<GlowApp>, row: @Composable (GlowApp) -> Unit) {
    val lit = heard.count { !it.muted }
    Column(Modifier.glowCard(vertical = 16.dp)) {
        SectionLabel(
            if (lit == heard.size) {
                pluralStringResource(R.plurals.apps_count_all, lit, lit)
            } else {
                stringResource(R.string.apps_count, lit, heard.size - lit)
            },
        )
        Spacer(Modifier.height(8.dp))
        heard.forEach { app -> key(app.pkg) { row(app) } }
        Spacer(Modifier.height(8.dp))
        OptionNote(stringResource(R.string.apps_hint))
    }
}

@Composable
private fun AppsEmptyCard() {
    Column(Modifier.glowCard(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.apps_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = GlowPalette.TextPrimary,
        )
        Text(
            text = stringResource(R.string.apps_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = GlowPalette.TextMuted,
        )
    }
}

/** How many other apps there are, and how many of them are already switched off. */
@Composable
private fun othersSummary(others: List<LauncherApp>, muted: Set<String>): String {
    val off = others.count { it.pkg in muted }
    val count = pluralStringResource(R.plurals.apps_others_summary, others.size, others.size)
    return if (off == 0) count else count + " " + pluralStringResource(R.plurals.apps_others_off, off, off)
}

/** Every app on the launcher but this one, by its launcher name. */
private fun launcherApps(context: Context): List<LauncherApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    return found
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { LauncherApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
}

/**
 * The search over the other apps: a magnifier, the query, and a clear button once there is one.
 * The keyboard's search key only puts the keyboard away; the list narrows as you type.
 */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onDone: () -> Unit) {
    val clear = stringResource(R.string.apps_search_clear)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = GlowPalette.TextPrimary),
        cursorBrush = SolidColor(GlowPalette.Cyan),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        decorationBox = { field ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(GlowShapes.Tile)
                    .background(GlowPalette.Void)
                    .border(1.dp, GlowPalette.OutlineSoft, GlowShapes.Tile)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(14.dp)) {
                    val line = 1.5.dp.toPx()
                    val radius = size.minDimension * 0.34f
                    val lens = Offset(radius + line / 2f, radius + line / 2f)
                    drawCircle(GlowPalette.TextMuted, radius = radius, center = lens, style = Stroke(line))
                    val reach = lens + Offset(radius * 0.72f, radius * 0.72f)
                    drawLine(GlowPalette.TextMuted, reach, Offset(size.width, size.height), line, StrokeCap.Round)
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.apps_search),
                            style = MaterialTheme.typography.bodyMedium,
                            color = GlowPalette.TextFaint,
                        )
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(GlowShapes.Pill)
                            .clickable(role = Role.Button) { onQuery("") }
                            .semantics { contentDescription = clear },
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.size(10.dp)) {
                            val line = 1.5.dp.toPx()
                            drawLine(GlowPalette.TextMuted, Offset.Zero, Offset(size.width, size.height), line, StrokeCap.Round)
                            drawLine(GlowPalette.TextMuted, Offset(size.width, 0f), Offset(0f, size.height), line, StrokeCap.Round)
                        }
                    }
                }
            }
        },
    )
}

/** Opens the Apps screen from the dashboard's header; [marked] while new apps wait there. */
@Composable
internal fun AppsButton(marked: Boolean, onClick: () -> Unit) {
    val description = stringResource(if (marked) R.string.apps_open_new else R.string.apps_open)
    Box {
        HeaderButton(description, onClick) {
            // Four tiles: the usual mark for apps.
            Canvas(Modifier.size(16.dp)) {
                val gap = 3.dp.toPx()
                val side = (size.width - gap) / 2f
                val corner = CornerRadius(1.5.dp.toPx())
                for (row in 0..1) {
                    for (col in 0..1) {
                        drawRoundRect(
                            GlowPalette.TextPrimary,
                            topLeft = Offset(col * (side + gap), row * (side + gap)),
                            size = Size(side, side),
                            cornerRadius = corner,
                        )
                    }
                }
            }
        }
        // Its own colours are premium's, so it carries premium's sparkle in its square corner, as
        // the element tiles do; the new-apps dot sits there too, and takes it while it shows.
        if (!marked) {
            Sparkle(
                PremiumTint,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 5.dp)
                    .size(8.dp),
            )
        }
        if (marked) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(GlowPalette.Void)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(GlowPalette.Cyan),
            )
        }
    }
}

/** A square button in the dashboard's header; [lit] edges it in the signature while it is busy. */
@Composable
internal fun HeaderButton(
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    lit: Boolean = false,
    glyph: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(GlowShapes.Pill)
            .background(GlowPalette.Surface)
            .border(1.dp, if (lit) GlowBrushes.Signature else SolidColor(GlowPalette.OutlineSoft), GlowShapes.Pill)
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        glyph()
    }
}
