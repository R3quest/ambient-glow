package app.lumement

import android.view.Display
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * The lock flow on [FakePhone] under whatever the phone and its user throw at it, in any order:
 * messages posted and read, power, taps, lifts, unlocks, the always-on display coming and going,
 * Battery Saver, a low battery, Do Not Disturb, calls, pockets, the shield and the process going,
 * and the minutes passing. Each seed plays one run ([Run]); after every step the promises in
 * claude.md and in [LockFlow]'s comments are checked. Seeded, so a failure plays again exactly:
 * its seed, its events and the phone's log are printed, and it becomes a scenario in [LockFlowTest].
 *
 * The listener is played here as [NotificationWakerService] does it, as far as the lock flow sees:
 * a post to the glow screen on top or a launch, a removal, Do Not Disturb, its relight at a
 * screen-off and its check 4 s after ([LED_CHECK]).
 */
class LockFlowFuzzTest {

    @Before
    fun clean() = reset()

    @After
    fun detach() = reset()

    private fun reset() {
        GlowSession.host?.let(GlowSession::detach)
        GlowPending.entries.clear()
        GlowPending.message = null
        GlowSession.resting = false
        GlowSession.screenTakers.clear()
        GlowSession.bright = BrightStreak()
        GlowSession.aodDue = false
    }

    @Test
    fun theLockFlowKeepsItsPromisesWhateverThePhoneDoes() {
        val failures = mutableListOf<String>()
        // FUZZ_SEED=n plays that seed alone, with its whole trace; FUZZ_SEEDS=n plays more.
        val one = System.getenv("FUZZ_SEED")?.toIntOrNull()
        val seeds = if (one != null) one..one else 1..(System.getenv("FUZZ_SEEDS")?.toIntOrNull() ?: SEEDS)
        for (seed in seeds) {
            reset()
            val failure = Run(seed, whole = one != null).play()
            if (failure != null) failures += failure
            if (failures.size >= 3) break
        }
        assertTrue(failures.joinToString("\n\n========\n\n"), failures.isEmpty())
    }

    /** One seed's run: a phone set up at random, then [EVENTS] events at random. */
    private class Run(private val seed: Int, private val whole: Boolean) {
        private val random = Random(seed)
        private val phone = FakePhone()
        private val trace = mutableListOf<String>()
        private var failure: String? = null
        private var nextKey = 1

        // ---- What the invariants keep track of ----------------------------------------------------

        private var lastStepAt = phone.now
        private var logSeen = 0

        /** The screen held on outside its bright phase since then, or null. */
        private var heldOutsideSince: Long? = null

        /** Screen-on time held by the LED since the user last looked, all read, or the process died. */
        private var heldInStreakMs = 0L

        /** The screen was held on at the last step. */
        private var wasHeld = false

        /** Nothing waiting since then, or null. */
        private var emptySince: Long? = null

        /** One UI ended its always-on display, the LED on it, by itself; nothing that may light the screen has happened since. */
        private var aodDropped = false

        /** A black cover over the always-on display since then, or null. */
        private var coverOverAodSince: Long? = null

        /** The user woke the phone: by then, no black cover over the lock screen. */
        private var userWokeAt: Long? = null

        /** The lit lock screen black under a cover since then, or null. */
        private var blackSince: Long? = null

        /** A new message on the locked phone: by then a glow screen is up for it, or it has been read. */
        private var glowDueBy: Long? = null

        /** A glow screen's timers run while the phone is dark, with nothing else happening. */
        private var darkTimerRuns = 0

        /** The last thing that may rightly start a glow screen's timers: a step other than a wait, or a broadcast. */
        private var eventAt = phone.now

        /** Since [eventAt], a bright phase's end has come long after it, in the dark. */
        private var lateBrightEnd = false

        // ---- The listener, as the lock flow sees it -------------------------------------------------

        private var relightAtDoze = false
        private var relightAfterCall = false

        private fun log(event: String) {
            trace += "${phone.now - START}: $event"
            if (whole) phone.log += "== ${phone.now - START}: $event"
        }

