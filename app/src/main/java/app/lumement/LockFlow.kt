package app.lumement

import android.service.notification.StatusBarNotification
import android.view.Display
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/** What the glow screen is doing right now. */
internal enum class Face {
    /** Covering the lock screen: black panel, only the LED blinking. */
    LED,

    /** On top of the lock screen without covering it: see-through, untouchable, invisible. */
    LOCK_SCREEN,

    /** Behind other apps because the phone was unlocked. */
    AWAY,
}

/** The lock flow's one-shot timers: at most one of each is due at a time ([LockPort.schedule]). */
enum class LockTimer {
    /** Light the panel ([LockFlow.wakeNow]). */
    WAKE,

    /** The wake didn't take: the full-screen intent instead. */
    VERIFY_WAKE,

    /** Focus never came after a wake into the LED, or the bars have slid out: show the dot. */
    SETTLE,

    /** Is the lock screen going to sleep? */
    SLEEP_WATCH,

    /** Cover the lit lock screen with the LED, just before its own timeout. */
    TAKEOVER,

    /** Dim the lit lock screen ahead of [TAKEOVER]. */
    TAKEOVER_DIM,

    /** A rate-limited relight, once a token is due. */
    RELIGHT,

    /** Covered for a while with the LED up: put away. */
    STOW_COVERED,

    /** Read elsewhere with the LED lit: sleep once its last breath is out. */
    SLEEP_AFTER_BREATH,
}

/**
 * Everything [LockFlow] reads from the phone and does to it and to the glow screen's window. The
 * glow screen implements it on Android; the JVM tests implement it with a phone of their own.
 */
interface LockPort {
    val interactive: Boolean
    val keyguardLocked: Boolean
    val inCall: Boolean

    /** The default display's state ([Display.STATE_ON], [Display.STATE_DOZE], ...), null without one. */
    val displayState: Int?

    /** The glow screen's window has the focus. */
    val windowFocused: Boolean

    /** The glow screen is resumed. */
    val resumed: Boolean

    /** Elapsed realtime, in ms. */
    val elapsedMs: Long

    /** Wall-clock time, in ms. */
    val wallMs: Long

    /** When the system's next alarm clock is due, or null with none set. */
    val nextAlarmAt: Long?

    /** [GlowSession.expectsAod]. */
    val expectsAod: Boolean?

    /** [ledOnAodOnly]: the LED shows on One UI's always-on display or not at all. */
    val aodOnly: Boolean

    /** How long the framework's slide-out of the system bars takes here. */
    val barsHideMs: Long

    /** [GlowPrefs.loadPlaying]. */
    fun loadSettings(): GlowSettings

    fun schedule(timer: LockTimer, delayMs: Long)
    fun cancel(timer: LockTimer)
    fun cancelTimers()

    /** [PanelWaker.wake]. */
    fun wakePanel()

    /** [PanelWaker.restartTimeout]. */
    fun restartTimeout()

    /** [DarkHold.acquire]. */
    fun holdCpu(ms: Long = LockFlow.DARK_HOLD_MS)

    /** The LED's screen lock ([holdPanel]) is held. */
    val panelHeld: Boolean
    fun holdPanel()
    fun releasePanel()

    /** The LED's proximity panel-off lock: [releasePocket] with [waitForFar] keeps it until the sensor clears. */
    fun holdPocket()
    fun releasePocket(waitForFar: Boolean)

    /** Cover (occlude) the lock screen, or let it show over the glow screen. */
    fun coverLockScreen(cover: Boolean)

    /** Black, opaque, portrait, touchable, the screen kept on, the bars hidden: the LED's face. */
    fun dressAsLed()

    /** See-through, untouchable, any orientation, the screen not kept on: out of the lock screen's way. */
    fun dressSeeThrough()

    fun clearKeepScreenOn()
    fun hideBars()

    /** The panel's rate for the LED's face ([lowRefresh]) or anything else. */
    fun applyWindow(lowRefresh: Boolean)

    /** Forget the LED's rate requests and a finger on it: a new face starts clean. */
    fun resetPanelRate()

    /** Only for the full-screen-intent fallback. */
    fun armTurnScreenOn(armed: Boolean)

    /** Display changes reach [LockFlow.onDisplayChanged] while on. */
    fun listenForDisplay(listen: Boolean)

    /** Finish the glow screen and its task: the session is over. */
    fun finishSession()
    fun moveToBack()

    /** [StowWatch]: lit, stowed, or not at all. */
    fun watchLit()
    fun stopWatchingLit()
    fun watchStowed()
    fun stopStowWatch()

    /** [AfterCall]: [LockFlow.callOver] once the call is over. */
    fun armAfterCall()
    fun cancelAfterCall()

    fun dismissBridge()
    fun dismissMessage()
    fun showMessage(sbn: StatusBarNotification)
    fun launchFromBackground(mode: WakeMode, color: Int): Boolean
}

/** [GlowShield]'s calls, as the lock flow makes them; [RealShield] on the phone. */
internal interface ShieldPort {
    fun show()
    fun blackout()
    fun dimIn(durationMs: Long)
    fun hide()
    fun showLit(colors: () -> List<Int>, round: Int, onTouch: () -> Unit, onTap: () -> Unit): Boolean
    fun focusLockScreen()
    fun hideLit()
    fun prepareLed()
    fun startAodLed(colors: () -> List<Int>, round: Int): Boolean
    fun stopAodLed()
    fun removeLit()
    fun removeLed()
    fun hideWhenDone(onGone: () -> Unit)
    fun playArrival(color: Int)
    fun stopArrival()
    fun sleepNow(): Boolean
}

internal object RealShield : ShieldPort {
    override fun show() = GlowShield.show()
    override fun blackout() = GlowShield.blackout()
    override fun dimIn(durationMs: Long) = GlowShield.dimIn(durationMs)
    override fun hide() = GlowShield.hide()
    override fun showLit(colors: () -> List<Int>, round: Int, onTouch: () -> Unit, onTap: () -> Unit) = GlowShield.showLit(colors, round, onTouch, onTap)
    override fun focusLockScreen() = GlowShield.focusLockScreen()
    override fun hideLit() = GlowShield.hideLit()
    override fun prepareLed() = GlowShield.prepareLed()
    override fun startAodLed(colors: () -> List<Int>, round: Int) = GlowShield.startAodLed(colors, round)
    override fun stopAodLed() = GlowShield.stopAodLed()
    override fun removeLit() = GlowShield.removeLit()
    override fun removeLed() = GlowShield.removeLed()
    override fun hideWhenDone(onGone: () -> Unit) = GlowShield.hideWhenDone(onGone)
    override fun playArrival(color: Int) = GlowShield.playArrival(color)
    override fun stopArrival() = GlowShield.stopArrival()
    override fun sleepNow() = GlowShield.sleepNow()
}

/**
 * The glow screen's lock flow ([WakeScreenActivity] explains the why): the face it shows, and
 * every switch between them, on the phone's events. Plain Kotlin over [LockPort] and [ShieldPort],
 * so the flow runs in a JVM test as it does on the phone. Main thread only.
 */
