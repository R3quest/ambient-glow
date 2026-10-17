package app.lumement.dashboard

import app.lumement.AodMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Then row's state under "Always-on display": ready, or the one thing missing ([aodReadiness]). */
class AodReadinessTest {

    @Test
    fun shownForNewNotificationsWithEffectsOnIsReady() {
        assertEquals(AodReadiness.READY, aodReadiness(AodMode.NEW_NOTIFICATIONS, shieldOn = true))
    }

    @Test
    fun onInAnotherModeItWorks() {
        val state = aodReadiness(AodMode.ON, shieldOn = true)
        assertEquals(AodReadiness.ALWAYS_ON, state)
        assertTrue(state.ready)
    }

    @Test
    fun theAlwaysOnDisplayOffComesFirst() {
        assertEquals("off with effects on", AodReadiness.AOD_OFF, aodReadiness(AodMode.OFF, shieldOn = true))
        assertEquals("off with effects off: the display first, effects next", AodReadiness.AOD_OFF, aodReadiness(AodMode.OFF, shieldOn = false))
        assertFalse(AodReadiness.AOD_OFF.ready)
    }

    @Test
    fun onWithoutEffectsNeedsThem() {
        assertEquals(AodReadiness.NEEDS_SHIELD, aodReadiness(AodMode.NEW_NOTIFICATIONS, shieldOn = false))
        assertEquals(AodReadiness.NEEDS_SHIELD, aodReadiness(AodMode.ON, shieldOn = false))
        assertFalse(AodReadiness.NEEDS_SHIELD.ready)
    }
}
