package com.example.ambientglow.dashboard

import com.example.ambientglow.ArrivalMode
import com.example.ambientglow.EdgeColor
import com.example.ambientglow.EdgeGlow
import com.example.ambientglow.EdgeMotion
import com.example.ambientglow.EdgeWidth
import com.example.ambientglow.GlassArea
import com.example.ambientglow.GlassBlur
import com.example.ambientglow.GlassFrost
import com.example.ambientglow.GlowSettings
import com.example.ambientglow.R
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryTest {

    private val glass = GlowSettings(
        arrival = ArrivalMode.LOCK_SCREEN,
        glassBlur = GlassBlur.MEDIUM,
        glassFrost = GlassFrost.SOFT,
        glassArea = GlassArea.REVEAL,
    )

    @Test
    fun edgeFrameSaysEveryOptionInOrder() {
        val settings = GlowSettings(
            edgeMotion = EdgeMotion.TWIN,
            edgeColor = EdgeColor.DUO,
            edgeWidth = EdgeWidth.THIN,
            edgeGlow = EdgeGlow.OFF,
        )
        assertEquals(
            Phrasing(
                R.string.edge_summary,
                listOf(
                    R.string.edge_motion_twin_phrase,
                    R.string.edge_color_duo_phrase,
                    R.string.edge_width_thin_phrase,
                    R.string.edge_glow_off_phrase,
                ),
            ),
            edgePhrasing(settings),
        )
    }

    @Test
    fun glassWithBlurAndFrostSaysBothAndTheArea() {
        assertEquals(
            Phrasing(
                R.string.glass_summary_both,
                listOf(R.string.glass_blur_medium_phrase, R.string.glass_frost_soft_phrase, R.string.glass_area_reveal_phrase),
            ),
            glassPhrasing(glass),
        )
    }

    @Test
    fun glassLeavesOutWhatIsOff() {
        assertEquals(
            Phrasing(R.string.glass_summary_one, listOf(R.string.glass_frost_soft_phrase, R.string.glass_area_reveal_phrase)),
            glassPhrasing(glass.copy(glassBlur = GlassBlur.OFF)),
        )
        assertEquals(
            Phrasing(R.string.glass_summary_one, listOf(R.string.glass_blur_medium_phrase, R.string.glass_area_reveal_phrase)),
            glassPhrasing(glass.copy(glassFrost = GlassFrost.OFF)),
        )
        assertEquals(
            Phrasing(R.string.glass_summary_none),
            glassPhrasing(glass.copy(glassBlur = GlassBlur.OFF, glassFrost = GlassFrost.OFF)),
        )
    }

    @Test
    fun glassOnABlackScreenSaysOnlyTheFrost() {
        // Nothing to blur on black, and no area to choose: the blur setting is ignored there.
        for (mode in listOf(ArrivalMode.BLACK, ArrivalMode.MESSAGE)) {
            val onBlack = glass.copy(arrival = mode)
            assertEquals(
                Phrasing(R.string.glass_summary_black, listOf(R.string.glass_frost_soft_phrase)),
                glassPhrasing(onBlack),
            )
            assertEquals(Phrasing(R.string.glass_summary_none), glassPhrasing(onBlack.copy(glassFrost = GlassFrost.OFF)))
        }
    }

    @Test
    fun aSentenceStartsWithACapitalAndKeepsTheRest() {
        assertEquals("Two lights in the app's colour.", sentence("two lights in the app's colour."))
        assertEquals("LED", sentence("LED"))
        assertEquals("", sentence(""))
    }
}
