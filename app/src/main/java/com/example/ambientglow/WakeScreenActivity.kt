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
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

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

    private val face = mutableStateOf(Face.LOCK_SCREEN)

    /** The last message was read while the LED was lit: the dot ends its breath, then black until the screen goes off. */
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
            if (face.value != Face.LOCK_SCREEN) return
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
        } else if (power.isInteractive) {
            GlowShield.hide() // bailed: give the lit lock screen back from under the dim
        }
    }

    /** Dims the lit lock screen to black over [TAKEOVER_DIM_MS], ending as [takeOver] covers it. */
    private val dimForTakeover = Runnable {
        if (face.value == Face.LOCK_SCREEN && autoTakeover && power.isInteractive && keyguard.isKeyguardLocked) {
            GlowShield.dimIn(TAKEOVER_DIM_MS)
        }
    }

    /**
     * Rate limit for lock screen → LED relights, as a token bucket: up to [RELIGHT_BURST] at once,
     * then one per [RELIGHT_REFILL_MS]. Every power press flips the face, and each flip back to the
     * LED is a relight, so no rule can tell a user's quick presses from something forcing sleep in
     * a loop. So it never drops a relight, it only spaces them out: quick presses see the LED at
     * most a refill late, and a loop is held to one panel wake per refill.
     */
    private var relightTokens = RELIGHT_BURST
    private var relightRefilledAt = 0L
    private val relightLater = Runnable { relightLed(RELIGHT_DELAY_MS) }
    private val settings = mutableStateOf(GlowSettings())
    private val geometry = mutableStateOf(ScreenGeometry.Unknown)

    /** The LED asked for its breath rate ([ledFrameRate]); a finger is on it ([onLedTouch]). */
    private var ledFast = false
    private var touching = false

    /** (idle, breath) display mode ids, see [ledModes]; cleared on every [showLed]. */
    private var ledModeIds: Pair<Int, Int>? = null

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
            if (interactiveSeen) {
                // Awake again: the sleep we were handling was cancelled; the next one is the user's.
                ledArmedForSleep = false
                revealingAfterPower = false
            }
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
                ending = ending.value,
                settling = settling.value,
                arriving = arriving.value,
                arrivalSeq = arrivalSeq.intValue,
                settings = settings.value,
                geometry = geometry.value.fitted(settings.value, resources.displayMetrics.density),
                onTap = ::onUserDismiss,
                onTouch = ::onLedTouch,
                onArrivalDone = ::onArrivalDone,
                onShowMessage = ::showMessage,
                onRetractMessage = ::retractMessage,
                onBlink = { leaveIfUnlocked() },
                onLedFade = ::ledFrameRate,
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
        if (face.value != Face.AWAY && !keyguard.isKeyguardLocked) {
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
        if (!GlowPending.isEmpty) return
        if (face.value == Face.LED && power.isInteractive) {
            // Read elsewhere while the LED is lit: don't pop the lock screen up. Let the dot finish
            // its breath, stay black, let the screen time out normally, and finish when it goes off.
            // An arrival in progress is cut short: brightness and rate drop, the dot lights no more.
            GlowLog.d("act draining")
            ending.value = true
            onArrivalDone()
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
        settings.value = GlowPrefs.load(this)
        if (GlowPending.isEmpty) {
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
        // SCREEN_OFF arrives ~0.2-0.3 s late. If the panel is already back on (a quick second
        // power press, or our own wake cancelled the sleep), it is stale: acting on it would put
        // the LED over a lit lock screen.
        if (power.isInteractive) return
        DarkHold.acquire(this)
        timers.removeCallbacks(takeOver)
        timers.removeCallbacks(dimForTakeover)
        timers.removeCallbacks(verifyWake)
        arrivalDue = false
        GlowShield.stopArrival()
        if (ending.value) {
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
            Face.LOCK_SCREEN -> relightLed(RELIGHT_DELAY_MS)
            Face.AWAY -> Unit // the listener relights through the full-screen intent
        }
    }

    private fun onScreenOn() {
        revealingAfterPower = false
        ledArmedForSleep = false
        timers.removeCallbacks(relightLater)
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
        if (face.value == Face.AWAY || keyguard.isKeyguardLocked) return false
        GlowLog.d("act unlocked underneath (face=${face.value})")
        goAway()
        return true
    }

    private fun scheduleTakeover() {
        timers.removeCallbacks(takeOver)
        timers.removeCallbacks(dimForTakeover)
        if (autoTakeover && power.isInteractive) {
            timers.postDelayed(dimForTakeover, TAKEOVER_MS - TAKEOVER_DIM_MS)
            timers.postDelayed(takeOver, TAKEOVER_MS)
        }
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
        if (face.value == Face.LOCK_SCREEN && power.isInteractive) {
            timers.postDelayed(sleepWatch, SLEEP_WATCH_MS)
        }
    }

    private fun onGoingToSleep() {
        GlowLog.d("act going to sleep on the lock screen")
        if (inCall() || GlowPending.isEmpty || !takeRelightToken()) return
        DarkHold.acquire(this)
        showLed()
        ledArmedForSleep = true
        // Relight while the panel is still fading, so the sleep is cancelled rather than finished.
        // If this wake is refused, SCREEN_OFF relights as before.
        wakeAfter(SLEEP_CANCEL_DELAY_MS)
    }

    /**
     * The lock screen is dark: put the LED up and light it after [delayMs], or, when the rate
     * limit has no token, as soon as it has one.
     */
    private fun relightLed(delayMs: Long) {
        timers.removeCallbacks(relightLater)
        if (power.isInteractive || face.value != Face.LOCK_SCREEN || inCall() || GlowPending.isEmpty) return
        if (!takeRelightToken()) return
        showLed()
        wakeAfter(delayMs)
    }

    /**
     * Takes a relight token. Without one, schedules [relightLater] for when the next one is due
     * and returns false.
     */
    private fun takeRelightToken(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val refills = (now - relightRefilledAt) / RELIGHT_REFILL_MS
        if (refills > 0) {
            relightTokens = minOf(RELIGHT_BURST.toLong(), relightTokens + refills).toInt()
            relightRefilledAt = if (relightTokens == RELIGHT_BURST) now else relightRefilledAt + refills * RELIGHT_REFILL_MS
        }
        if (relightTokens > 0) {
            relightTokens--
            return true
        }
        val wait = relightRefilledAt + RELIGHT_REFILL_MS - now
        GlowLog.d("act relight rate-limited, in $wait ms")
        timers.removeCallbacks(relightLater)
        timers.postDelayed(relightLater, wait)
        // Handler time stops while the CPU sleeps.
        DarkHold.acquire(this, wait + RELIGHT_DELAY_MS + 500)
        return false
    }

    /**
     * A finger came down on the LED, or left without a tap. The LED holds the panel at its idle
     * rate (10 Hz on One UI), which also overrides the system's touch boost, so the taps would be
     * read and the lock screen's reveal start drawing at that rate. Leave it at the first touch:
     * the panel is at full rate by the time the double tap completes. On release, back to the
     * LED's own rate, the breath's if one is lit.
     */
    private fun onLedTouch(down: Boolean) {
        touching = down
        if (face.value != Face.LED || arriving.value) return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = if (down) 0 else if (ledFast) ledModes().second else ledModes().first
            preferredRefreshRate = if (down) highestRefreshRate() else 0f
        }
    }

    /**
     * The LED face idles at the panel's lowest rate (10 Hz on One UI), where a 0.4 s fade is only
     * four frames. Each breath asks for ~[LED_FADE_HZ] just before it lights and lets go once it
     * is dark again, so the rate only ever changes while the dot is off.
     */
    private fun ledFrameRate(fast: Boolean) {
        if (ledFast == fast) return
        ledFast = fast
        if (face.value != Face.LED || arriving.value || settling.value || touching) return
        val (idle, fade) = ledModes()
        if (idle == fade) return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = if (fast) fade else idle
            preferredRefreshRate = 0f
        }
    }

    /** Tap or back on the LED: show the lock screen, with no automatic hand-back. */
    private fun onUserDismiss() {
        GlowLog.d("act user dismiss face=${face.value}")
        if (leaveIfUnlocked()) return
        if (ending.value) {
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
        face.value == Face.LED && !power.isInteractive && !inCall() && !ledArmedForSleep

    /** Set from the power press until the panel is back on, so that sleep's SCREEN_OFF is ignored. */
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
        // showLockScreen ran while still asleep, so it couldn't start this; a quick second press
        // on the lock screen should go back to the LED like any other.
        watchForSleep()
    }

    /** Cover the lock screen with the LED; [arrival] first plays the effect on the black panel. */
    private fun showLed(arrival: Boolean = false) {
        GlowLog.d("act showLed arrival=$arrival pending=${GlowPending.entries.size}")
        timers.removeCallbacks(sleepWatch)
        timers.removeCallbacks(takeOver)
        timers.removeCallbacks(dimForTakeover)
        // The double tap never reports a release; a resolution change needs new mode ids.
        touching = false
        ledFast = false
        ledModeIds = null
        arrivalDue = false
        GlowShield.stopArrival()
        if (GlowPending.isEmpty) {
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
        // until we own the bars. Already focused: go straight to full.
        val focused = hasWindowFocus() && power.isInteractive
        settling.value = !focused
        // One UI's bars come up over us until the hand-over; cover them if the user allowed it.
        if (!focused) GlowShield.show()
        // Already lit, so no SCREEN_ON will come to end the settling; if focus doesn't change
        // either, the dot would stay hidden on a black panel at the lowest brightness.
        if (!focused && power.isInteractive) timers.postDelayed(settleNow, SETTLE_FALLBACK_MS)
        applyWindow(
            brightness = if (focused) ledLevel() else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF,
            lowRefresh = true,
        )
        hideSystemBars()
        face.value = Face.LED
        setLockScreenCover(cover = true)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) acquireKeepOn()
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

    /** [ArrivalMode.MESSAGE]: slide the pop-up away while brightness and rate still hold. */
    private fun retractMessage() {
        if (arriving.value) GlowLauncher.dismissMessage(this)
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
        if (ending.value || GlowPending.isEmpty) {
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
        touching = false
        ledFast = false
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
     * because SystemUI keeps the rate up there. Otherwise the dot keeps the rate it asked for
     * ([ledFrameRate]), and a finger on it the top rate ([onLedTouch]).
     */
    private fun applyWindow(brightness: Float, lowRefresh: Boolean) {
        val playing = lowRefresh && arriving.value
        val boosted = playing || (lowRefresh && touching)
        window.attributes = window.attributes.apply {
            screenBrightness = brightness
            preferredDisplayModeId = when {
                !lowRefresh || boosted -> 0
                ledFast -> ledModes().second
                else -> ledModes().first
            }
            preferredRefreshRate = if (boosted) highestRefreshRate() else 0f
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

    /**
     * The LED's display modes at the current resolution, as (idle, breath): the lowest rate, for
     * fewer panel scans while it sits dark, and the one nearest [LED_FADE_HZ] for its breaths.
     * Looked up once per [showLed], never per frame; (0, 0) (no preference) without a display.
     */
    private fun ledModes(): Pair<Int, Int> {
        ledModeIds?.let { return it }
        val display = currentDisplay() ?: return 0 to 0
        val current = display.mode
        val modes = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        val idle = modes.minByOrNull { it.refreshRate }?.modeId ?: 0
        val fade = modes.minByOrNull { abs(it.refreshRate - LED_FADE_HZ) }?.modeId ?: idle
        return (idle to fade).also { ledModeIds = it }
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

        /** Lock screen dims to black over this long before [TAKEOVER_MS], so the takeover isn't a cut. */
        const val TAKEOVER_DIM_MS = 240L

        /**
         * The rate the LED asks for while it breathes. The S23's panel offers 10/24/30/48/60/96/120
         * Hz: 30 is three times smoother than the 10 Hz idle at half the cost of 60.
         */
        const val LED_FADE_HZ = 30f

        /** If focus never arrives after a wake into the LED, show it anyway. */
        const val SETTLE_FALLBACK_MS = 600L

        /** The framework's slide-out of bars a window gains while they show (InsetsController, 340 ms). */
        const val BARS_HIDE_MS = 360L

        /** Relight rate limit: a burst for quick presses by hand (~1 s per LED ↔ lock screen round trip), then one per refill. */
        const val RELIGHT_BURST = 8
        const val RELIGHT_REFILL_MS = 2_000L

        /** Lock screen dark → LED: the switch is committed ~10 ms after screen-off; small margin. */
        const val RELIGHT_DELAY_MS = 150L

        /** After a full-screen launch, light the panel ourselves if the system didn't. */
        const val LAUNCH_WAKE_CHECK_MS = 600L
        const val WAKE_VERIFY_MS = 1_200L
    }
}

// One LED breath: a quick soft rise, a short sinking crest, a slower exhale, then dark.
// One clock (ms into the breath) drives it; the dashboard previews breathe with the same function.
internal const val LED_RISE_MS = 400f
internal const val LED_CREST_MS = 560f
internal const val LED_FALL_MS = 900f
internal const val LED_BREATH_MS = LED_RISE_MS + LED_CREST_MS + LED_FALL_MS // 1860
private const val LED_CREST_LEVEL = 0.88f

/** Dark between breaths; with the breath it keeps a ~3.37 s period. */
private const val LED_DARK_MS = 1_510L

/** Dark between two apps' breaths within one round; the round ends with the full [LED_DARK_MS]. */
private const val LED_GAP_MS = 700L

/**
 * One 10 Hz relayout vsync plus SurfaceFlinger's switch at its next vsync, with margin; taken
 * from the dark gap, so the period is unchanged.
 */
private const val LED_RATE_PREROLL_MS = 200L

private val LedRise = CubicBezierEasing(0.3f, 0f, 0.15f, 1f)
private val LedCrest = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
private val LedFall = CubicBezierEasing(0.4f, 0f, 0.12f, 1f)

/** LED level at [ms] into one breath. Every joint has zero velocity on both sides, so there is no knee. */
internal fun ledBreathAt(ms: Float): Float = when {
    ms <= 0f -> 0f
    ms < LED_RISE_MS -> LedRise.transform(ms / LED_RISE_MS)
    ms < LED_RISE_MS + LED_CREST_MS ->
        1f - (1f - LED_CREST_LEVEL) * LedCrest.transform((ms - LED_RISE_MS) / LED_CREST_MS)
    ms < LED_BREATH_MS ->
        LED_CREST_LEVEL * (1f - LedFall.transform((ms - LED_RISE_MS - LED_CREST_MS) / LED_FALL_MS))
    else -> 0f
}

private const val LED_HALO_FACTOR = 3.2f

internal object RealTimeMotion : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/** Burn-in guard: the glow steps through a 2 px square, one corner per breath. */
private val PIXEL_SHIFTS = listOf(Offset(0f, 0f), Offset(2f, 0f), Offset(2f, 2f), Offset(0f, 2f))

/** The ring's burn-in guard: it breathes 1 px in and out instead, so it stays centred on the lens. */
private val RING_SHIFTS = listOf(0f, 1f, 0f, -1f)

@Composable
private fun GlowScreen(
    face: Face,
    ending: Boolean,
    settling: Boolean,
    arriving: Boolean,
    arrivalSeq: Int,
    settings: GlowSettings,
    geometry: ScreenGeometry,
    onTap: () -> Unit,
    onTouch: (down: Boolean) -> Unit,
    onArrivalDone: () -> Unit,
    onShowMessage: () -> Unit,
    onRetractMessage: () -> Unit,
    onBlink: () -> Unit,
    onLedFade: (fast: Boolean) -> Unit,
) {
    // Only the LED face draws anything. The others stay fully transparent, so the lock screen
    // (or, right after an unlock, the home screen) is what the user sees.
    if (face != Face.LED) return
    val colors = GlowPending.colors().ifEmpty { listOf(DEFAULT_GLOW_COLOR) }
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
        // Settling: nothing until the bars are ours. Ending (read elsewhere): an arrival vanishes
        // (the activity ends it too), the dot finishes the breath it is in and lights no more.
        if (!settling) {
            key(arrivalSeq) {
                if (arriving && !ending) {
                    if (settings.arrival == ArrivalMode.MESSAGE) {
                        // The effect plays once around the system's pop-up; the pop-up sets the length.
                        ArrivalEffect(settings, colors.first(), geometry, onDone = {})
                        MessagePopUp(onShow = onShowMessage, onRetract = onRetractMessage, onDone = onArrivalDone)
                    } else {
                        ArrivalEffect(settings, colors.first(), geometry, onDone = onArrivalDone)
                    }
                } else if (!arriving) {
                    LedLayer(settings, colors, geometry, onBlink, onFade = onLedFade, stopping = ending)
                }
            }
        }
    }
}

/** How long the black panel holds the system's message pop-up before retracting it. */
private const val MESSAGE_HOLD_MS = 6_000L

/** The heads-up's slide-out before brightness drops and the dot starts; tune 350-450 against One UI. */
private const val MESSAGE_RETRACT_MS = 400L

/**
 * [ArrivalMode.MESSAGE]: the system's pop-up is the message; this only raises it, times it and
 * retracts it, so the card is gone before the dot takes over.
 */
@Composable
private fun MessagePopUp(onShow: () -> Unit, onRetract: () -> Unit, onDone: () -> Unit) {
    val show by rememberUpdatedState(onShow)
    val retract by rememberUpdatedState(onRetract)
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        show()
        withContext(RealTimeMotion) {
            delay(MESSAGE_HOLD_MS)
            retract()
            delay(MESSAGE_RETRACT_MS)
        }
        done()
    }
}

/**
 * Old-school notification LED: a round of breaths, one per waiting app, newest first, then a
 * rest. Always the dot; the chosen style is only the new-message effect ([ArrivalEffect]).
 * [onFade] asks for the breath's frame rate while dark, before it lights and after it fades.
 * [stopping]: the breath in progress runs out on its own exhale and no new one starts.
 */
@Composable
private fun LedLayer(
    settings: GlowSettings,
    colors: List<Int>,
    geometry: ScreenGeometry,
    onBlink: () -> Unit,
    onFade: (fast: Boolean) -> Unit,
    stopping: Boolean,
) {
    val clock = remember { Animatable(0f) }
    val blink by rememberUpdatedState(onBlink)
    val fade by rememberUpdatedState(onFade)
    val stop by rememberUpdatedState(stopping)
    val palette by rememberUpdatedState(colors)
    var cycle by remember { mutableIntStateOf(0) }
    // Fixed per breath, so a palette change (or the default once all is read) waits for the dark.
    var shown by remember { mutableIntStateOf(colors.first()) }
    DisposableEffect(Unit) { onDispose { fade(false) } }
    // The breath is a signal, not decoration: keep its timing even when developer options
    // shorten or disable animations (animator duration scale).
    LaunchedEffect(Unit) {
        withContext(RealTimeMotion) {
            while (true) {
                if (stop) break
                fade(true)
                delay(LED_RATE_PREROLL_MS) // dark: the new rate is in place before the first lit frame
                if (stop) {
                    fade(false)
                    break
                }
                shown = palette[cycle % palette.size]
                clock.snapTo(0f)
                clock.animateTo(LED_BREATH_MS, tween(LED_BREATH_MS.toInt(), easing = LinearEasing))
                fade(false)
                val last = palette.size <= 1 || cycle % palette.size == palette.size - 1
                delay((if (last) LED_DARK_MS else LED_GAP_MS) - LED_RATE_PREROLL_MS)
                cycle++
                blink()
            }
        }
    }
    val onCamera = settings.ledOnCamera
    val shift = if (onCamera) Offset.Zero else PIXEL_SHIFTS[cycle % PIXEL_SHIFTS.size]
    LedDot(
        color = Color(shown),
        alpha = { ledBreathAt(clock.value) },
        dotX = settings.dotX,
        dotY = settings.dotY,
        radius = settings.dotSize.radius,
        onCamera = onCamera,
        geometry = geometry,
        ringGrowPx = if (onCamera) RING_SHIFTS[cycle % RING_SHIFTS.size] else 0f,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = shift.x
                translationY = shift.y
            },
    )
}

/** Ring LED: line thickness as a share of the dot radius, and its clearance from the lens. */
private const val LED_RING_STROKE_FACTOR = 0.6f
private val LED_RING_GAP = 1.5.dp

/**
 * A bright core, a hot white centre and a soft radial bloom. The brush is built once per
 * colour/size change in drawWithCache; each breath frame only changes the layer alpha.
 * The dashboard draws its real-size LED preview and the phone mock-up's LED with this too.
 *
 * [onCamera]: the same light as a ring hugging the punch-hole, whose pixels cannot light;
 * [radius] then sets the ring's thickness and [ringGap] its clearance from the lens (the mock-up
 * scales it with its lens).
 */
@Composable
internal fun LedDot(
    color: Color,
    alpha: () -> Float,
    dotX: Float,
    dotY: Float,
    radius: Dp,
    modifier: Modifier = Modifier,
    onCamera: Boolean = false,
    geometry: ScreenGeometry = ScreenGeometry.Unknown,
    ringGrowPx: Float = 0f,
    ringGap: Dp = LED_RING_GAP,
) {
    Spacer(
        modifier
            .graphicsLayer { this.alpha = alpha() }
            .drawWithCache {
                val core = radius.toPx()
                val hot = lerp(color, Color.White, 0.45f)
                if (onCamera) {
                    val metrics = GlowMetrics.FullScreen
                    val lens = geometry.cutout ?: CutoutSpot(
                        centerX = size.width / 2f,
                        centerY = metrics.fallbackCameraCenterY.toPx(),
                        radius = metrics.fallbackCameraRadius.toPx(),
                    )
                    val center = Offset(lens.centerX, lens.centerY)
                    val line = core * LED_RING_STROKE_FACTOR
                    val ring = lens.radius + ringGap.toPx() + line / 2f + ringGrowPx
                    val bloom = ring + line / 2f + core * (LED_HALO_FACTOR - 1f)
                    val halo = Brush.radialGradient(
                        0f to Color.Transparent,
                        lens.radius / bloom to Color.Transparent,
                        ring / bloom to color.copy(alpha = 0.65f),
                        (ring + (bloom - ring) * 0.35f) / bloom to color.copy(alpha = 0.22f),
                        1f to Color.Transparent,
                        center = center,
                        radius = bloom,
                    )
                    val coreStroke = Stroke(line)
                    val hotStroke = Stroke(line * 0.4f)
                    return@drawWithCache onDrawBehind {
                        drawCircle(halo, radius = bloom, center = center)
                        drawCircle(color, radius = ring, center = center, style = coreStroke)
                        drawCircle(hot, radius = ring, center = center, style = hotStroke)
                    }
                }
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
                onDrawBehind {
                    drawCircle(halo, radius = bloom, center = center)
                    drawCircle(color, radius = core, center = center)
                    drawCircle(hot, radius = core * 0.5f, center = center)
                }
            },
    )
}
