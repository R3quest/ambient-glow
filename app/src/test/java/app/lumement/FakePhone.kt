package app.lumement

import android.service.notification.StatusBarNotification
import android.view.Display

/**
 * A phone for [LockFlow] in a JVM test: a clock, the system's answers to what the flow does, and
 * [GlowShield]. Times are One UI's as measured on the S23 (the wait for more presses, the sleep
 * fade, SCREEN_OFF's lag), so a scenario plays out in the order it does on the phone. Everything
 * the flow does is in [log], in order.
 *
 * Each glow screen is an activity of its own ([Screen]): its window, its wake locks, its timers
 * and its receivers. One that has finished is destroyed a moment later ([destroyAfterMs]), and a
 * launch meanwhile starts a new one, as Android never reuses a finishing activity.
 */
internal class FakePhone(relightLimit: RelightLimiter = RelightLimiter(LockFlow.RELIGHT_BURST, LockFlow.RELIGHT_REFILL_MS)) : ShieldPort {
    val log = mutableListOf<String>()

    var now = 1_000_000L
        private set

    private class Task(val at: Long, val order: Int, val owner: Screen?, val timer: LockTimer?, val tag: String?, val run: () -> Unit)

    private val tasks = mutableListOf<Task>()
    private var order = 0

    // ---- The glow screens ---------------------------------------------------------------------

    /** StowWatch's state, as the flow left it. */
    enum class Watch { OFF, LIT, STOWED }

    /** One glow screen: an activity instance, and the [LockPort] it gives its [flow]. */
    inner class Screen(limit: RelightLimiter) : LockPort {
        val flow = LockFlow(this, this@FakePhone, limit)

        /** onCreate has run: [LockFlow.create]. */
        var created = false
        var covering = false
        var finished = false
        var destroyed = false
        var panelLocked = false
        var pocketLocked = false
        var listeningToDisplay = false
        var watching = Watch.OFF
        var afterCallArmed = false
        override var windowFocused = false
        override var resumed = false

        /** What the collector last saw ([settle]). */
        var seen: List<Any> = emptyList()

        /** The arrival whose end is due ([arrivalMs]). */
        var arrivalDueFor = -1

        override val interactive get() = this@FakePhone.interactive
        override val keyguardLocked get() = this@FakePhone.keyguardLocked
        override val inCall get() = this@FakePhone.inCall
        override val displayState get() = this@FakePhone.displayState
        override val elapsedMs get() = now
        override val wallMs get() = now
        override val nextAlarmAt get() = this@FakePhone.nextAlarmAt
        override val expectsAod get() = this@FakePhone.expectsAod
        override val aodOnly get() = this@FakePhone.aodOnly
        override val batterySaver get() = this@FakePhone.batterySaver
        override val batteryPct get() = this@FakePhone.batteryPct
        override val barsHideMs = 360L

        override fun loadSettings() = settings

        override fun schedule(timer: LockTimer, delayMs: Long) = later(delayMs, this, timer) {
            onTimerFired(timer)
            flow.onTimer(timer)
        }
        override fun cancel(timer: LockTimer) {
            tasks.removeAll { it.owner === this && it.timer == timer }
        }

        override fun cancelTimers() {
            tasks.removeAll { it.owner === this && it.timer != null }
        }

        /** A wake lock that causes a wakeup wakes the phone within the call: interactive when it returns. */
        override fun wakePanel() {
            if (this@FakePhone.interactive) return
            log += "wake"
            wakeUp()
        }

        override fun restartTimeout() {
            log += "restart timeout"
            userActivity()
        }

        override fun holdCpu(ms: Long) {
            cpuHeldMs = maxOf(cpuHeldMs, ms)
        }

        override val panelHeld: Boolean get() = panelLocked

        override fun holdPanel() {
            panelLocked = true
            log += "hold panel"
        }

        override fun releasePanel() {
            panelLocked = false
            log += "release panel"
            checkTimeout()
        }

        override fun holdPocket() {
            pocketLocked = true
            proximity()
        }

        override fun releasePocket(waitForFar: Boolean) {
            if (!pocketLocked) return
            pocketLocked = false
            if (waitForFar && near) pocketLingers = true
            proximity()
        }

        override fun coverLockScreen(cover: Boolean) {
            if (covering == cover) return
            covering = cover
            log += if (cover) "cover" else "uncover"
            // The lock screen in front: its own, shorter timeout may have run out already.
            if (!cover) later(0) { checkTimeout() }
        }

        override fun dressAsLed() = Unit
        override fun dressSeeThrough() = Unit
        override fun clearKeepScreenOn() = Unit
        override fun hideBars() = Unit
        override fun applyWindow(lowRefresh: Boolean) = Unit
        override fun resetPanelRate() = Unit
        override fun armTurnScreenOn(armed: Boolean) = Unit

        override fun listenForDisplay(listen: Boolean) {
            listeningToDisplay = listen
        }

        override fun finishSession() {
            if (finished) return
            finished = true
            log += "finish"
            later(destroyAfterMs) { destroy() }
        }

        /** onDestroy: the flow's, then the receivers go. The Handler stays: a timer left on it would still run. */
        fun destroy() {
            if (destroyed) return
            flow.destroy()
            destroyed = true
            screens.remove(this)
            if (panelLocked || pocketLocked) broken += "$now: a wake lock left held by a destroyed glow screen"
            if (listeningToDisplay) broken += "$now: display listener left registered by a destroyed glow screen"
            if (watching != Watch.OFF) broken += "$now: sensors left watching ($watching) by a destroyed glow screen"
            if (afterCallArmed) broken += "$now: the call watch left armed by a destroyed glow screen"
            val left = timersOf(this)
            if (left.isNotEmpty()) broken += "$now: timers left pending by a destroyed glow screen: $left"
        }

        override fun moveToBack() {
            log += "move to back"
        }

        override fun watchLit() {
            watching = Watch.LIT
        }

        override fun stopWatchingLit() {
            if (watching == Watch.LIT) watching = Watch.OFF
        }

        override fun watchStowed() {
            watching = Watch.STOWED
        }

        override fun stopStowWatch() {
            watching = Watch.OFF
        }

        override fun armAfterCall() {
            afterCallArmed = true
        }

        override fun cancelAfterCall() {
            afterCallArmed = false
        }

        override fun dismissBridge() = Unit
        override fun dismissMessage() = Unit
        override fun showMessage(sbn: StatusBarNotification) = Unit

        override fun note(event: String, detail: String) {
            log += "diary: $event${if (detail.isEmpty()) "" else " $detail"}"
        }

        override fun launchFromBackground(mode: WakeMode, color: Int): Boolean {
            log += "full-screen intent $mode"
            return true
        }
    }

