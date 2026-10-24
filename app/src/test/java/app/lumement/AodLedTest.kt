package app.lumement

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AodLedTest {

    private fun sw(setting: Int, forNew: Boolean = false) = aodSwitchesOf(setting, forNew)

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
        assertEquals("showed, switch unchanged", true, sleepWillDoze(lastShowed = true, settingThen = sw(1), settingNow = sw(1)))
        assertEquals("on but tap to show: went off, unchanged", false, sleepWillDoze(lastShowed = false, settingThen = sw(1), settingNow = sw(1)))
        assertEquals("no switch to read: the last sleep", false, sleepWillDoze(lastShowed = false, settingThen = null, settingNow = null))
    }

    @Test
    fun switchedSinceTheSwitchSays() {
        assertEquals("turned on", true, sleepWillDoze(lastShowed = false, settingThen = sw(0), settingNow = sw(1)))
        assertEquals("turned off", false, sleepWillDoze(lastShowed = true, settingThen = sw(1), settingNow = sw(0)))
        assertEquals("no sleep seen yet, on", true, sleepWillDoze(lastShowed = null, settingThen = null, settingNow = sw(1)))
        assertEquals("nothing known", null, sleepWillDoze(lastShowed = null, settingThen = null, settingNow = null))
    }

    /** One UI's "Show for new notifications": off with nothing waiting, on while a message waits. */
    @Test
    fun shownForNewNotificationsTheMessageBringsIt() {
        assertEquals("the last sleep, nothing waiting, went off", true, sleepWillDoze(lastShowed = false, settingThen = sw(1, forNew = true), settingNow = sw(1, forNew = true)))
        assertEquals("no sleep seen yet", true, sleepWillDoze(lastShowed = null, settingThen = null, settingNow = sw(1, forNew = true)))
        assertEquals("the always-on display off: the mode doesn't matter", false, sleepWillDoze(lastShowed = false, settingThen = sw(0, forNew = true), settingNow = sw(0, forNew = true)))
        assertEquals("no switch to read: the last sleep", false, sleepWillDoze(lastShowed = false, settingThen = null, settingNow = null))
    }

    /**
     * Switched from "Show for new notifications" to "Show always" (2026-10-13): the main switch is on
     * in both, and the last sleep, with nothing new, went off. That answer is stale; the switches say.
     */
    @Test
    fun switchedBetweenOneUisModesTheSwitchesSay() {
        val forNew = aodSwitchesOf(setting = 1, forNew = true)
        val always = aodSwitchesOf(setting = 1, forNew = false)
        assertEquals("to Show always", true, sleepWillDoze(lastShowed = false, settingThen = forNew, settingNow = always))
        assertEquals("unchanged, Show always: the last sleep", false, sleepWillDoze(lastShowed = false, settingThen = always, settingNow = always))
        assertEquals("off is off, whatever the other switch", sw(0), aodSwitchesOf(setting = 0, forNew = true))
        assertNull(aodSwitchesOf(setting = null, forNew = true))
    }

    @Test
    fun theAlwaysOnDisplaysModeFromItsSwitches() {
        assertEquals(AodMode.OFF, aodSwitchesOf(setting = 0, forNew = true)?.mode)
        assertEquals("Show for new notifications", AodMode.NEW_NOTIFICATIONS, aodSwitchesOf(setting = 1, forNew = true)?.mode)
        assertEquals("always, on a tap, on a schedule", AodMode.ON, aodSwitchesOf(setting = 1, forNew = false)?.mode)
        assertNull("no switch to read", aodSwitchesOf(setting = null, forNew = false)?.mode)
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

    @Test
    fun aRoundStartingAfterALightJustGoneKeepsTheRoundsDark() {
        val out = 100_000L
        assertEquals("straight after the arrival's last exhale: the whole dark", LED_DARK_MS, ledDarkLeft(out, out))
        assertEquals("the rest of it", LED_DARK_MS - 400, ledDarkLeft(out, out + 400))
        // The first breath then lights a whole round's dark after the light went, as every later one does.
        assertEquals(out + LED_DARK_MS, out + 400 + ledDarkLeft(out, out + 400))
        assertEquals("a whole dark since: no wait", 0L, ledDarkLeft(out, out + LED_DARK_MS))
        assertEquals("long since (a wake from sleep): no wait", 0L, ledDarkLeft(out, out + 3_600_000L))
        assertEquals("never more than one dark", LED_DARK_MS, ledDarkLeft(out, out - 5_000L))
        assertEquals("never marked", 0L, ledDarkLeft(0L, 60_000L))
    }
}
