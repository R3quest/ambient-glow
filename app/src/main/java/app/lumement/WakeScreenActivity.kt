package app.lumement

import android.annotation.SuppressLint
import android.app.AlarmManager
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
import android.service.notification.StatusBarNotification
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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
 * - With [GlowShield], the lit LED breathes in a window of its own over everything, and the lock
 *   screen waits in front under it, ready ([LockFlow.updateLedWindow]). The window holds the focus, so
 *   One UI shows no fingerprint icon over the black. A power press, a sleep request on the lit
 *   phone, is cancelled at once and the window lifted: the lock screen is whole in one frame,
 *   and only One UI's wait for more presses is left. A finger down hands the lock screen the
 *   focus, so the fingerprint unlocks.
 * - Where the always-on display shows, the LED breathes on it instead ([LockFlow.onDisplayChanged]): the phone
 *   dozes, never woken for a breath, and power wakes it straight into the lock screen.
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
 *
 * The flow itself is [LockFlow], plain Kotlin; this activity is its window and its phone
 * ([LockPort]): the system services, the wake locks, the timers, the broadcasts.
 */
class WakeScreenActivity : ComponentActivity(), LockPort {

    private val power by lazy(LazyThreadSafetyMode.NONE) { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy(LazyThreadSafetyMode.NONE) { getSystemService(KeyguardManager::class.java) }
    private val audio by lazy(LazyThreadSafetyMode.NONE) { getSystemService(AudioManager::class.java) }
    private val alarms by lazy(LazyThreadSafetyMode.NONE) { getSystemService(AlarmManager::class.java) }
    private val displays by lazy(LazyThreadSafetyMode.NONE) { getSystemService(DisplayManager::class.java) }

    private val flow = LockFlow(this, RealShield)

    // Some builds stop honouring FLAG_KEEP_SCREEN_ON once this window has taken over a showing
    // lock screen, so an explicit screen lock backs it up while the LED is resumed. The power
    // button still turns the screen off; released the moment the LED is not in front.
    @Suppress("DEPRECATION")
    private val keepPanelOn by lazy(LazyThreadSafetyMode.NONE) {
        power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Lumement:led").apply { setReferenceCounted(false) }
    }

    // In a pocket or face down, the LED has no one to show to: while the proximity sensor is
    // covered the panel goes off, as in a call, and the LED is back the moment it is uncovered.
    // The phone stays awake (no SCREEN_OFF, no pause), so the lock flow sees nothing. Held with
    // [keepPanelOn]; null on a phone without the sensor.
    private val pocketDark by lazy(LazyThreadSafetyMode.NONE) {
        if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Lumement:pocket").apply { setReferenceCounted(false) }
        } else {
            null
        }
    }

    private val timers = Handler(Looper.getMainLooper())
    private val due = LockTimer.entries.associateWith { Runnable { flow.onTimer(it) } }

    private val stowWatch by lazy(LazyThreadSafetyMode.NONE) {
        StowWatch(this, covered = { flow.covered.value }, onStow = flow::stow, onTakenOut = flow::takenOut, onPickedUp = flow::pickedUp)
    }

    /** The screen went off during a call: once it is over, the LED as the screen-off would have had it. */
    private val afterCall by lazy(LazyThreadSafetyMode.NONE) { AfterCall(this, flow::callOver) }

    private val geometry = mutableStateOf(ScreenGeometry.Unknown)

    /** [holdPortrait]'s last request, so a relight costs no extra window call. */
    private var portraitHeld = false

    /** The LED asked for its breath rate ([ledFrameRate]); a finger is on it ([onLedTouch]). */
    private var ledFast = false
    private var touching = false

    /** The LED's display modes; looked up again on every new face ([resetPanelRate]). */
    private val panelModes = PanelModes(::currentDisplay, LED_FADE_HZ)

