package com.example.ambientglow

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the glow screen is doing right now. */
internal enum class Face {
    /** Covering the lock screen: black panel, only the LED blinking. */
    LED,

    /** On top of the lock screen without covering it: see-through, untouchable, invisible. */
    LOCK_SCREEN,

    /** Behind other apps because the phone was unlocked. */
    AWAY,
}

/**
 * The glow screen. Once on top of the lock screen it stays there for the whole session and
 * switches between [Face.LED] and [Face.LOCK_SCREEN] in place with setShowWhenLocked. No
 * relaunch is needed, and background-launch rules don't apply.
 *
 * Why: the screen only comes on without a flash when the right thing is already on top. So
 * every switch that comes before a wake happens while the panel is dark, and then the panel is
 * lit with a short wake lock ([PanelWaker]):
 * - Screen off with the LED in front (power button): switch to the lock screen, then wake, so
 *   power "opens" the phone like it would without the app.
 * - Screen off with the lock screen in front (timeout, or power on the lock screen): switch to
 *   the LED, then wake. The panel comes on already black with the dot.
 * - A new message ([ArrivalMode.LOCK_SCREEN], default): light the system lock screen (all its
 *   notifications) and play the user's chosen effect over it ([GlowShield]). If nobody touched
 *   it, the LED dot covers it again just before the lock screen would dim and sleep (about 3 s
 *   on One UI).
 * - A new message ([ArrivalMode.BLACK] / [ArrivalMode.MESSAGE]): light the panel straight into
 *   the black LED face, play the effect there (with Message, also the system's pop-up of only
 *   the new message), then the dot. While the user is looking at the lock screen, the effect
 *   plays over it instead.
 * - Unlock: step behind the user's apps (see-through, so no black frame). Only then can a later
 *   relight show the lock screen for a moment, because Android starts every wake on the lock
 *   screen until we are back on top.
 *
 * It finishes only when every waiting message is read or dismissed.
 */
class WakeScreenActivity : ComponentActivity(), GlowSession.Host {

    private val power by lazy(LazyThreadSafetyMode.NONE) { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy(LazyThreadSafetyMode.NONE) { getSystemService(KeyguardManager::class.java) }
    private val audio by lazy(LazyThreadSafetyMode.NONE) { getSystemService(AudioManager::class.java) }
    private val displays by lazy(LazyThreadSafetyMode.NONE) { getSystemService(DisplayManager::class.java) }

    // Some builds stop honouring FLAG_KEEP_SCREEN_ON once this window has taken over a showing
    // lock screen, so an explicit screen lock backs it up while the LED is resumed. The power
    // button still turns the screen off; released the moment the LED is not in front.
    @Suppress("DEPRECATION")
    private val keepPanelOn by lazy(LazyThreadSafetyMode.NONE) {
        power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "AmbientGlow:led").apply { setReferenceCounted(false) }
    }

    private val timers = Handler(Looper.getMainLooper())
    private val wakeNow = Runnable {
        PanelWaker.wake(this)
        timers.postDelayed(verifyWake, WAKE_VERIFY_MS)
    }

    // Fallback if this build refuses the wake lock: the full-screen intent lights it (armed
    // with a one-shot turnScreenOn in case SystemUI doesn't wake for it).
    private val verifyWake = Runnable {
        if (power.isInteractive || inCall() || face.value == Face.AWAY) return@Runnable
        GlowLog.d("act wake lock ignored, falling back to full-screen intent")
        armTurnScreenOnOnce()
        val mode = if (face.value == Face.LED) WakeMode.LED else WakeMode.WAKE
        GlowLauncher.launchFromBackground(this, mode, GlowPending.entries.firstOrNull()?.color ?: DEFAULT_GLOW_COLOR)
    }
    private val closeNow = Runnable { finishAndRemoveTask() }

    private val face = mutableStateOf(Face.LOCK_SCREEN)

    /** The last message was read while the LED was lit: dot stopped, black until the screen goes off. */
    private val ending = mutableStateOf(false)

    /** A new message came in while the panel was dark: play its effect once the screen is on. */
    private var arrivalDue = false

    /** Black arrival: the effect or message is showing on the black panel; cleared when the dot takes over. */
    private val arriving = mutableStateOf(false)

    /** Bumped per announced message, so one arriving during the effect restarts it. */
    private val arrivalSeq = mutableIntStateOf(0)

    /**
     * After a wake into the LED, One UI's shade keeps focus, the system bars and the system
     * brightness until its wake transition ends (our brightness override only applies from then).
     * Until our window gets focus the dot stays hidden and we ask for the lowest brightness.
     */
    private val settling = mutableStateOf(false)
    private val settleNow = Runnable { finishSettling() }