        fun play(): String? {
            setUp()
            phone.onStep = ::check
            phone.onTimerFired = ::darkTimerCheck
            phone.onSystem = ::systemEvent
            for (i in 0 until EVENTS) {
                if (failure != null) break
                step()
                darkTimerRunsCheck()
            }
            if (failure == null) {
                // Then left alone: everything must come to rest.
                log("left alone")
                phone.advance(20 * MINUTE)
                darkTimerRunsCheck()
                // Nothing left to come in the dark: a later poll would not have run yet.
                if (!phone.interactive && phone.timersOf().isNotEmpty()) fail("left alone in the dark, the glow screen's ${phone.timersOf()} still pending")
            }
            return failure?.let { report(it) }
        }

        private fun setUp() {
            phone.shieldOn = random.nextInt(6) != 0
            phone.dozes = random.nextBoolean()
            phone.dozesAfterOffMs = if (phone.dozes && random.nextBoolean()) 100L else null
            phone.aodOnly = phone.dozes && phone.dozesAfterOffMs != null && random.nextBoolean()
            phone.expectsAod = when (random.nextInt(5)) {
                0 -> null
                1 -> !phone.dozes // stale: the switch changed since the last sleep
                else -> phone.dozes
            }
            phone.settings = GlowSettings(
                arrival = ArrivalMode.entries.random(random),
                brightFor = BrightFor.entries.random(random),
                afterBright = if (random.nextInt(4) == 0) AfterBright.OFF else AfterBright.AOD,
            )
            phone.arrivalMs = ARRIVAL_PLAYS_MS
            phone.destroyAfterMs = listOf(16L, 120L, 600L).random(random)
            phone.batterySaver = random.nextInt(8) == 0
            phone.batteryPct = if (random.nextInt(8) == 0) LOW_BATTERY_PCT - 1 else 80
            // The LED's window takes the focus in 0.03-0.2 s on the S23; past 0.3 s GlowShield stops waiting.
            phone.litFocusMs = listOf(30L, 40L, 120L, 200L, 400L).random(random)
            log(
                "set up: shield=${phone.shieldOn} dozes=${phone.dozes} afterOff=${phone.dozesAfterOffMs} aodOnly=${phone.aodOnly} " +
                    "expectsAod=${phone.expectsAod} ${phone.settings.arrival} ${phone.settings.brightFor} ${phone.settings.afterBright} " +
                    "destroyAfter=${phone.destroyAfterMs} saver=${phone.batterySaver} battery=${phone.batteryPct} litFocus=${phone.litFocusMs}",
            )
            // The phone starts lit and locked, the user looking at something else; the first event is often a message.
            phone.pressPower()
            phone.advance(2_000)
            phone.log.clear()
            logSeen = 0
            lastStepAt = phone.now
        }

