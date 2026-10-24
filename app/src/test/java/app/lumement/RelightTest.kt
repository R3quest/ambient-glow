package app.lumement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelightTest {

    private fun plan(
        screenOn: Boolean = false,
        waiting: Boolean = true,
        resting: Boolean = false,
        hostOnTop: Boolean = false,
        inCall: Boolean = false,
        locked: Boolean = true,
    ) = relightPlan(screenOn, waiting, resting, hostOnTop, inCall, locked)

    @Test
    fun aWaitingMessageOnADarkLockedPhoneLightsTheLed() {
        assertEquals(Relight.NOW, plan())
    }

    @Test
    fun aLockDelayOrACallOnlyPutItOff() {
        assertEquals("screen timed out, locks 5 s later", Relight.AFTER_LOCK, plan(locked = false))
        assertEquals("screen off in a call", Relight.AFTER_CALL, plan(inCall = true))
        assertEquals("both: the call first", Relight.AFTER_CALL, plan(inCall = true, locked = false))
    }

    @Test
    fun nothingToDoWhenItIsNotTheListenersToLight() {
        assertEquals(Relight.NONE, plan(waiting = false))
        assertEquals(Relight.NONE, plan(screenOn = true))
        assertEquals("Do Not Disturb", Relight.NONE, plan(resting = true))
        assertEquals("the glow screen on top sees to it", Relight.NONE, plan(hostOnTop = true))
    }

    /**
     * The promise, over every state: with messages waiting and the screen off, the listener lights
     * the LED, or puts it off with a way back, or leaves it to someone whose job it is. Never just
     * nothing.
     */
    @Test
    fun aWaitingMessageIsNeverLeftUnseen() {
        val flags = listOf(false, true)
        for (resting in flags) for (hostOnTop in flags) for (inCall in flags) for (locked in flags) {
            val what = "resting=$resting hostOnTop=$hostOnTop inCall=$inCall locked=$locked"
            val result = plan(resting = resting, hostOnTop = hostOnTop, inCall = inCall, locked = locked)
            if (result == Relight.NONE) assertTrue(what, resting || hostOnTop)
        }
    }

    @Test
    fun aKnownLockDelayIsLookedAtJustAfterItAndOnceMore() {
        assertEquals(listOf(5_600L, 7_600L), lockChecks(5_000L))
        assertEquals("locks at once", listOf(600L, 2_600L), lockChecks(0L))
        assertEquals("a minute: an alarm instead", emptyList<Long>(), lockChecks(60_000L))
        assertEquals(62_600L, lockAlarmMs(60_000L))
        assertEquals("held awake for instead", null, lockAlarmMs(30_000L))
        assertEquals("unknown: the backing-off looks", null, lockAlarmMs(null))
    }

    @Test
    fun anUnknownLockDelayIsLookedAtAFewTimesBackingOff() {
        val looks = lockChecks(null)
        assertEquals(3, looks.size)
        assertEquals("first where One UI's default would lock", 5_600L, looks.first())
        assertEquals(MAX_LOCK_WAIT_MS, looks.last())
        assertTrue("later looks wait longer", looks.zipWithNext().all { (a, b) -> b - a >= 2 * 2_000L })
    }

    @Test
    fun aLongLockDelayIsNotWaitedOut() {
        assertEquals(emptyList<Long>(), lockChecks(MAX_LOCK_WAIT_MS + 1))
        assertEquals("never a look past the cap", true, lockChecks(MAX_LOCK_WAIT_MS).isNotEmpty())
    }

    private fun missing(
        screenOn: Boolean = false,
        waiting: Boolean = true,
        resting: Boolean = false,
        inCall: Boolean = false,
        putAway: Boolean = false,
        ending: Boolean = false,
        lit: Boolean = true,
        onAod: Boolean = false,
    ) = ledMissing(screenOn, waiting, resting, inCall, putAway, ending, lit, onAod)

    @Test
    fun theWatchdogRelightsOnlyWhatShouldBeLit() {
        assertTrue(missing())
        assertFalse("the LED is up", missing(screenOn = true))
        assertFalse("all read", missing(waiting = false))
        assertFalse("Do Not Disturb", missing(resting = true))
        assertFalse("in a call (lit after it)", missing(inCall = true))
        assertFalse("put away", missing(putAway = true))
        assertFalse("the last one just read", missing(ending = true))
    }

    @Test
    fun afterItsBrightPhaseTheLedIsMissingOnlyFromTheAlwaysOnDisplay() {
        assertFalse("dark is right", missing(lit = false, onAod = false))
        assertTrue("the always-on display shows without it", missing(lit = false, onAod = true))
    }
}
