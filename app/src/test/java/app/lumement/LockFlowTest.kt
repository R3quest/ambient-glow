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
        phone = FakePhone()
    }

    @After
    fun detach() {
        assertTrue("broken on the way:\n${phone.broken.joinToString("\n")}", phone.broken.isEmpty())
        GlowSession.detach(flow)
        GlowPending.entries.clear()
    }

    private fun message(key: String = "chat-1") = GlowPending.put(PendingGlow(key, "chat", 0xFF25D366.toInt()))

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

        logged("system: going to sleep", "wake", "system: sleep cancelled", "lit LED down")
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

    @Test
    fun anAlwaysOnDisplayThatNeverComesLeavesItToTheListener() {
        phone.expectsAod = true
        phone.dozes = false
        message()
        phone.launch(WakeMode.WAKE, 4_000)
        phone.pressPower()
        phone.advance(2_000)
        assertFalse("the glow screen waits for a doze that doesn't come", phone.interactive)

        phone.host { ensureLed() } // the listener's check, 4 s after SCREEN_OFF
        phone.advance(2_000)

        logged("cover", "wake", "lit LED up")
        assertTrue("the lit LED, in its window", flow.ledInWindow.value)
        assertTrue(phone.interactive)
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

    @Test
    fun aLaunchWithNothingWaitingFinishesAtOnce() {
        phone.launch(WakeMode.LED, 500)

        logged("finish")
        assertFalse(phone.panelLocked)
    }
}