internal class LockFlow(private val port: LockPort, private val shield: ShieldPort) : GlowSession.Host {

    val face = mutableStateOf(Face.LOCK_SCREEN)

    /**
     * The panel is off while the phone is awake: the LED's proximity lock has turned it off. Android
     * keeps sending frames to a dark panel, so the LED round pauses rather than drawing them.
     */
    val covered = mutableStateOf(false)

    /** The last message was read while the LED was lit: the dot ends its breath, then black until the screen goes off. */
    val ending = mutableStateOf(false)

    /** A new message came in while the panel was dark: play its effect once the screen is on. */
    private var arrivalDue = false

    /** Black arrival: the effect or message is showing on the black panel; cleared when the dot takes over. */
    val arriving = mutableStateOf(false)

    /** Bumped per announced message, so one arriving during the effect restarts it. */
    val arrivalSeq = mutableIntStateOf(0)

    /**
     * After a wake into the LED, One UI's shade keeps focus and the system bars until its wake
     * transition ends. Until our window gets focus (and the bars have slid out) the dot stays hidden.
     */
    val settling = mutableStateOf(false)

    /**
     * The lit LED breathes in [GlowShield]'s window, the lock screen ready in front under it
     * ([readyLockScreen]); the glow screen, behind the lock screen, keeps its own dot out.
     */
    val ledInWindow = mutableStateOf(false)

    val settings = mutableStateOf(GlowSettings())

    /** Resumed: no call, alarm or other activity has come over the LED. */
    private var inFront = false

    /** The LED was put up during the sleep fade; the SCREEN_OFF / pause that may follow are expected. */
    private var ledArmedForSleep = false

    /**
     * After an automatic wake (a new message lit the lock screen), cover the lock screen with
     * the LED while the phone is still fully awake, just before One UI's 3 s lock-screen timeout.
     * One UI only processes "covered" while awake, so this is the one switch with no off/on and
     * no system bars at all. Not after a tap or power press: then the user is likely unlocking.
     */
    private var autoTakeover = false

    private val relightLimit = RelightLimiter(RELIGHT_BURST, RELIGHT_REFILL_MS)

    /**
     * The lock screen was handed back to run out its own short timeout under a black cover
     * ([sleepUnderCover]): nobody is looking at it, whatever face says.
     */
    private var coverSleep = false

    /**
     * Put away with the LED up: face down (on any surface, glass included) or covered for a while.
     * The panel and the CPU sleep, and the LED is back once the phone is taken out ([takenOut]).
     */
    private var stowed = false

    /** [LockPort.interactive] at the last display change. */
    private var interactiveSeen = false

    /** Display changes come in: only while on top of the lock screen, never while [Face.AWAY]. */
    private var listeningForSleep = false

    /** When the system's next alarm clock is due, as last seen ([onNextAlarmChanged]); null with none set. */
    private var alarmDueAt: Long? = null

    /** The LED breathes on the always-on display ([LedRound]): the phone dozes, the lock screen in front. */
    private var aodLit = false

    /** SCREEN_OFF came with the panel still on, on its way to the always-on display or off: [onScreenOff] once it has gone. */
    private var sleepSettling = false

    /** Set from the power press until the panel is back on: the lock screen is in front, waiting to be lit. */
    private var revealingAfterPower = false

    /** The glow screen is gone ([destroy]): a callback still on its way does nothing. */
    private var destroyed = false

    override val isAway: Boolean
        get() = face.value == Face.AWAY

    override val isPutAway: Boolean
        get() = stowed

    /** No LED for now: Do Not Disturb ([GlowSession.resting]) or [stowed]. */
    private val ledResting: Boolean get() = GlowSession.resting || stowed

    /**
     * Let the lock screen sleep rather than keep the phone awake for the lit LED: this sleep is
     * expected to show the always-on display ([GlowSession.expectsAod]), or it is not known. There
     * the LED breathes on it ([startAodLed]): power then wakes the phone at once, and no screen
     * stays on. Where the display goes off instead, the lit LED takes the lock screen over before
     * it sleeps, and catches the sleep to relight ([onGoingToSleep]).
     */
    private val letsSleep: Boolean get() = port.expectsAod != false

    // ---------------------------------------------------------------------------------------
    // The glow screen's life
    // ---------------------------------------------------------------------------------------

    /** The glow screen is created, asked for [mode]. */
    fun create(mode: WakeMode) {
        // Never cover the lock screen by accident before start() decides.
        port.coverLockScreen(cover = false)
        alarmDueAt = port.nextAlarmAt
        listenForSleep(true)
        GlowSession.attach(this)
        start(mode)
    }

    fun onResume() {
        GlowLog.d { "act onResume face=${face.value} interactive=${port.interactive} locked=${port.keyguardLocked}" }
        // Resumed with no lock screen: the phone was unlocked underneath us.
        if (face.value != Face.AWAY && !port.keyguardLocked) {
            goAway()
            return
        }
        if (face.value == Face.LED) {
            port.hideBars()
            // Ending: the screen is meant to time out; holding it on here would keep it lit for good.
            if (!ending.value) acquireKeepOn()
        }
        inFront = true
        updateLedWindow()
    }

    fun onPause() {
        GlowLog.d { "act onPause face=${face.value} interactive=${port.interactive}" }
        // The lock screen readied under the LED pauses us: the LED's locks stay held.
        if (!ledInWindow.value) releaseKeepOn()
        inFront = false
        // Paused by the panel going dark while the LED was in front: the user pressed power (or
        // double-tapped to sleep). Normally [onDisplayChanged] has already handled it; otherwise
        // open the lock screen now rather than waiting for the late ACTION_SCREEN_OFF, which
        // arrives ~0.3 s after the panel is already dark.
        if (ledTurnedOff()) {
            revealAfterPower()
        } else {
            updateLedWindow()
        }
    }

    fun onFocusChanged(hasFocus: Boolean) {
        if (hasFocus && leaveIfUnlocked()) return
        // Android re-shows the bars when a window regains focus over the lock screen.
        if (hasFocus && face.value == Face.LED) {
            port.hideBars()
            // Focus is the hand-over: the bars are ours from here on, but they arrive still
            // showing and the framework slides them out. Stay dark until that has finished.
            if (settling.value) {
                port.cancel(LockTimer.SETTLE)
                port.schedule(LockTimer.SETTLE, port.barsHideMs)
            }
        }
    }

    fun destroy() {
        GlowLog.d { "act onDestroy" }
        destroyed = true
        endLedWindow()
        port.cancelAfterCall()
        unstow()
        port.cancelTimers()
        GlowSession.detach(this)
        shield.hide()
        // Messages still waiting: the always-on display's window stays, for the LED at the next sleep.
        if (GlowPending.isEmpty) shield.removeLed() else shield.removeLit()
        shield.stopArrival()
        port.dismissMessage()
        listenForSleep(false)
    }

