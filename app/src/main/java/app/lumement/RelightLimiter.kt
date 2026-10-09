package app.lumement

/**
 * Rate limit for lock screen → LED relights, as a token bucket: up to [burst] at once, then one
 * per [refillMs]. Every power press flips the face, and each flip back to the LED is a relight,
 * so no rule can tell a user's quick presses from something forcing sleep in a loop. So it never
 * drops a relight, it only spaces them out: quick presses see the LED at most a refill late, and
 * a loop is held to one panel wake per refill.
 */
internal class RelightLimiter(private val burst: Int, private val refillMs: Long) {
    private var tokens = burst
    private var refilledAt = 0L

    /** Takes a token at [now] (elapsed realtime) and returns 0, or, with none left, the ms until the next one. */
    fun take(now: Long): Long {
        val refills = (now - refilledAt) / refillMs
        if (refills > 0) {
            tokens = minOf(burst.toLong(), tokens + refills).toInt()
            refilledAt = if (tokens == burst) now else refilledAt + refills * refillMs
        }
        if (tokens > 0) {
            tokens--
            return 0L
        }
        return refilledAt + refillMs - now
    }
}
