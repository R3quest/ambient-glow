package app.lumement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The effect and LED curves: pure functions of one clock, so their shape can be pinned down here. */
class TimelineTest {

    @Test
    fun smoothstepClampsAndEases() {
        assertEquals(0f, smoothstep(1f, 2f, 0f), 0f)
        assertEquals(0.5f, smoothstep(1f, 2f, 1.5f), 1e-6f)
        assertEquals(1f, smoothstep(1f, 2f, 3f), 0f)
    }

    @Test
    fun solveRisingFindsTheCrossing() {
        assertEquals(1f, solveRising(1f, 0f, 2f, steps = 24) { it * it }, 1e-5f)
    }

    @Test
    fun ledBreathRisesToFullThenEndsDark() {
        assertEquals(0f, ledBreathAt(0f), 0f)
        assertEquals(1f, ledBreathAt(LED_RISE_MS), 1e-4f)
        assertEquals(0f, ledBreathAt(LED_BREATH_MS), 0f)
        var last = 0f
        for (i in 1..100) {
            val level = ledBreathAt(LED_RISE_MS * i / 100f)
            assertTrue("rise must not dip at step $i", level >= last)
            last = level
        }
    }

    @Test
    fun spawnWaveGrowsFromTheCameraToItsReach() {
        assertEquals(0f, spawnWaveAt(0f), 0f)
        assertEquals(0f, spawnWaveAt(SPAWN_GATHER_MS), 1e-6f)
        assertEquals(1f, spawnWaveAt(SPAWN_GATHER_MS + SPAWN_MS), 1e-6f)
        var last = 0f
        for (ms in 0..(SPAWN_GATHER_MS + SPAWN_MS).toInt() step 10) {
            val wave = spawnWaveAt(ms.toFloat())
            assertTrue("wave must not shrink at $ms ms", wave >= last)
            last = wave
        }
    }

    @Test
    fun everyGlassAreaRisesAndHasClearedByItsEnd() {
        for (area in GlassArea.entries) {
            val end = hazeEndMs(area)
            val peak = (0..end.toInt()).maxOf { hazeAt(it.toFloat(), area) }
            assertTrue("$area never blurs", peak > 0.5f)
            assertEquals("$area still blurred at its end", 0f, hazeAt(end + 1f, area), 1e-4f)
        }
    }
}
