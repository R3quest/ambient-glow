package app.lumement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A message that comes while the phone is in use starts the unread streak over ([brightAfterMessage]). */
class StreakInUseTest {

    private val ten = BrightFor.TEN.ms
    private val min = 60_000L

    @Test
    fun messagesWhileInUseLeaveTheNextPhaseWhole() {
        // An hour in use, a message every 5 minutes: the last one, as the phone is put down, still
        // gets its whole 10 minutes.
        var streak = BrightStreak()
        for (at in 0..60 step 5) streak = brightAfterMessage(streak, at * min, ten, inUse = true)
        assertTrue(streak.lit(69 * min))
        assertEquals(70 * min, streak.until)
    }

    @Test
    fun messagesWhileAwayStillSpendTheStreak() {
        // The phone left alone: 10 + 10 + 10 minutes spend the streak, and the message at 30 gets none.
        var streak = BrightStreak()
        for (at in 0..30 step 10) streak = brightAfterMessage(streak, at * min, ten, inUse = false)
        assertFalse(streak.lit(30 * min))
    }

    @Test
    fun aMessageWhileInUseAfterASpentStreakGivesItsTimeBack() {
        var streak = BrightStreak()
        for (at in 0..30 step 10) streak = brightAfterMessage(streak, at * min, ten, inUse = false)
        streak = brightAfterMessage(streak, 35 * min, ten, inUse = true)
        assertEquals(45 * min, streak.until)
        assertEquals("the frame calms from the newest message", 35 * min, streak.messageAt)
    }

    @Test
    fun noPhaseChosenStaysDarkInUseToo() {
        val streak = brightAfterMessage(BrightStreak(), 0L, BrightFor.OFF.ms, inUse = true)
        assertFalse(streak.lit(0L))
    }
}
