package com.example.ambientglow.dashboard

import com.example.ambientglow.dashboard.AccessStep.BRIDGE
import com.example.ambientglow.dashboard.AccessStep.FULL_SCREEN
import com.example.ambientglow.dashboard.AccessStep.LISTENER
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

    @Test
    fun readyIsNothingLeftToWalk() {
        assertTrue(access(listener = true, bridge = true, fullScreen = true).ready)
        assertFalse(access(listener = true, bridge = true).ready)
        // The shield is optional: off, the app is still ready.
        assertTrue(access(listener = true, bridge = true, applies = false).ready)
    }
}
