package app.lumement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlinkTest {

    private fun sleeps(
        canSleep: Boolean = true,
        ledInFront: Boolean = true,
        screenOn: Boolean = true,
        arriving: Boolean = false,
        touched: Boolean = false,
        ending: Boolean = false,
        putAway: Boolean = false,
        covered: Boolean = false,
        inCall: Boolean = false,
    ) = sleepsBetweenBreaths(canSleep, ledInFront, screenOn, arriving, touched, ending, putAway, covered, inCall)

    @Test
    fun aBreathOutSleepsUntilTheNext() {
        assertTrue(sleeps())
    }

    @Test
    fun theLedStaysLitWhereSleepWouldHideOrCutSomethingShort() {
        assertFalse("no GlowShield to sleep it", sleeps(canSleep = false))
        assertFalse("another window in front (an alarm, a call)", sleeps(ledInFront = false))
        assertFalse("already dark", sleeps(screenOn = false))
        assertFalse("an arrival plays", sleeps(arriving = true))
        assertFalse("a finger on the dot", sleeps(touched = true))
        assertFalse("the last message just read", sleeps(ending = true))
        assertFalse("in a call", sleeps(inCall = true))
    }

    @Test
    fun putAwayOrCoveredTheStowingRunsInstead() {
        assertFalse("put away", sleeps(putAway = true))
        // A pocket: the proximity lock has the panel off; blinking would wake it every few seconds.
        assertFalse("covered", sleeps(covered = true))
    }
}
