package app.lumement

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The lock flow on [FakePhone], scenario by scenario: what the glow screen does today, as seen on
 * the S23. Most are a bug from the device notes, so it can't come back unseen.
 */
class LockFlowTest {

    private lateinit var phone: FakePhone
    private val flow get() = phone.flow

    @Before
    fun clean() {
        GlowPending.entries.clear()
        GlowPending.message = null
        GlowSession.resting = false
        GlowSession.screenTakers.clear()
        GlowSession.bright = BrightStreak()
        phone = FakePhone()
    }

    @After
    fun detach() {
        assertTrue("broken on the way:\n${phone.broken.joinToString("\n")}", phone.broken.isEmpty())
        GlowSession.detach(flow)
        GlowPending.entries.clear()
        GlowSession.bright = BrightStreak()
    }

    /** A new message, as the listener takes it: waiting on the LED, and its bright phase started over. */
    private fun message(key: String = "chat-1") {
        GlowPending.put(PendingGlow(key, "chat", 0xFF25D366.toInt()))
        GlowSession.bright = GlowSession.bright.onMessage(phone.now, phone.settings.brightFor.ms)
    }

    /** A message lit the lock screen, nobody touched it: the lit LED over the lock screen, ready under it. */
    private fun readyUnderLed() {
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        assertTrue("the LED's window", flow.ledInWindow.value)
        phone.log.clear()
    }

    private fun logged(vararg steps: String) {
        var from = 0
        for (step in steps) {
            val at = phone.log.subList(from, phone.log.size).indexOf(step)
            assertTrue("\"$step\" not in order in:\n${phone.log.joinToString("\n")}", at >= 0)
            from += at + 1
        }
    }

    private fun notLogged(step: String) = assertFalse("\"$step\" in:\n${phone.log.joinToString("\n")}", step in phone.log)

    @Test
    fun aMessageEndsWithTheLitLedOverTheReadyLockScreen() {
        message()
        phone.launch(WakeMode.WAKE, 4_000)

        logged("arrival effect", "cover", "hold panel", "lit LED up", "uncover")
        assertEquals("the lock screen in front, under the LED's window", Face.LOCK_SCREEN, flow.face.value)
        assertFalse(phone.covering)
        assertTrue("the screen kept on for the LED", phone.panelLocked)
    }

    @Test
    fun powerOpensTheReadyLockScreenWithoutASleep() {
        readyUnderLed()
        phone.pressPower()
        phone.advance(1_000)

        logged("system: going to sleep", "diary: peek power", "wake", "system: sleep cancelled", "lit LED down")
        notLogged("system: SCREEN_OFF")
        assertTrue(phone.interactive)
        assertFalse(flow.ledInWindow.value)
    }

    /** The timeout trap (2026-10-11): let go bare, the screen's long-expired timeout slept the phone at once. */
    @Test
    fun lettingGoOfTheLedRestartsTheTimeoutFirst() {
        readyUnderLed()
        phone.tapLed()

        logged("lit LED down", "restart timeout", "release panel")
        assertTrue(phone.interactive)
    }

    @Test
    fun readElsewhereUnderTheLedSleepsAtOnceAndEnds() {
        readyUnderLed()
        GlowPending.remove("chat-1")
        phone.host { onPendingChanged() }
        phone.advance(2_000)

        logged("blackout", "lit LED down", "sleep now", "system: going to sleep", "system: SCREEN_OFF", "finish")
        notLogged("wake")
        assertFalse(phone.panelLocked)
    }

    @Test
    fun theShieldGoingCoversTheLockScreenWithTheLedsFace() {
        readyUnderLed()
        phone.shieldGoes()
        phone.advance(1_000)

        assertEquals(Face.LED, flow.face.value)
        assertTrue("the LED's face covers the lock screen", phone.covering)
        assertTrue("its screen lock is the face's now", phone.panelLocked)
        assertFalse(flow.ledInWindow.value)
    }

    @Test
    fun theShieldGoingThenPowerOpensTheLockScreenAsWithoutIt() {
        readyUnderLed()
        phone.shieldGoes()
        phone.advance(1_000)
        phone.log.clear()

        phone.pressPower()
        phone.advance(2_000)

        logged("system: going to sleep", "uncover", "system: SCREEN_OFF", "wake")
        assertEquals(Face.LOCK_SCREEN, flow.face.value)
        assertTrue(phone.interactive)
    }

