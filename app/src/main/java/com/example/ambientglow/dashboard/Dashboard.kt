package com.example.ambientglow.dashboard

import android.animation.ValueAnimator
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.ambientglow.ArrivalEffect
import com.example.ambientglow.GlassHaze
import com.example.ambientglow.GlassHazeTarget
import com.example.ambientglow.GlowLauncher
import com.example.ambientglow.GlowPrefs
import com.example.ambientglow.GlowSettings
import com.example.ambientglow.R
import com.example.ambientglow.ScreenGeometry
import com.example.ambientglow.glassHaze
import com.example.ambientglow.ui.components.Chevron
import com.example.ambientglow.ui.components.Disclosure
import com.example.ambientglow.ui.components.SettingsGroup
import com.example.ambientglow.ui.components.StatusPill
import com.example.ambientglow.ui.theme.GlowBrushes
import com.example.ambientglow.ui.theme.GlowMotion
import com.example.ambientglow.ui.theme.GlowPalette
import com.example.ambientglow.ui.theme.GlowShapes
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Dashboard shell: header, tab bar, swipeable pages, the styling page, pinned test dock, and the
// real-size previews every change starts.
// ---------------------------------------------------------------------------------------------

private enum class DashboardTab(val label: Int) {
    ACCESS(R.string.tab_access),
    STYLE(R.string.tab_style),
}

/** Outer margins and the gap between sections: generous, so each block reads on its own. */
private val PageGutter = 24.dp
private val SectionGap = 28.dp

/** How long a real-size effect dissolves when the next preview replaces it. */
internal const val SHOWCASE_FADE_MS = 110

/** How long a pick has to land (chip blade, swatch ring, tile) before its real-size effect starts. */
private const val SHOWCASE_SETTLE_MS = 140L

// Ignoring visibility is the long-standing (still experimental-marked) way to keep bar padding.
@OptIn(ExperimentalLayoutApi::class)
private val DashboardInsets: WindowInsets
    @Composable get() = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)