    fun onTimer(timer: LockTimer) {
        when (timer) {
            LockTimer.WAKE -> wakeNow()
            LockTimer.VERIFY_WAKE -> verifyWake()
            LockTimer.SETTLE -> finishSettling()
            LockTimer.SLEEP_WATCH -> sleepWatch()
            LockTimer.TAKEOVER -> takeOver()
            LockTimer.TAKEOVER_DIM -> dimForTakeover()
            LockTimer.RELIGHT -> relightLed(RELIGHT_DELAY_MS)
            LockTimer.STOW_COVERED -> if (covered.value) stow()
            LockTimer.SLEEP_AFTER_BREATH -> if (ending.value && face.value == Face.LED && port.interactive) sleepUnderCover()
        }
    }

    private fun wakeNow() {
        port.wakePanel()
        port.schedule(LockTimer.VERIFY_WAKE, WAKE_VERIFY_MS)
    }

    // Fallback if this build refuses the wake lock: the full-screen intent lights it (armed
    // with a one-shot turnScreenOn in case SystemUI doesn't wake for it).
    private fun verifyWake() {
        if (port.interactive || port.inCall || face.value == Face.AWAY) return
        GlowLog.d { "act wake lock ignored, falling back to full-screen intent" }
        port.armTurnScreenOn(true)
        val mode = if (face.value == Face.LED) WakeMode.LED else WakeMode.WAKE
        port.launchFromBackground(mode, GlowPending.newestColor)
    }

    /**
     * Watches for the start of sleep while the system lock screen is on screen. Android fades the
     * panel for ~0.4 s between going-to-sleep and actually sleeping; switching to the LED and
     * relighting inside that window cancels the sleep before One UI reaches its dozing state.
     * Waking from a finished doze instead makes One UI run a ~300 ms DOZING → OCCLUDED transition
     * during which its shade holds focus, shows the battery icon and nav handle, and keeps the
     * system brightness (our override only applies once it lets go). Measured on the recording.
     * There is no callback for "going to sleep", so this is a check that runs only while the lock
     * screen is in front: every [SLEEP_WATCH_MS], or every [READY_SLEEP_WATCH_MS] while it waits
     * under the LED, where a press is opened as soon as it is seen ([openUnderLed]).
     */
    private fun sleepWatch() {
        if (face.value != Face.LOCK_SCREEN) return
        if (port.interactive) {
            port.schedule(LockTimer.SLEEP_WATCH, sleepWatchMs())
        } else {
            onGoingToSleep()
        }
    }

    private fun takeOver() {
        GlowLog.d { "act takeover interactive=${port.interactive} locked=${port.keyguardLocked}" }
        if (leaveIfUnlocked()) return
        if (face.value == Face.LOCK_SCREEN && autoTakeover && !ledResting && !letsSleep && port.interactive && port.keyguardLocked) {
            autoTakeover = false
            showLed()
        } else if (port.interactive) {
            shield.hide() // bailed: give the lit lock screen back from under the dim
        }
    }

