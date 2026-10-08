package com.example.ambientglow

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
    fun theWatchdogRelightsOnlyWhatShouldBeLit() {
        assertTrue(ledMissing(screenOn = false, waiting = true, resting = false, inCall = false, putAway = false, ending = false))
        assertFalse("the LED is up", ledMissing(screenOn = true, waiting = true, resting = false, inCall = false, putAway = false, ending = false))
        assertFalse("all read", ledMissing(screenOn = false, waiting = false, resting = false, inCall = false, putAway = false, ending = false))
        assertFalse("Do Not Disturb", ledMissing(screenOn = false, waiting = true, resting = true, inCall = false, putAway = false, ending = false))
        assertFalse("in a call (lit after it)", ledMissing(screenOn = false, waiting = true, resting = false, inCall = true, putAway = false, ending = false))
        assertFalse("put away", ledMissing(screenOn = false, waiting = true, resting = false, inCall = false, putAway = true, ending = false))
        assertFalse("the last one just read", ledMissing(screenOn = false, waiting = true, resting = false, inCall = false, putAway = false, ending = true))
    }
}
