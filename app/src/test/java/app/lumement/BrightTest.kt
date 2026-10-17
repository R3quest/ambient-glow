package app.lumement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bright phase after a message, and the streak that caps it ([BrightStreak]). */
class BrightTest {

    private val ten = BrightFor.TEN.ms
    private val min = 60_000L

    @Test
    fun aMessageLightsTheLedForItsTime() {
        val streak = BrightStreak().onMessage(now = 0L, brightForMs = ten)
        assertTrue(streak.lit(9 * min))
        assertFalse("over at 10 minutes", streak.lit(10 * min))
    }

    @Test
    fun offMeansNoBrightPhaseAtAll() {
        assertFalse(BrightStreak().onMessage(now = 0L, brightForMs = BrightFor.OFF.ms).lit(0L))
    }

    @Test
    fun aNewMessageStartsItOver() {
        val streak = BrightStreak().onMessage(0L, ten).onMessage(8 * min, ten)
        assertTrue("its own 10 minutes from the second message", streak.lit(17 * min))
        assertFalse(streak.lit(18 * min))
    }

    /** A busy chat: a message every 8 minutes. Bright for 30 minutes in all, then not, until someone looks. */
    @Test
    fun aBusyChatGetsThirtyMinutesInAll() {
        var streak = BrightStreak()
        for (at in 0..32 step 8) streak = streak.onMessage(at * min, ten)
        // Each phase counts what ran before the next message: 8 + 8 + 8 by the one at 24, which
        // then had 6 minutes left, to 30; the one at 32 has none.
        assertFalse("the streak's 30 minutes are spent", streak.lit(32 * min))
        assertEquals(30 * min, streak.spentMs + (streak.until - streak.from))
    }

    @Test
    fun aPhaseThatRanOutCountsInFull() {
        val streak = BrightStreak().onMessage(0L, ten).onMessage(60 * min, ten)
        assertEquals("the first phase's 10 minutes", 10 * min, streak.spentMs)
        assertTrue(streak.lit(69 * min))
    }

    @Test
    fun lookingStartsANewStreakAndLetsThePhaseRunOut() {
        var streak = BrightStreak()
        for (at in 0..24 step 8) streak = streak.onMessage(at * min, ten)
        streak = streak.looked(26 * min)
        assertTrue("the phase under way runs out", streak.lit(29 * min))
        assertFalse(streak.lit(30 * min))

        streak = streak.onMessage(40 * min, ten)
        assertTrue("the next message has its whole time again", streak.lit(49 * min))
    }

    @Test
    fun lookingAfterThePhaseLeavesNone() {
        assertEquals(BrightStreak(), BrightStreak().onMessage(0L, ten).looked(12 * min))
    }

    private fun allowed(now: Long, streak: BrightStreak, saver: Boolean = false, pct: Int? = 80) =
        litLedAllowed(now, streak, { saver }, { pct })

    @Test
    fun theBatteryCanKeepTheLedOffTheScreen() {
        val streak = BrightStreak().onMessage(0L, ten)
        assertTrue(allowed(min, streak))
        assertFalse("Battery Saver", allowed(min, streak, saver = true))
        assertFalse("low", allowed(min, streak, pct = LOW_BATTERY_PCT - 1))
        assertTrue("at the limit", allowed(min, streak, pct = LOW_BATTERY_PCT))
        assertTrue("level unknown", allowed(min, streak, pct = null))
        assertFalse("after the phase", allowed(11 * min, streak))
    }

    /** Asking the battery is a call into the system: only in the bright phase. */
    @Test
    fun theBatteryIsAskedOnlyInTheBrightPhase() {
        var asked = 0
        val after = BrightStreak().onMessage(0L, ten)
        litLedAllowed(11 * min, after, { asked++; false }, { asked++; 80 })
        assertEquals("no phase: never asked", 0, asked)
        litLedAllowed(min, after, { asked++; false }, { asked++; 80 })
        assertEquals("in the phase: both", 2, asked)
    }
}