    /** A ringing call shows as a pop-up under accessibility overlays: the window makes way. */
    @Test
    fun aPostThatWantsTheScreenLiftsTheWindow() {
        readyUnderLed()
        GlowSession.screenTakers += "call"
        phone.host { onScreenTakersChanged() }
        phone.advance(1_000)

        assertFalse(flow.ledInWindow.value)
        assertEquals(Face.LOCK_SCREEN, flow.face.value)
        logged("lit LED down", "restart timeout", "release panel")
    }

    /** Samsung Clock opens its ringing screen itself, under the window (2026-10-12). */
    @Test
    fun anAlarmClockRingingLiftsTheWindow() {
        message()
        phone.nextAlarmAt = phone.now + 60_000
        phone.launch(WakeMode.WAKE, 4_000)
        assertTrue(flow.ledInWindow.value)

        phone.advance(60_000)
        phone.alarmRings(next = phone.now + 24 * 3_600_000L)

        assertFalse("lifted for the alarm's screen", flow.ledInWindow.value)
    }

    @Test
    fun anAlarmSetOrMovedLeavesTheWindow() {
        message()
        phone.nextAlarmAt = phone.now + 3_600_000
        phone.launch(WakeMode.WAKE, 4_000)

        phone.alarmRings(next = phone.now + 7_200_000)

        assertTrue(flow.ledInWindow.value)
    }

    @Test
    fun doNotDisturbUnderTheLedSleepsAndKeepsItDark() {
        readyUnderLed()
        GlowSession.resting = true
        phone.host { onRestChanged() }
        phone.advance(3_000)

        logged("blackout", "sleep now", "system: SCREEN_OFF")
        notLogged("wake")
        assertFalse(phone.interactive)
        assertEquals("the lock screen at the next wake, never the LED", Face.LOCK_SCREEN, flow.face.value)
    }

    @Test
    fun unlockingStepsAwayAndLetsGoOfTheScreen() {
        readyUnderLed()
        phone.unlock()
        phone.advance(500)

        logged("lit LED down", "move to back")
        assertEquals(Face.AWAY, flow.face.value)
        assertFalse(phone.panelLocked)
        assertFalse("the bright phase ends with the unlock", GlowSession.bright.lit(phone.now))
    }

    @Test
    fun aNewMessageUnderTheLedLightsTheLockScreenThenTheLedComesBack() {
        readyUnderLed()
        message("chat-2")
        phone.host { onNewMessage() }

        logged("lit LED down", "arrival effect")
        assertEquals(Face.LOCK_SCREEN, flow.face.value)
        assertFalse(phone.covering)

        phone.advance(4_000)
        assertTrue("taken over again once nobody touched it", flow.ledInWindow.value)
    }

    @Test
    fun whereTheSleepDozesTheLedGoesOnTheAlwaysOnDisplay() {
        phone.settings = GlowSettings(brightFor = BrightFor.OFF) // no bright phase: straight onto it
        phone.expectsAod = true
        phone.dozes = true
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        assertFalse("no takeover: the lock screen sleeps", flow.ledInWindow.value)
        assertEquals(Face.LOCK_SCREEN, flow.face.value)

        phone.pressPower() // or its own timeout
        phone.advance(2_000)
        logged("system: going to sleep", "aod LED on")
        notLogged("wake")
        assertFalse(phone.interactive)

        phone.log.clear()
        phone.pressPower()
        phone.advance(1_000)
        logged("system: awake", "aod LED off")
        assertEquals("power wakes straight into the lock screen", Face.LOCK_SCREEN, flow.face.value)
        assertFalse(phone.covering)
    }

    /**
     * One UI's "Show for new notifications" (2026-10-12): the display goes off first and the
     * always-on display comes ~0.1 s after SCREEN_OFF. The flow relit the lit LED at that
     * SCREEN_OFF, and the always-on display, with the panel holding the LED for next to nothing,
     * never got its turn.
     */
    @Test
    fun shownForNewNotificationsTheLedWaitsForTheAlwaysOnDisplay() {
        phone.settings = GlowSettings(brightFor = BrightFor.OFF)
        phone.expectsAod = true
        phone.dozes = true
        phone.dozesAfterOffMs = 100
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        assertFalse("no takeover: the lock screen sleeps", flow.ledInWindow.value)

        phone.pressPower() // or its own timeout
        phone.advance(2_000)

        logged("system: going to sleep", "system: SCREEN_OFF", "system: always-on display", "aod LED on")
        notLogged("wake")
        assertFalse(phone.interactive)
    }

