package app.lumement

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodLedTest {

    @Test
    fun onlyADozingPanelShowsTheAlwaysOnDisplay() {
        assertTrue(dozing(Display.STATE_DOZE))
        assertTrue("held still between its updates", dozing(Display.STATE_DOZE_SUSPEND))
        assertFalse("lit for use", dozing(Display.STATE_ON))
        assertFalse("off: no always-on display (or it timed out)", dozing(Display.STATE_OFF))
        assertFalse(dozing(Display.STATE_UNKNOWN))
        assertFalse(dozing(null))
    }

    private fun onAod(
        waiting: Boolean = true,
        resting: Boolean = false,
        putAway: Boolean = false,
        inCall: Boolean = false,
        ending: Boolean = false,
    ) = ledOnAod(waiting, resting, putAway, inCall, ending)

    @Test
    fun theLedTakesTheAlwaysOnDisplayWhileAMessageWaits() {
        assertTrue(onAod())
        assertFalse("all read", onAod(waiting = false))
        assertFalse("Do Not Disturb", onAod(resting = true))
        assertFalse("put away", onAod(putAway = true))
        assertFalse("in a call", onAod(inCall = true))
        assertFalse("the last message just read", onAod(ending = true))
    }

    @Test
    fun aRoundPausesBrieflyBetweenAppsAndLongAfterTheLast() {
        assertEquals("one app: every breath ends the round", LED_DARK_MS, ledPauseAfter(0, 1))
        assertEquals(LED_DARK_MS, ledPauseAfter(7, 1))
        assertEquals("first of three", LED_GAP_MS, ledPauseAfter(0, 3))
        assertEquals("second of three", LED_GAP_MS, ledPauseAfter(1, 3))
        assertEquals("the round's last", LED_DARK_MS, ledPauseAfter(2, 3))
        assertEquals("the next round's first", LED_GAP_MS, ledPauseAfter(3, 3))
    }
}
