package com.example.ambientglow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The LED's layer is only as big as its light: everything it draws must fit, centred. */
class LedLightTest {
    @Test
    fun dotLayerHoldsTheBloom() {
        for (core in listOf(3f, 7.5f, 12.25f, 30f)) {
            val light = ledLight(core, onCamera = false, lens = 0f, ringGrowPx = 0f, ringGap = 0f)
            assertEquals(core * LED_HALO_FACTOR, light.bloom, 1e-5f)
            assertFits(light)
        }
    }

    @Test
    fun ringKeepsItsShape() {
        val core = 9f
        val lens = 36f
        val gap = 4.5f
        for (grow in listOf(-1f, 0f, 1f)) {
            val light = ledLight(core, onCamera = true, lens = lens, ringGrowPx = grow, ringGap = gap)
            val line = core * LED_RING_STROKE_FACTOR
            assertEquals(line, light.line, 1e-5f)
            assertEquals(lens + gap + line / 2f + grow, light.ring, 1e-5f)
            assertEquals(light.ring + line / 2f + core * (LED_HALO_FACTOR - 1f), light.bloom, 1e-5f)
            // The stroke's outer edge lies inside the bloom.
            assertTrue(light.ring + line / 2f <= light.bloom)
            assertFits(light)
        }
    }

    @Test
    fun lensRadiusIsLensWithoutTheWidth() {
        val spot = CutoutSpot(540f, 60f, 30f)
        for (geometry in listOf(ScreenGeometry(spot, null), ScreenGeometry.Unknown)) {
            for (width in listOf(0f, 400f, 1080f)) {
                assertEquals(geometry.lens(width, 3f, 0.5f).radius, geometry.lensRadius(3f, 0.5f), 0f)
            }
        }
    }

    /** Even, so the light sits exactly at the centre, with room for antialiasing past the bloom. */
    private fun assertFits(light: LedLight) {
        assertEquals(0, light.side % 2)
        assertTrue(light.side / 2f >= light.bloom + 1f)
    }
}
