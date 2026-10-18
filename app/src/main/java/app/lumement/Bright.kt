package app.lumement

// ---------------------------------------------------------------------------------------------
// The bright phase: the lit LED for a few minutes after a new message ([BrightFor]), then One UI's
// always-on display with the LED on it, or dark ([AfterBright]). A screen held on for the LED costs
// the S23 175-189 mA, the always-on display alone 88 (both measured with adb on), and the phone's
// own power profile puts a lit screen's floor near 100 mA: no tuning makes the lit LED cheap, so
// it is kept to the minutes after a message, when someone is most likely to look.
//
// Tested on the S23 (2026-10-17): One UI shows its always-on display for a message that arrived
// while the phone slept even after its lock screen was lit for 10 minutes, so the phone can sleep
// into it when the phase ends.
// ---------------------------------------------------------------------------------------------

/**
 * The bright time, in all, that a run of unread messages gets until the user looks at the phone:
 * each new message starts the phase over, so a busy chat would otherwise keep the screen lit for
 * hours.
 */
internal const val BRIGHT_STREAK_MS = 30 * 60_000L

/** Below this battery level the LED never holds the screen on: that is what drains it. */
internal const val LOW_BATTERY_PCT = 20

/**
 * The unread streak's bright phases: the one under way started at [from] and ends at [until]
 * (elapsed ms); [spentMs] is the bright time the streak's earlier phases used. [until] 0: none.
 * [messageAt]: when the newest message arrived (elapsed ms), which started the phase under way;
 * a look keeps it.
 */
internal data class BrightStreak(val from: Long = 0L, val until: Long = 0L, val spentMs: Long = 0L, val messageAt: Long = 0L) {

    /** In the bright phase at [now] (elapsed ms). */
    fun lit(now: Long): Boolean = now < until

    /**
     * A new message at [now]: a phase of [brightForMs] from now, as long as the streak's bright time
     * stays within [streakMs].
     */
    fun onMessage(now: Long, brightForMs: Long, streakMs: Long = BRIGHT_STREAK_MS): BrightStreak {
        val spent = spentMs + (minOf(now, until) - from).coerceAtLeast(0L)
        val left = (streakMs - spent).coerceAtLeast(0L)
        return BrightStreak(from = now, until = now + minOf(brightForMs, left), spentMs = spent, messageAt = now)
    }

    /**
     * How far the phase under way has run at [now], 0 as its message arrives to 1 as it ends, and 1
     * with none: the frame LED quiets down over it ([LedLook]), so the newest message is the most
     * vivid and a long wait the calmest. A look doesn't start it over; a new message does.
     */
    fun settledAt(now: Long): Float =
        if (until <= messageAt) 1f else ((now - messageAt).toFloat() / (until - messageAt)).coerceIn(0f, 1f)

    /**
     * The user looked at the lock screen (a press, a tap, a lift): a new streak, so the next
     * message has its whole time again. The phase under way runs out as it would have: until then
     * the LED still comes back when the lock screen sleeps.
     */
    fun looked(now: Long): BrightStreak = if (lit(now)) BrightStreak(from = now, until = until, messageAt = messageAt) else BrightStreak()
}

/**
 * May the LED hold the screen on at [now]: in [streak]'s bright phase, and with the battery
 * neither in Battery Saver nor low ([batteryPct], null where it can't be read). The battery is
 * asked only in the phase: each answer is a call into the system, and most of the time there's
 * no phase at all.
 */
internal inline fun litLedAllowed(now: Long, streak: BrightStreak, batterySaver: () -> Boolean, batteryPct: () -> Int?): Boolean =
    streak.lit(now) && !batterySaver() && batteryPct().let { it == null || it >= LOW_BATTERY_PCT }
