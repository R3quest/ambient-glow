package com.example.ambientglow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlowSettingsTest {

    @Test
    fun movingTheLedDoesNotRestartAnEdgeFramePreview() {
        val edge = GlowSettings(style = GlowStyle.EDGE_FRAME)
        val moved = edge.copy(dotX = 0.7f, dotY = 0.4f, dotSize = DotSize.LARGE, ledOnCamera = true, lensGrowDp = 2f)
        assertEquals(edge.forPreview(), moved.forPreview())
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
