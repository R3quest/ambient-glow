package com.example.ambientglow

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OklabTest {
    private fun assertClose(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, 2e-3f)
        assertEquals(expected.green, actual.green, 2e-3f)
        assertEquals(expected.blue, actual.blue, 2e-3f)
    }

    @Test
    fun whiteIsFullLightnessWithNoChroma() {
        val (l, c) = toOklch(Color.White)
        assertEquals(1f, l, 1e-3f)
        assertEquals(0f, c, 1e-3f)
    }

    @Test
    fun oklchRoundTripsAnInGamutColour() {
        val brand = Color(0.15f, 0.83f, 0.4f)
        val (l, c, h) = toOklch(brand)
        assertClose(brand, oklch(l, c, h))
    }

    @Test
    fun distanceIsZeroToItselfAndOneFromBlackToWhite() {
        val brand = Color(DEFAULT_GLOW_COLOR)
        assertEquals(0f, oklabDistance(brand, brand), 1e-6f)
        assertEquals(1f, oklabDistance(Color.Black, Color.White), 1e-3f)
    }

    @Test
    fun mixingAColourWithItselfKeepsIt() {
        val brand = Color(DEFAULT_GLOW_COLOR)
        assertClose(brand, oklabMix(brand, brand))
    }

    @Test
    fun spectrumHuesStayInsideSrgb() {
        for (step in 0 until 360 step 15) {
            val c = oklch(0.72f, 0.18f, step.toFloat())
            for (channel in floatArrayOf(c.red, c.green, c.blue)) assertTrue("hue $step out of gamut", channel in 0f..1f)
        }
    }
}