    /** The newest glow screen: the one a launch created last, maybe not up yet. */
    var screen = Screen(relightLimit)
        private set

    /** Glow screens created and not destroyed yet, oldest first. */
    private val screens = mutableListOf<Screen>()

    /** Every glow screen there has been: a wake lock outlives its activity. */
    private val everScreens = mutableListOf(screen)

    val flow: LockFlow get() = screen.flow
    val covering: Boolean get() = screen.covering
    val finished: Boolean get() = screen.finished
    val panelLocked: Boolean get() = screen.panelLocked
    val pocketLocked: Boolean get() = screen.pocketLocked
    val listeningToDisplay: Boolean get() = screen.listeningToDisplay

    /** Any glow screen, alive or gone, holds the screen on. */
    val anyPanelLocked: Boolean get() = everScreens.any { it.panelLocked }

    /** How long after finishing a glow screen is destroyed. */
    var destroyAfterMs = FOCUS_MS

    /** Set: an arrival on the black panel ends ([LockFlow.onArrivalDone]) this long after it starts playing, as the glow screen's effect does. */
    var arrivalMs: Long? = null

    /** The longest CPU hold asked for since this was last cleared. */
    var cpuHeldMs = 0L

    /** The system's broadcasts and display changes, for a test's listener: "screen-off", "screen-on", "display". */
    var onSystem: (String) -> Unit = {}

    /** After every step, once the window manager and the collector have had their say. */
    var onStep: () -> Unit = {}

    /** A glow screen's timer is running. */
    var onTimerFired: (LockTimer) -> Unit = {}

    // ---- The phone's state --------------------------------------------------------------------

    var interactive = true
        private set
    var keyguardLocked = true
    var inCall = false
    var displayState: Int? = Display.STATE_ON
        private set
    var nextAlarmAt: Long? = null
    var expectsAod: Boolean? = false
    var aodOnly = false
    var batterySaver = false
    var batteryPct: Int? = 80

