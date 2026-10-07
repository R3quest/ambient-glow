package com.example.ambientglow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
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

    @Test
    fun effectsLandOnTheLedAsItLights() {
        val density = Density(3f)
        val lens = CutoutSpot(540f, 60f, 30f)
        val size = Size(1080f, 2340f)
        val scale = 0.5f
        with(density) {
            val core = ledRadiusAt(DotSize.MEDIUM, scale).toPx()
            // On the camera: the ring round the lens, as LedDot lights its first breath.
            val ring = ledLanding(GlowSettings(dotSize = DotSize.MEDIUM, ledOnCamera = true), lens, size, scale)
            assertEquals(lens.center, ring.center)
            assertEquals(lens.radius + ledRingGapAt(scale).toPx() + core * LED_RING_STROKE_FACTOR / 2f, ring.light.ring, 1e-4f)
            // A dot in the corner: still fully on screen, its halo touching the edges.
            val dot = ledLanding(GlowSettings(dotSize = DotSize.MEDIUM, ledOnCamera = false, dotX = 1f, dotY = 1f), lens, size, scale)
            val margin = core * DOT_HALO_FACTOR
            assertEquals(Offset(size.width - margin, size.height - margin), dot.center)
            assertEquals(core, dot.light.core, 0f)
        }
    }

    /** Even, so the light sits exactly at the centre, with room for antialiasing past the bloom. */
    private fun assertFits(light: LedLight) {
        assertEquals(0, light.side % 2)
        assertTrue(light.side / 2f >= light.bloom + 1f)
    }
}