        private fun step() {
            val dark = !phone.interactive
            // Only a wake with nothing after it is the user's own look at the lock screen.
            val woken = userWokeAt
            userWokeAt = null
            val roll = random.nextInt(100)
            if (roll !in 34..64 && roll < 91) eventHappened()
            when (roll) {
                in 0..11 -> post()
                in 12..15 -> post(update = true)
                in 16..20 -> readOne()
                in 21..23 -> readAll()
                in 24..33 -> {
                    log("power")
                    // On the LED's face too: it opens the lock screen, lit once the fade is over.
                    if (dark) userWakes() else lightMayCome()
                    phone.pressPower()
                }
                in 34..48 -> pass(random.nextLong(30, 800), woken)
                in 49..58 -> pass(random.nextLong(1_000, 8_000), woken)
                in 59..62 -> pass(random.nextLong(20_000, 3 * MINUTE), woken)
                in 63..64 -> pass(random.nextLong(5 * MINUTE, 16 * MINUTE), woken)
                in 65..66 -> {
                    log("tap the LED's window")
                    phone.tapLed()
                }
                67 -> {
                    log("finger on the LED's window")
                    phone.touchLed()
                }
                68 -> {
                    log("tap the LED's face")
                    phone.tapLedFace()
                }
                69 -> {
                    log("touch")
                    phone.touch()
                }
                70, 71 -> if (phone.interactive) {
                    log("unlock")
                    phone.unlock()
                } else {
                    log("fingerprint on the dark phone")
                    userWakes()
                    phone.pressPower()
                    phone.advance(random.nextLong(50, 400))
                    phone.unlock()
                }
                72 -> {
                    log("face down")
                    phone.faceDown()
                }
                73 -> {
                    log("taken out")
                    lightMayCome() // the user's hand: the LED or the lock screen may light
                    phone.takeOut()
                }
                74 -> {
                    log("picked up")
                    lightMayCome() // the user's hand: the LED or the lock screen may light
                    phone.pickUp()
                }
                75 -> {
                    log(if (phone.near) "sensor clears" else "sensor covered")
                    if (phone.near) lightMayCome() // out of the pocket
                    phone.cover(!phone.near)
                }
                in 76..78 -> if (dark) {
                    log("One UI ends its always-on display")
                    // The LED on it goes dark with it. A message's own wake may still be on its way.
                    if (phone.aodBreathing && phone.now - lightMayComeAt > LIGHT_SETTLES_MS) aodDropped = true
                    phone.aodGoes()
                }
                79 -> if (dark) {
                    log("One UI's own doze screen")
                    phone.dozeScreen()
                }
                80 -> {
                    log("doze suspends / resumes")
                    phone.dozeSuspends(phone.displayState == Display.STATE_DOZE)
                }
                81 -> {
                    log("Battery Saver ${if (phone.batterySaver) "off" else "on"}")
                    lightMayCome()
                    phone.batterySaverTurns(!phone.batterySaver)
                }
                82 -> {
                    phone.batteryPct = if ((phone.batteryPct ?: 100) >= LOW_BATTERY_PCT) LOW_BATTERY_PCT - 1 else 80
                    // No broadcast for a level: the phase under way runs out, the next is asked.
                    lowInPhase = GlowSession.bright.takeIf { (phone.batteryPct ?: 100) < LOW_BATTERY_PCT && it.lit(phone.now) }
                    log("battery at ${phone.batteryPct}%")
                }
                83 -> doNotDisturb(!GlowSession.resting)
                84 -> call(!phone.inCall)
                85 -> screenTaker()
                86 -> {
                    log("alarm clock set")
                    phone.alarmRings(next = phone.now + random.nextLong(1_000, 120_000))
                }
                87 -> if (phone.nextAlarmAt != null && phone.nextAlarmAt!! <= phone.now) {
                    log("the alarm clock rings")
                    phone.alarmRings(next = phone.now + 24 * 60 * MINUTE)
                }
                88 -> if (random.nextInt(3) == 0) {
                    if (phone.shieldOn) {
                        log("the shield goes")
                        lightMayCome()
                        phone.shieldGoes()
                    } else {
                        log("the shield comes back")
                        phone.shieldComes()
                    }
                }
                89 -> if (random.nextInt(4) == 0) {
                    log("the process dies")
                    lightMayCome()
                    heldInStreakMs = 0
                    relightAtDoze = false
                    relightAfterCall = false
                    glowDueBy = null // the listener's relight decides again, after the bright phase lost with the process
                    phone.processDies()
                    // The listener reconnects and relights what was waiting.
                    phone.post(RECONNECT, 1_500) { relightIfWaiting() }
                }
                90 -> if (random.nextInt(3) == 0) {
                    phone.dozes = !phone.dozes
                    log("always-on display switched ${if (phone.dozes) "on" else "off"}")
                } else if (dark && phone.aodBreathing) {
                    log("the LED on the always-on display goes out by itself")
                    phone.aodGoesOut()
                }
                else -> pass(random.nextLong(100, 3_000), woken)
            }
        }

        private fun pass(ms: Long, woken: Long?) {
            log("wait $ms ms")
            userWokeAt = woken
            phone.advance(ms)
        }

        /**
         * The user wakes the dark phone. No hand reacts within the few ms the display takes to say
         * the phone went dark ([LockFlow.onDisplayChanged] sees the sleep before the wake).
         */
        private fun userWakes() {
            phone.advance(REACTION_MS)
            lightMayCome()
            userWokeAt = phone.now
        }

        /** Something happened that may rightly light the screen. */
        private fun lightMayCome() {
            aodDropped = false
            lightMayComeAt = phone.now
        }

        private var lightMayComeAt = 0L

        /** The last message's post: its arrival may still be playing. */
        private var postedAt = 0L

        // ---- The listener ---------------------------------------------------------------------------

