package app.lumement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedWindowTest {

    private fun wanted(
        ledInFront: Boolean = true,
        screenOn: Boolean = true,
        settled: Boolean = true,
        arriving: Boolean = false,
        ending: Boolean = false,
        covered: Boolean = false,
        putAway: Boolean = false,
        inCall: Boolean = false,
        screenTaken: Boolean = false,
    ) = ledWindowWanted(ledInFront, screenOn, settled, arriving, ending, covered, putAway, inCall, screenTaken)

    @Test
    fun theSettledLitLedGoesIntoItsWindow() {
        assertTrue(wanted())
    }

    @Test
    fun notWhileTheLedFaceIsNotYetAloneLitAndSettled() {
        assertFalse("another window in front (an alarm, a call)", wanted(ledInFront = false))
        assertFalse("dark", wanted(screenOn = false))
        assertFalse("still handing over from the wake", wanted(settled = false))
        assertFalse("an arrival plays", wanted(arriving = true))
    }

    @Test
    fun notWhenSomethingElseHoldsTheScreen() {
        assertFalse("the last message just read", wanted(ending = true))
        assertFalse("in a call", wanted(inCall = true))
        assertFalse("a ringing call's pop-up", wanted(screenTaken = true))
    }

    @Test
    fun coveredOrPutAwayTheLedsOwnStowingRuns() {
        assertFalse("covered", wanted(covered = true))
        assertFalse("put away", wanted(putAway = true))
    }

    @Test
    fun theWindowMakesWayForACallAPopUpOrTheEnd() {
        assertFalse("nothing else wants the screen", ledWindowEnds(screenTaken = false, inCall = false, ending = false))
        assertTrue("a post that wants the screen", ledWindowEnds(screenTaken = true, inCall = false, ending = false))
        assertTrue("in a call", ledWindowEnds(screenTaken = false, inCall = true, ending = false))
        assertTrue("the last message read", ledWindowEnds(screenTaken = false, inCall = false, ending = true))
    }

    @Test
    fun anAlarmRangOnlyWhenTheOneNamedIsDue() {
        assertTrue("due now", alarmRang(dueAt = 1_000L, now = 1_000L))
        assertTrue("due a moment ago", alarmRang(dueAt = 1_000L, now = 1_012L))
        assertFalse("moved or cancelled, still ahead", alarmRang(dueAt = 5_000L, now = 1_000L))
        assertFalse("none was set", alarmRang(dueAt = null, now = 1_000L))
    }
}
