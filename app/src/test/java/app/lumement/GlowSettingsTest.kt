package app.lumement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the settings derive: the LED's shape and flags, the shape a screen allows, and what restarts a preview. */
class GlowSettingsTest {

    @Test
    fun movingTheLedRestartsAnEdgeFramePreviewWhoseLastLightFliesThere() {
        val edge = GlowSettings(style = GlowStyle.EDGE_FRAME, ledForm = LedForm.DOT)
        assertNotEquals(edge.forPreview(), edge.copy(dotX = 0.7f).forPreview())
        assertNotEquals(edge.forPreview(), edge.copy(dotSize = DotSize.LARGE).forPreview())
        assertNotEquals(edge.forPreview(), edge.copy(ledForm = LedForm.RING).forPreview())
        assertNotEquals(edge.forPreview(), edge.copy(ledForm = LedForm.FRAME).forPreview())
        // The lens fit still reaches it only through its geometry.
        assertEquals(edge.forPreview(), edge.copy(lensGrowDp = 2f).forPreview())
    }

    @Test
    fun movingTheLedRestartsABeaconPreviewThatPlaysThere() {
        val beacon = GlowSettings(style = GlowStyle.BEACON, ledForm = LedForm.DOT)
        assertNotEquals(beacon.forPreview(), beacon.copy(dotX = 0.7f).forPreview())
        assertNotEquals(beacon.forPreview(), beacon.copy(ledForm = LedForm.RING).forPreview())
        assertNotEquals(beacon.forPreview(), beacon.copy(ledForm = LedForm.FRAME).forPreview())
    }

    /** The beacon's light runs out into the frame LED, which wears the Edge Frame's colours, line and glow. */
    @Test
    fun aFrameBeaconPreviewTakesTheEdgeFramesLook() {
        val base = GlowSettings(style = GlowStyle.BEACON, ledForm = LedForm.FRAME)
        assertEquals(LedForm.FRAME, base.forPreview().ledForm)
        assertNotEquals(base.forPreview(), base.copy(edgeWidth = EdgeWidth.HEAVY).forPreview())
        assertNotEquals(base.forPreview(), base.copy(edgeGlow = EdgeGlow.OFF).forPreview())
        assertNotEquals(base.forPreview(), base.copy(edgeColor = EdgeColor.SPECTRUM).forPreview())
    }

    @Test
    fun eachFormHasItsFlags() {
        val dot = GlowSettings(ledForm = LedForm.DOT)
        assertFalse(dot.ledOnCamera)
        assertFalse(dot.ledOnEdge)
        val ring = GlowSettings(ledForm = LedForm.RING)
        assertTrue(ring.ledOnCamera)
        assertFalse(ring.ledOnEdge)
        val frame = GlowSettings(ledForm = LedForm.FRAME)
        assertFalse(frame.ledOnCamera)
        assertTrue(frame.ledOnEdge)
    }

    @Test
    fun aRingIsKeptWhereTheCameraIsOrNotYetKnown() {
        val ring = GlowSettings(ledForm = LedForm.RING)
        assertEquals(LedForm.RING, ring.forScreen(ScreenGeometry(CutoutSpot(540f, 60f, 30f), null)).ledForm)
        assertEquals(LedForm.RING, ring.forScreen(ScreenGeometry(null, null, measured = false)).ledForm)
        // Only a screen measured with no camera turns it into a dot; a dot stays one.
        assertEquals(LedForm.DOT, ring.forScreen(ScreenGeometry(null, null)).ledForm)
        assertEquals(LedForm.DOT, GlowSettings(ledForm = LedForm.DOT).forScreen(ScreenGeometry(null, null)).ledForm)
    }

    @Test
    fun lensFitNeverReachesThePreview() {
        val base = GlowSettings(style = GlowStyle.BEACON, ledForm = LedForm.RING)
        val changed = base.copy(lensOffsetDp = 3f, lensOffsetXDp = -1f)
        assertEquals(base.forPreview(), changed.forPreview())
    }

    /** The Screen tab's Bright LED and Then rows sit under the preview; the effect never reads them. */
    @Test
    fun theBrightPhaseNeverReachesThePreview() {
        val base = GlowSettings()
        assertEquals(base.forPreview(), base.copy(brightFor = BrightFor.OFF, afterBright = AfterBright.OFF).forPreview())
    }
}