        private fun post(update: Boolean = false) {
            val key = if (update && GlowPending.entries.isNotEmpty()) GlowPending.entries.random(random).key else "chat-${nextKey++}"
            log("message $key")
            lightMayCome()
            postedAt = phone.now
            heldOutsideSince = null // its arrival holds the screen anew, bright phase or not
            val color = listOf(0xFF25D366.toInt(), 0xFF2AABEE.toInt(), 0xFFE1306C.toInt()).random(random)
            GlowPending.put(PendingGlow(key, "chat", color))
            GlowSession.bright = GlowSession.bright.onMessage(phone.now, phone.settings.brightFor.ms)
            if (!phone.interactive || phone.keyguardLocked) GlowSession.aodDue = true
            val host = GlowSession.host
            if (host != null && !host.isAway) {
                glowDueBy = phone.now + GLOW_DUE_MS
                phone.then { host.onNewMessage() }
                return
            }
            if (phone.interactive && !phone.keyguardLocked) return
            if (phone.inCall) return
            glowDueBy = phone.now + GLOW_DUE_MS
            phone.startGlowScreen(wakeModeFor(arrivalFor(phone.settings.arrival, GlowSession.resting), phone.interactive))
        }

        private fun readOne() {
            val entry = GlowPending.entries.randomOrNull(random) ?: return
            log("read ${entry.key}")
            remove(entry.key)
        }

        private fun readAll() {
            if (GlowPending.isEmpty) return
            log("read all")
            for (entry in GlowPending.entries.toList()) {
                remove(entry.key)
                phone.advance(random.nextLong(0, 30))
            }
        }

        private fun remove(key: String) {
            if (!GlowPending.remove(key)) return
            if (GlowPending.isEmpty) {
                GlowSession.bright = BrightStreak()
                if (GlowSession.host == null) phone.removeLed()
            }
            phone.then { GlowSession.host?.onPendingChanged() }
        }

        private fun doNotDisturb(on: Boolean) {
            log("Do Not Disturb ${if (on) "on" else "off"}")
            lightMayCome()
            GlowSession.resting = on
            val host = GlowSession.host
            if (host != null && !host.isAway) {
                phone.then { host.onRestChanged() }
            } else if (!on) {
                phone.then { relightIfWaiting() }
            }
        }

        private fun call(on: Boolean) {
            log("call ${if (on) "starts" else "ends"}")
            lightMayCome()
            // A ringing call posts a notification that wants the screen.
            if (on) GlowSession.screenTakers += "call" else GlowSession.screenTakers -= "call"
            phone.then { GlowSession.host?.onScreenTakersChanged() }
            phone.callTurns(on)
            if (!on && relightAfterCall) {
                relightAfterCall = false
                phone.then { relightIfWaiting() }
            }
        }

        private fun screenTaker() {
            val up = "alarm" !in GlowSession.screenTakers
            log("a post that wants the screen ${if (up) "comes" else "goes"}")
            lightMayCome()
            if (up) GlowSession.screenTakers += "alarm" else GlowSession.screenTakers -= "alarm"
            phone.then { GlowSession.host?.onScreenTakersChanged() }
        }

        /** The listener's screen receiver and its sleep watch ([NotificationWakerService.watchSleep]). */
        private fun systemEvent(event: String) {
            eventHappened()
            when (event) {
                "screen-off", "screen-on" -> {
                    phone.unpost(RELIGHT)
                    phone.unpost(LED_CHECK)
                    val screenOff = event == "screen-off"
                    val relights = screenOff && !GlowPending.isEmpty && !GlowSession.resting
                    val away = GlowSession.host?.isAway != false
                    relightAtDoze = relights && away && phone.expectsAod != false
                    if (relights) {
                        if (away && !relightAtDoze) phone.post(RELIGHT, 400) { relightIfWaiting() }
                        phone.post(LED_CHECK, 4_000) {
                            val host = GlowSession.host
                            if (host != null && !host.isAway) host.ensureLed() else relightIfWaiting()
                        }
                    }
                    if (screenOff) noteSleep()
                }
                "display" -> if (!phone.interactive) noteSleep()
            }
        }

        private fun noteSleep() {
            if (!dozing(phone.displayState) || !relightAtDoze) return
            relightAtDoze = false
            phone.unpost(RELIGHT)
            phone.post(RELIGHT, 0) { relightIfWaiting() }
        }

        private fun relightIfWaiting() {
            val plan = relightPlan(
                screenOn = phone.interactive,
                waiting = !GlowPending.isEmpty,
                resting = GlowSession.resting,
                hostOnTop = GlowSession.host?.isAway == false,
                inCall = phone.inCall,
                locked = phone.keyguardLocked,
            )
            when (plan) {
                Relight.NONE, Relight.AFTER_LOCK -> return
                Relight.AFTER_CALL -> {
                    relightAfterCall = true
                    return
                }
                Relight.NOW -> Unit
            }
            val onAod = !phone.interactive && dozing(phone.displayState)
            if (onAod) {
                if (phone.settings.afterBright != AfterBright.AOD || phone.aodOnly) return
            } else if (!litLedAllowed(phone.now, GlowSession.bright, { phone.batterySaver }, { phone.batteryPct })) {
                return
            }
            log("listener relights${if (onAod) " onto the always-on display" else ""}")
            phone.startGlowScreen(if (onAod) WakeMode.AOD else WakeMode.LED)
        }

