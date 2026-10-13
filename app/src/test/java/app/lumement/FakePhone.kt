package app.lumement

import android.service.notification.StatusBarNotification
import android.view.Display

/**
 * A phone for [LockFlow] in a JVM test: a clock, the system's answers to what the flow does, and
 * [GlowShield]. Times are One UI's as measured on the S23 (the wait for more presses, the sleep
 * fade, SCREEN_OFF's lag), so a scenario plays out in the order it does on the phone. Everything
 * the flow does is in [log], in order.
 */
internal class FakePhone : LockPort, ShieldPort {
    val flow = LockFlow(this, this)
    val log = mutableListOf<String>()

    var now = 1_000_000L
        private set

    private class Task(val at: Long, val order: Int, val timer: LockTimer?, val run: () -> Unit)

    private val tasks = mutableListOf<Task>()
    private var order = 0

    // ---- The phone's state --------------------------------------------------------------------

    override var interactive = true
        private set
    override var keyguardLocked = true
    override var inCall = false
    override var displayState: Int? = Display.STATE_ON
        private set
    override var windowFocused = false
        private set
    override var resumed = false
        private set
    override var nextAlarmAt: Long? = null
    override var expectsAod: Boolean? = false
    override var aodOnly = false

    /** The look and arrival the glow screen loads. */
    var settings = GlowSettings()

    /** Where a sleep goes: the always-on display, or off. */
    var dozes = false

    /**
     * One UI's "Show for new notifications": the display goes off first, and the always-on display
     * comes this long after SCREEN_OFF (measured on the S23: ~0.1 s); null: it dozes at once.
     */
    var dozesAfterOffMs: Long? = null

    /** The glow screen covers the lock screen ([coverLockScreen]). */
    var covering = false
        private set
    var finished = false
        private set
    var panelLocked = false
        private set
    var pocketLocked = false
        private set
    var listeningToDisplay = false
        private set

    /** A sleep has started (power, or the shield's) and not finished or been cancelled. */
    private var goingToSleep = false

    /** The last user activity (a wake, a touch, [restartTimeout]): the screen's timeout runs from it. */
    private var lastActivity = now

    // ---- GlowShield ---------------------------------------------------------------------------

