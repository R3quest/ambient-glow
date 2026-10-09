package app.lumement

import org.junit.Assert.assertEquals
import org.junit.Test

class RelightLimiterTest {

    @Test
    fun allowsABurstThenSpacesRelightsOut() {
        val limiter = RelightLimiter(burst = 8, refillMs = 2_000L)
        val now = 10_000L
        repeat(8) { assertEquals("relight ${it + 1} of the burst", 0L, limiter.take(now)) }
        assertEquals(2_000L, limiter.take(now))
        assertEquals(500L, limiter.take(now + 1_500L))
        assertEquals(0L, limiter.take(now + 2_000L))
        assertEquals(2_000L, limiter.take(now + 2_000L))
    }

    @Test
    fun refillsToTheFullBurstAfterAQuietSpell() {
        val limiter = RelightLimiter(burst = 3, refillMs = 1_000L)
        repeat(3) { limiter.take(5_000L) }
        repeat(3) { assertEquals(0L, limiter.take(60_000L)) }
        assertEquals(1_000L, limiter.take(60_000L))
    }
}