    private val screenSignals = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GlowLog.d { "act ${intent.action?.substringAfterLast('.')} face=${flow.face.value} display=${currentDisplay()?.state}" }
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> flow.onScreenOff()
                Intent.ACTION_SCREEN_ON -> flow.onScreenOn()
            }
        }
    }

    private val displaySignal = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) flow.onDisplayChanged()
        }
    }

    // USER_PRESENT is sent by SystemUI, not the system uid, so a RECEIVER_NOT_EXPORTED receiver
    // never gets it (seen on One UI 8.5: the LED stayed over the unlocked phone). It is a
    // protected broadcast, so exporting this receiver lets no other app trigger it.
    private val unlockSignal = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = flow.onUnlocked()
    }

    private val alarmSignal = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = flow.onNextAlarmChanged()
    }

    private val batterySaverSignal = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = flow.onBatterySaverChanged()
    }

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
        // Never let a relaunch show a stale snapshot as its starting window.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        observeScreenGeometry()
        registerScreenEvents(screenSignals)
        ContextCompat.registerReceiver(this, unlockSignal, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, alarmSignal, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, batterySaverSignal, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onBackPressedDispatcher.addCallback(this) { flow.onUserDismiss() }

        setContent {
            GlowScreen(
                face = flow.face.value,
                ending = flow.ending.value,
                // The LED's window has the LED, or is going up over the face: the glow screen keeps its own dot out.
                settling = flow.settling.value || flow.ledInWindow.value || flow.litDue,
                covered = flow.covered.value,
                arriving = flow.arriving.value,
                arrivalSeq = flow.arrivalSeq.intValue,
                settings = flow.settings.value.forScreen(geometry.value),
                geometry = geometry.value.fitted(flow.settings.value, resources.displayMetrics.density),
                onTap = flow::onUserDismiss,
                onHold = flow::onFingerHeld,
                onTouch = ::onLedTouch,
                onArrivalDone = flow::onArrivalDone,
                onShowMessage = flow::showMessage,
                onRetractMessage = flow::retractMessage,
                onBlink = { flow.leaveIfUnlocked() },
                onLedFade = ::ledFrameRate,
            )
        }
        flow.create(modeOf(intent))
        lifecycleScope.launch {
            snapshotFlow { listOf(flow.face.value, flow.settling.value, flow.arriving.value, flow.ending.value, flow.covered.value) }
                .collect { flow.updateLedWindow() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        GlowLog.d {
            "act onNewIntent mode=${intent.getStringExtra(GlowLauncher.EXTRA_MODE)} face=${flow.face.value} " +
                "dark=${intent.getBooleanExtra(GlowLauncher.EXTRA_DARK, false)} interactive=${power.isInteractive}"
        }
        setIntent(intent)
        flow.start(modeOf(intent))
    }

    override fun onResume() {
        super.onResume()
        flow.onResume()
    }

    override fun onPause() {
        flow.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        flow.onFocusChanged(hasFocus)
    }

    @SuppressLint("Wakelock") // they outlive onPause on purpose: the LED holds the panel behind the lock screen
    override fun onDestroy() {
        flow.destroy()
        // Its locks with no timeout go with it, whatever the flow left held: destroyed without a
        // finish (a relaunch for a theme or navigation change), it kept the panel lit till the GC.
        if (keepPanelOn.isHeld) keepPanelOn.release()
        pocketDark?.let { if (it.isHeld) it.release() }
        unregisterReceiver(screenSignals)
        unregisterReceiver(unlockSignal)
        unregisterReceiver(alarmSignal)
        unregisterReceiver(batterySaverSignal)
        super.onDestroy()
    }

    private fun modeOf(intent: Intent?): WakeMode =
        runCatching { WakeMode.valueOf(intent?.getStringExtra(GlowLauncher.EXTRA_MODE).orEmpty()) }.getOrDefault(WakeMode.LED)

    /**
     * A finger came down on the LED, or left without a tap. The LED holds the panel at its idle
     * rate (10 Hz on One UI), which also overrides the system's touch boost, so the taps would be
     * read and the lock screen's reveal start drawing at that rate. Leave it at the first touch:
     * the panel is at full rate by the time the double tap completes. On release, back to the
     * LED's own rate, the breath's if one is lit.
     */
    private fun onLedTouch(down: Boolean) {
        touching = down
        if (flow.face.value != Face.LED || flow.arriving.value) return
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
        if (flow.face.value != Face.LED || flow.arriving.value || flow.settling.value || touching) return
        val idle = panelModes.idle
        val fade = panelModes.fade
        if (idle == fade) return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = if (fast) fade else idle
            preferredRefreshRate = 0f
        }
    }

    // ---------------------------------------------------------------------------------------
    // LockPort: the phone
    // ---------------------------------------------------------------------------------------

    override val interactive: Boolean get() = power.isInteractive
    override val keyguardLocked: Boolean get() = keyguard.isKeyguardLocked
    override val inCall: Boolean get() = audio.inCall
    override val displayState: Int? get() = currentDisplay()?.state
    override val windowFocused: Boolean get() = hasWindowFocus()
    override val resumed: Boolean get() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    override val elapsedMs: Long get() = SystemClock.elapsedRealtime()
    override val wallMs: Long get() = System.currentTimeMillis()
    override val nextAlarmAt: Long? get() = alarms.nextAlarmClock?.triggerTime
    override val expectsAod: Boolean? get() = GlowSession.expectsAod(this)
    override val aodOnly: Boolean get() = ledOnAodOnly(contentResolver)
    override val batterySaver: Boolean get() = power.isPowerSaveMode
    override val batteryPct: Int? get() = (this as Context).batteryPct()

    override val ledDarkLeftMs: Long get() = LedRhythm.darkLeft()

    /** The framework's bar slide-out follows the animator scale, so wait no longer than it takes. */
    override val barsHideMs: Long
        get() {
            val scale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            return (BARS_HIDE_SLIDE_MS * scale).toLong() + BARS_HIDE_MARGIN_MS
        }

    override fun loadSettings(): GlowSettings = GlowPrefs.loadPlaying(this)

    override fun schedule(timer: LockTimer, delayMs: Long) {
        timers.postDelayed(due.getValue(timer), delayMs)
    }

    override fun cancel(timer: LockTimer) {
        timers.removeCallbacks(due.getValue(timer))
    }

    override fun cancelTimers() {
        timers.removeCallbacksAndMessages(null)
    }

    override fun wakePanel() = PanelWaker.wake(this)

    override fun restartTimeout() = PanelWaker.restartTimeout(this)

    override fun holdCpu(ms: Long) = DarkHold.acquire(this, ms)

    override val panelHeld: Boolean get() = keepPanelOn.isHeld

    @SuppressLint("WakelockTimeout") // released when the LED leaves the front (LockFlow.releaseKeepOn)
    override fun holdPanel() = keepPanelOn.acquire()

    override fun releasePanel() = keepPanelOn.release()

    @SuppressLint("WakelockTimeout") // released with keepPanelOn
    override fun holdPocket() {
        pocketDark?.let { if (!it.isHeld) it.acquire() }
    }

    override fun releasePocket(waitForFar: Boolean) {
        pocketDark?.let { if (it.isHeld) it.release(if (waitForFar) PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY else 0) }
    }

    override fun watchLit() = stowWatch.watchLit()
    override fun stopWatchingLit() = stowWatch.stopLit()
    override fun watchStowed() = stowWatch.watchStowed()
    override fun stopStowWatch() = stowWatch.stop()
    override fun armAfterCall() = afterCall.arm()
    override fun cancelAfterCall() = afterCall.cancel()

    override fun dismissBridge() = GlowLauncher.dismissBridge(this)
    override fun dismissMessage() = GlowLauncher.dismissMessage(this)
    override fun showMessage(sbn: StatusBarNotification) = GlowLauncher.showMessage(this, sbn)
    override fun launchFromBackground(mode: WakeMode, color: Int) = GlowLauncher.launchFromBackground(this, mode, color)
    override fun note(event: String, detail: String) = LedDiary.note(this, event, detail)

    override fun finishSession() = finishAndRemoveTask()

    override fun moveToBack() {
        moveTaskToBack(true)
    }

    // ---------------------------------------------------------------------------------------
    // LockPort: the window
    // ---------------------------------------------------------------------------------------

    /**
     * Cover (occlude) the lock screen, or let it show over us. Works while stopped or asleep.
     * turnScreenOn stays off: an armed turnScreenOn activity that occludes while the device is
     * asleep can make WindowManager wake the screen by itself. [PanelWaker] does every wake.
     */
    override fun coverLockScreen(cover: Boolean) {
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
    override fun armTurnScreenOn(armed: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(armed)
        } else {
            @Suppress("DEPRECATION")
            if (armed) {
                window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
    }

    override fun dressAsLed() {
        setSeeThrough(false)
        holdPortrait(true)
        window.setBackgroundDrawable(android.graphics.Color.BLACK.toDrawable())
        window.clearFlags(UNTOUCHABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyWindow(lowRefresh = true)
        window.hideBarsOnBlack()
    }

    override fun dressSeeThrough() {
        window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
        holdPortrait(false)
        setSeeThrough(true)
        window.addFlags(UNTOUCHABLE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyWindow(lowRefresh = false)
    }

    override fun clearKeepScreenOn() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun hideBars() = window.hideBarsOnBlack()

    override fun resetPanelRate() {
        // The double tap never reports a release; a resolution change needs new mode ids.
        touching = false
        ledFast = false
        panelModes.reset()
    }

    override fun listenForDisplay(listen: Boolean) {
        if (!listen) {
            displays.unregisterDisplayListener(displaySignal)
            return
        }
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
            displays.registerDisplayListener(mainExecutor, events, displaySignal)
        } else {
            displays.registerDisplayListener(displaySignal, timers)
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

    /**
     * [lowRefresh]: the LED face. While it plays the arrival effect it asks for the panel's top
     * rate instead: adaptive-refresh panels drop to their idle rate (10 Hz on One UI) over an
     * almost static black window, and an effect started there only ever draws at that rate, so
     * the system never sees a reason to raise it. The lock-screen overlay never had this problem
     * because SystemUI keeps the rate up there. Otherwise the dot keeps the rate it asked for
     * ([ledFrameRate]), and a finger on it the top rate ([onLedTouch]).
     *
     * Brightness is never overridden: the LED and its effects show at the system brightness, the
     * lock screen's own. Any override (even the lowest, while settling) turns the light sensor
     * off, and whatever came next then showed at the override until auto-brightness had a fresh
     * reading (~0.1 s): the lock screen over the LED at 100 % (measured 500 → 176 nits), or the
     * settled dot at 0. So the dot follows the room's light like the rest of the phone, and the
     * system's brightness slider is its control too.
     */
    override fun applyWindow(lowRefresh: Boolean) {
        val playing = lowRefresh && flow.arriving.value
        val boosted = playing || (lowRefresh && touching)
        window.attributes = window.attributes.apply {
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

        /** The framework's slide-out of bars a window gains while they show (InsetsController, 340 ms at 1x). */
        const val BARS_HIDE_SLIDE_MS = 340L
        const val BARS_HIDE_MARGIN_MS = 20L
    }
}
