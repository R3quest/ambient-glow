package app.lumement.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFollowsTest {
    private val effectTab = DashboardTab.entries.indexOf(DashboardTab.EFFECT)
    private val ledTab = DashboardTab.entries.indexOf(DashboardTab.LED)

    @Test
    fun aTabOnScreenFollowsEvenMidDrag() {
        assertTrue(pageFollows(ledTab, setOf(ledTab), ledHolding = true))
        assertTrue(pageFollows(effectTab, setOf(effectTab, ledTab), ledHolding = true))
    }

    @Test
    fun aTabOffScreenWaitsOutAnLedDrag() {
        assertFalse(pageFollows(effectTab, setOf(ledTab), ledHolding = true))
    }

    @Test
    fun aTabOffScreenFollowsAPickSoItPlaysOutBeforeItSlidesIn() {
        // Just the LED picked on the Screen tab: the Effect tab beside it swaps its cards now.
        assertTrue(pageFollows(effectTab, setOf(DashboardTab.entries.indexOf(DashboardTab.SCREEN)), ledHolding = false))
        assertTrue(pageFollows(ledTab, setOf(effectTab), ledHolding = false))
    }
}