    /** The look and arrival the glow screen loads. */
    var settings = GlowSettings()

    /** Where a sleep goes: the always-on display, or off. */
    var dozes = false

    /**
     * One UI's "Show for new notifications": the display goes off first, and the always-on display
     * comes this long after SCREEN_OFF (measured on the S23: ~0.1 s); null: it dozes at once.
     */
    var dozesAfterOffMs: Long? = null

    /** Something covers the proximity sensor (a pocket, a hand). */
    var near = false
        private set

    /** A pocket lock let go of while covered, waiting for the sensor to clear. */
    private var pocketLingers = false

    /** A sleep has started (power, or the shield's) and not finished or been cancelled. */
    private var goingToSleep = false

    /** Sleeps started so far: the one under way is the last. */
    private var sleeps = 0

    /** The last user activity (a wake, a touch, [restartTimeout]): the screen's timeout runs from it. */
    private var lastActivity = now

    // ---- GlowShield ---------------------------------------------------------------------------

    var shieldOn = true

    /** A black cover of GlowShield's is up ([show], [blackout], [dimIn]) and not taken down yet. */
    var coverUp = false
        private set
    var litShown = false
        private set
    var aodBreathing = false
        private set

    /** The always-on display's window is up and drawn ([prepareLed]). */
    private var aodReady = false
    private var litTouch: (() -> Unit)? = null
    private var litTap: (() -> Unit)? = null

    // ---- Driving it ---------------------------------------------------------------------------