@Composable
internal fun Dashboard(reported: ScreenGeometry) {
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
    // Remove animations (animator scale 0): nothing plays on its own; changes show only in the
    // inline preview, once. Read again on resume, since it is changed in Settings.
    var reduceMotion by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }

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
    // Picked, waiting for the control to land (SHOWCASE_SETTLE_MS) before it plays.
    var showcasePending by remember { mutableStateOf(false) }
    var ledRun by remember { mutableIntStateOf(0) }
    var ledHolding by remember { mutableStateOf(false) }
    var ledShowing by remember { mutableStateOf(false) }
    var ledLeaving by remember { mutableStateOf(false) }
    // The run that is playing, as it was started: while it fades out for the next one it must
    // not pick up the change that replaced it.
    var shownSettings by remember { mutableStateOf(settings) }
    var shownColor by remember { mutableIntStateOf(SAMPLE_COLORS[sample].color.toArgb()) }
    // Hand-offs dissolve the playing effect instead of cutting it. One job holds the fade and
    // the settle of a pick, so the next pick or the LED replaces both.
    val showcaseFade = remember { Animatable(1f) }
    var fadeJob by remember { mutableStateOf<Job?>(null) }
    // The showcase's glass wave blurs the dashboard under it, as it blurs the lock screen; the
    // blur relaxes with the fade instead of dropping when the run goes.
    val haze = remember { GlassHaze() }
    val fadingHaze = remember { GlassHazeTarget { level, wave -> haze.haze(level * showcaseFade.value, wave) } }
    val start = {
        shownSettings = settings
        shownColor = SAMPLE_COLORS[sample].color.toArgb()
        showcaseRun++
        showcasing = true
    }
    val fadeThen = { after: () -> Unit ->
        fadeJob?.cancel()
        fadeJob = scope.launch {
            showcaseFade.animateTo(0f, tween(SHOWCASE_FADE_MS, easing = FastOutLinearInEasing))
            after()
            showcaseFade.snapTo(1f)
        }
    }
    // The pick lands first (blade, ring, tile), then its effect plays: a playing one dissolves
    // meanwhile, and quick hops restart the wait, so only the last one plays.
    val showcase = {
        if (!reduceMotion) {
            if (ledShowing) ledLeaving = true
            fadeJob?.cancel()
            showcasePending = true
            fadeJob = scope.launch {
                coroutineScope {
                    if (showcasing) launch { showcaseFade.animateTo(0f, tween(SHOWCASE_FADE_MS, easing = FastOutLinearInEasing)) }
                    delay(SHOWCASE_SETTLE_MS)
                }
                showcaseFade.snapTo(1f)
                showcasePending = false
                start()
            }
        }
    }
    val showLed = { holding: Boolean ->
        // The LED is the newer request: a pick still waiting to play gives way to it.
        if (showcasePending) {
            fadeJob?.cancel()
            showcasePending = false
        }
        // Once per hand-off, not on every drag frame: a fade restarted each frame never ends.
        if (showcasing && (!ledShowing || ledLeaving)) fadeThen { showcasing = false }
        ledLeaving = false
        if (!holding) ledRun++
        ledHolding = holding
        ledShowing = true
    }

    LifecycleResumeEffect(Unit) {
        refresh()
        reduceMotion = !ValueAnimator.areAnimatorsEnabled()
        // Leaving mid-run ends both previews: the LED's window brightness and hidden bars are
        // released with it, and nothing is left to replay on return.
        onPauseOrDispose {
            fadeJob?.cancel()
            showcasePending = false
            showcasing = false
            ledShowing = false
            ledLeaving = false
        }
    }

    val stylePage: @Composable () -> Unit = {
        StylePage(
            settings = settings,
            sample = sample,
            // The step the real-size preview shows (the newer one during a hand-off), or null.
            previewHeld = when {
                ledShowing && !ledLeaving -> PreviewPhase.LED
                showcasing || showcasePending -> PreviewPhase.EFFECT
                ledShowing -> PreviewPhase.LED
                else -> null
            },
            loop = !reduceMotion,
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
            Column(
                Modifier
                    .fillMaxSize()
                    .glassHaze(haze, shownSettings, geometry)
                    .windowInsetsPadding(DashboardInsets),
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
                    // Access lives behind the ARMED pill; its summary carries its own gap, so it
                    // opens and closes without a jump.
                    PageColumn(Modifier.weight(1f)) {
                        Column {
                            Disclosure(visible = accessOpen) {
                                Box(Modifier.padding(bottom = SectionGap)) { AccessSummary(access, actions) }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(SectionGap)) { stylePage() }
                        }
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

            // The LED first, so an effect that replaces it is never dimmed by its lifting scrim.
            if (ledShowing) {
                LedShowcase(
                    settings = settings,
                    color = SAMPLE_COLORS[sample].color,
                    geometry = geometry,
                    run = ledRun,
                    holding = ledHolding,
                    leaving = ledLeaving,
                    onDone = {
                        ledShowing = false
                        ledLeaving = false
                    },
                )
            }
            // Each effect change plays once at real size over the whole screen, as a real message
            // would. It only draws, so taps go through to the options underneath while it plays.
            // A change mid-run dissolves it (no offscreen pass) and starts the new one.
            if (showcasing) {
                key(showcaseRun) {
                    val run = showcaseRun
                    ArrivalEffect(
                        settings = shownSettings,
                        color = shownColor,
                        geometry = geometry,
                        onDone = { if (run == showcaseRun) showcasing = false },
                        modifier = Modifier.graphicsLayer {
                            alpha = showcaseFade.value
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                        onBlurBehind = fadingHaze,
                        overBlack = shownSettings.arrival.onBlack,
                    )
                }
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

/**
 * Two groups, in the order things happen: what plays when a message arrives, then the LED that
 * waits until it is read. Each change is saved the moment it is made.
 */
@Composable
private fun StylePage(
    settings: GlowSettings,
    sample: Int,
    previewHeld: PreviewPhase?,
    loop: Boolean,
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
    // Every change shows at once; [save] also stores it. A drag or a run of taps is stored once, as it settles.
    fun update(next: GlowSettings, save: Boolean) {
        latest = next
        onChange(next)
        if (save) GlowPrefs.save(context, next)
    }

    SettingsGroup(index = "01", title = R.string.group_arrival_title, body = R.string.group_arrival_body) {
        EffectCard(
            settings = settings,
            sample = sample,
            previewHeld = previewHeld,
            loop = loop,
            onStyle = { style ->
                update(latest.copy(style = style), save = true)
                onShowcase()
            },
            onEffect = { next ->
                update(next, save = true)
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
            onSelect = { mode -> update(latest.copy(arrival = mode), save = true) },
        )
    }

    // The waiting LED is always the dot, whatever arrival style is chosen.
    SettingsGroup(index = "02", title = R.string.group_led_title, body = R.string.group_led_body) {
        LedCard(
            settings = settings,
            // While it moves, the real LED follows on screen; it breathes once when let go.
            onMove = { x, y, onCamera ->
                update(latest.copy(dotX = x, dotY = y, ledOnCamera = onCamera), save = false)
                onLed(true)
            },
            onCommit = {
                GlowPrefs.save(context, latest)
                onLed(false)
            },
            onSize = { size ->
                update(latest.copy(dotSize = size), save = true)
                onLed(false)
            },
            onBrightness = { level ->
                update(latest.copy(ledBrightness = level), save = true)
                onLed(false)
            },
            onLensFit = { fit ->
                update(fit(latest), save = false)
                onLed(true)
            },
            onLensFitDone = {
                GlowPrefs.save(context, latest)
                onLed(false)
            },
        )
    }
}

/** One compact line: mark, name, and a status pill that opens access (or jumps to setup). */
@Composable
private fun TopBar(access: AccessState, accessOpen: Boolean, onStatusClick: () -> Unit) {
    // Same springs as the panel's size each way (DisclosureEnter / DisclosureExit), so the chevron lands with it.
    val chevronTurn by animateFloatAsState(
        if (accessOpen) 180f else 0f,
        spring(1f, if (accessOpen) 500f else 700f),
        label = "chevron",
    )
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
            // Comes and goes with ARMED, the pill easing to its width as the access panel does.
            AnimatedVisibility(
                visible = access.armed,
                enter = expandHorizontally(GlowMotion.SizeIn, expandFrom = Alignment.Start) +
                    GlowMotion.SwapIn,
                exit = GlowMotion.SwapOut +
                    shrinkHorizontally(GlowMotion.SizeOut, shrinkTowards = Alignment.Start),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(6.dp))
                    Chevron(
                        tint = GlowPalette.Lime,
                        modifier = Modifier.size(8.dp).graphicsLayer { rotationZ = chevronTurn },
                    )
                }
            }
        }
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

/**
 * Segmented blade control. The highlighted blade and the label tints track the pager's scroll
 * position as you swipe; that is read in the layout and draw phases, so swiping does not
 * recompose the bar.
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
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(GlowShapes.Tile)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The label lights as the blade reaches it, read in draw like the blade.
                    BasicText(
                        text = stringResource(tab.label),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = {
                            val position = pager.currentPage + pager.currentPageOffsetFraction
                            lerp(GlowPalette.TextPrimary, GlowPalette.TextFaint, abs(position - tab.ordinal).coerceIn(0f, 1f))
                        },
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

private const val COUNTDOWN_SECONDS = (GlowLauncher.TEST_DELAY_MS / 1_000L).toInt()

@Composable
private fun TestDock() {
    val context = LocalContext.current
    var countdown by remember { mutableIntStateOf(0) }
    var runs by remember { mutableIntStateOf(0) }
    var blocked by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val dim = animateFloatAsState(
        targetValue = if (countdown > 0) 0.55f else 1f,
        animationSpec = GlowMotion.stateChange(),
        label = "test-dim",
    )

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
    ) {
        // The one end-to-end check: real messages through the listener, on the locked phone.
        // The effect alone replays at full size whenever it is changed.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .graphicsLayer { alpha = dim.value }
                .clip(GlowShapes.Button)
                .background(GlowBrushes.SignatureHorizontal)
                .clickable(role = Role.Button, enabled = countdown == 0) {
                    blocked = !GlowLauncher.scheduleTestNotification(context)
                    haptics.performHapticFeedback(if (blocked) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
                    if (!blocked) runs++
                },
            contentAlignment = Alignment.Center,
        ) {
            // The label and every count swap in place, as one.
            AnimatedContent(
                targetState = countdown,
                transitionSpec = { GlowMotion.swap() },
                contentAlignment = Alignment.Center,
                label = "test-label",
            ) { count ->
                Text(
                    text = if (count > 0) {
                        stringResource(R.string.test_locked_countdown, count)
                    } else {
                        stringResource(R.string.test_locked)
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = GlowPalette.Void,
                )
            }
        }
        // Carries its own gap, so the dock grows and shrinks in one movement.
        Disclosure(visible = blocked || countdown > 0) {
            Box(Modifier.padding(top = 10.dp)) {
                Text(
                    text = stringResource(if (blocked) R.string.test_locked_blocked else R.string.test_locked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (blocked) GlowPalette.Amber else GlowPalette.TextMuted,
                )
            }
        }
    }
}