    /** With no bright phase, an always-on display that never comes leaves the LED dark: no lit LED in its place. */
    @Test
    fun anAlwaysOnDisplayThatNeverComesLeavesTheLedDark() {
        phone.settings = GlowSettings(brightFor = BrightFor.OFF)
        phone.expectsAod = true
        phone.dozes = false
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        phone.pressPower()
        phone.advance(2_000)
        assertFalse("the glow screen waits for a doze that doesn't come", phone.interactive)

        phone.host { ensureLed() } // the listener's check, 4 s after SCREEN_OFF
        phone.advance(2_000)

        notLogged("wake")
        assertFalse(phone.interactive)
        assertFalse(flow.ledInWindow.value)
        assertFalse("no black cover for the next wake", phone.coverUp)
    }

    /**
     * One UI's "Show for new notifications", with no bright phase: the LED goes straight onto its
     * always-on display, as it did before the bright phase (2026-10-17).
     */
    private fun aodForNewNotifications() {
        phone.settings = phone.settings.copy(brightFor = BrightFor.OFF)
        phone.aodOnly = true
        phone.expectsAod = true
        phone.dozes = true
        phone.dozesAfterOffMs = 100
    }

    /**
     * Woken and looked at, the message is no longer new to One UI, and its always-on display stays
     * off at the next sleep. The LED respects that: dark too, no lit LED (user, 2026-10-13).
     */
    @Test
    fun shownForNewNotificationsSeenOnceTheLedStaysDarkAsTheAlwaysOnDisplay() {
        aodForNewNotifications()
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        phone.pressPower()
        phone.advance(2_000)
        assertTrue("on the always-on display", phone.aodBreathing)

        phone.pressPower() // woken: the lock screen, the message seen
        phone.advance(2_000)
        phone.dozes = false // One UI shows no always-on display for a seen message
        phone.log.clear()
        phone.pressPower()
        phone.advance(3_000)
        phone.host { ensureLed() } // the listener's check
        phone.advance(5_000)

        notLogged("wake")
        notLogged("cover")
        assertFalse(phone.interactive)
        assertFalse(flow.ledInWindow.value)
    }

    @Test
    fun shownForNewNotificationsTheAlwaysOnDisplayGoingTakesTheLedWithIt() {
        aodForNewNotifications()
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        phone.pressPower()
        phone.advance(2_000)
        assertTrue(phone.aodBreathing)

        phone.log.clear()
        phone.aodGoes() // One UI ends it (low light, its own timeout)
        phone.advance(3_000)
        phone.host { ensureLed() }
        phone.advance(5_000)

        logged("aod LED off")
        notLogged("wake")
        assertFalse(phone.interactive)
    }

    /**
     * A missed fingerprint on the dark phone (2026-10-13): One UI shows a short doze screen of its
     * own, which isn't the always-on display shown for the message. The LED had gone onto it.
     */
    @Test
    fun shownForNewNotificationsTheLedTakesOnlyThatMessagesAlwaysOnDisplay() {
        aodForNewNotifications()
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        phone.pressPower()
        phone.advance(2_000)
        assertTrue("on the always-on display shown for the message", phone.aodBreathing)

        phone.aodGoes()
        phone.advance(1_000)
        phone.log.clear()
        phone.dozeScreen() // the missed fingerprint's screen
        phone.advance(3_000)

        notLogged("aod LED on")
        assertFalse(phone.aodBreathing)
    }

    @Test
    fun shownForNewNotificationsTheNextMessageBringsTheLedBack() {
        aodForNewNotifications()
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        phone.pressPower()
        phone.advance(2_000)
        phone.aodGoes()
        phone.advance(1_000)

        message("chat-2")
        phone.host { onNewMessage() }
        phone.advance(4_000)
        phone.pressPower() // the lock screen's own sleep
        phone.advance(2_000)

        assertTrue("the new message's always-on display, the LED on it", phone.aodBreathing)
    }

