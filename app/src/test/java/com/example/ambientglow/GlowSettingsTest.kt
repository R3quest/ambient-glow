package com.example.ambientglow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlowSettingsTest {

    @Test
    fun movingTheLedRestartsAnEdgeFramePreviewWhoseLastLightFliesThere() {
        val edge = GlowSettings(style = GlowStyle.EDGE_FRAME, ledOnCamera = false)
        assertNotEquals(edge.forPreview(), edge.copy(dotX = 0.7f).forPreview())
        assertNotEquals(edge.forPreview(), edge.copy(dotSize = DotSize.LARGE).forPreview())
        assertNotEquals(edge.forPreview(), edge.copy(ledOnCamera = true).forPreview())
        // The lens fit still reaches it only through its geometry.
        assertEquals(edge.forPreview(), edge.copy(lensGrowDp = 2f).forPreview())
    }

    @Test
    fun movingTheLedRestartsABeaconPreviewThatPlaysThere() {
        val beacon = GlowSettings(style = GlowStyle.BEACON, ledOnCamera = false)
        assertNotEquals(beacon.forPreview(), beacon.copy(dotX = 0.7f).forPreview())
        assertNotEquals(beacon.forPreview(), beacon.copy(ledOnCamera = true).forPreview())
    }

    @Test
    fun lensFitNeverReachesThePreview() {
        val base = GlowSettings(style = GlowStyle.BEACON, ledOnCamera = true)
        val changed = base.copy(lensOffsetDp = 3f, lensOffsetXDp = -1f)
        assertEquals(base.forPreview(), changed.forPreview())
    }
}
