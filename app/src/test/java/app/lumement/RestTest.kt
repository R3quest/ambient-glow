package app.lumement

import android.service.notification.NotificationListenerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestTest {

    @Test
    fun everyDoNotDisturbModeRestsTheLed() {
        assertTrue(restsUnder(NotificationListenerService.INTERRUPTION_FILTER_PRIORITY))
        assertTrue(restsUnder(NotificationListenerService.INTERRUPTION_FILTER_NONE))
        assertTrue(restsUnder(NotificationListenerService.INTERRUPTION_FILTER_ALARMS))
    }

    @Test
    fun allAlertsOrAnUnknownFilterKeepItLit() {
        assertFalse(restsUnder(NotificationListenerService.INTERRUPTION_FILTER_ALL))
        assertFalse(restsUnder(NotificationListenerService.INTERRUPTION_FILTER_UNKNOWN))
    }

    @Test
    fun aMessageBreakingThroughWhileRestingLightsTheLockScreen() {
        for (chosen in ArrivalMode.entries) {
            assertEquals(chosen.name, ArrivalMode.LOCK_SCREEN, arrivalFor(chosen, resting = true))
            assertEquals(chosen.name, chosen, arrivalFor(chosen, resting = false))
        }
        // And from a dark screen it wakes onto the lock screen, never into the black LED face.
        assertEquals(WakeMode.WAKE, wakeModeFor(arrivalFor(ArrivalMode.LED_ONLY, resting = true), screenOn = false))
    }
}