    /** A black arrival ends on the LED's face; shown for new notifications, the phone then sleeps into One UI's always-on display. */
    @Test
    fun shownForNewNotificationsABlackArrivalSleepsIntoTheAlwaysOnDisplay() {
        aodForNewNotifications()
        phone.settings = phone.settings.copy(arrival = ArrivalMode.BLACK)
        message()
        phone.launch(WakeMode.ARRIVAL, 2_000)
        assertEquals(Face.LED, flow.face.value)

        flow.onArrivalDone()
        phone.advance(3_000)

        logged("sleep now", "system: SCREEN_OFF", "system: always-on display", "aod LED on")
        assertFalse(phone.interactive)
        assertFalse("the black cover it slept under is gone from the always-on display", phone.coverUp)
    }

    // ---- The bright phase, then the always-on display or dark (2026-10-17) ----------------------

    /**
     * Bright for its minutes, then the phone sleeps into One UI's always-on display with the LED on
     * it. Tested on the S23 first: One UI still shows it for the message after 10 minutes lit.
     */
    @Test
    fun brightFirstThenTheAlwaysOnDisplay() {
        aodForNewNotifications()
        phone.settings = phone.settings.copy(brightFor = BrightFor.TEN)
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        assertTrue("bright: the lit LED takes the lock screen over", flow.ledInWindow.value)

        phone.advance(9 * MINUTE)
        assertTrue("still bright at 9 minutes", flow.ledInWindow.value)
        phone.log.clear()
        phone.advance(2 * MINUTE)

        logged("blackout", "lit LED down", "release panel", "sleep now", "system: going to sleep", "system: SCREEN_OFF", "system: always-on display", "aod LED on")
        logged("diary: bright-end", "diary: aod-on")
        notLogged("wake")
        assertFalse(phone.interactive)
        assertFalse("no screen lock left", phone.panelLocked)
        assertFalse("no black cover over the always-on display", phone.coverUp)
    }

    @Test
    fun brightFirstThenDarkWithoutAnAlwaysOnDisplay() {
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        assertTrue(flow.ledInWindow.value)

        phone.log.clear()
        phone.advance(10 * MINUTE)
        phone.host { ensureLed() } // the listener's check, 4 s after SCREEN_OFF
        phone.advance(MINUTE)

        logged("blackout", "lit LED down", "sleep now", "system: SCREEN_OFF")
        notLogged("wake")
        assertFalse(phone.interactive)
        assertFalse(phone.panelLocked)
        assertFalse("the next wake shows the lock screen, not black", phone.coverUp)
    }

    @Test
    fun thenOffLeavesOneUisAlwaysOnDisplayWithoutTheLed() {
        aodForNewNotifications()
        phone.settings = phone.settings.copy(brightFor = BrightFor.TEN, afterBright = AfterBright.OFF)
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        phone.advance(11 * MINUTE)

        logged("sleep now", "system: always-on display")
        notLogged("aod LED on")
        assertFalse(phone.interactive)
        assertFalse("One UI's always-on display shows, not a black cover", phone.coverUp)
    }

    /** A busy chat starts the bright phase over and over, up to 30 minutes in all ([BRIGHT_STREAK_MS]). */
    @Test
    fun aBusyChatIsBrightForThirtyMinutesInAll() {
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        for (i in 2..4) { // at 8, 16 and 24 minutes
            phone.advance(8 * MINUTE - 4_000)
            message("chat-$i")
            phone.host { onNewMessage() }
            phone.advance(4_000)
            assertTrue("bright again after message $i", flow.ledInWindow.value)
        }
        phone.advance(5 * MINUTE)
        assertTrue("still bright at 29 minutes", flow.ledInWindow.value)
        phone.advance(2 * MINUTE)
        assertFalse("asleep at 30", phone.interactive)

        phone.log.clear()
        message("chat-5")
        phone.host { onNewMessage() }
        phone.advance(4_000)
        assertFalse("its arrival plays, but the streak's time is spent", flow.ledInWindow.value)
        phone.pressPower() // the lock screen's own sleep
        phone.advance(5_000)

        logged("wake", "arrival effect")
        notLogged("lit LED up")
        assertFalse(phone.interactive)
        assertFalse(phone.panelLocked)
    }