    /** Runs whatever falls due in the next [ms], in order. */
    fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = tasks.filter { it.at <= until }.minWithOrNull(compareBy<Task>({ it.at }, { it.order })) ?: break
            tasks.remove(next)
            now = next.at
            next.run()
            settle()
        }
        now = until
    }

    /** As the glow screen does: asked to start in [mode] (a launch), then [ms] of the phone. */
    fun launch(mode: WakeMode, ms: Long = 3_000) {
        startGlowScreen(mode)
        advance(ms)
    }

    /**
     * The listener's launch ([GlowLauncher]): a glow screen up and not finishing gets the new
     * intent; otherwise a new one is created.
     */
    fun startGlowScreen(mode: WakeMode) {
        val live = screen.takeIf { it.created && !it.finished && !it.destroyed }
        if (live != null) {
            log += "new intent $mode"
            live.flow.start(mode)
        } else {
            if (screen.created || screen.destroyed) {
                screen = Screen(RelightLimiter(LockFlow.RELIGHT_BURST, LockFlow.RELIGHT_REFILL_MS))
                everScreens += screen
            }
            log += "glow screen $mode"
            screen.created = true
            screens += screen
            screen.flow.create(mode)
        }
        settle()
    }

    /** A glow screen is up, and not finishing: the listener's [GlowSession.host] should be it. */
    val glowScreenUp: Boolean get() = screen.created && !screen.finished && !screen.destroyed

    /** The user presses power: after One UI's wait for more presses, a lit phone starts to sleep; a dark one wakes. */
    fun pressPower() {
        if (interactive) {
            later(MULTI_PRESS_WAIT_MS) { goToSleep() }
        } else {
            later(0) { wakeUp() }
        }
    }

    /** The user unlocks (fingerprint): USER_PRESENT, and the lock screen goes. */
    fun unlock() {
        keyguardLocked = false
        for (s in screens.toList()) s.flow.onUnlocked()
        settle()
    }

    /** A touch on the lit phone that isn't the LED: the lock screen's timeout starts over. */
    fun touch() {
        if (interactive) userActivity()
    }

    fun touchLed() {
        userActivity()
        litTouch?.invoke()
    }

    fun tapLed() {
        userActivity()
        litTap?.also { litTap = null }?.invoke()
        settle()
    }

    /** A tap (or back) on the LED's own face: the glow screen hears it only while resumed. */
    fun tapLedFace() {
        val s = screen
        if (!s.resumed || s.flow.face.value != Face.LED) return
        userActivity()
        s.flow.onUserDismiss()
        settle()
    }

    /** The system's next alarm clock rang ([dueAt]) and the next one is [next]. */
    fun alarmRings(next: Long?) {
        nextAlarmAt = next
        for (s in screens.toList()) s.flow.onNextAlarmChanged()
        settle()
    }

    /** As the listener does, for an event. */
    fun host(event: GlowSession.Host.() -> Unit) {
        flow.event()
        settle()
    }

    /** Something of the test's own (the listener's calls, to whichever host it has), and what follows it. */
    fun then(event: () -> Unit) {
        event()
        settle()
    }

    /** Battery Saver turned on or off: the glow screen's receiver hears. */
    fun batterySaverTurns(on: Boolean) {
        batterySaver = on
        for (s in screens.toList()) s.flow.onBatterySaverChanged()
        settle()
    }

    /** A call starts or ends (the audio mode); at its end a glow screen's call watch fires ([AfterCall]). */
    fun callTurns(on: Boolean) {
        inCall = on
        if (!on) {
            for (s in screens.toList()) {
                if (!s.afterCallArmed) continue
                s.afterCallArmed = false
                s.flow.callOver()
            }
        }
        settle()
    }

    /** The proximity sensor is covered or clears: with a pocket lock held, the panel goes off while the phone stays awake. */
    fun cover(covered: Boolean) {
        near = covered
        proximity()
        settle()
    }

    /** StowWatch sees the lit LED laid face down and still. */
    fun faceDown() {
        if (screen.watching != Watch.LIT) return
        log += "system: face down"
        screen.flow.stow()
        settle()
    }

    /** StowWatch sees the phone, put away, taken out. */
    fun takeOut() {
        if (screen.watching != Watch.STOWED) return
        log += "system: taken out"
        screen.flow.takenOut()
        settle()
    }

    /** The pick-up gesture while the LED is lit and watched. */
    fun pickUp() {
        if (screen.watching != Watch.LIT) return
        screen.flow.pickedUp()
        settle()
    }

    /** The dozing panel holds its frame by itself (DOZE_SUSPEND), or wakes to take one (DOZE). */
    fun dozeSuspends(suspend: Boolean) {
        val from = if (suspend) Display.STATE_DOZE else Display.STATE_DOZE_SUSPEND
        if (interactive || displayState != from) return
        displayState = if (suspend) Display.STATE_DOZE_SUSPEND else Display.STATE_DOZE
        displayChanged()
        settle()
    }

    /**
     * The process dies (an update, a crash, the system short of memory): every glow screen, its
     * wake locks and timers, and GlowShield's windows go with it, with no callbacks. What is kept
     * in memory only is gone too ([GlowSession.bright]); [GlowPending] is restored from its file.
     */
    fun processDies() {
        log += "system: process died"
        for (s in everScreens) {
            s.destroyed = true
            s.panelLocked = false
            s.pocketLocked = false
            s.listeningToDisplay = false
            s.watching = Watch.OFF
            s.afterCallArmed = false
            s.resumed = false
            s.windowFocused = false
            GlowSession.detach(s.flow)
        }
        tasks.removeAll { it.owner != null || it.tag != null }
        screens.clear()
        pocketLingers = false
        litShown = false
        aodBreathing = false
        aodReady = false
        coverUp = false
        litTouch = null
        litTap = null
        GlowSession.bright = BrightStreak()
        proximity()
    }

    /** A task of the test's own on the main thread, as the listener's Handler posts; [tag] to take it back. */
    fun post(tag: String, ms: Long, run: () -> Unit) {
        tasks += Task(now + ms, order++, null, null, tag) {
            run()
        }
    }

    fun unpost(tag: String) {
        tasks.removeAll { it.tag == tag }
    }

    /** Timers a glow screen has pending, by name. */
    fun timersOf(s: Screen = screen): List<LockTimer> = tasks.filter { it.owner === s && it.timer != null }.mapNotNull { it.timer }

    // ---- The system's side ----------------------------------------------------------------------

    private fun later(ms: Long, owner: Screen? = null, timer: LockTimer? = null, run: () -> Unit) {
        tasks += Task(now + ms, order++, owner, timer, null, run)
    }

    /** Going to sleep: no longer interactive at once, the panel fades, then off (or dozing), then SCREEN_OFF. */
    private fun goToSleep() {
        if (!interactive) return
        log += "system: going to sleep"
        interactive = false
        goingToSleep = true
        // A sleep cancelled in its fade is over: its fade and SCREEN_OFF never come in a later one.
        val sleep = ++sleeps
        displayChanged()
        later(SLEEP_FADE_MS) {
            if (!goingToSleep || sleep != sleeps) return@later
            val lag = dozesAfterOffMs
            // Covered, the always-on display stays off.
            displayState = if (dozes && lag == null && !near) Display.STATE_DOZE else Display.STATE_OFF
            displayChanged()
            later(SCREEN_OFF_LAG_MS) {
                if (!goingToSleep || sleep != sleeps) return@later
                goingToSleep = false
                // The power key locks at once.
                keyguardLocked = true
                log += "system: SCREEN_OFF"
                for (s in screens.toList()) s.flow.onScreenOff()
                onSystem("screen-off")
                if (dozes && lag != null) {
                    later(lag) {
                        if (interactive || near) return@later
                        log += "system: always-on display"
                        displayState = Display.STATE_DOZE
                        displayChanged()
                    }
                }
            }
        }
    }

    private fun wakeUp() {
        if (interactive) return
        val cancelled = goingToSleep
        goingToSleep = false
        interactive = true
        displayState = Display.STATE_ON
        log += if (cancelled) "system: sleep cancelled" else "system: awake"
        userActivity()
        displayChanged()
        proximity()
        // A sleep cancelled in its fade never sent SCREEN_OFF, so no SCREEN_ON either.
        if (!cancelled) {
            later(SCREEN_ON_LAG_MS) {
                log += "system: SCREEN_ON"
                for (s in screens.toList()) s.flow.onScreenOn()
                onSystem("screen-on")
            }
        }
    }

    /** The panel off while awake: a pocket lock held (or let go of, waiting for far) with the sensor covered. */
    private fun proximity() {
        if (!near) pocketLingers = false
        if (!interactive) return
        val off = near && (pocketLingers || everScreens.any { it.pocketLocked })
        val state = if (off) Display.STATE_OFF else Display.STATE_ON
        if (displayState == state) return
        displayState = state
        log += if (off) "system: panel off, covered" else "system: panel on, uncovered"
        displayChanged()
    }

    /**
     * The screen's timeout: the lock screen's own short one while it is in front, the user's
     * setting over the glow screen or unlocked. A screen lock holds it off; let go of after the
     * timeout has run out, the phone sleeps at once (the trap [restartTimeout] is for, measured on
     * the S23).
     */
    private fun timeoutMs() = if ((covering && !finished) || !keyguardLocked) SCREEN_TIMEOUT_MS else LOCK_SCREEN_TIMEOUT_MS

    private fun userActivity() {
        lastActivity = now
        later(LOCK_SCREEN_TIMEOUT_MS) { checkTimeout() }
        later(SCREEN_TIMEOUT_MS) { checkTimeout() }
    }

    private fun checkTimeout() {
        if (!interactive || anyPanelLocked || goingToSleep) return
        if (now - lastActivity < timeoutMs()) return
        log += "system: timeout"
        goToSleep()
    }

    private fun displayChanged() {
        for (s in screens) {
            if (s.listeningToDisplay) later(DISPLAY_EVENT_MS, s) { if (s.listeningToDisplay && !s.destroyed) s.flow.onDisplayChanged() }
        }
        later(DISPLAY_EVENT_MS) { onSystem("display") }
    }

    /** Invariants broken on the way ([settle]); a test fails if any are. */
    val broken = mutableListOf<String>()

    /** What follows every step: the lifecycle and focus the window manager would give, and the glow screen's collector. */
    private fun settle() {
        val top = screen
        for (s in screens.toList()) {
            val inFront = s === top && s.covering && interactive && !s.finished
            if (inFront == s.resumed) continue
            s.resumed = inFront
            if (inFront) {
                log += "system: resumed"
                s.flow.onResume()
                later(FOCUS_MS, s) {
                    if (s.resumed && !s.windowFocused) {
                        s.windowFocused = true
                        s.flow.onFocusChanged(true)
                    }
                }
            } else {
                s.windowFocused = false
                log += "system: paused"
                s.flow.onPause()
            }
        }
        for (s in screens.toList()) {
            val f = s.flow
            // What must never be, whatever happened: checked after every step.
            if (f.face.value == Face.LED && f.ledInWindow.value) broken += "${now}: the LED's face and its window both up"
            if (s.finished && s.panelLocked) broken += "${now}: the screen held on after the session finished"
            if (f.face.value == Face.AWAY && s.panelLocked) broken += "${now}: the screen held on over the unlocked phone"
            if (f.ledInWindow.value && !litShown && !s.finished) broken += "${now}: the LED's window gone from under the glow screen"
            val state = listOf(f.face.value, f.settling.value, f.arriving.value, f.ending.value, f.covered.value)
            if (state != s.seen) {
                s.seen = state
                f.updateLedWindow()
            }
            // The effect on the black panel plays only while it is drawn, and ends by itself.
            val ms = arrivalMs
            val seq = f.arrivalSeq.intValue
            if (ms != null && f.arriving.value && s.resumed && interactive && s.arrivalDueFor != seq) {
                s.arrivalDueFor = seq
                later(ms, s) { if (f.arrivalSeq.intValue == seq && s.resumed) f.onArrivalDone() else s.arrivalDueFor = -1 }
            }
        }
        onStep()
    }

    // ---- ShieldPort ---------------------------------------------------------------------------

    override fun show() {
        if (shieldOn) coverUp = true
    }

    override fun blackout() {
        if (!shieldOn) return
        log += "blackout"
        coverUp = true
    }

    override fun dimIn(durationMs: Long) {
        if (shieldOn) coverUp = true
    }

    override fun hide() {
        coverUp = false
    }

    override fun showLit(colors: () -> List<Int>, round: Int, onTouch: () -> Unit, onTap: () -> Unit): Boolean {
        if (!shieldOn) return false
        litShown = true
        litTouch = onTouch
        litTap = onTap
        log += "lit LED up"
        return true
    }

    override fun focusLockScreen() {
        log += "focus to the lock screen"
    }

    override fun hideLit() {
        if (!litShown) return
        litShown = false
        litTouch = null
        litTap = null
        log += "lit LED down"
    }

    override fun prepareLed() {
        if (shieldOn) aodReady = true
    }

    override fun startAodLed(colors: () -> List<Int>, round: Int): Boolean {
        if (!shieldOn || !aodReady) return false
        aodBreathing = true
        log += "aod LED on"
        return true
    }

    override fun stopAodLed() {
        if (!aodBreathing) return
        aodBreathing = false
        log += "aod LED off"
    }

    override fun removeLit() = hideLit()
    override fun removeLed() {
        hideLit()
        stopAodLed()
        aodReady = false
    }

    override fun hideWhenDone(onGone: () -> Unit) {
        coverUp = false
        onGone()
    }

    override fun playArrival(color: Int) {
        if (shieldOn) log += "arrival effect"
    }

    override fun stopArrival() = Unit

    override fun sleepNow(): Boolean {
        if (!shieldOn) return false
        log += "sleep now"
        later(0) { goToSleep() }
        return true
    }

    /**
     * One UI's own short doze screen that isn't its always-on display: a missed fingerprint on a dark
     * phone (DOZE_MOD on the S23), then off again. To an app it is a doze like any other.
     */
    fun dozeScreen() {
        if (interactive) return
        log += "system: doze screen"
        displayState = Display.STATE_DOZE
        displayChanged()
        settle()
    }

    /** One UI ends its always-on display by itself (low light, its timeout): the panel goes off, the phone still asleep. */
    fun aodGoes() {
        if (interactive) return
        log += "system: always-on display off"
        displayState = Display.STATE_OFF
        displayChanged()
        settle()
    }

    /** GlowShield is turned off, or unbound by the system: its windows go, and the glow screen hears. */
    fun shieldGoes() {
        shieldOn = false
        litShown = false
        aodBreathing = false
        aodReady = false
        coverUp = false
        GlowSession.host?.onShieldGone()
        settle()
    }

    /** GlowShield is back (turned on again, or rebound): no windows of its own yet. */
    fun shieldComes() {
        shieldOn = true
        settle()
    }

    private companion object {
        /** One UI waits this long after a press on a lit phone for more presses (Emergency SOS). */
        const val MULTI_PRESS_WAIT_MS = 172L
        const val SLEEP_FADE_MS = 300L
        const val SCREEN_OFF_LAG_MS = 250L
        const val SCREEN_ON_LAG_MS = 40L
        const val DISPLAY_EVENT_MS = 5L
        const val FOCUS_MS = 16L

        /** One UI's lock-screen timeout (keyguard activityTimeoutWM), and the user's screen timeout. */
        const val LOCK_SCREEN_TIMEOUT_MS = 5_000L
        const val SCREEN_TIMEOUT_MS = 30_000L
    }
}
