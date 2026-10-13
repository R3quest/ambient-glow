package app.lumement.dashboard

import app.lumement.AodMode
import app.lumement.dashboard.AccessStep.BRIDGE
import app.lumement.dashboard.AccessStep.FULL_SCREEN
import app.lumement.dashboard.AccessStep.LISTENER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessStepTest {

    private fun access(listener: Boolean = false, bridge: Boolean = false, fullScreen: Boolean = false, applies: Boolean = true) =
        AccessState(listener, bridge, fullScreen, fullScreenApplies = applies, shield = false)

    @Test
    fun setupWalksTheStepsInOrder() {
        // Alerts first: a dialog over the app, before the trips to Settings.
        assertEquals(BRIDGE, access().nextStep)
        assertEquals(LISTENER, access(bridge = true).nextStep)
        assertEquals(FULL_SCREEN, access(listener = true, bridge = true).nextStep)
        // A step granted out of order is skipped.
        assertEquals(BRIDGE, access(listener = true, fullScreen = true).nextStep)
    }

    @Test
    fun theWalkEndsWhereReadyBegins() {
        assertNull(access(listener = true, bridge = true, fullScreen = true).nextStep)
        // Before Android 14 there is no full-screen grant to walk to.
        assertNull(access(listener = true, bridge = true, applies = false).nextStep)
        assertEquals(listOf(BRIDGE, LISTENER), access(applies = false).steps)
    }

    /** The Screen tab's While a message waits shows it on One UI only, in every mode. */
    @Test
    fun theAlwaysOnDisplayIsShownOnOneUiOnly() {
        fun aod(mode: AodMode?, forNew: Boolean) = access().copy(aod = mode, aodForNew = forNew).oneUiAod
        assertEquals(AodMode.OFF, aod(AodMode.OFF, forNew = true))
        assertEquals(AodMode.ON, aod(AodMode.ON, forNew = true))
        assertEquals(AodMode.NEW_NOTIFICATIONS, aod(AodMode.NEW_NOTIFICATIONS, forNew = true))
        assertNull("not One UI: Android's switch may not be the phone's", aod(AodMode.ON, forNew = false))
        assertNull("not One UI, off", aod(AodMode.OFF, forNew = false))
        assertNull("can't be read", aod(null, forNew = true))
    }

    /** Suggested (setup's optional step) only on One UI, and only while it is off. */
    @Test
    fun theAlwaysOnDisplayIsSuggestedWhileOneUisIsOff() {
        fun aod(mode: AodMode?, forNew: Boolean) = access().copy(aod = mode, aodForNew = forNew).aodOff
        assertTrue(aod(AodMode.OFF, forNew = true))
        assertFalse("already for new notifications", aod(AodMode.NEW_NOTIFICATIONS, forNew = true))
        assertFalse("on, Show always: the LED glows on it already", aod(AodMode.ON, forNew = true))
        assertFalse("not One UI: no such mode to suggest", aod(AodMode.OFF, forNew = false))
        assertFalse("can't be read", aod(null, forNew = false))
    }

    @Test
    fun readyIsNothingLeftToWalk() {
        assertTrue(access(listener = true, bridge = true, fullScreen = true).ready)
        assertFalse(access(listener = true, bridge = true).ready)
        // The shield is optional: off, the app is still ready.
        assertTrue(access(listener = true, bridge = true, applies = false).ready)
    }
}