        // ---- The promises ----------------------------------------------------------------------------

        private fun fail(what: String) {
            if (failure != null) return
            failure = "$what (at ${phone.now - START} ms)"
            phone.log += "!! $what"
        }

        private val brightNow: Boolean
            get() = !GlowSession.resting && litLedAllowed(phone.now, GlowSession.bright, { phone.batterySaver }, { batteryAsSeen })

        /** The bright phase the battery went low in, if it is still the one under way. */
        private var lowInPhase: BrightStreak? = null

        /**
         * The battery level as the lock flow can know it: it is asked at events, not watched (no
         * broadcast for a level), so a drop below [LOW_BATTERY_PCT] mid-phase ends the next one.
         */
        private val batteryAsSeen: Int?
            get() = if (lowInPhase?.let { it.until == GlowSession.bright.until && it.lit(phone.now) } == true) 80 else phone.batteryPct

        /** After every step of the phone. */
        private fun check() {
            if (failure != null) return
            val now = phone.now
            val dt = now - lastStepAt
            lastStepAt = now
            phone.broken.firstOrNull()?.let { fail("FakePhone: $it") }

            val newLog = phone.log.subList(logSeen, phone.log.size).toList()
            logSeen = phone.log.size
            if (newLog.any { it.startsWith("diary: peek") } || newLog.contains("move to back") || GlowPending.isEmpty) heldInStreakMs = 0

            // The screen held on: only in the bright phase, a breath or an arrival past it at most.
            val held = phone.anyPanelLocked && phone.interactive
            // Held since the last step, not only from now.
            if (held && wasHeld) heldInStreakMs += dt
            wasHeld = held
            if (held && !brightNow) {
                val since = heldOutsideSince ?: now.also { heldOutsideSince = it }
                if (now - since > HELD_GRACE_MS) fail("the screen held on ${now - since} ms outside the bright phase (saver=${phone.batterySaver} battery=${phone.batteryPct} resting=${GlowSession.resting} bright=${GlowSession.bright})")
            } else {
                heldOutsideSince = null
            }
            if (heldInStreakMs > BRIGHT_STREAK_MS + HELD_GRACE_MS) fail("the screen held on ${heldInStreakMs / 1000} s in one unread streak")

            // One UI ended its always-on display: the LED never lights the screen in its place.
            if (aodDropped && ("wake" in newLog || newLog.any { it.startsWith("full-screen intent") })) {
                fail("the screen lit after One UI ended its always-on display")
            }

            // No black cover over the always-on display.
            if (phone.aodBreathing && phone.coverUp && !phone.interactive) {
                val since = coverOverAodSince ?: now.also { coverOverAodSince = it }
                if (now - since > 500) fail("a black cover over the LED on the always-on display")
            } else {
                coverOverAodSince = null
            }

            // Woken by the user to the lock screen: not a black one. Black for a moment is the LED's
            // own sleep under cover, on its way down.
            val f = phone.flow
            val black = phone.interactive && phone.glowScreenUp && f.face.value == Face.LOCK_SCREEN && !f.ledInWindow.value && phone.coverUp
            if (!black) blackSince = null else if (blackSince == null) blackSince = now
            userWokeAt?.let { at ->
                if (now - at >= WOKEN_LOOK_MS) {
                    userWokeAt = null
                    // A message's arrival on black, then its sleep under cover, is no wake to the lock screen.
                    if (now - postedAt < WOKEN_LOOK_MS + ARRIVAL_PLAYS_MS + LIGHT_SETTLES_MS) return@let
                    // All read: the session's last sleep, under cover; the all-read check has the rest.
                    if (GlowPending.isEmpty) return@let
                    if (black && now - blackSince!! >= BLACK_GRACE_MS) fail("woken by the user to a black lock screen")
                }
            }

            // All read: the glow screen finishes, and lets go of everything.
            if (GlowPending.isEmpty) {
                val since = emptySince ?: now.also { emptySince = it }
                if (now - since > ALL_READ_MS) {
                    if (phone.glowScreenUp) fail("all read ${(now - since) / 1000} s ago and the glow screen is still up (face=${phone.flow.face.value})")
                    if (phone.anyPanelLocked) fail("all read and the screen still held on")
                    if (phone.aodBreathing || phone.litShown) fail("all read and the LED still shows (aod=${phone.aodBreathing} lit=${phone.litShown})")
                }
            } else {
                emptySince = null
            }

            // A new message on the locked phone has a glow screen for it.
            glowDueBy?.let { by ->
                if (now >= by) {
                    glowDueBy = null
                    if (!GlowPending.isEmpty && !phone.glowScreenUp) fail("a new message, and no glow screen up for it")
                }
            }
            // Read, or unlocked (the user's apps show it): no glow screen owed.
            if (GlowPending.isEmpty || !phone.keyguardLocked) glowDueBy = null

            // Every CPU hold has a timeout, and a short one.
            if (phone.cpuHeldMs > MAX_CPU_HOLD_MS) fail("the CPU held for ${phone.cpuHeldMs} ms at once")
            phone.cpuHeldMs = 0
        }