    /**
     * Watches for the start of sleep while the system lock screen is on screen. Android fades the
     * panel for ~0.4 s between going-to-sleep and actually sleeping; switching to the LED and
     * relighting inside that window cancels the sleep before One UI reaches its dozing state.
     * Waking from a finished doze instead makes One UI run a ~300 ms DOZING → OCCLUDED transition
     * during which its shade holds focus, shows the battery icon and nav handle, and keeps the
     * system brightness (our override only applies once it lets go). Measured on the recording.
     * There is no callback for "going to sleep", so this is a 50 ms check that runs only while
     * the lock screen is visible.
     */
    private val sleepWatch = object : Runnable {
        override fun run() {
            if (face.value != Face.LOCK_SCREEN || preview.value) return
            if (power.isInteractive) {
                timers.postDelayed(this, SLEEP_WATCH_MS)
            } else {
                onGoingToSleep()
            }
        }
    }

    /** The LED was put up during the sleep fade; the SCREEN_OFF / pause that may follow are expected. */
    private var ledArmedForSleep = false

    /**
     * After an automatic wake (a new message lit the lock screen), cover the lock screen with
     * the LED while the phone is still fully awake, just before One UI's 3 s lock-screen timeout.
     * One UI only processes "covered" while awake, so this is the one switch with no off/on and
     * no system bars at all. Not after a tap or power press: then the user is likely unlocking.
     */
    private var autoTakeover = false
    private val takeOver = Runnable {
        GlowLog.d("act takeover interactive=${power.isInteractive} locked=${keyguard.isKeyguardLocked}")
        if (leaveIfUnlocked()) return@Runnable
        if (face.value == Face.LOCK_SCREEN && autoTakeover && power.isInteractive && keyguard.isKeyguardLocked) {
            autoTakeover = false
            showLed()
        }
    }

    private val relightTimes = ArrayDeque<Long>()
    private val settings = mutableStateOf(GlowSettings())
    private val geometry = mutableStateOf(ScreenGeometry.Unknown)
    private val preview = mutableStateOf(false)

