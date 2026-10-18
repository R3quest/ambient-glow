package app.lumement

import org.junit.Assert.assertEquals
import org.junit.Test

/** The debug frame trace's stutter count, at the panel's own rate. */
class FrameTraceTest {

    private fun gaps(frameNs: Long, count: Int, slow: Map<Int, Long> = emptyMap()) =
        LongArray(count) { slow[it] ?: frameNs }

    @Test
    fun oneDroppedFrameCountsAt120Hz() {
        // 8.3 ms frames; three gaps of two frames each, the length a 20 ms threshold never saw.
        val trace = gaps(8_333_333L, 400, mapOf(10 to 16_666_667L, 200 to 16_666_667L, 399 to 16_666_667L))
        assertEquals(3, stuttersIn(trace, trace.size))
    }

    @Test
    fun oneDroppedFrameCountsAt60Hz() {
        val trace = gaps(16_666_667L, 200, mapOf(5 to 33_333_333L, 150 to 33_333_333L))
        assertEquals(2, stuttersIn(trace, trace.size))
    }

    @Test
    fun mostFramesDroppedStillCount() {
        // Sustained jank at 120 Hz: more than half the gaps are two frames long, so the median is
        // the slow gap, but each of them is still a dropped frame.
        val trace = LongArray(400) { if (it % 2 == 0 || it == 1) 16_666_667L else 8_333_333L }
        assertEquals(201, stuttersIn(trace, trace.size))
    }

    @Test
    fun aSmoothRunHasNone() {
        assertEquals(0, stuttersIn(gaps(8_333_333L, 400), 400))
        assertEquals(0, stuttersIn(gaps(16_666_667L, 200), 200))
        // Vsync jitter is not a stutter.
        val jittery = LongArray(400) { if (it % 2 == 0) 8_000_000L else 8_700_000L }
        assertEquals(0, stuttersIn(jittery, jittery.size))
    }

    @Test
    fun onlyKeptGapsCountAndNoneIsNothing() {
        val trace = gaps(8_333_333L, 100, mapOf(50 to 25_000_000L))
        assertEquals(1, stuttersIn(trace, 500))
        assertEquals(0, stuttersIn(trace, 0))
    }
}
