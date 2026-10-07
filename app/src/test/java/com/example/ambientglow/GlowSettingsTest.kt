package com.example.ambientglow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlowSettingsTest {

    @Test
    fun movingTheLedRestartsAnEdgeFramePreviewWhoseLastLightFliesThere() {
        val edge = GlowSettings(style = GlowStyle.EDGE_FRAME)
        assertNotEquals(edge.forPreview(), edge.copy(dotX = 0.7f).forPreview())
        assertNotEquals(edge.forPreview(), edge.copy(dotSize = DotSize.LARGE).forPreview())
        assertNotEquals(edge.forPreview(), edge.copy(ledOnCamera = true).forPreview())
        // The lens fit still reaches it only through its geometry.
        assertEquals(edge.forPreview(), edge.copy(lensGrowDp = 2f).forPreview())
    }

    @Test
    fun movingTheDotRestartsACustomDotPreview() {
        val dot = GlowSettings(style = GlowStyle.CUSTOM_DOT)
        assertNotEquals(dot.forPreview(), dot.copy(dotX = 0.7f).forPreview())
    }

    @Test
    fun ledBrightnessAndLensFitNeverReachThePreview() {
        val base = GlowSettings(style = GlowStyle.CAMERA_RING)
        val changed = base.copy(ledBrightness = LedBrightness.SOFT, lensOffsetDp = 3f, lensOffsetXDp = -1f)
        assertEquals(base.forPreview(), changed.forPreview())
    }
}