    private val screenSignals = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GlowLog.d("act ${intent.action?.substringAfterLast('.')} face=${face.value}")
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
            }
        }
    }

    /**
     * The earliest sign of a power press while the LED is in front: going to sleep changes the
     * display 40-75 ms after the press, as the ~340 ms screen-off fade starts, and the phone is
     * no longer interactive by then. Opening the lock screen and waking right here cancels the
     * sleep, so the panel never goes dark. onPause only comes once the panel is off, which costs
     * the whole fade plus a panel off/on cycle (~0.5 s). Event-driven: nothing runs in between.
     *
     * Only on the change from interactive to not: we also put the LED up while the panel is
     * already dark (a black arrival, or the lock screen timing out), and the display changes
     * then too.
     */
    private val sleepSignal = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val wasInteractive = interactiveSeen
            interactiveSeen = power.isInteractive
            if (wasInteractive && ledTurnedOff()) {
                GlowLog.d("act display changed: going to sleep with the LED in front")
                revealAfterPower()
            }
        }
    }

    /** [PowerManager.isInteractive] at the last display change. */
    private var interactiveSeen = false

    // USER_PRESENT is sent by SystemUI, not the system uid, so a RECEIVER_NOT_EXPORTED receiver
    // never gets it (seen on One UI 8.5: the LED stayed over the unlocked phone). It is a
    // protected broadcast, so exporting this receiver lets no other app trigger it.
    private val unlockSignal = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GlowLog.d("act USER_PRESENT face=${face.value}")
            goAway()
        }
    }

    override val isAway: Boolean
        get() = face.value == Face.AWAY

    override fun onCreate(savedInstanceState: Bundle?) {
        // "Light" bar style = DARK icons and gesture handle. On our black panel they are invisible,
        // which matters because One UI re-shows the bars for ~150 ms on every wake before our
        // "hidden" request applies (seen on the recording as a white nav handle flash).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        GlowLog.d("act onCreate mode=${intent?.getStringExtra(GlowLauncher.EXTRA_MODE)}")
        // Never cover the lock screen by accident before start() decides.
        setLockScreenCover(cover = false)
        // Never let a relaunch show a stale snapshot as its starting window.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        observeScreenGeometry()
        ContextCompat.registerReceiver(
            this,
            screenSignals,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            unlockSignal,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED,
        )
        listenForSleep()
        GlowSession.attach(this)
        onBackPressedDispatcher.addCallback(this) { onUserDismiss() }

        setContent {
            GlowScreen(
                face = face.value,
                ending = ending.value || settling.value,
                arriving = arriving.value,
                arrivalSeq = arrivalSeq.intValue,
                settings = settings.value,
                preview = preview.value,
                geometry = geometry.value,
                onTap = ::onUserDismiss,
                onTouch = ::onLedTouch,
                onArrivalDone = ::onArrivalDone,
                onShowMessage = ::showMessage,
                onBlink = { leaveIfUnlocked() },
            )
        }
        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        GlowLog.d("act onNewIntent mode=${intent.getStringExtra(GlowLauncher.EXTRA_MODE)} face=${face.value}")
        setIntent(intent)
        start(intent)
    }

    override fun onResume() {
        super.onResume()
        GlowLog.d("act onResume face=${face.value} interactive=${power.isInteractive} locked=${keyguard.isKeyguardLocked}")
        // Resumed with no lock screen: the phone was unlocked underneath us.
        if (!preview.value && face.value != Face.AWAY && !keyguard.isKeyguardLocked) {
            goAway()
            return
        }
        if (face.value == Face.LED) {
            hideSystemBars()
            acquireKeepOn()
        }
    }

    override fun onPause() {
        GlowLog.d("act onPause face=${face.value} interactive=${power.isInteractive}")
        releaseKeepOn()
        // Paused by the panel going dark while the LED was in front: the user pressed power (or
        // double-tapped to sleep). Normally [sleepSignal] has already handled it; otherwise open
        // the lock screen now rather than waiting for the late ACTION_SCREEN_OFF, which arrives
        // ~0.3 s after the panel is already dark.
        if (ledTurnedOff()) revealAfterPower()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && leaveIfUnlocked()) return
        // Android re-shows the bars when a window regains focus over the lock screen.
        if (hasFocus && face.value == Face.LED) {
            hideSystemBars()
            // Focus is the hand-over: the bars are ours from here on, but they arrive still
            // showing and the framework slides them out. Stay dark until that has finished.
            if (settling.value) {
                timers.removeCallbacks(settleNow)
                timers.postDelayed(settleNow, BARS_HIDE_MS)
            }
        }
    }

    override fun onDestroy() {
        GlowLog.d("act onDestroy")
        timers.removeCallbacksAndMessages(null)
        GlowSession.detach(this)
        GlowShield.hide()
        GlowShield.stopArrival()
        GlowLauncher.dismissMessage(this)
        unregisterReceiver(screenSignals)
        unregisterReceiver(unlockSignal)
        displays.unregisterDisplayListener(sleepSignal)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // GlowSession.Host
    // ---------------------------------------------------------------------------------------

    override fun onNewMessage() {
        GlowLog.d("act onNewMessage face=${face.value} interactive=${power.isInteractive}")
        if (face.value == Face.AWAY) return
        ending.value = false
        settings.value = GlowPrefs.load(this) // the arrival choice may have changed since launch
        when (settings.value.arrival) {
            ArrivalMode.LOCK_SCREEN -> {
                // Show the system lock screen with all its notifications, lit, with the effect
                // over it. If the user is already looking at it, leave the hand-back off.
                val userOnLockScreen = face.value == Face.LOCK_SCREEN && power.isInteractive && !autoTakeover
                if (!userOnLockScreen) showLockScreen(auto = true)
                announce()
                if (!power.isInteractive) {
                    DarkHold.acquire(this)
                    wakeAfter(0)
                }
            }
            ArrivalMode.BLACK, ArrivalMode.MESSAGE -> {
                // The lock screen is only lit and in front when the user woke it themselves: they
                // are looking at it (maybe typing a PIN), so don't cover it; play the effect over it
                // instead, and the dot takes over when it sleeps.
                if (face.value == Face.LOCK_SCREEN && power.isInteractive) {
                    announce()
                    return
                }
                val dark = !power.isInteractive
                if (dark) DarkHold.acquire(this)
                showLed(arrival = true)
                // Arranged while dark: give the cover a moment to commit, then light straight into it.
                if (dark) wakeAfter(RELIGHT_DELAY_MS)
            }
        }
    }

    override fun onPendingChanged() {
        if (preview.value || !GlowPending.isEmpty) return
        if (face.value == Face.LED && power.isInteractive) {
            // Read elsewhere while the LED is lit: don't pop the lock screen up. Stop the dot,
            // stay black, let the screen time out normally, and finish when it goes off.
            GlowLog.d("act draining")
            ending.value = true
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            releaseKeepOn()
        } else {
            finishAndRemoveTask()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lock flow
    // ---------------------------------------------------------------------------------------

    private fun start(intent: Intent?) {
        GlowLauncher.dismissBridge(this)
        timers.removeCallbacksAndMessages(null)
        preview.value = intent?.getBooleanExtra(GlowLauncher.EXTRA_PREVIEW, false) == true
        settings.value = GlowPrefs.load(this)
        if (!preview.value && GlowPending.isEmpty) {
            // Stale launch: everything was read before we came up.
            finishAndRemoveTask()
            return
        }
        val mode = runCatching { WakeMode.valueOf(intent?.getStringExtra(GlowLauncher.EXTRA_MODE).orEmpty()) }
            .getOrDefault(WakeMode.LED)
        when (mode) {
            // With a black arrival the user is already looking at the lock screen: the effect
            // over it, no automatic cover.
            WakeMode.WAKE -> {
                showLockScreen(auto = settings.value.arrival == ArrivalMode.LOCK_SCREEN)
                announce()
            }
            WakeMode.LED -> showLed()
            WakeMode.ARRIVAL -> showLed(arrival = true)
        }
        // The full-screen intent normally lights the panel; make sure, without relaunching.
        if (!power.isInteractive) wakeAfter(LAUNCH_WAKE_CHECK_MS)
    }

    private fun onScreenOff() {
        DarkHold.acquire(this)
        timers.removeCallbacks(takeOver)
        timers.removeCallbacks(verifyWake)
        arrivalDue = false
        GlowShield.stopArrival()
        if (preview.value || ending.value) {
            finishAndRemoveTask()
            return
        }
        if (inCall()) return // proximity during a call: stay dark
        if (revealingAfterPower) return // onPause already switched to the lock screen and woke it
        if (ledArmedForSleep) {
            // Already the LED, and One UI knows it is covered: just light the panel.
            ledArmedForSleep = false
            wakeAfter(RELIGHT_DELAY_MS)
            return
        }
        when (face.value) {
            // The user turned the LED off (power, double tap): open the phone, like without us.
            Face.LED -> revealAfterPower()
            // The lock screen went dark (timeout, or power on the lock screen): back to the LED,
            // arranged while dark so the panel comes on already black with the dot.
            Face.LOCK_SCREEN -> {
                if (overRelightBudget()) {
                    GlowLog.d("act relight budget used; staying dark")
                    return
                }
                showLed()
                wakeAfter(RELIGHT_DELAY_MS)
            }
            Face.AWAY -> Unit // the listener relights through the full-screen intent
        }
    }

    private fun onScreenOn() {
        revealingAfterPower = false
        disarmTurnScreenOn()
        timers.removeCallbacks(verifyWake)
        when (face.value) {
            Face.LED -> {
                hideSystemBars()
                if (settling.value) timers.postDelayed(settleNow, SETTLE_FALLBACK_MS)
            }
            Face.LOCK_SCREEN -> {
                if (arrivalDue) announce()
                watchForSleep()
                scheduleTakeover()
            }
            Face.AWAY -> Unit
        }
    }

    private fun listenForSleep() {
        interactiveSeen = power.isInteractive
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // Also state, refresh-rate and (36.1+) brightness changes: with only the default
            // events the first one comes ~135 ms after the press (One UI 8.5).
            var events = DisplayManager.EVENT_TYPE_DISPLAY_CHANGED or
                DisplayManager.EVENT_TYPE_DISPLAY_REFRESH_RATE or
                DisplayManager.EVENT_TYPE_DISPLAY_STATE
            if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                @SuppressLint("InlinedApi")
                events = events or DisplayManager.EVENT_TYPE_DISPLAY_BRIGHTNESS
            }
            displays.registerDisplayListener(mainExecutor, events, sleepSignal)
        } else {
            displays.registerDisplayListener(sleepSignal, timers)
        }
    }

    /** Backup for a missed unlock broadcast: never stay over an unlocked phone. */
    private fun leaveIfUnlocked(): Boolean {
        if (preview.value || face.value == Face.AWAY || keyguard.isKeyguardLocked) return false
        GlowLog.d("act unlocked underneath (face=${face.value})")
        goAway()
        return true
    }

    private fun scheduleTakeover() {
        timers.removeCallbacks(takeOver)
        if (autoTakeover && power.isInteractive) timers.postDelayed(takeOver, TAKEOVER_MS)
    }

    /**
     * Play the user's chosen effect once over the lit lock screen, in the newest message's colour.
     * Needs the optional [GlowShield]; without it the lock screen simply lights up.
     */
    private fun announce() {
        arrivalDue = !power.isInteractive
        if (arrivalDue) return
        GlowShield.playArrival(GlowPending.entries.firstOrNull()?.color ?: DEFAULT_GLOW_COLOR)
    }

    private fun watchForSleep() {
        timers.removeCallbacks(sleepWatch)
        if (face.value == Face.LOCK_SCREEN && power.isInteractive && !preview.value) {
            timers.postDelayed(sleepWatch, SLEEP_WATCH_MS)
        }
    }

    private fun onGoingToSleep() {
        GlowLog.d("act going to sleep on the lock screen")
        if (inCall() || GlowPending.isEmpty) return
        if (overRelightBudget()) {
            GlowLog.d("act relight budget used; staying dark")
            return
        }
        DarkHold.acquire(this)
        showLed()
        ledArmedForSleep = true
        // Relight while the panel is still fading, so the sleep is cancelled rather than finished.
        // If this wake is refused, SCREEN_OFF relights as before.
        wakeAfter(SLEEP_CANCEL_DELAY_MS)
    }

    /**
     * A finger came down on the LED, or left without a tap. The LED holds the panel at its idle
     * rate (10 Hz on One UI), which also overrides the system's touch boost, so the taps would be
     * read and the lock screen's reveal start drawing at that rate. Leave it at the first touch:
     * the panel is at full rate by the time the double tap completes.
     */
    private fun onLedTouch(down: Boolean) {
        if (face.value != Face.LED || arriving.value) return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = if (down) 0 else lowestRefreshModeId()
            preferredRefreshRate = if (down) highestRefreshRate() else 0f
        }
    }

    /** Tap or back on the LED: show the lock screen, with no automatic hand-back. */
    private fun onUserDismiss() {
        GlowLog.d("act user dismiss face=${face.value}")
        if (leaveIfUnlocked()) return
        if (preview.value || ending.value) {
            finishAndRemoveTask()
        } else if (face.value == Face.LED) {
            showLockScreen()
        }
    }

    /**
     * The phone stopped being interactive with the LED in front: the user pressed power. Not
     * when we put the LED up ourselves during the sleep fade, and not for the proximity sensor
     * during a call.
     */
    private fun ledTurnedOff(): Boolean =
        face.value == Face.LED && !power.isInteractive && !preview.value && !inCall() && !ledArmedForSleep

    /** Set from the power press until the panel is back on, so the late SCREEN_OFF is ignored. */
    private var revealingAfterPower = false

    private fun revealAfterPower() {
        GlowLog.d("act revealAfterPower")
        revealingAfterPower = true
        DarkHold.acquire(this)
        // Uncover, then wake. One UI starts its sleep transition ~7 ms after the press, before
        // any signal reaches us, so the lock screen always comes in through its ~0.3 s wake
        // transition, never the quicker uncover a tap gets. Waking first and uncovering once
        // One UI is back to "covered" was measured slower (0.5-0.65 s).
        showLockScreen()
        // Wake now, not through the queue: during the screen-off fade the main thread can be held
        // up by a frame for ~0.2 s, long enough for the panel to go dark first.
        timers.removeCallbacks(wakeNow)
        wakeNow.run()
    }

    /** Cover the lock screen with the LED; [arrival] first plays the effect on the black panel. */
    private fun showLed(arrival: Boolean = false) {
        GlowLog.d("act showLed arrival=$arrival pending=${GlowPending.entries.size}")
        timers.removeCallbacks(sleepWatch)
        timers.removeCallbacks(takeOver)
        arrivalDue = false
        GlowShield.stopArrival()
        if (!preview.value && GlowPending.isEmpty) {
            finishAndRemoveTask()
            return
        }
        if (arrival) {
            arriving.value = true
            arrivalSeq.intValue++
        }
        // Paint black first, cover the lock screen last, so the first frame is already black.
        setSeeThrough(false)
        window.setBackgroundDrawable(android.graphics.Color.BLACK.toDrawable())
        window.clearFlags(UNTOUCHABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Arranged while dark (or before focus): come up at the lowest brightness, dot hidden,
        // until we own the bars. Already focused (e.g. preview): go straight to full.
        val focused = hasWindowFocus() && power.isInteractive
        settling.value = !focused
        // One UI's bars come up over us until the hand-over; cover them if the user allowed it.
        if (!focused) GlowShield.show()
        applyWindow(
            brightness = if (focused) ledLevel() else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF,
            lowRefresh = true,
        )
        hideSystemBars()
        face.value = Face.LED
        setLockScreenCover(cover = true)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) acquireKeepOn()
        // A preview has no notification to read, so it demos the LED briefly and closes.
        if (preview.value) timers.postDelayed(closeNow, PREVIEW_LED_MS)
    }

    /**
     * Let the system lock screen show over us while we stay the top task. The LED comes back
     * when the system's lock-screen timeout turns the screen off.
     */
    private fun showLockScreen(auto: Boolean = false) {
        GlowLog.d("act showLockScreen auto=$auto interactive=${power.isInteractive}")
        ledArmedForSleep = false
        autoTakeover = auto
        face.value = Face.LOCK_SCREEN
        becomeInvisible()
        watchForSleep()
        scheduleTakeover()
    }

    /**
     * [ArrivalMode.MESSAGE], once the black panel is up and focused: let the system pop up only
     * the new message. If it will not by itself (its channel does not peek), re-post it.
     */
    private fun showMessage() {
        val message = GlowPending.message ?: return
        GlowLog.d("act message pop-up systemPopsUp=${message.systemPopsUp}")
        if (!message.systemPopsUp) GlowLauncher.showMessage(this, message.sbn)
    }

    /** The effect on the black panel has handed over to the dot: drop to the user's LED brightness. */
    private fun onArrivalDone() {
        if (!arriving.value) return
        GlowLog.d("act arrival done")
        arriving.value = false
        GlowLauncher.dismissMessage(this)
        if (face.value == Face.LED && !settling.value) applyWindow(brightness = ledLevel(), lowRefresh = true)
    }

    /** Full brightness while the effect announces a message, the user's level for the dot. */
    private fun ledLevel(): Float = if (arriving.value) 1f else settings.value.ledBrightness.level

    /** The phone was unlocked: get out of the way of the user's apps. */
    private fun goAway() {
        GlowLog.d("act goAway face=${face.value}")
        timers.removeCallbacksAndMessages(null)
        if (preview.value || ending.value || GlowPending.isEmpty) {
            finishAndRemoveTask()
            return
        }
        face.value = Face.AWAY
        ledArmedForSleep = false
        arrivalDue = false
        GlowShield.stopArrival()
        becomeInvisible()
        timers.removeCallbacks(sleepWatch)
        moveTaskToBack(true)
    }

    private fun finishSettling() {
        timers.removeCallbacks(settleNow)
        GlowShield.hide()
        if (!settling.value) return
        settling.value = false
        if (face.value == Face.LED) {
            GlowLog.d("act settled: LED at full brightness")
            applyWindow(brightness = ledLevel(), lowRefresh = true)
        }
    }

    // Uncover the lock screen first, then go transparent and untouchable.
    private fun becomeInvisible() {
        timers.removeCallbacks(settleNow)
        settling.value = false
        arriving.value = false // the dot comes back alone
        GlowLauncher.dismissMessage(this) // the lock screen lists the original already
        GlowShield.hide()
        setLockScreenCover(cover = false)
        window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
        setSeeThrough(true)
        window.addFlags(UNTOUCHABLE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyWindow(brightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, lowRefresh = false)
        releaseKeepOn()
    }

    private fun wakeAfter(delayMs: Long) {
        timers.removeCallbacks(wakeNow)
        timers.removeCallbacks(verifyWake)
        timers.postDelayed(wakeNow, delayMs)
    }

    private fun inCall(): Boolean = audio.mode != AudioManager.MODE_NORMAL

    /** Loop guard: at most [RELIGHT_BUDGET] lock-screen → LED relights per [RELIGHT_WINDOW_MS]. */
    private fun overRelightBudget(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (relightTimes.isNotEmpty() && now - relightTimes.first() > RELIGHT_WINDOW_MS) relightTimes.removeFirst()
        if (relightTimes.size >= RELIGHT_BUDGET) return true
        relightTimes.addLast(now)
        return false
    }

    // ---------------------------------------------------------------------------------------
    // Window plumbing
    // ---------------------------------------------------------------------------------------

    /**
     * Cover (occlude) the lock screen, or let it show over us. Works while stopped or asleep.
     * turnScreenOn stays off: an armed turnScreenOn activity that occludes while the device is
     * asleep can make WindowManager wake the screen by itself. [PanelWaker] does every wake.
     */
    private fun setLockScreenCover(cover: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(cover)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            if (cover) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }
        }
    }

    /** Only for the full-screen-intent fallback; cleared again once the screen is on. */
    private fun armTurnScreenOnOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    private fun disarmTurnScreenOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    /**
     * Opaque while the LED shows (so nothing behind is kept alive or can leak through), see-through
     * otherwise (so an unlock reveals the home screen, not a black frame). API 30+; older
     * versions keep the translucent theme throughout.
     */
    private fun setSeeThrough(seeThrough: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setTranslucent(seeThrough)
    }

    @SuppressLint("WakelockTimeout") // released in onPause / when the LED leaves the front
    private fun acquireKeepOn() {
        if (!keepPanelOn.isHeld) keepPanelOn.acquire()
    }

    private fun releaseKeepOn() {
        if (keepPanelOn.isHeld) keepPanelOn.release()
    }

    /**
     * [lowRefresh]: the LED face. While it plays the arrival effect it asks for the panel's top
     * rate instead: adaptive-refresh panels drop to their idle rate (10 Hz on One UI) over an
     * almost static black window, and an effect started there only ever draws at that rate, so
     * the system never sees a reason to raise it. The lock-screen overlay never had this problem
     * because SystemUI keeps the rate up there.
     */
    private fun applyWindow(brightness: Float, lowRefresh: Boolean) {
        val playing = lowRefresh && arriving.value
        window.attributes = window.attributes.apply {
            screenBrightness = brightness
            preferredDisplayModeId = if (lowRefresh && !playing) lowestRefreshModeId() else 0
            preferredRefreshRate = if (playing) highestRefreshRate() else 0f
        }
    }

    private fun currentDisplay(): Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    }

    private fun highestRefreshRate(): Float =
        currentDisplay()?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f

    /** Same resolution, lowest refresh rate: fewer panel scans while the LED sits mostly dark. */
    private fun lowestRefreshModeId(): Int {
        val display = currentDisplay() ?: return 0
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { it.refreshRate }
            ?.modeId ?: 0
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // Dark icons + handle on black: invisible even during the moment One UI shows them.
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun observeScreenGeometry() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            geometry.value = ScreenGeometry.from(insets)
            ViewCompat.onApplyWindowInsets(view, insets)
        }
    }

    private companion object {
        const val UNTOUCHABLE = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        const val SLEEP_WATCH_MS = 50L

        /**
         * Lock screen starting to sleep → relight. One UI's LOCKSCREEN → DOZING step takes ~260 ms
         * (power press); waking late in it leaves the shortest wake transition (measured: 0 ms gave
         * 180 ms of nav handle, 120 ms gave 80 ms, 250 ms was too late and ran the full one).
         */
        const val SLEEP_CANCEL_DELAY_MS = 120L

        /** One UI sleeps an untouched lock screen 3 s after the wake; take over 0.5 s before. */
        const val TAKEOVER_MS = 2_500L

        /** If focus never arrives after a wake into the LED, show it anyway. */
        const val SETTLE_FALLBACK_MS = 600L

        /** The framework's slide-out of bars a window gains while they show (InsetsController, 340 ms). */
        const val BARS_HIDE_MS = 360L

        const val RELIGHT_BUDGET = 3
        const val RELIGHT_WINDOW_MS = 15_000L

        /** Lock screen dark → LED: the switch is committed ~10 ms after screen-off; small margin. */
        const val RELIGHT_DELAY_MS = 150L

        /** After a full-screen launch, light the panel ourselves if the system didn't. */
        const val LAUNCH_WAKE_CHECK_MS = 600L
        const val WAKE_VERIFY_MS = 1_200L
        const val PREVIEW_LED_MS = 12_000L
    }
}