        private fun eventHappened() {
            eventAt = phone.now
            lateBrightEnd = false
        }

        /**
         * No polling in the dark: once [QUIET_MS] have passed since anything happened, a glow
         * screen's timers are done, but for a bright phase's end that was due, once.
         */
        private fun darkTimerCheck(timer: LockTimer) {
            if (phone.interactive) return
            darkTimerRuns++
            val quiet = phone.now - eventAt
            if (quiet <= QUIET_MS) return
            if (timer != LockTimer.BRIGHT_END || lateBrightEnd) fail("a glow screen's $timer ran in the dark ${quiet / 1000} s after anything happened")
            lateBrightEnd = true
        }

        /** No polling in the dark: a run of timers with the phone dark and nothing else happening. */
        private fun darkTimerRunsCheck() {
            if (darkTimerRuns > MAX_DARK_TIMER_RUNS) fail("$darkTimerRuns glow screen timers ran in the dark without an event")
            darkTimerRuns = 0
        }

        private fun report(what: String): String = buildString {
            appendLine("seed $seed: $what")
            appendLine("events (ms from the start):")
            trace.takeLast(if (whole) Int.MAX_VALUE else 60).forEach { appendLine("  $it") }
            appendLine("the phone's log${if (whole) "" else ", last 80"}:")
            phone.log.takeLast(if (whole) Int.MAX_VALUE else 80).forEach { appendLine("  $it") }
        }
    }

    private companion object {
        /** Kept to a couple of seconds; FUZZ_SEEDS looks harder (300 000 passed on 2026-10-18). */
        const val SEEDS = 1_000
        const val EVENTS = 160
        const val MINUTE = 60_000L
        const val START = 1_000_000L

        /** A breath, an arrival, a sleep under cover: how long past its phase the LED may still hold the screen. */
        const val HELD_GRACE_MS = 10_000L

        /** Read while the LED was lit: its last breath, the lock screen's own timeout, the screen-off. */
        const val ALL_READ_MS = 45_000L

        /** How long after the user's wake the lock screen must show. */
        const val WOKEN_LOOK_MS = 1_500L

        /** A sleep under the black cover starts at once ([LockFlow.sleepUnderCover]). */
        const val BLACK_GRACE_MS = 500L

        /** [LockFlow.DRAIN_HOLD_MS] is the longest. */
        const val MAX_CPU_HOLD_MS = 10_000L

        const val MAX_DARK_TIMER_RUNS = 40

        /** The longest a glow screen's timers may run on in the dark after an event: a put-away's [COVERED_MS] and a breath. */
        const val QUIET_MS = 30_000L

        /** The quickest a press follows the phone going dark: longer than FakePhone's display event lag. */
        const val REACTION_MS = 20L

        const val ARRIVAL_PLAYS_MS = 2_500L

        /** A launch's wake, or one put off by the rate limit. */
        const val LIGHT_SETTLES_MS = 3_000L

        /** A launch and its create, or a message to the glow screen up. */
        const val GLOW_DUE_MS = 2_000L

        const val RELIGHT = "listener relight"
        const val LED_CHECK = "listener check"
        const val RECONNECT = "listener reconnect"
    }
}