    var shieldOn = true
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
        flow.create(mode)
        settle()
        advance(ms)
    }

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
        flow.onUnlocked()
        settle()
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

    /** The system's next alarm clock rang ([dueAt]) and the next one is [next]. */
    fun alarmRings(next: Long?) {
        nextAlarmAt = next
        flow.onNextAlarmChanged()
        settle()
    }

    /** As the listener does, for an event. */
    fun host(event: GlowSession.Host.() -> Unit) {
        flow.event()
        settle()
    }

    // ---- The system's side ----------------------------------------------------------------------

    private fun later(ms: Long, timer: LockTimer? = null, run: () -> Unit) {
        tasks += Task(now + ms, order++, timer, run)
    }

    /** Going to sleep: no longer interactive at once, the panel fades, then off (or dozing), then SCREEN_OFF. */
    private fun goToSleep() {
        if (!interactive) return
        log += "system: going to sleep"
        interactive = false
        goingToSleep = true
        displayChanged()
        later(SLEEP_FADE_MS) {
            if (!goingToSleep) return@later
            val lag = dozesAfterOffMs
            displayState = if (dozes && lag == null) Display.STATE_DOZE else Display.STATE_OFF
            displayChanged()
            later(SCREEN_OFF_LAG_MS) {
                if (!goingToSleep) return@later
                goingToSleep = false
                log += "system: SCREEN_OFF"
                flow.onScreenOff()
                if (dozes && lag != null) {
                    later(lag) {
                        if (interactive) return@later
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
        // A sleep cancelled in its fade never sent SCREEN_OFF, so no SCREEN_ON either.
        if (!cancelled) {
            later(SCREEN_ON_LAG_MS) {
                log += "system: SCREEN_ON"
                flow.onScreenOn()
            }
        }
    }

    /**
     * The screen's timeout: the lock screen's own short one while it is in front, the user's
     * setting over the glow screen. A screen lock holds it off; let go of after the timeout has
     * run out, the phone sleeps at once (the trap [restartTimeout] is for, measured on the S23).
     */
    private fun timeoutMs() = if (covering && !finished) SCREEN_TIMEOUT_MS else LOCK_SCREEN_TIMEOUT_MS

    private fun userActivity() {
        lastActivity = now
        later(LOCK_SCREEN_TIMEOUT_MS) { checkTimeout() }
        later(SCREEN_TIMEOUT_MS) { checkTimeout() }
    }

    private fun checkTimeout() {
        if (!interactive || panelLocked || goingToSleep) return
        if (now - lastActivity < timeoutMs()) return
        log += "system: timeout"
        goToSleep()
    }

    private fun displayChanged() {
        if (listeningToDisplay) later(DISPLAY_EVENT_MS) { flow.onDisplayChanged() }
    }

    /** Invariants broken on the way ([settle]); a test fails if any are. */
    val broken = mutableListOf<String>()

    /** What follows every step: the lifecycle and focus the window manager would give, and the glow screen's collector. */
    private var seen: List<Any> = emptyList()

    private fun settle() {
        val inFront = covering && interactive && !finished
        if (inFront != resumed) {
            resumed = inFront
            if (inFront) {
                log += "system: resumed"
                flow.onResume()
                later(FOCUS_MS) {
                    if (resumed && !windowFocused) {
                        windowFocused = true
                        flow.onFocusChanged(true)
                    }
                }
            } else {
                windowFocused = false
                log += "system: paused"
                flow.onPause()
            }
        }
        // What must never be, whatever happened: checked after every step.
        if (flow.face.value == Face.LED && flow.ledInWindow.value) broken += "${now}: the LED's face and its window both up"
        if (finished && panelLocked) broken += "${now}: the screen held on after the session finished"
        if (flow.face.value == Face.AWAY && panelLocked) broken += "${now}: the screen held on over the unlocked phone"
        val state = listOf(flow.face.value, flow.settling.value, flow.arriving.value, flow.ending.value, flow.covered.value)
        if (state != seen) {
            seen = state
            flow.updateLedWindow()
        }
    }

    // ---- LockPort -----------------------------------------------------------------------------

    override val elapsedMs: Long get() = now
    override val wallMs: Long get() = now
    override val barsHideMs: Long = 360L

    override fun loadSettings() = settings

    override fun schedule(timer: LockTimer, delayMs: Long) = later(delayMs, timer) { flow.onTimer(timer) }
    override fun cancel(timer: LockTimer) {
        tasks.removeAll { it.timer == timer }
    }

    override fun cancelTimers() {
        tasks.removeAll { it.timer != null }
    }

    /** A wake lock that causes a wakeup wakes the phone within the call: interactive when it returns. */
    override fun wakePanel() {
        if (interactive) return
        log += "wake"
        wakeUp()
    }

    override fun restartTimeout() {
        log += "restart timeout"
        userActivity()
    }

    override fun holdCpu(ms: Long) = Unit

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
    }

    override fun releasePocket(waitForFar: Boolean) {
        pocketLocked = false
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
        later(FOCUS_MS) { flow.destroy() }
    }

    override fun moveToBack() {
        log += "move to back"
    }

    override fun watchLit() = Unit
    override fun stopWatchingLit() = Unit
    override fun watchStowed() = Unit
    override fun stopStowWatch() = Unit
    override fun armAfterCall() = Unit
    override fun cancelAfterCall() = Unit
    override fun dismissBridge() = Unit
    override fun dismissMessage() = Unit
    override fun showMessage(sbn: StatusBarNotification) = Unit

    override fun launchFromBackground(mode: WakeMode, color: Int): Boolean {
        log += "full-screen intent $mode"
        return true
    }

    // ---- ShieldPort ---------------------------------------------------------------------------

    override fun show() = Unit
    override fun blackout() {
        if (shieldOn) log += "blackout"
    }

    override fun dimIn(durationMs: Long) = Unit
    override fun hide() = Unit

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

    override fun hideWhenDone(onGone: () -> Unit) = onGone()

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
        flow.onShieldGone()
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