// One LED blink: fade in, hold, fade out, dark. Frames are drawn only during the fades.
private const val LED_FADE_IN_MS = 420
private const val LED_HOLD_MS = 650L
private const val LED_FADE_OUT_MS = 700
private const val LED_DARK_MS = 1_600L
private const val LED_HALO_FACTOR = 3.2f

internal object RealTimeMotion : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/** Burn-in guard: the glow steps through a 2 px square, one corner per blink. */
private val PIXEL_SHIFTS = listOf(Offset(0f, 0f), Offset(2f, 0f), Offset(2f, 2f), Offset(0f, 2f))

private val PREVIEW_COLORS = listOf(DEFAULT_GLOW_COLOR, 0xFFFF2E93.toInt(), 0xFFB6FF3B.toInt())

@Composable
private fun GlowScreen(
    face: Face,
    ending: Boolean,
    arriving: Boolean,
    arrivalSeq: Int,
    settings: GlowSettings,
    preview: Boolean,
    geometry: ScreenGeometry,
    onTap: () -> Unit,
    onTouch: (down: Boolean) -> Unit,
    onArrivalDone: () -> Unit,
    onShowMessage: () -> Unit,
    onBlink: () -> Unit,
) {
    // Only the LED face draws anything. The others stay fully transparent, so the lock screen
    // (or, right after an unlock, the home screen) is what the user sees.
    if (face != Face.LED) return
    val colors = if (preview) PREVIEW_COLORS else GlowPending.colors().ifEmpty { listOf(DEFAULT_GLOW_COLOR) }
    val tap by rememberUpdatedState(onTap)
    val touch by rememberUpdatedState(onTouch)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallow both taps of a double tap, so the second one can't reach the lock screen
            // (where One UI's double-tap-to-sleep would turn the screen off again). Act on the
            // second press, not its release: its lift still comes to this window, and the lock
            // screen starts showing one press-length sooner.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    touch(true)
                    if (waitForUpOrCancellation()?.consume() == null) {
                        touch(false)
                        return@awaitEachGesture
                    }
                    withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }?.consume()
                    tap()
                }
            },
    ) {
        // Keyed per announced message, so a new one restarts the effect. Newest colour first.
        if (!ending) {
            key(arrivalSeq) {
                if (arriving && settings.arrival == ArrivalMode.MESSAGE) {
                    // The effect plays once around the system's pop-up; the pop-up sets the length.
                    ArrivalEffect(settings, colors.first(), geometry, onDone = {})
                    MessagePopUp(onShow = onShowMessage, onDone = onArrivalDone)
                } else if (arriving) {
                    ArrivalEffect(settings, colors.first(), geometry, onDone = onArrivalDone)
                } else {
                    LedLayer(settings, colors, onBlink)
                }
            }
        }
    }
}

