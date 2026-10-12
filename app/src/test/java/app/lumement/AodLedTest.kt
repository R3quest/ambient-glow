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

    @Test
    fun aSleepIsReadOnceTheDisplayHasSettled() {
        assertEquals("the always-on display", true, sleepShowsAod(Display.STATE_DOZE))
        assertEquals("the always-on display, held still", true, sleepShowsAod(Display.STATE_DOZE_SUSPEND))
        assertEquals("off: no always-on display", false, sleepShowsAod(Display.STATE_OFF))
        assertEquals("still lit, on its way: not known yet", null, sleepShowsAod(Display.STATE_ON))
        assertEquals(null, sleepShowsAod(Display.STATE_UNKNOWN))
        assertEquals(null, sleepShowsAod(null))
    }

    @Test
    fun theLastSleepSaysWhileTheSwitchIsAsItWas() {
        assertEquals("showed, switch unchanged", true, sleepWillDoze(lastShowed = true, settingThen = 1, settingNow = 1))
        assertEquals("on but tap to show: went off, unchanged", false, sleepWillDoze(lastShowed = false, settingThen = 1, settingNow = 1))
        assertEquals("no switch to read: the last sleep", false, sleepWillDoze(lastShowed = false, settingThen = null, settingNow = null))
    }

    @Test
    fun switchedSinceTheSwitchSays() {
        assertEquals("turned on", true, sleepWillDoze(lastShowed = false, settingThen = 0, settingNow = 1))
        assertEquals("turned off", false, sleepWillDoze(lastShowed = true, settingThen = 1, settingNow = 0))
        assertEquals("no sleep seen yet, on", true, sleepWillDoze(lastShowed = null, settingThen = null, settingNow = 1))
        assertEquals("nothing known", null, sleepWillDoze(lastShowed = null, settingThen = null, settingNow = null))
    }

    /** One UI's "Show for new notifications": off with nothing waiting, on while a message waits. */
    @Test
    fun shownForNewNotificationsTheMessageBringsIt() {
        assertEquals("the last sleep, nothing waiting, went off", true, sleepWillDoze(lastShowed = false, settingThen = 1, settingNow = 1, newOnly = true))
        assertEquals("no sleep seen yet", true, sleepWillDoze(lastShowed = null, settingThen = null, settingNow = 1, newOnly = true))
        assertEquals("the always-on display off: the mode doesn't matter", false, sleepWillDoze(lastShowed = false, settingThen = 0, settingNow = 0, newOnly = true))
        assertEquals("no switch to read: the last sleep", false, sleepWillDoze(lastShowed = false, settingThen = null, settingNow = null, newOnly = true))
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
