package app.lumement.dashboard

import app.lumement.DotSize
import app.lumement.GlowSettings
import app.lumement.LedForm
import app.lumement.R
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedResizeTest {
    // An S23's window: 1080 × 2340 px at 2.625 px per dp, the punch-hole top centre.
    private val camera = ScreenCamera(x = 0.5f, y = 0.017f, radius = 0.016f)
    private val window = IntSize(1080, 2340)
    private val density = Density(2.625f)
    private fun spots(size: DotSize) = dotSpots(camera, window, size, density)

    private fun on(label: Int, size: DotSize) = spots(size).first { it.label == label && !it.camera }

    @Test
    fun aDotOnASpotStaysOnItWhenItGrows() {
        val small = on(R.string.dot_spot_edge_left, DotSize.SMALL)
        val large = on(R.string.dot_spot_edge_left, DotSize.LARGE)
        // The spot moves with the size: the larger glow keeps it further from the edge.
        assertNotEquals(small.x, large.x)
        val settings = GlowSettings(dotX = small.x, dotY = small.y, dotSize = DotSize.SMALL, ledForm = LedForm.DOT)

        val grown = resized(settings, DotSize.LARGE, spots(DotSize.SMALL), spots(DotSize.LARGE))

        assertEquals(DotSize.LARGE, grown.dotSize)
        assertTrue(large.matches(grown.dotX, grown.dotY))
    }

    @Test
    fun everySpotCarriesOverBothWays() {
        for (from in DotSize.entries) {
            for (to in DotSize.entries) {
                for (spot in spots(from)) {
                    val settings = GlowSettings(dotX = spot.x, dotY = spot.y, dotSize = from)
                    val moved = resized(settings, to, spots(from), spots(to))
                    val same = spots(to).first { it.label == spot.label && it.camera == spot.camera }
                    assertTrue("$spot from $from to $to", same.matches(moved.dotX, moved.dotY))
                }
            }
        }
    }

    @Test
    fun theRingStaysOnTheLens() {
        val ring = spots(DotSize.LED).first { it.camera }
        val settings = GlowSettings(dotX = ring.x, dotY = ring.y, dotSize = DotSize.LED, ledForm = LedForm.RING)

        val thicker = resized(settings, DotSize.MEDIUM, spots(DotSize.LED), spots(DotSize.MEDIUM))

        assertTrue(spots(DotSize.MEDIUM).first { it.camera }.matches(thicker.dotX, thicker.dotY))
        assertEquals(LedForm.RING, thicker.ledForm)
    }

    @Test
    fun aDotLeftAnywhereElseKeepsItsPlace() {
        val settings = GlowSettings(dotX = 0.31f, dotY = 0.62f, dotSize = DotSize.SMALL, ledForm = LedForm.DOT)

        val grown = resized(settings, DotSize.LARGE, spots(DotSize.SMALL), spots(DotSize.LARGE))

        assertEquals(0.31f, grown.dotX, 0f)
        assertEquals(0.62f, grown.dotY, 0f)
        assertEquals(DotSize.LARGE, grown.dotSize)
    }
}