/** How long the black panel holds for the system's message pop-up before the dot takes over. */
private const val MESSAGE_HOLD_MS = 6_000L

/** [ArrivalMode.MESSAGE]: the system's pop-up is the message; this only raises it and times it. */
@Composable
private fun MessagePopUp(onShow: () -> Unit, onDone: () -> Unit) {
    val show by rememberUpdatedState(onShow)
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        show()
        withContext(RealTimeMotion) { delay(MESSAGE_HOLD_MS) }
        done()
    }
}

/**
 * Old-school notification LED: blink, rest, next app's colour, repeat. Always the dot; the
 * chosen style is only the new-message effect ([ArrivalEffect]).
 */
@Composable
private fun LedLayer(settings: GlowSettings, colors: List<Int>, onBlink: () -> Unit) {
    val glow = remember { Animatable(0f) }
    val blink by rememberUpdatedState(onBlink)
    var cycle by remember { mutableIntStateOf(0) }
    // The blink is a signal, not decoration: keep its timing even when developer options
    // shorten or disable animations (animator duration scale).
    LaunchedEffect(Unit) {
        withContext(RealTimeMotion) {
            while (true) {
                glow.animateTo(1f, tween(LED_FADE_IN_MS, easing = FastOutSlowInEasing))
                delay(LED_HOLD_MS)
                glow.animateTo(0f, tween(LED_FADE_OUT_MS, easing = LinearOutSlowInEasing))
                delay(LED_DARK_MS)
                cycle++
                blink()
            }
        }
    }
    val shift = PIXEL_SHIFTS[cycle % PIXEL_SHIFTS.size]
    LedDot(
        color = Color(colors[cycle % colors.size]),
        alpha = { glow.value },
        dotX = settings.dotX,
        dotY = settings.dotY,
        radius = settings.dotSize.radius,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = shift.x
                translationY = shift.y
            },
    )
}

/**
 * A bright core, a hot white centre and a soft radial bloom. The brush is built once per
 * colour/size change in drawWithCache; each blink frame only changes the layer alpha.
 */
@Composable
private fun LedDot(
    color: Color,
    alpha: () -> Float,
    dotX: Float,
    dotY: Float,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier
            .graphicsLayer { this.alpha = alpha() }
            .drawWithCache {
                val core = radius.toPx()
                val bloom = core * LED_HALO_FACTOR
                // Same margin as GlowGraphic, so the LED sits exactly where the dashboard showed it.
                val center = dotCenter(dotX, dotY, size, core * DOT_HALO_FACTOR)
                val halo = Brush.radialGradient(
                    0f to color.copy(alpha = 0.65f),
                    0.35f to color.copy(alpha = 0.22f),
                    1f to Color.Transparent,
                    center = center,
                    radius = bloom,
                )
                val hot = lerp(color, Color.White, 0.45f)
                onDrawBehind {
                    drawCircle(halo, radius = bloom, center = center)
                    drawCircle(color, radius = core, center = center)
                    drawCircle(hot, radius = core * 0.5f, center = center)
                }
            },
    )
}