    /** Dims the lit lock screen to black over [TAKEOVER_DIM_MS], ending as [takeOver] covers it. */
    private fun dimForTakeover() {
        if (face.value == Face.LOCK_SCREEN && autoTakeover && !ledResting && !letsSleep && port.interactive && port.keyguardLocked) {
            shield.dimIn(TAKEOVER_DIM_MS)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Put away
    // ---------------------------------------------------------------------------------------

    /**
     * Picked up with the LED lit and the system's lift to wake on: the lock screen, as a power
     * press opens it ([openUnderLed]). The system's own lift listens only while the phone sleeps.
     */
    fun pickedUp() {
        GlowLog.d { "act picked up face=${face.value} inWindow=${ledInWindow.value}" }
        openUnderLed("lift")
    }

    /** Covered with the LED up and not put away yet: put away in [COVERED_MS]; else no timer. */
    private fun armStowCovered() {
        port.cancel(LockTimer.STOW_COVERED)
        if (covered.value && face.value == Face.LED && !stowed) port.schedule(LockTimer.STOW_COVERED, COVERED_MS)
    }

    /**
     * The LED is lit: watch for it being put away, face down ([StowWatch]) or covered
     * ([armStowCovered]; lit while already covered, no display change will say so). Asked again,
     * as when a stow had to wait for an arrival, it looks again.
     */
    private fun watchPutAway() {
        port.watchLit()
        armStowCovered()
    }

    private fun stopWatchingPutAway() {
        port.stopWatchingLit()
        port.cancel(LockTimer.STOW_COVERED)
    }

    /** Put away while the LED is lit: let the panel and the CPU sleep until it is taken out. */
    fun stow() {
        coverWithLed("put away")
        // An arrival plays out first; [onArrivalDone] asks again.
        if (stowed || face.value != Face.LED || arriving.value || ending.value || !port.interactive) return
        GlowLog.d { "act put away covered=${covered.value}" }
        stowed = true
        updateLedWindow()
        port.watchStowed()
        if (covered.value) {
            // Covered (pocket, sofa, most desks): the proximity lock keeps the panel off. Let go of
            // only what keeps the CPU awake and leave the LED in front, so it is simply there the
            // moment the phone is uncovered: no wake, no lock screen. The phone sleeps at its own
            // timeout ([ledTurnedOff] knows).
            port.clearKeepScreenOn()
            if (port.panelHeld) port.releasePanel()
        } else {
            // Face down where the sensor sees nothing (a glass desk): only sleep turns the panel off.
            sleepUnderCover()
        }
    }

    /** Taken out again: the LED is back, straight away if the panel never got to sleep. */
    fun takenOut() {
        if (!stowed) return
        GlowLog.d { "act taken out face=${face.value} interactive=${port.interactive}" }
        // As it was put away: unstowing may already ready the lock screen under the LED's window.
        val ledInFront = face.value == Face.LED
        unstow()
        if (GlowPending.isEmpty || GlowSession.resting) return
        when {
            // Uncovered with the LED still in front: hold the panel on again, unless the LED's
            // window has it already, holding it on, with the lock screen ready under it.
            ledInFront && port.interactive -> if (!ledInWindow.value) showLed()
            // Asleep with the LED in front: wake straight into it.
            ledInFront -> {
                port.holdCpu()
                if (takeRelightToken()) {
                    showLed()
                    wakeAfter(RELIGHT_DELAY_MS)
                }
            }
            // Still handing over to the lock screen's timeout, under the black cover: cover it again.
            port.interactive -> if (port.keyguardLocked) showLed()
            else -> {
                port.holdCpu()
                relightOrAod()
            }
        }
    }

    private fun unstow() {
        stowed = false
        port.stopStowWatch()
        // Woken by hand while put away: the LED, if in front, watches again.
        if (port.panelHeld) watchPutAway()
        updateLedWindow()
    }

    /** The screen went off during a call, and the call is over: the LED as the screen-off would have had it. */
    fun callOver() {
        if (!port.interactive && face.value != Face.AWAY) {
            GlowLog.d { "act relight after the call face=${face.value}" }
            when (face.value) {
                Face.LOCK_SCREEN -> relightOrAod()
                // The LED was in front when the call came: straight back into it.
                else -> if (!ledResting && !GlowPending.isEmpty && takeRelightToken()) {
                    showLed()
                    wakeAfter(RELIGHT_DELAY_MS)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // GlowSession.Host
    // ---------------------------------------------------------------------------------------

    override fun onNewMessage() {
        GlowLog.d { "act onNewMessage face=${face.value} interactive=${port.interactive}" }
        if (face.value == Face.AWAY) return
        ending.value = false
        if (stowed) return // nobody to show it to: the LED has it once the phone is taken out
        // The message takes the screen; the LED's round starts over after it, newest colour first.
        // Ready under the LED, the lock screen was in front, but nobody was looking at it.
        val wasReady = ledInWindow.value
        endAodLed()
        port.cancel(LockTimer.SLEEP_AFTER_BREATH)
        settings.value = port.loadSettings() // the arrival choice may have changed since launch
        val arrival = arrivalFor(settings.value.arrival, GlowSession.resting)
        when (arrival) {
            ArrivalMode.LOCK_SCREEN -> {
                // Show the system lock screen with all its notifications, lit, with the effect
                // over it. If the user is already looking at it, leave the hand-back off.
                val userOnLockScreen = face.value == Face.LOCK_SCREEN && port.interactive && !autoTakeover && !coverSleep && !wasReady
                endLedWindow()
                if (!userOnLockScreen) showLockScreen(auto = true)
                announce()
                if (!port.interactive) {
                    port.holdCpu()
                    wakeAfter(0)
                }
            }
            ArrivalMode.BLACK, ArrivalMode.MESSAGE, ArrivalMode.LED_ONLY -> {
                val effect = arrival.playsEffect
                // The lock screen is only lit and in front when the user woke it themselves: they
                // are looking at it (maybe typing a PIN), so don't cover it; play the effect over it
                // instead (if there is one), and the dot takes over when it sleeps.
                if (face.value == Face.LOCK_SCREEN && port.interactive && !coverSleep && !wasReady) {
                    if (effect) announce()
                    return
                }
                val dark = !port.interactive
                if (dark) port.holdCpu()
                showLed(arrival = effect)
                // Arranged while dark: give the cover a moment to commit, then light straight into it.
                if (dark) wakeAfter(RELIGHT_DELAY_MS)
            }
        }
    }

    override fun onPendingChanged() {
        if (!GlowPending.isEmpty) return
        if (ledInWindow.value && port.interactive) {
            // Read elsewhere while the lock screen waits under the LED: don't pop it up. Black over
            // it and asleep at once; the glow screen finishes when the screen goes off.
            GlowLog.d { "act read elsewhere under the LED" }
            ending.value = true
            sleepFromUnderLed()
            return
        }
        if (face.value == Face.LED && port.interactive) {
            // Read elsewhere while the LED is lit: don't pop the lock screen up. Let the dot finish
            // its breath, then sleep under cover rather than at the screen timeout (10 min on some
            // phones), and finish when the screen goes off. An arrival in progress is cut short:
            // the rate drops, the dot lights no more.
            GlowLog.d { "act draining" }
            ending.value = true
            onArrivalDone()
            port.clearKeepScreenOn()
            port.cancel(LockTimer.SLEEP_AFTER_BREATH)
            if (stowed) {
                // Put away: nobody sees the last breath. Sleep now, and let go of the proximity lock
                // without waiting for the sensor to clear: a covered sensor keeps the phone awake
                // (as in a call), so it would stay up until taken out and light its lock screen then.
                // The CPU may be let sleep mid-way while put away; hold it for the hand-over.
                port.holdCpu(DRAIN_HOLD_MS)
                port.releasePocket(waitForFar = false)
                releaseKeepOn()
                sleepUnderCover()
            } else {
                releaseKeepOn()
                port.schedule(LockTimer.SLEEP_AFTER_BREATH, LED_BREATH_MS.toLong())
            }
        } else {
            port.finishSession()
        }
    }

    override fun onScreenTakersChanged() {
        updateLedWindow()
    }

    /**
     * [GlowShield] went with the LED in one of its windows. Ready under the LED, the lock screen
     * would be left lit, held on by the LED's locks with no LED over it, and the next power press
     * cancelled: the LED's own face covers it again, as without [GlowShield]. On the always-on
     * display, the LED is lit instead, as where it can't go there.
     */
    override fun onShieldGone() {
        GlowLog.d { "act shield gone face=${face.value} inWindow=${ledInWindow.value} aod=$aodLit" }
        if (aodLit) {
            aodLit = false
            if (!port.interactive && face.value == Face.LOCK_SCREEN) {
                port.holdCpu()
                relightLed(RELIGHT_DELAY_MS)
            }
        }
        coverWithLed("shield gone")
    }

    override fun ensureLed() {
        if (aodLit) return
        val missing = face.value != Face.AWAY && ledMissing(
            screenOn = port.interactive,
            waiting = !GlowPending.isEmpty,
            resting = GlowSession.resting,
            inCall = port.inCall,
            putAway = stowed,
            ending = ending.value,
        )
        if (!missing) {
            if (port.inCall && !port.interactive) port.armAfterCall()
            return
        }
        GlowLog.d { "act LED missing face=${face.value}: relighting" }
        port.holdCpu()
        when (face.value) {
            Face.LOCK_SCREEN -> relightOrAod()
            else -> if (takeRelightToken()) {
                showLed()
                wakeAfter(RELIGHT_DELAY_MS)
            }
        }
    }

    override fun onRestChanged() {
        GlowLog.d { "act rest=${GlowSession.resting} face=${face.value} interactive=${port.interactive}" }
        if (GlowSession.resting) {
            if (ledInWindow.value) sleepFromUnderLed() // no lock screen popping up: dark, as from the LED's face
            clearLed()
            port.cancel(LockTimer.TAKEOVER)
            port.cancel(LockTimer.TAKEOVER_DIM)
            port.cancel(LockTimer.RELIGHT)
            when {
                face.value != Face.LED -> Unit // the lock screen times out as it would without us
                // A black arrival plays out first ([onArrivalDone]).
                arriving.value -> Unit
                port.interactive -> sleepUnderCover()
                // Dark, a relight on its way: call it off and leave the lock screen for the next wake.
                else -> {
                    port.cancel(LockTimer.WAKE)
                    port.cancel(LockTimer.VERIFY_WAKE)
                    showLockScreen()
                }
            }
        } else if (face.value == Face.LOCK_SCREEN && !port.interactive && !GlowPending.isEmpty) {
            // Do Not Disturb is over and messages are still unread: the LED lights again, on the
            // always-on display if it shows. Looking at the lock screen, the dot takes over when
            // it sleeps, as ever.
            port.holdCpu()
            relightOrAod()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lock flow
    // ---------------------------------------------------------------------------------------

    /** Launched (or launched again) in [requested] mode. */
    fun start(requested: WakeMode) {
        port.dismissBridge()
        port.cancelTimers()
        clearLed()
        settings.value = port.loadSettings()
        if (GlowPending.isEmpty) {
            // Stale launch: everything was read before we came up.
            port.finishSession()
            return
        }
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
            WakeMode.AOD -> showLockScreen()
        }
        // The full-screen intent normally lights the panel; make sure, without relaunching.
        // Arrived while the screen is still dark: started in the dark ([GlowLauncher.startInTheDark]),
        // or by a full-screen intent that stock Android launches before anything wakes the panel
        // (One UI wakes it first). Resumed and paused at once while asleep, that pause is ours,
        // not a power press; and nobody else is lighting the panel, so it does, once its cover is
        // committed. Through the full-screen intent the system may still light it first: then
        // the wake finds it lit and does nothing.
        if (mode == WakeMode.AOD && !port.interactive) {
            // Asleep on the always-on display: the LED on it, no wake. Its window is up already, drawn
            // while the screen was on (no window draws while dozing); without it, lit as ever.
            port.holdCpu()
            relightOrAod()
        } else if (!port.interactive) {
            if (face.value == Face.LED) ledArmedForSleep = true
            wakeAfter(RELIGHT_DELAY_MS)
        }
        prepareLed() // started with the screen on already: no SCREEN_ON will come
    }

    fun onScreenOff() {
        // SCREEN_OFF arrives ~0.2-0.3 s late. If the panel is already back on (a quick second
        // power press, or our own wake cancelled the sleep), it is stale: acting on it would put
        // the LED over a lit lock screen.
        if (port.interactive) return
        port.holdCpu()
        val state = port.displayState
        if (state == Display.STATE_ON && !revealingAfterPower) {
            // Asleep, the panel still on: One UI keeps it on while it goes to the always-on
            // display. Its next change says where it went ([onDisplayChanged] brings it back here).
            sleepSettling = true
            return
        }
        if (awaitsAod(state)) {
            // The always-on display is expected and the panel is off on its way: shown only for new
            // notifications, One UI turns it off first and dozes ~0.1 s after SCREEN_OFF (measured
            // on the S23). The doze puts the LED on it ([onDisplayChanged]); if none comes, the
            // listener's check lights the LED ([ensureLed]).
            sleepSettling = true
            return
        }
        sleepSettling = false
        if (aodLit) return // the LED is on the always-on display
        port.cancel(LockTimer.TAKEOVER)
        port.cancel(LockTimer.TAKEOVER_DIM)
        port.cancel(LockTimer.VERIFY_WAKE)
        arrivalDue = false
        shield.stopArrival()
        if (ending.value) {
            port.finishSession()
            return
        }
        coverSleep = false
        if (ledResting) {
            shield.hide()
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
        if (port.inCall) {
            port.armAfterCall() // proximity during a call: stay dark, and light the LED once it is over
            return
        }
        if (revealingAfterPower) {
            // The lock screen is already in front, and the phone has finished going to sleep: light it.
            wakeRevealed()
            return
        }
        if (ledArmedForSleep) {
            // Already the LED, and One UI knows it is covered: just light the panel.
            ledArmedForSleep = false
            wakeAfter(RELIGHT_DELAY_MS)
            return
        }
        when (face.value) {
            // The user turned the LED off (power, double tap): open the phone, like without us.
            Face.LED -> revealAfterPower(asleep = true)
            // The lock screen went dark (timeout, or power on the lock screen): back to the LED,
            // arranged while dark so the panel comes on already black with the dot.
            Face.LOCK_SCREEN -> relightLed(RELIGHT_DELAY_MS)
            Face.AWAY -> Unit // the listener relights through the full-screen intent
        }
    }

    fun onScreenOn() {
        // Woken by the user while put away: they have it in hand; the lock screen as ever.
        if (stowed) unstow()
        revealingAfterPower = false
        ledArmedForSleep = false
        port.cancel(LockTimer.RELIGHT)
        port.armTurnScreenOn(false)
        port.cancel(LockTimer.VERIFY_WAKE)
        when (face.value) {
            Face.LED -> {
                port.hideBars()
                if (settling.value) port.schedule(LockTimer.SETTLE, SETTLE_FALLBACK_MS)
            }
            Face.LOCK_SCREEN -> {
                if (arrivalDue) announce()
                watchForSleep()
                scheduleTakeover()
            }
            Face.AWAY -> Unit
        }
        prepareLed()
    }

    /**
     * The earliest sign of a power press while the LED is in front: going to sleep changes the
     * display as the screen-off fade starts, and the phone is no longer interactive by then. The
     * lock screen is opened right here, hidden under the fade, so it is in front by the time the
     * display reports the panel off, and is lit then ([revealAfterPower]). onPause only comes
     * once the panel is off. Event-driven: nothing runs in between.
     *
     * Only on the change from interactive to not: we also put the LED up while the panel is
     * already dark (a black arrival, or the lock screen timing out), and the display changes
     * then too.
     */
    fun onDisplayChanged() {
        val wasInteractive = interactiveSeen
        interactiveSeen = port.interactive
        val state = port.displayState
        if (ledInWindow.value && !interactiveSeen) openUnderLed("display")
        // Also true for a moment while a wake turns the panel on; it turns on with an event too.
        val dark = interactiveSeen && state == Display.STATE_OFF
        if (dark != covered.value) {
            GlowLog.d { "act panel ${if (dark) "covered" else "uncovered"} face=${face.value}" }
            covered.value = dark
            if (dark) coverWithLed("covered")
            // Covered for a while with the LED up: put away. One timer from this event, no sampling.
            armStowCovered()
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
        if (aodLit && (interactiveSeen || !dozing(state))) {
            // Awake, or the always-on display has gone (its timeout, its schedule): the lit LED
            // takes over.
            GlowLog.d { "act aod: interactive=$interactiveSeen state=$state" }
            endAodLed()
            if (!interactiveSeen && face.value == Face.LOCK_SCREEN) relightLed(RELIGHT_DELAY_MS)
        } else if (!aodLit && !interactiveSeen && face.value == Face.LOCK_SCREEN && dozing(state) && !revealingAfterPower) {
            // Not on the way to the lock screen after a power press: that wakes at SCREEN_OFF.
            startAodLed()
        }
        if (sleepSettling && !interactiveSeen && state != Display.STATE_ON) onScreenOff()
        updateLedWindow()
    }

    /** The phone was unlocked (USER_PRESENT). */
    fun onUnlocked() {
        GlowLog.d { "act USER_PRESENT face=${face.value}" }
        goAway()
    }

    /**
     * The system's next alarm clock changed: set, snoozed, cancelled, or the one it named rang
     * ([alarmRang]). Sent as an alarm clock fires (measured on the S23: 12 ms after, ~0.19 s before
     * Samsung Clock's ringing screen is up).
     */
    fun onNextAlarmChanged() {
        val rang = alarmRang(alarmDueAt, port.wallMs)
        alarmDueAt = port.nextAlarmAt
        if (rang) alarmRings()
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
            port.listenForDisplay(false)
            covered.value = false // nothing would clear it until the listener is back
            return
        }
        interactiveSeen = port.interactive
        port.listenForDisplay(true)
    }

    /** Backup for a missed unlock broadcast: never stay over an unlocked phone. */
    fun leaveIfUnlocked(): Boolean {
        if (face.value == Face.AWAY || port.keyguardLocked) return false
        GlowLog.d { "act unlocked underneath (face=${face.value})" }
        goAway()
        return true
    }

    private fun scheduleTakeover() {
        port.cancel(LockTimer.TAKEOVER)
        port.cancel(LockTimer.TAKEOVER_DIM)
        if (autoTakeover && port.interactive && !ledResting) {
            port.schedule(LockTimer.TAKEOVER_DIM, TAKEOVER_MS - TAKEOVER_DIM_MS)
            port.schedule(LockTimer.TAKEOVER, TAKEOVER_MS)
        }
    }

    /**
     * Play the user's chosen effect once over the lit lock screen, in the newest message's colour.
     * Needs the optional [GlowShield]; without it the lock screen simply lights up.
     */
    private fun announce() {
        arrivalDue = !port.interactive
        if (arrivalDue) return
        shield.playArrival(GlowPending.newestColor)
    }

    private fun watchForSleep() {
        port.cancel(LockTimer.SLEEP_WATCH)
        // Not during a call: onGoingToSleep would bail anyway, and a screen the call keeps on
        // would be polled for the whole call. Afterwards SCREEN_OFF still brings the LED back.
        if (face.value == Face.LOCK_SCREEN && port.interactive && !port.inCall) {
            port.schedule(LockTimer.SLEEP_WATCH, sleepWatchMs())
        }
    }

    private fun sleepWatchMs(): Long = if (ledInWindow.value) READY_SLEEP_WATCH_MS else SLEEP_WATCH_MS

    private fun onGoingToSleep() {
        GlowLog.d { "act going to sleep on the lock screen expectsAod=${port.expectsAod}" }
        if (ledInWindow.value) {
            openUnderLed("sleep watch")
            return
        }
        if (letsSleep) return // the LED goes onto the always-on display once the phone dozes
        if (port.inCall || GlowPending.isEmpty || ledResting || !takeRelightToken()) return
        port.holdCpu()
        showLed()
        ledArmedForSleep = true
        // Relight while the panel is still fading, so the sleep is cancelled rather than finished.
        // If this wake is refused, SCREEN_OFF relights as before.
        wakeAfter(SLEEP_CANCEL_DELAY_MS)
    }

    /**
     * The lock screen is dark: put the LED up and light it after [delayMs], or, when the rate
     * limit has no token, as soon as it has one. Not where the LED lives on One UI's always-on
     * display alone ([LockPort.aodOnly]): dark there is One UI's choice (the message seen, low light,
     * its timeout), and the LED stays dark with it.
     */
    private fun relightLed(delayMs: Long) {
        port.cancel(LockTimer.RELIGHT)
        if (port.aodOnly || port.interactive || face.value != Face.LOCK_SCREEN || port.inCall || GlowPending.isEmpty || ledResting) return
        if (!takeRelightToken()) return
        showLed()
        wakeAfter(delayMs)
    }

    /**
     * Takes a relight token. Without one, schedules a [LockTimer.RELIGHT] for when the next one is
     * due and returns false.
     */
    private fun takeRelightToken(): Boolean {
        val wait = relightLimit.take(port.elapsedMs)
        if (wait == 0L) return true
        GlowLog.d { "act relight rate-limited, in $wait ms" }
        port.cancel(LockTimer.RELIGHT)
        port.schedule(LockTimer.RELIGHT, wait)
        // Handler time stops while the CPU sleeps.
        port.holdCpu(wait + RELIGHT_DELAY_MS + 500)
        return false
    }

    /** Tap or back on the LED: show the lock screen, with no automatic hand-back. */
    fun onUserDismiss() {
        GlowLog.d { "act user dismiss face=${face.value}" }
        if (leaveIfUnlocked()) return
        if (ending.value) {
            port.finishSession()
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
        face.value == Face.LED && !ending.value && !port.interactive && !port.inCall && !ledArmedForSleep && !stowed

    /**
     * The settled LED goes into [GlowShield]'s window ([ledWindowWanted]), and the lock screen
     * comes in front under it, ready ([readyLockScreen]); the window makes way when something
     * else wants the screen ([ledWindowEnds]). Also asked by the glow screen whenever the face,
     * settling, arrival, ending or covered state changes.
     */
    fun updateLedWindow() {
        val screenTaken = GlowSession.screenTakers.isNotEmpty()
        if (!ledInWindow.value) {
            val wanted = ledWindowWanted(
                ledInFront = face.value == Face.LED && inFront,
                screenOn = port.interactive,
                settled = !settling.value,
                arriving = arriving.value,
                ending = ending.value,
                covered = covered.value,
                putAway = stowed,
                inCall = port.inCall,
                screenTaken = screenTaken,
            )
            if (wanted) readyLockScreen()
        } else if (ledWindowEnds(screenTaken, port.inCall, ending.value)) {
            GlowLog.d { "act LED window making way" }
            endLedWindow()
        }
    }

    /**
     * The lit LED into its window over everything, and the lock screen in front under it, ready.
     * The window holds the focus ([GlowShield.showLit]): One UI shows its fingerprint icon, and
     * listens for the fingerprint, only while its lock screen has it, so the black stays black. A
     * power press then needs no lock screen lit again from sleep: it is cancelled at once and the
     * window lifted ([openUnderLed]), and the lock screen is whole in one frame (measured on the
     * S23: the window off 189-213 ms after key down, 172 of them One UI's wait for more presses;
     * ~0.93 s when the lock screen was lit from sleep). A finger down hands it the focus
     * ([onLedWindowTouch]): listening ~40 ms later, it reads a finger resting on the sensor. The
     * glow screen pauses behind the lock screen; the LED's locks stay held (screen on, the
     * proximity sensor's panel-off), as while its face was in front.
     */
    private fun readyLockScreen() {
        if (!shield.showLit({ GlowPending.colors() }, 0, ::onLedWindowTouch, ::onLedWindowTap)) return
        GlowLog.d { "act LED in its window, the lock screen ready under it" }
        ledInWindow.value = true
        acquireKeepOn()
        showLockScreen(underLed = true)
    }

    /**
     * Power pressed with the lock screen ready under the LED: a sleep request, as on any lit
     * screen, cancelled at once and the window lifted off the lock screen, which then runs its own
     * timeout. A pickup ([pickedUp]) opens it the same way. Woken after One UI's lock screen had
     * started for sleep (as soon as 12 ms after the request), its wallpaper was hidden and shown
     * again ~0.1 s after the lift: 1 press in 8 with [READY_SLEEP_WATCH_MS], 2 in 5 with
     * [SLEEP_WATCH_MS] (measured on the S23).
     */
    private fun openUnderLed(seenBy: String) {
        if (!ledInWindow.value) return
        GlowLog.d { "act opened under the LED ($seenBy): sleep cancelled, window lifted" }
        port.cancel(LockTimer.WAKE)
        wakeNow()
        endLedWindow()
    }

    /**
     * Covered or put away with the lock screen ready under the LED: the LED's own face covers it
     * again, black in front before the window lifts, and its stowing runs as ever.
     */
    private fun coverWithLed(why: String) {
        if (!ledInWindow.value) return
        GlowLog.d { "act $why under the LED: its face covers the lock screen again" }
        showLed()
    }

    /** Nothing more to show over the ready lock screen (read elsewhere, Do Not Disturb): black over it, and asleep at once. */
    private fun sleepFromUnderLed() {
        shield.blackout()
        endLedWindow()
        if (shield.sleepNow()) GlowLog.d { "act asleep at once, under the cover" }
    }

    /**
     * The LED's window goes. The lock screen under it, if ready, is the one in front now, on its
     * own timeout: the LED's locks go too, unless the LED's face is in front again (they are its).
     */
    private fun endLedWindow() {
        if (!ledInWindow.value) return
        ledInWindow.value = false
        shield.hideLit()
        if (face.value != Face.LED) releaseKeepOn()
    }

    /** A finger down on the LED, maybe on the fingerprint sensor: the lock screen under it gets the focus, and listens. */
    private fun onLedWindowTouch() {
        GlowLog.d { "act LED window touched" }
        shield.focusLockScreen()
    }

    /** A tap on the LED: the lock screen under it, as a tap on the LED's face opens it. */
    private fun onLedWindowTap() {
        if (!leaveIfUnlocked()) endLedWindow()
    }

    /**
     * An alarm clock rang with the lock screen ready under the LED. Any other app shows its ringing
     * screen through a full-screen notification, and the window makes way for that post
     * ([takesScreen]); Samsung Clock, a system app, opens its screen itself, under the window (above
     * every app window), and posts only ~3 s later (measured on the S23). So the window lifts now:
     * the lock screen in front, as a stock phone has it when an alarm rings while it shows, and the
     * alarm's screen over it. Once that is gone, the lock screen's own timeout runs.
     */
    private fun alarmRings() {
        if (!ledInWindow.value) return
        GlowLog.d { "act alarm rang under the LED: window lifted" }
        if (!leaveIfUnlocked()) endLedWindow()
    }

    /** Something else has the screen now (a message, Do Not Disturb, an unlock): no LED in its window or on the always-on display. */
    private fun clearLed() {
        endLedWindow()
        endAodLed()
    }

    /**
     * While the screen is on, the windows the LED breathes in once the panel sleeps go up, hidden
     * ([GlowShield.prepareLed]): One UI draws no window while dozing, and a window shows only once
     * it has drawn.
     */
    private fun prepareLed() {
        if (port.interactive && face.value != Face.AWAY && !GlowPending.isEmpty) shield.prepareLed()
    }

    /**
     * The phone dozes with the lock screen in front (its own sleep, or the user's): the LED goes
     * onto the always-on display, and no relight wakes the phone. Where it can't (no window drawn
     * yet, below Android 10), the lit LED relights at the screen-off.
     */
    private fun startAodLed() {
        val allowed = ledOnAod(
            waiting = !GlowPending.isEmpty,
            resting = GlowSession.resting,
            putAway = stowed,
            inCall = port.inCall,
            ending = ending.value,
        )
        if (!allowed || !shield.startAodLed(GlowPending::colors, 0)) return
        GlowLog.d { "act aod: LED on the always-on display" }
        aodLit = true
    }

    private fun endAodLed() {
        if (!aodLit) return
        GlowLog.d { "act aod: LED off" }
        aodLit = false
        shield.stopAodLed()
    }

    /**
     * The lock screen is dark and the LED should be back: on the always-on display if it shows,
     * without a wake ([startAodLed]), else lit ([relightLed]).
     */
    private fun relightOrAod() {
        if (!aodLit && dozing(port.displayState)) startAodLed()
        if (!aodLit) relightLed(RELIGHT_DELAY_MS)
    }

    /**
     * Uncover now, light the lock screen once the phone has gone to sleep ([asleep]: it has, the
     * SCREEN_OFF is in). The screen-off fade is drawn over everything, so the uncover never
     * shows. Lit from off, Android holds the panel dark until the lock screen has drawn and gives
     * it the system brightness from the start. Not before SCREEN_OFF, which comes once the system
     * has finished going to sleep: lit as soon as the panel was off, One UI's lock screen was
     * still on its way to sleep and showed its clock and notifications on black for ~190 ms
     * before the wallpaper; lit during the fade (cancelling the sleep, under a cover), its
     * wallpaper faded in over ~0.4 s. From SCREEN_OFF it is whole within a frame or two, as
     * without the app (measured on the S23).
     */
    private fun revealAfterPower(asleep: Boolean = false) {
        GlowLog.d { "act revealAfterPower asleep=$asleep" }
        revealingAfterPower = true
        port.holdCpu()
        showLockScreen()
        if (asleep) wakeRevealed()
    }

    /**
     * This sleep is the lock screen's own, the LED is due on the always-on display ([letsSleep]),
     * and the panel is off ([state]), not dozing yet.
     */
    private fun awaitsAod(state: Int?): Boolean =
        face.value == Face.LOCK_SCREEN && letsSleep && state == Display.STATE_OFF && !aodLit &&
            !revealingAfterPower && !ledArmedForSleep && !ending.value && !ledResting && !port.inCall && !GlowPending.isEmpty

    /** [revealAfterPower]'s phone is asleep: light the lock screen. */
    private fun wakeRevealed() {
        GlowLog.d { "act asleep: lighting the lock screen" }
        port.cancel(LockTimer.WAKE)
        wakeNow()
        // showLockScreen ran while asleep, so it couldn't start this; a quick second press on the
        // lock screen should go back to the LED like any other.
        watchForSleep()
    }

    /** Cover the lock screen with the LED; [arrival] first plays the effect on the black panel. */
    private fun showLed(arrival: Boolean = false) {
        GlowLog.d { "act showLed arrival=$arrival pending=${GlowPending.entries.size}" }
        coverSleep = false
        port.cancel(LockTimer.SLEEP_WATCH)
        port.cancel(LockTimer.TAKEOVER)
        port.cancel(LockTimer.TAKEOVER_DIM)
        // The double tap never reports a release; a resolution change needs new mode ids.
        port.resetPanelRate()
        arrivalDue = false
        shield.stopArrival()
        if (GlowPending.isEmpty) {
            port.finishSession()
            return
        }
        listenForSleep(true)
        if (arrival) {
            arriving.value = true
            arrivalSeq.intValue++
        }
        // Arranged while dark (or before focus): the dot stays hidden until we own the bars.
        val focused = port.windowFocused && port.interactive
        settling.value = !focused
        // One UI's bars come up over us until the hand-over; cover them if the user allowed it.
        if (!focused) shield.show()
        // Already lit, so no SCREEN_ON will come to end the settling; if focus doesn't change
        // either, the dot would stay hidden on a black panel.
        if (!focused && port.interactive) port.schedule(LockTimer.SETTLE, SETTLE_FALLBACK_MS)
        // Paint black first, cover the lock screen last, so the first frame is already black.
        port.dressAsLed()
        face.value = Face.LED
        port.coverLockScreen(cover = true)
        // The LED's face covers the lock screen now: a lock screen ready under the LED's window is
        // no longer in front, so the window goes too, the face black in front first so the lift
        // shows no lock screen. Left up, a power press would only wake into the face again, the
        // window back over it, and the fingerprint wouldn't listen (the lock screen covered).
        endLedWindow()
        if (port.resumed) acquireKeepOn()
    }

    /**
     * Let the system lock screen show over us while we stay the top task. The LED comes back
     * when the system's lock-screen timeout turns the screen off. [underLed]: the LED's window
     * stays over it, the lock screen ready under the LED ([readyLockScreen]).
     */
    private fun showLockScreen(auto: Boolean = false, underCover: Boolean = false, underLed: Boolean = false) {
        GlowLog.d { "act showLockScreen auto=$auto underCover=$underCover underLed=$underLed interactive=${port.interactive}" }
        ledArmedForSleep = false
        if (!underLed) endLedWindow()
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
    fun showMessage() {
        val message = GlowPending.message ?: return
        GlowLog.d { "act message pop-up systemPopsUp=${message.systemPopsUp}" }
        if (!message.systemPopsUp) port.showMessage(message.sbn)
    }

    /** [ArrivalMode.MESSAGE]: slide the pop-up away while the rate still holds. */
    fun retractMessage() {
        if (arriving.value) port.dismissMessage()
    }

    /**
     * Let the panel sleep soon, not at the screen timeout (10 min on some phones): hand the lock
     * screen back, whose own short timeout then runs, and hold the display black meanwhile with
     * [GlowShield] (without it the lock screen shows until then). [onScreenOff] takes it from there.
     * With [GlowShield] and nothing else in use (the LED has focus: no shade, no pop-up over it),
     * the phone sleeps at once instead of lit black until that timeout.
     */
    private fun sleepUnderCover() {
        val unused = port.windowFocused
        shield.blackout()
        showLockScreen(underCover = true)
        if (unused && shield.sleepNow()) GlowLog.d { "act asleep at once, under the cover" }
    }

    /** The effect on the black panel has handed over to the dot: drop to the dot's frame rate. */
    fun onArrivalDone() {
        if (!arriving.value) return
        GlowLog.d { "act arrival done" }
        arriving.value = false
        port.dismissMessage()
        if (face.value == Face.LED && !settling.value) port.applyWindow(lowRefresh = true)
        // Do Not Disturb came on while it played: no LED after it. Or the LED lives on One UI's
        // always-on display alone: asleep, it comes for the new message.
        if ((GlowSession.resting || port.aodOnly) && face.value == Face.LED && port.interactive && !ending.value) sleepUnderCover()
        // Laid face down or covered while it played (a stow waits for it): look again.
        if (face.value == Face.LED && !stowed && port.panelHeld) watchPutAway()
    }

    /** The phone was unlocked: get out of the way of the user's apps. */
    private fun goAway() {
        GlowLog.d { "act goAway face=${face.value}" }
        endLedWindow()
        port.cancelTimers()
        if (ending.value || GlowPending.isEmpty) {
            port.finishSession()
            return
        }
        face.value = Face.AWAY
        coverSleep = false
        clearLed()
        // Over the user's apps the lit LED has nothing to wait for. The always-on display's window
        // stays, see-through and skipped by the compositor: drawn while the screen is on, it lets
        // the LED onto the always-on display at the next sleep without a wake.
        shield.removeLit()
        unstow()
        listenForSleep(false)
        ledArmedForSleep = false
        // Nothing resets it while away; the next power press must start clean.
        revealingAfterPower = false
        arrivalDue = false
        shield.stopArrival()
        becomeInvisible()
        port.cancel(LockTimer.SLEEP_WATCH)
        port.moveToBack()
    }

    private fun finishSettling() {
        port.cancel(LockTimer.SETTLE)
        // The Edge Frame's LED may still be going out on its first exhale in the cover: the dot
        // starts its round once it has, in the same spot, so the light never cuts.
        shield.hideWhenDone(::showSettled)
    }

    private fun showSettled() {
        if (destroyed || !settling.value) return
        settling.value = false
        if (face.value == Face.LED) {
            GlowLog.d { "act settled: LED shows" }
            port.applyWindow(lowRefresh = true)
        }
    }

    // Uncover the lock screen first, then go transparent and untouchable. [keepCover]: a black
    // cover is holding the display while the lock screen times out ([sleepUnderCover]).
    private fun becomeInvisible(keepCover: Boolean = false) {
        port.cancel(LockTimer.SETTLE)
        settling.value = false
        port.resetPanelRate()
        arriving.value = false // the dot comes back alone
        port.dismissMessage() // the lock screen lists the original already
        if (!keepCover) shield.hide()
        port.coverLockScreen(cover = false)
        port.dressSeeThrough()
        // The lock screen readied under the LED: the LED's locks stay held.
        if (!ledInWindow.value) releaseKeepOn()
    }

    private fun wakeAfter(delayMs: Long) {
        port.cancel(LockTimer.WAKE)
        port.cancel(LockTimer.VERIFY_WAKE)
        port.schedule(LockTimer.WAKE, delayMs)
    }

    private fun acquireKeepOn() {
        if (!port.panelHeld) port.holdPanel()
        port.holdPocket()
        if (!stowed) watchPutAway()
    }

    private fun releaseKeepOn() {
        if (port.panelHeld) {
            // What is in front next (the lock screen, mostly) runs its own timeout from here: let go
            // bare, the phone would sleep at once, cutting a message's lock screen and its effect.
            if (port.interactive) port.restartTimeout()
            port.releasePanel()
        }
        // Still covered (read elsewhere while in a pocket): the panel stays off until it isn't,
        // rather than lighting up in the pocket.
        port.releasePocket(waitForFar = true)
        stopWatchingPutAway()
    }

    companion object {
        const val SLEEP_WATCH_MS = 50L

        /**
         * The sleep watch while the lock screen waits under the LED: within a frame at 60 Hz, so
         * the press is cancelled before One UI's lock screen has started for sleep, mostly
         * ([openUnderLed]). Costs ~3.5 % of a core over [SLEEP_WATCH_MS], most of it the system
         * answering (measured on the S23).
         */
        const val READY_SLEEP_WATCH_MS = 16L

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

        /** If focus never arrives after a wake into the LED, show it anyway. */
        const val SETTLE_FALLBACK_MS = 600L

        /** Relight rate limit: a burst for quick presses by hand (~1 s per LED ↔ lock screen round trip), then one per refill. */
        const val RELIGHT_BURST = 8
        const val RELIGHT_REFILL_MS = 2_000L

        /** Lock screen dark → LED: the switch is committed ~10 ms after screen-off; small margin. */
        const val RELIGHT_DELAY_MS = 150L

        /** The CPU held while a drain, put away, hands over to the lock screen's timeout and sleeps. */
        const val DRAIN_HOLD_MS = 8_000L

        const val WAKE_VERIFY_MS = 1_200L

        /** [DarkHold]'s own default. */
        const val DARK_HOLD_MS = 2_000L
    }
}
