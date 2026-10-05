package com.example.ambientglow.dashboard

import com.example.ambientglow.dashboard.DashboardTab.ACCESS
import com.example.ambientglow.dashboard.DashboardTab.EFFECT
import com.example.ambientglow.dashboard.DashboardTab.LED
import com.example.ambientglow.dashboard.DashboardTab.SCREEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardTabsTest {

    @Test
    fun setupShowsAccessFirstThenTheMessageInOrder() {
        assertEquals(listOf(ACCESS, SCREEN, EFFECT, LED), dashboardTabs(armed = false))
    }

    @Test
    fun onceArmedAccessLeavesTheTabs() {
        // The screen comes first: it decides whether there is an effect to style at all.
        assertEquals(listOf(SCREEN, EFFECT, LED), dashboardTabs(armed = true))
    }

    @Test
    fun accessComingOrGoingKeepsTheSameTabShowing() {
        val armed = dashboardTabs(armed = true)
        val setup = dashboardTabs(armed = false)
        // On LED when a grant is withdrawn: Access slides in ahead, LED moves one page on.
        assertEquals(3, setup.pageKeeping(LED, current = armed.indexOf(LED)))
        // On LED when setup completes: Access leaves, LED moves one page back.
        assertEquals(2, armed.pageKeeping(LED, current = setup.indexOf(LED)))
    }

    @Test
    fun nothingToDoWhenThePageAlreadyFitsOrTheTabIsGone() {
        val armed = dashboardTabs(armed = true)
        assertNull(armed.pageKeeping(EFFECT, current = armed.indexOf(EFFECT)))
        // On Access when setup completes: it is gone, so the pager's own page (Screen) stands.
        assertNull(armed.pageKeeping(ACCESS, current = 0))
    }
}