    /**
     * Woken from the LED on the always-on display (a press, a lift) is a look like one on the lit
     * LED: a new streak, so the next message has its whole bright time. It wasn't counted, and a
     * busy chat ran out of bright time sooner (the power-press A/B on the S23, 2026-10-17).
     */
    @Test
    fun wokenFromTheAlwaysOnDisplayStartsANewStreak() {
        aodForNewNotifications()
        phone.settings = phone.settings.copy(brightFor = BrightFor.TEN)
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        phone.advance(11 * MINUTE)
        assertTrue("on the always-on display after the bright phase", phone.aodBreathing)

        phone.log.clear()
        phone.pressPower()
        phone.advance(2_000)

        logged("aod LED off", "diary: peek aod")
        assertEquals("a new streak", BrightStreak(), GlowSession.bright)
    }

    /**
     * One UI drops its always-on display in low light after ~10 minutes (measured on the S23,
     * 2026-10-12), and the flow lit the screen in its place, all night in a dark room.
     */
    @Test
    fun theAlwaysOnDisplayGoingNeverLightsTheScreen() {
        phone.settings = GlowSettings(brightFor = BrightFor.OFF)
        phone.expectsAod = true
        phone.dozes = true
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        phone.pressPower()
        phone.advance(2_000)
        assertTrue(phone.aodBreathing)

        phone.log.clear()
        phone.aodGoes()
        phone.advance(3_000)
        phone.host { ensureLed() }
        phone.advance(5_000)

        logged("aod LED off")
        notLogged("wake")
        assertFalse(phone.interactive)
    }

    @Test
    fun batterySaverKeepsTheLedOffTheScreen() {
        phone.batterySaver = true
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        assertFalse("no takeover", flow.ledInWindow.value)

        phone.pressPower() // the lock screen's own sleep
        phone.advance(5_000)

        notLogged("lit LED up")
        notLogged("wake")
        assertFalse(phone.interactive)
        assertFalse(phone.panelLocked)
    }

    /** Battery Saver switched on while the LED is lit: the bright phase ends then, not at its time. */
    @Test
    fun batterySaverComingOnEndsTheBrightPhase() {
        readyUnderLed()
        phone.batterySaver = true
        flow.onBatterySaverChanged()
        phone.advance(3_000)

        logged("diary: bright-end", "blackout", "lit LED down", "sleep now", "system: SCREEN_OFF")
        assertFalse(phone.interactive)
        assertFalse(phone.panelLocked)
    }

    @Test
    fun batterySaverGoingOffLeavesTheLedAlone() {
        readyUnderLed()
        flow.onBatterySaverChanged()
        phone.advance(3_000)

        assertTrue(flow.ledInWindow.value)
    }

    @Test
    fun aLowBatteryKeepsTheLedOffTheScreen() {
        phone.batteryPct = LOW_BATTERY_PCT - 1
        message()
        phone.launch(WakeMode.WAKE, 4_000)

        assertFalse(flow.ledInWindow.value)
        assertFalse(phone.panelLocked)
    }

    /** With no bright phase, Just the LED still shows its dot for a breath before the phone sleeps. */
    @Test
    fun withNoBrightPhaseJustTheLedShowsABreathThenSleeps() {
        phone.settings = GlowSettings(arrival = ArrivalMode.LED_ONLY, brightFor = BrightFor.OFF)
        message()
        phone.launch(WakeMode.LED, 1_000)
        logged("lit LED up")

        phone.advance(5_000)

        logged("sleep now", "system: SCREEN_OFF")
        assertFalse(phone.interactive)
        assertFalse(phone.panelLocked)
        assertFalse(phone.coverUp)
    }

