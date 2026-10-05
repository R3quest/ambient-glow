package com.example.ambientglow.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapDotTest {
    private val corner = DotSpot(label = 0, x = 0.06f, y = 0.008f, onCameraLine = false)
    private val lens = DotSpot(label = 0, x = 0.5f, y = 0.02f, onCameraLine = false, camera = true)

    @Test
    fun snapsOntoANearbySpot() {
        val snapped = snapDot(0.07f, 0.02f, listOf(corner, lens))
        assertEquals(Snapped(corner.x, corner.y, corner), snapped)
    }

    @Test
    fun snapsEachAxisToTheRulerQuarters() {
        val snapped = snapDot(0.255f, 0.4f, emptyList())
        assertEquals(0.25f, snapped.x, 0f)
        assertEquals(0.4f, snapped.y, 0f)
        assertEquals(0.25f to null, snapped.target)
    }

    @Test
    fun leavesAFreePositionAlone() {
        val snapped = snapDot(0.33f, 0.61f, listOf(corner, lens))
        assertEquals(0.33f, snapped.x, 0f)
        assertEquals(0.61f, snapped.y, 0f)
        assertNull(snapped.target)
    }
}
