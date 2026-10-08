package com.example.ambientglow

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle

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
 * - A new message ([ArrivalMode.LED_ONLY]): the same, without the effect: straight into the
 *   dot, and nothing at all while the user is looking at the lock screen.
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

    // In a pocket or face down, the LED has no one to show to: while the proximity sensor is
    // covered the panel goes off, as in a call, and the LED is back the moment it is uncovered.
    // The phone stays awake (no SCREEN_OFF, no pause), so the lock flow sees nothing. Held with
    // [keepPanelOn]; null on a phone without the sensor.
    private val pocketDark by lazy(LazyThreadSafetyMode.NONE) {
        if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "AmbientGlow:pocket").apply { setReferenceCounted(false) }
        } else {
            null
        }
    }

    /**
     * The panel is off while the phone is awake: [pocketDark] has turned it off. Android keeps
     * sending frames to a dark panel, so the LED round pauses rather than drawing them.
     */
    private val covered = mutableStateOf(false)

    private val timers = Handler(Looper.getMainLooper())
    private val wakeNow = Runnable {
        PanelWaker.wake(this)
        timers.postDelayed(verifyWake, WAKE_VERIFY_MS)
    }

    // Fallback if this build refuses the wake lock: the full-screen intent lights it (armed
    // with a one-shot turnScreenOn in case SystemUI doesn't wake for it).
    private val verifyWake = Runnable {
        if (power.isInteractive || audio.inCall || face.value == Face.AWAY) return@Runnable
        GlowLog.d { "act wake lock ignored, falling back to full-screen intent" }
        armTurnScreenOnOnce()
        val mode = if (face.value == Face.LED) WakeMode.LED else WakeMode.WAKE
        GlowLauncher.launchFromBackground(this, mode, GlowPending.newestColor)
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
        GlowLog.d { "act takeover interactive=${power.isInteractive} locked=${keyguard.isKeyguardLocked}" }
        if (leaveIfUnlocked()) return@Runnable
        if (face.value == Face.LOCK_SCREEN && autoTakeover && !ledResting && power.isInteractive && keyguard.isKeyguardLocked) {
            autoTakeover = false
            showLed()
        } else if (power.isInteractive) {
            GlowShield.hide() // bailed: give the lit lock screen back from under the dim
        }
    }

    /** Dims the lit lock screen to black over [TAKEOVER_DIM_MS], ending as [takeOver] covers it. */
    private val dimForTakeover = Runnable {
        if (face.value == Face.LOCK_SCREEN && autoTakeover && !ledResting && power.isInteractive && keyguard.isKeyguardLocked) {
            GlowShield.dimIn(TAKEOVER_DIM_MS)
        }
    }

    private val relightLimit = RelightLimiter(RELIGHT_BURST, RELIGHT_REFILL_MS)
    private val relightLater = Runnable { relightLed(RELIGHT_DELAY_MS) }

    /**
     * The lock screen was handed back to run out its own short timeout under a black cover
     * ([sleepUnderCover]): nobody is looking at it, whatever face says.
     */
    private var coverSleep = false

    /**
     * Put away with the LED up: face down (on any surface, glass included) or covered for a while.
     * The panel and the CPU sleep, and the LED is back once the phone is taken out ([stowWatch]).
     */
    private var stowed = false
    private val stowWatch by lazy(LazyThreadSafetyMode.NONE) {
        StowWatch(this, covered = { covered.value }, onStow = ::stow, onTakenOut = ::takenOut)
    }

    /** The screen went off during a call: once it is over, the LED as the screen-off would have had it. */
    private val afterCall by lazy(LazyThreadSafetyMode.NONE) {
        AfterCall(this) {
            if (!power.isInteractive && face.value != Face.AWAY) {
                GlowLog.d { "act relight after the call face=${face.value}" }
                when (face.value) {
                    Face.LOCK_SCREEN -> relightLed(RELIGHT_DELAY_MS)
                    // The LED was in front when the call came: straight back into it.
                    else -> if (!ledResting && !GlowPending.isEmpty && takeRelightToken()) {
                        showLed()
                        wakeAfter(RELIGHT_DELAY_MS)
                    }
                }
            }
        }
    }

    /** No LED for now: Do Not Disturb ([GlowSession.resting]) or [stowed]. */
    private val ledResting: Boolean get() = GlowSession.resting || stowed

    /** Read elsewhere while the LED was lit: once the dot's last breath is out, let the panel sleep. */
    private val sleepAfterBreath = Runnable {
        if (ending.value && face.value == Face.LED && power.isInteractive) sleepUnderCover()
    }
    private val settings = mutableStateOf(GlowSettings())
    private val geometry = mutableStateOf(ScreenGeometry.Unknown)

    /** [holdPortrait]'s last request, so a relight costs no extra window call. */
    private var portraitHeld = false

    /** The LED asked for its breath rate ([ledFrameRate]); a finger is on it ([onLedTouch]). */
    private var ledFast = false
    private var touching = false

    /** The LED's display modes; looked up again on every [showLed]. */
    private val panelModes = PanelModes(::currentDisplay, LED_FADE_HZ)

    private val screenSignals = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GlowLog.d { "act ${intent.action?.substringAfterLast('.')} face=${face.value}" }
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
            // Also true for a moment while a wake turns the panel on; it turns on with an event too.
            val dark = interactiveSeen && currentDisplay()?.state == Display.STATE_OFF
            if (dark != covered.value) {
                GlowLog.d { "act panel ${if (dark) "covered" else "uncovered"} face=${face.value}" }
                covered.value = dark
                // Put away covered, the LED still in front: the system has lit the panel because the
                // sensor cleared. That is the phone taken out, whether or not our own proximity
                // listener heard it (a phone may only have one that can't wake the CPU). Still face
                // down, the watch puts it away again a moment later.
                if (!dark && interactiveSeen && stowed && face.value == Face.LED) takenOut()
            }
            if (interactiveSeen) {
                // Awake again: the sleep we were handling was cancelled; the next one is the user's.
                ledArmedForSleep = false
                revealingAfterPower = false
            }
            if (wasInteractive && ledTurnedOff()) {
                GlowLog.d { "act display changed: going to sleep with the LED in front" }
                revealAfterPower()
            }
        }
    }

    /** [PowerManager.isInteractive] at the last display change. */
    private var interactiveSeen = false

    /** [sleepSignal] is registered: only while on top of the lock screen, never while [Face.AWAY]. */
    private var listeningForSleep = false

    // USER_PRESENT is sent by SystemUI, not the system uid, so a RECEIVER_NOT_EXPORTED receiver
    // never gets it (seen on One UI 8.5: the LED stayed over the unlocked phone). It is a
    // protected broadcast, so exporting this receiver lets no other app trigger it.
    private val unlockSignal = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GlowLog.d { "act USER_PRESENT face=${face.value}" }
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
        GlowLog.d { "act onCreate mode=${intent?.getStringExtra(GlowLauncher.EXTRA_MODE)} dark=${intent?.getBooleanExtra(GlowLauncher.EXTRA_DARK, false)} interactive=${power.isInteractive}" }
        // Never cover the lock screen by accident before start() decides.
        setLockScreenCover(cover = false)
        // Never let a relaunch show a stale snapshot as its starting window.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        observeScreenGeometry()
        registerScreenEvents(screenSignals)
        ContextCompat.registerReceiver(
            this,
            unlockSignal,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED,
        )
        listenForSleep(true)
        GlowSession.attach(this)
        onBackPressedDispatcher.addCallback(this) { onUserDismiss() }

        setContent {
            GlowScreen(
                face = face.value,
                ending = ending.value,
                settling = settling.value,
                covered = covered.value,
                arriving = arriving.value,
                arrivalSeq = arrivalSeq.intValue,
                settings = settings.value.forScreen(geometry.value),
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
        GlowLog.d {
            "act onNewIntent mode=${intent.getStringExtra(GlowLauncher.EXTRA_MODE)} face=${face.value} " +
                "dark=${intent.getBooleanExtra(GlowLauncher.EXTRA_DARK, false)} interactive=${power.isInteractive}"
        }
        setIntent(intent)
        start(intent)
    }

    override fun onResume() {
        super.onResume()
        GlowLog.d { "act onResume face=${face.value} interactive=${power.isInteractive} locked=${keyguard.isKeyguardLocked}" }
        // Resumed with no lock screen: the phone was unlocked underneath us.
        if (face.value != Face.AWAY && !keyguard.isKeyguardLocked) {
            goAway()
            return
        }
        if (face.value == Face.LED) {
            window.hideBarsOnBlack()
            // Ending: the screen is meant to time out; holding it on here would keep it lit for good.
            if (!ending.value) acquireKeepOn()
        }
    }

    override fun onPause() {
        GlowLog.d { "act onPause face=${face.value} interactive=${power.isInteractive}" }
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
            window.hideBarsOnBlack()
            // Focus is the hand-over: the bars are ours from here on, but they arrive still
            // showing and the framework slides them out. Stay dark until that has finished.
            if (settling.value) {
                timers.removeCallbacks(settleNow)
                timers.postDelayed(settleNow, barsHideMs())
            }
        }
    }

    override fun onDestroy() {
        GlowLog.d { "act onDestroy" }
        afterCall.cancel()
        unstow()
        timers.removeCallbacksAndMessages(null)
        GlowSession.detach(this)
        GlowShield.hide()
        GlowShield.stopArrival()
        GlowLauncher.dismissMessage(this)
        unregisterReceiver(screenSignals)
        unregisterReceiver(unlockSignal)
        listenForSleep(false)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // GlowSession.Host
    // ---------------------------------------------------------------------------------------

    override fun onNewMessage() {
        GlowLog.d { "act onNewMessage face=${face.value} interactive=${power.isInteractive}" }
        if (face.value == Face.AWAY) return
        ending.value = false
        if (stowed) return // nobody to show it to: the LED has it once the phone is taken out
        timers.removeCallbacks(sleepAfterBreath)
        settings.value = GlowPrefs.load(this) // the arrival choice may have changed since launch
        val arrival = arrivalFor(settings.value.arrival, GlowSession.resting)
        when (arrival) {
            ArrivalMode.LOCK_SCREEN -> {
                // Show the system lock screen with all its notifications, lit, with the effect
                // over it. If the user is already looking at it, leave the hand-back off.
                val userOnLockScreen = face.value == Face.LOCK_SCREEN && power.isInteractive && !autoTakeover && !coverSleep
                if (!userOnLockScreen) showLockScreen(auto = true)
                announce()
                if (!power.isInteractive) {
                    DarkHold.acquire(this)
                    wakeAfter(0)
                }
            }
            ArrivalMode.BLACK, ArrivalMode.MESSAGE, ArrivalMode.LED_ONLY -> {
                val effect = arrival.playsEffect
                // The lock screen is only lit and in front when the user woke it themselves: they
                // are looking at it (maybe typing a PIN), so don't cover it; play the effect over it
                // instead (if there is one), and the dot takes over when it sleeps.
                if (face.value == Face.LOCK_SCREEN && power.isInteractive && !coverSleep) {
                    if (effect) announce()
                    return
                }
                val dark = !power.isInteractive
                if (dark) DarkHold.acquire(this)
                showLed(arrival = effect)
                // Arranged while dark: give the cover a moment to commit, then light straight into it.
                if (dark) wakeAfter(RELIGHT_DELAY_MS)
            }
        }
    }

    override fun onPendingChanged() {
        if (!GlowPending.isEmpty) return
        if (face.value == Face.LED && power.isInteractive) {
            // Read elsewhere while the LED is lit: don't pop the lock screen up. Let the dot finish
            // its breath, then sleep under cover rather than at the screen timeout (10 min on some
            // phones), and finish when the screen goes off. An arrival in progress is cut short:
            // brightness and rate drop, the dot lights no more.
            GlowLog.d { "act draining" }
            ending.value = true
            onArrivalDone()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            timers.removeCallbacks(sleepAfterBreath)
            if (stowed) {
                // Put away: nobody sees the last breath. Sleep now, and let go of the proximity lock
                // without waiting for the sensor to clear: a covered sensor keeps the phone awake
                // (as in a call), so it would stay up until taken out and light its lock screen then.
                // The CPU may be let sleep mid-way while put away; hold it for the hand-over.
                DarkHold.acquire(this, DRAIN_HOLD_MS)
                pocketDark?.let { if (it.isHeld) it.release() }
                releaseKeepOn()
                sleepUnderCover()
            } else {
                releaseKeepOn()
                timers.postDelayed(sleepAfterBreath, LED_BREATH_MS.toLong())
            }
        } else {
            finishAndRemoveTask()
        }
    }

    override fun ensureLed() {
        val missing = face.value != Face.AWAY && ledMissing(
            screenOn = power.isInteractive,
            waiting = !GlowPending.isEmpty,
            resting = GlowSession.resting,
            inCall = audio.inCall,
            putAway = stowed,
            ending = ending.value,
        )
        if (!missing) {
            if (audio.inCall && !power.isInteractive) afterCall.arm()
            return
        }
        GlowLog.d { "act LED missing face=${face.value}: relighting" }
        DarkHold.acquire(this)
        when (face.value) {
            Face.LOCK_SCREEN -> relightLed(RELIGHT_DELAY_MS)
            else -> if (takeRelightToken()) {
                showLed()
                wakeAfter(RELIGHT_DELAY_MS)
            }
        }
    }

    override fun onRestChanged() {
        GlowLog.d { "act rest=${GlowSession.resting} face=${face.value} interactive=${power.isInteractive}" }
        if (GlowSession.resting) {
            timers.removeCallbacks(takeOver)
            timers.removeCallbacks(dimForTakeover)
            timers.removeCallbacks(relightLater)
            when {
                face.value != Face.LED -> Unit // the lock screen times out as it would without us
                // A black arrival plays out first ([onArrivalDone]).
                arriving.value -> Unit
                power.isInteractive -> sleepUnderCover()
                // Dark, a relight on its way: call it off and leave the lock screen for the next wake.
                else -> {
                    timers.removeCallbacks(wakeNow)
                    timers.removeCallbacks(verifyWake)
                    showLockScreen()
                }
            }
        } else if (face.value == Face.LOCK_SCREEN && !power.isInteractive && !GlowPending.isEmpty) {
            // Do Not Disturb is over and messages are still unread: the LED lights again. Looking
            // at the lock screen, the dot takes over when it sleeps, as ever.
            DarkHold.acquire(this)
            relightLed(RELIGHT_DELAY_MS)
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
        val requested = runCatching { WakeMode.valueOf(intent?.getStringExtra(GlowLauncher.EXTRA_MODE).orEmpty()) }
            .getOrDefault(WakeMode.LED)
        // Resting (Do Not Disturb): only ever the lock screen.
        val mode = if (GlowSession.resting) WakeMode.WAKE else requested
        val arrival = arrivalFor(settings.value.arrival, GlowSession.resting)
        when (mode) {
            // With a black arrival the user is already looking at the lock screen: the effect
            // over it, no automatic cover.
            WakeMode.WAKE -> {
                showLockScreen(auto = arrival == ArrivalMode.LOCK_SCREEN)
                if (arrival.playsEffect) announce()
            }
            WakeMode.LED -> showLed()
            WakeMode.ARRIVAL -> showLed(arrival = true)
        }
        // The full-screen intent normally lights the panel; make sure, without relaunching.
        // Arrived while the screen is still dark: started in the dark ([GlowLauncher.startInTheDark]),
        // or by a full-screen intent that stock Android launches before anything wakes the panel
        // (One UI wakes it first). Resumed and paused at once while asleep, that pause is ours,
        // not a power press; and nobody else is lighting the panel, so it does, once its cover is
        // committed. Through the full-screen intent the system may still light it first: then
        // the wake finds it lit and does nothing.
        if (!power.isInteractive) {
            if (face.value == Face.LED) ledArmedForSleep = true
            wakeAfter(RELIGHT_DELAY_MS)
        }
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
        coverSleep = false
        if (ledResting) {
            GlowShield.hide()
            if (GlowSession.resting) {
                // Do Not Disturb: dark until it is over or the user wakes the phone, and then to the
                // lock screen, never the LED.
                if (face.value == Face.LED) showLockScreen()
            } else if (face.value == Face.LOCK_SCREEN && !GlowPending.isEmpty) {
                // Put away, asleep at last: the LED back in front in the dark, so taking the phone
                // out wakes straight into it, never the lock screen.
                showLed()
            }
            return
        }
        if (audio.inCall) {
            afterCall.arm() // proximity during a call: stay dark, and light the LED once it is over
            return
        }
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
        // Woken by the user while put away: they have it in hand; the lock screen as ever.
        if (stowed) unstow()
        revealingAfterPower = false
        ledArmedForSleep = false
        timers.removeCallbacks(relightLater)
        disarmTurnScreenOn()
        timers.removeCallbacks(verifyWake)
        when (face.value) {
            Face.LED -> {
                window.hideBarsOnBlack()
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

    /**
     * Display changes matter only while we are on top of the lock screen. Away, behind the user's
     * apps, every refresh-rate switch and brightness step would wake us for nothing, and an
     * adaptive panel switches all the time while the phone is in use.
     */
    private fun listenForSleep(listen: Boolean) {
        if (listen == listeningForSleep) return
        listeningForSleep = listen
        if (!listen) {
            displays.unregisterDisplayListener(sleepSignal)
            covered.value = false // nothing would clear it until the listener is back
            return
        }
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
        GlowLog.d { "act unlocked underneath (face=${face.value})" }
        goAway()
        return true
    }

    private fun scheduleTakeover() {
        timers.removeCallbacks(takeOver)
        timers.removeCallbacks(dimForTakeover)
        if (autoTakeover && power.isInteractive && !ledResting) {
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
        GlowShield.playArrival(GlowPending.newestColor)
    }

    private fun watchForSleep() {
        timers.removeCallbacks(sleepWatch)
        // Not during a call: onGoingToSleep would bail anyway, and a screen the call keeps on
        // would be polled for the whole call. Afterwards SCREEN_OFF still brings the LED back.
        if (face.value == Face.LOCK_SCREEN && power.isInteractive && !audio.inCall) {
            timers.postDelayed(sleepWatch, SLEEP_WATCH_MS)
        }
    }

    private fun onGoingToSleep() {
        GlowLog.d { "act going to sleep on the lock screen" }
        if (audio.inCall || GlowPending.isEmpty || ledResting || !takeRelightToken()) return
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
        if (power.isInteractive || face.value != Face.LOCK_SCREEN || audio.inCall || GlowPending.isEmpty || ledResting) return
        if (!takeRelightToken()) return
        showLed()
        wakeAfter(delayMs)
    }

    /**
     * Takes a relight token. Without one, schedules [relightLater] for when the next one is due
     * and returns false.
     */
    private fun takeRelightToken(): Boolean {
        val wait = relightLimit.take(SystemClock.elapsedRealtime())
        if (wait == 0L) return true
        GlowLog.d { "act relight rate-limited, in $wait ms" }
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
            preferredDisplayModeId = if (down) 0 else if (ledFast) panelModes.fade else panelModes.idle
            preferredRefreshRate = if (down) panelModes.highestRate() else 0f
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
        val idle = panelModes.idle
        val fade = panelModes.fade
        if (idle == fade) return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = if (fast) fade else idle
            preferredRefreshRate = 0f
        }
    }

    /** How long the framework's bar slide-out takes here: it follows the animator scale, so wait no longer. */
    private fun barsHideMs(): Long {
        val scale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        return (BARS_HIDE_SLIDE_MS * scale).toLong() + BARS_HIDE_MARGIN_MS
    }

    /** Tap or back on the LED: show the lock screen, with no automatic hand-back. */
    private fun onUserDismiss() {
        GlowLog.d { "act user dismiss face=${face.value}" }
        if (leaveIfUnlocked()) return
        if (ending.value) {
            finishAndRemoveTask()
        } else if (face.value == Face.LED) {
            showLockScreen()
        }
    }

    /**
     * The phone stopped being interactive with the LED in front: the user pressed power. Not
     * when we put the LED up ourselves during the sleep fade, not for the proximity sensor
     * during a call, and not while [ending]: with nothing left to show, the screen going off
     * (timeout or power) ends the session in [onScreenOff] instead of opening the lock screen.
     * Nor while [stowed]: covered, the LED stays in front and the phone sleeps at its own timeout.
     */
    private fun ledTurnedOff(): Boolean =
        face.value == Face.LED && !ending.value && !power.isInteractive && !audio.inCall && !ledArmedForSleep && !stowed

    /** Set from the power press until the panel is back on, so that sleep's SCREEN_OFF is ignored. */
    private var revealingAfterPower = false

    private fun revealAfterPower() {
        GlowLog.d { "act revealAfterPower" }
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
        GlowLog.d { "act showLed arrival=$arrival pending=${GlowPending.entries.size}" }
        coverSleep = false
        timers.removeCallbacks(sleepWatch)
        timers.removeCallbacks(takeOver)
        timers.removeCallbacks(dimForTakeover)
        // The double tap never reports a release; a resolution change needs new mode ids.
        touching = false
        ledFast = false
        panelModes.reset()
        arrivalDue = false
        GlowShield.stopArrival()
        if (GlowPending.isEmpty) {
            finishAndRemoveTask()
            return
        }
        listenForSleep(true)
        if (arrival) {
            arriving.value = true
            arrivalSeq.intValue++
        }
        // Paint black first, cover the lock screen last, so the first frame is already black.
        setSeeThrough(false)
        holdPortrait(true)
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
            brightness = if (focused) LED_WINDOW_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF,
            lowRefresh = true,
        )
        window.hideBarsOnBlack()
        face.value = Face.LED
        setLockScreenCover(cover = true)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) acquireKeepOn()
    }

    /**
     * Let the system lock screen show over us while we stay the top task. The LED comes back
     * when the system's lock-screen timeout turns the screen off.
     */
    private fun showLockScreen(auto: Boolean = false, underCover: Boolean = false) {
        GlowLog.d { "act showLockScreen auto=$auto underCover=$underCover interactive=${power.isInteractive}" }
        ledArmedForSleep = false
        autoTakeover = auto
        coverSleep = underCover
        listenForSleep(true)
        face.value = Face.LOCK_SCREEN
        becomeInvisible(keepCover = underCover)
        watchForSleep()
        scheduleTakeover()
    }

    /**
     * [ArrivalMode.MESSAGE], once the black panel is up and focused: let the system pop up only
     * the new message. If it will not by itself (its channel does not peek), re-post it.
     */
    private fun showMessage() {
        val message = GlowPending.message ?: return
        GlowLog.d { "act message pop-up systemPopsUp=${message.systemPopsUp}" }
        if (!message.systemPopsUp) GlowLauncher.showMessage(this, message.sbn)
    }

    /** [ArrivalMode.MESSAGE]: slide the pop-up away while brightness and rate still hold. */
    private fun retractMessage() {
        if (arriving.value) GlowLauncher.dismissMessage(this)
    }

    /**
     * Let the panel sleep soon, not at the screen timeout (10 min on some phones): hand the lock
     * screen back, whose own short timeout then runs, and hold the display black meanwhile with
     * [GlowShield] (without it the lock screen shows until then). [onScreenOff] takes it from there.
     */
    private fun sleepUnderCover() {
        GlowShield.blackout()
        showLockScreen(underCover = true)
    }

    /** Put away while the LED is lit: let the panel and the CPU sleep until it is taken out. */
    private fun stow() {
        // An arrival plays out first; the watch asks again with its next sample.
        if (stowed || face.value != Face.LED || arriving.value || ending.value || !power.isInteractive) return
        GlowLog.d { "act put away covered=${covered.value}" }
        stowed = true
        stowWatch.watchStowed()
        if (covered.value) {
            // Covered (pocket, sofa, most desks): the proximity lock keeps the panel off. Let go of
            // only what keeps the CPU awake and leave the LED in front, so it is simply there the
            // moment the phone is uncovered: no wake, no lock screen. The phone sleeps at its own
            // timeout ([ledTurnedOff] knows).
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (keepPanelOn.isHeld) keepPanelOn.release()
        } else {
            // Face down where the sensor sees nothing (a glass desk): only sleep turns the panel off.
            sleepUnderCover()
        }
    }

    /** Taken out again: the LED is back, straight away if the panel never got to sleep. */
    private fun takenOut() {
        if (!stowed) return
        GlowLog.d { "act taken out face=${face.value} interactive=${power.isInteractive}" }
        unstow()
        if (GlowPending.isEmpty || GlowSession.resting) return
        when {
            // Uncovered with the LED still in front: hold the panel on again.
            face.value == Face.LED && power.isInteractive -> showLed()
            // Asleep with the LED in front: wake straight into it.
            face.value == Face.LED -> {
                DarkHold.acquire(this)
                if (takeRelightToken()) {
                    showLed()
                    wakeAfter(RELIGHT_DELAY_MS)
                }
            }
            // Still handing over to the lock screen's timeout, under the black cover: cover it again.
            power.isInteractive -> if (keyguard.isKeyguardLocked) showLed()
            else -> {
                DarkHold.acquire(this)
                relightLed(RELIGHT_DELAY_MS)
            }
        }
    }

    private fun unstow() {
        stowed = false
        stowWatch.stop()
        // Woken by hand while put away: the LED, if in front, watches again.
        if (keepPanelOn.isHeld) stowWatch.watchLit()
    }

    /** The effect on the black panel has handed over to the dot: drop to the dot's frame rate. */
    private fun onArrivalDone() {
        if (!arriving.value) return
        GlowLog.d { "act arrival done" }
        arriving.value = false
        GlowLauncher.dismissMessage(this)
        if (face.value == Face.LED && !settling.value) applyWindow(brightness = LED_WINDOW_BRIGHTNESS, lowRefresh = true)
        // Do Not Disturb came on while it played: no LED after it.
        if (GlowSession.resting && face.value == Face.LED && power.isInteractive && !ending.value) sleepUnderCover()
    }

    /** The phone was unlocked: get out of the way of the user's apps. */
    private fun goAway() {
        GlowLog.d { "act goAway face=${face.value}" }
        timers.removeCallbacksAndMessages(null)
        if (ending.value || GlowPending.isEmpty) {
            finishAndRemoveTask()
            return
        }
        face.value = Face.AWAY
        coverSleep = false
        unstow()
        listenForSleep(false)
        ledArmedForSleep = false
        // Nothing resets it while away; the next power press must start clean.
        revealingAfterPower = false
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
            GlowLog.d { "act settled: LED at full brightness" }
            applyWindow(brightness = LED_WINDOW_BRIGHTNESS, lowRefresh = true)
        }
    }

    // Uncover the lock screen first, then go transparent and untouchable. [keepCover]: a black
    // cover is holding the display while the lock screen times out ([sleepUnderCover]).
    private fun becomeInvisible(keepCover: Boolean = false) {
        timers.removeCallbacks(settleNow)
        settling.value = false
        touching = false
        ledFast = false
        arriving.value = false // the dot comes back alone
        GlowLauncher.dismissMessage(this) // the lock screen lists the original already
        if (!keepCover) GlowShield.hide()
        setLockScreenCover(cover = false)
        window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
        holdPortrait(false)
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

    /**
     * The LED sits at portrait screen fractions, and the lens fit is in portrait dp, so the LED
     * face holds the panel in portrait: turned sideways, the dot would move to another spot on the
     * glass. Only where the face is opaque (API 30+, [setSeeThrough]): Android 8.0 refuses a fixed
     * orientation from a translucent window. Released whenever it goes see-through.
     */
    private fun holdPortrait(hold: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || portraitHeld == hold) return
        portraitHeld = hold
        requestedOrientation = if (hold) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    @SuppressLint("WakelockTimeout") // released in onPause / when the LED leaves the front
    private fun acquireKeepOn() {
        if (!keepPanelOn.isHeld) keepPanelOn.acquire()
        pocketDark?.let { if (!it.isHeld) it.acquire() }
        if (!stowed) stowWatch.watchLit()
    }

    private fun releaseKeepOn() {
        if (keepPanelOn.isHeld) keepPanelOn.release()
        // Still covered (read elsewhere while in a pocket): the panel stays off until it isn't,
        // rather than lighting up in the pocket.
        pocketDark?.let { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
        stowWatch.stopLit()
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
                ledFast -> panelModes.fade
                else -> panelModes.idle
            }
            preferredRefreshRate = if (boosted) panelModes.highestRate() else 0f
        }
    }

    private fun currentDisplay(): Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
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
        const val TAKEOVER_DIM_MS = 300L

        /**
         * The rate the LED asks for while it breathes. The S23's panel offers 10/24/30/48/60/96/120
         * Hz: 30 is three times smoother than the 10 Hz idle at half the cost of 60.
         */
        const val LED_FADE_HZ = 30f

        /** If focus never arrives after a wake into the LED, show it anyway. */
        const val SETTLE_FALLBACK_MS = 600L

        /** The framework's slide-out of bars a window gains while they show (InsetsController, 340 ms at 1x). */
        const val BARS_HIDE_SLIDE_MS = 340L
        const val BARS_HIDE_MARGIN_MS = 20L

        /** Relight rate limit: a burst for quick presses by hand (~1 s per LED ↔ lock screen round trip), then one per refill. */
        const val RELIGHT_BURST = 8
        const val RELIGHT_REFILL_MS = 2_000L

        /** Lock screen dark → LED: the switch is committed ~10 ms after screen-off; small margin. */
        const val RELIGHT_DELAY_MS = 150L

        /** The CPU held while a drain, put away, hands over to the lock screen's timeout and sleeps. */
        const val DRAIN_HOLD_MS = 8_000L

        const val WAKE_VERIFY_MS = 1_200L
    }
}

/** Window brightness while the dot is lit: fixed, so [LedBrightness] dims only the dot's pixels. */
private const val LED_WINDOW_BRIGHTNESS = 1f