    /**
     * Just the LED, in Battery Saver or below 20 %: its dot for a breath, then asleep, as with no
     * bright phase. The phase's end was set by its time alone, so the LED's face held the screen
     * on for all of its minutes (found by [LockFlowFuzzTest]).
     */
    @Test
    fun justTheLedOnALowBatteryShowsABreathThenSleeps() {
        for (saver in listOf(false, true)) {
            clean()
            phone.settings = GlowSettings(arrival = ArrivalMode.LED_ONLY, brightFor = BrightFor.TEN)
            if (saver) phone.batterySaver = true else phone.batteryPct = LOW_BATTERY_PCT - 1
            message()
            phone.launch(WakeMode.LED, 1_000)
            logged("lit LED up")

            phone.advance(5_000)

            logged("sleep now", "system: SCREEN_OFF")
            assertFalse("saver=$saver", phone.interactive)
            assertFalse("saver=$saver", phone.panelLocked)
            assertTrue("broken on the way:\n${phone.broken.joinToString("\n")}", phone.broken.isEmpty())
        }
    }

    /**
     * Read elsewhere under the LED, the phone put to sleep at once under a black cover, and power
     * pressed in the sleep's fade: the sleep was cancelled, and the lit lock screen stayed black
     * under the cover until its timeout (found by [LockFlowFuzzTest]).
     */
    @Test
    fun powerInTheFadeOfOurOwnSleepShowsTheLockScreen() {
        readyUnderLed()
        GlowPending.remove("chat-1")
        phone.host { onPendingChanged() }
        phone.advance(100) // asleep at once, fading
        assertFalse(phone.interactive)

        phone.pressPower()
        phone.advance(1_000)

        logged("sleep now", "system: going to sleep", "system: sleep cancelled")
        assertTrue(phone.interactive)
        assertEquals(Face.LOCK_SCREEN, flow.face.value)
        assertFalse("the lock screen, not black", phone.coverUp)
    }

    /**
     * Asleep under the black cover, waiting for an always-on display that never came (the message
     * seen, or the display's switch changed since the last sleep), and woken before the listener's
     * check took the cover down: a black lock screen (found by [LockFlowFuzzTest]).
     */
    @Test
    fun wokenWhileTheLedWaitsForAnAlwaysOnDisplayShowsTheLockScreen() {
        phone.settings = GlowSettings(arrival = ArrivalMode.LED_ONLY, brightFor = BrightFor.OFF)
        phone.expectsAod = true
        phone.dozes = false
        message()
        phone.launch(WakeMode.LED, 1_000)
        phone.advance(4_000) // a breath, then asleep under the cover
        logged("blackout", "sleep now", "system: SCREEN_OFF")
        assertTrue("the cover waits for the doze", phone.coverUp)

        phone.pressPower() // before the listener's check, 4 s after SCREEN_OFF
        phone.advance(1_000)

        assertTrue(phone.interactive)
        assertEquals(Face.LOCK_SCREEN, flow.face.value)
        assertFalse("the lock screen, not black", phone.coverUp)
    }

    @Test
    fun withoutTheShieldTheLedFaceCoversAndPowerOpensTheLockScreen() {
        phone.shieldOn = false
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        assertEquals("taken over by the LED's own face", Face.LED, flow.face.value)
        assertTrue(phone.covering)

        phone.log.clear()
        phone.pressPower()
        phone.advance(2_000)

        logged("system: going to sleep", "uncover", "system: SCREEN_OFF", "wake", "system: SCREEN_ON")
        assertEquals(Face.LOCK_SCREEN, flow.face.value)
        assertTrue(phone.interactive)
    }

    /**
     * A sleep that waited for an always-on display One UI didn't show (the message already seen)
     * kept waiting through the next wake. At the next sleep the panel going off ran its screen-off
     * early: power on the LED's face lit the lock screen before SCREEN_OFF, while One UI's lock
     * screen was still on its way to sleep ([LockFlow.revealAfterPower]).
     */
    @Test
    fun aDozeThatNeverCameIsForgottenOnceAwake() {
        phone.shieldOn = false // the LED's own face, which power opens at SCREEN_OFF
        phone.settings = GlowSettings(brightFor = BrightFor.OFF)
        phone.expectsAod = true
        phone.dozes = false
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        phone.pressPower() // the lock screen's own sleep, waiting for a doze that doesn't come
        phone.advance(2_000)
        assertFalse(phone.interactive)

        phone.settings = phone.settings.copy(brightFor = BrightFor.TEN)
        message("chat-2")
        phone.host { onNewMessage() }
        phone.advance(4_000)
        assertEquals("bright: the LED's face", Face.LED, flow.face.value)

        phone.log.clear()
        phone.pressPower()
        phone.advance(2_000)

        logged("system: going to sleep", "uncover", "system: SCREEN_OFF", "wake")
        assertEquals(Face.LOCK_SCREEN, flow.face.value)
        assertTrue(phone.interactive)
    }

    /**
     * A relight the rate limit puts off with the LED's face in front (a call over, the phone taken
     * out, the watchdog's check) comes once a token is due: the limit spaces relights out, it never
     * drops one ([RelightLimiter]). Its timer only relit the lock screen in front, and the LED
     * stayed dark.
     */
    @Test
    fun aRelightPutOffWithTheLedsFaceInFrontStillComes() {
        val limit = RelightLimiter(LockFlow.RELIGHT_BURST, LockFlow.RELIGHT_REFILL_MS)
        phone = FakePhone(limit)
        phone.shieldOn = false
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        assertEquals(Face.LED, flow.face.value)

        phone.inCall = true
        phone.pressPower() // the call's proximity: dark, the LED's face left in front
        phone.advance(2_000)
        assertFalse(phone.interactive)
        assertEquals(Face.LED, flow.face.value)

        repeat(LockFlow.RELIGHT_BURST) { limit.take(phone.now) } // quick presses just spent the burst
        phone.inCall = false
        phone.log.clear()
        flow.callOver()
        phone.advance(LockFlow.RELIGHT_REFILL_MS + 1_000)

        logged("wake")
        assertTrue(phone.interactive)
        assertEquals(Face.LED, flow.face.value)
    }

    @Test
    fun theLockScreenTimingOutBringsTheLedBackLit() {
        phone.shieldOn = false
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        phone.pressPower()
        phone.advance(2_000)
        assertEquals(Face.LOCK_SCREEN, flow.face.value)

        phone.log.clear()
        phone.pressPower() // the lock screen's own sleep, as its timeout would
        phone.advance(2_000)

        logged("system: going to sleep", "cover", "wake")
        assertEquals(Face.LED, flow.face.value)
        assertTrue(phone.interactive)
    }

    /** The "power won't open" loop (2026-10-11): taken out, the LED's face and its window were both up. */
    @Test
    fun putAwayThenTakenOutEndsWithOneLed() {
        readyUnderLed()
        flow.stow()
        phone.advance(7_000)
        assertFalse("asleep at the lock screen's own timeout, put away", phone.interactive)
        logged("blackout", "uncover", "restart timeout", "release panel", "system: timeout", "system: SCREEN_OFF")

        phone.pressPower() // taken out and woken by hand
        phone.advance(4_000)
        flow.takenOut()
        phone.advance(4_000)

        assertTrue(phone.interactive)
        // The invariant check in detach() covers the face and the window together.
    }

    /**
     * Put away in a pocket with the LED lit, asleep with the LED's face in front, and its bright
     * phase over meanwhile (its end waited while put away). Taken out and woken by hand before the
     * stow watch had looked, the LED's face took the screen lock at resume and nothing let go of
     * it: the screen stayed lit until the user did something, in Battery Saver too (found by
     * [LockFlowFuzzTest]).
     */
    @Test
    fun wokenByHandAfterTheBrightPhaseWhilePutAwayTheLockScreenShows() {
        phone.pressPower() // dark when the message comes: its wake starts the screen's timeout
        phone.advance(1_000)
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        assertTrue(flow.ledInWindow.value)
        phone.cover(true) // into a pocket
        phone.advance(COVERED_MS + 40_000)
        assertFalse("put away, asleep at the screen's timeout", phone.interactive)
        assertEquals("the LED's face in front in the dark, for when it is taken out", Face.LED, flow.face.value)
        phone.advance(11 * MINUTE) // the bright phase ends while it is put away

        phone.cover(false)
        phone.pressPower()
        phone.advance(2_000)
        assertTrue(phone.interactive)
        assertEquals("they have it in hand: the lock screen as ever", Face.LOCK_SCREEN, flow.face.value)
        phone.advance(MINUTE)

        assertFalse("no screen held on after the bright phase", phone.panelLocked)
        assertFalse("asleep at the lock screen's own timeout", phone.interactive)
    }

    @Test
    fun aLaunchWithNothingWaitingFinishesAtOnce() {
        phone.launch(WakeMode.LED, 500)

        logged("finish")
        assertFalse(phone.panelLocked)
    }

    /** A new message as the listener hands it on ([NotificationWakerService.onNotificationPosted]): to the glow screen on top, or a launch. */
    private fun messageArrives(key: String) {
        message(key)
        val host = GlowSession.host
        if (host != null && !host.isAway) phone.then { host.onNewMessage() } else phone.startGlowScreen(WakeMode.WAKE)
    }

    /** Read as the listener has it: the glow screen on top is told. */
    private fun read(key: String) {
        GlowPending.remove(key)
        phone.then { GlowSession.host?.onPendingChanged() }
    }

    /**
     * Read on the dark phone, and a new message before Android had destroyed the finished glow
     * screen (a chat clearing its notification and posting the next, or a message read just as
     * another came): the listener handed it to the finishing glow screen, which lit the phone and
     * went, and none came up for it. The LED stayed dark for that message (found by
     * [LockFlowFuzzTest]).
     */
    @Test
    fun aMessageJustAfterTheLastWasReadGetsAGlowScreenOfItsOwn() {
        phone.destroyAfterMs = 600 // Android takes a moment to destroy a finished activity
        phone.settings = GlowSettings(brightFor = BrightFor.OFF)
        phone.pressPower() // dark when the message comes
        phone.advance(1_000)
        message()
        phone.launch(WakeMode.WAKE, 10_000)
        assertFalse("the lock screen timed out", phone.interactive)

        read("chat-1")
        assertTrue(phone.finished)
        messageArrives("chat-2")
        phone.advance(4_000)

        assertTrue("a glow screen up for the new message", phone.glowScreenUp)
        assertTrue("and the listener's host", GlowSession.host === flow)
    }

    /**
     * Android destroys a finished activity once the next one is idle, up to 10 s later. The
     * finished glow screen, destroyed after the next one came up, took GlowShield's windows with
     * it: the new one's lit LED was gone from over the ready lock screen, which its locks held on.
     */
    @Test
    fun aFinishedGlowScreenGoingLeavesTheNextOnesLedAlone() {
        phone.destroyAfterMs = 5_000
        readyUnderLed()
        read("chat-1") // read elsewhere under the LED: asleep at once, finished at the screen-off
        phone.advance(1_000)
        assertTrue(phone.finished)

        messageArrives("chat-2")
        phone.advance(6_000)

        assertTrue("the new glow screen's LED in its window", flow.ledInWindow.value)
        assertTrue("and the window up", phone.litShown)
    }

    /**
     * Read on the dark phone, and a new message as One UI dozed the panel, before Android had
     * destroyed the finished glow screen: still listening to the display, the finished one put the
     * LED onto the always-on display for the new message, which the new glow screen never knew of,
     * and it breathed on under the new one's black cover (found by [LockFlowFuzzTest]).
     */
    @Test
    fun aFinishedGlowScreenPutsNoLedOnTheAlwaysOnDisplay() {
        phone.destroyAfterMs = 5_000
        phone.settings = GlowSettings(brightFor = BrightFor.OFF)
        phone.pressPower() // dark when the message comes
        phone.advance(1_000)
        message()
        phone.launch(WakeMode.WAKE, 10_000)
        assertFalse("the lock screen timed out", phone.interactive)
        read("chat-1")
        assertTrue(phone.finished)
        phone.log.clear()

        phone.dozeScreen()
        messageArrives("chat-2")
        phone.advance(4_000)

        notLogged("aod LED on")
    }

    /**
     * The last message read with the phone dark in a call, and a new one in it: the finished glow
     * screen's call watch was still armed, and as the call ended it relit the phone, besides the
     * listener's own relight for the new message.
     */
    @Test
    fun aFinishedGlowScreenDoesNotRelightAfterTheCall() {
        phone.destroyAfterMs = 5_000
        phone.shieldOn = false // the LED's face, left in front in the call
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        phone.callTurns(true)
        phone.pressPower() // the call's proximity: dark
        phone.advance(2_000)
        assertFalse(phone.interactive)
        read("chat-1")
        assertTrue(phone.finished)
        message("chat-2") // in the call: the listener relights once it is over

        phone.log.clear()
        phone.callTurns(false)
        phone.advance(1_000)

        notLogged("wake")
        assertFalse(phone.interactive)
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
