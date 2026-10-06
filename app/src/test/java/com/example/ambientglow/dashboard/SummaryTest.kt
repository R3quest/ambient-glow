package com.example.ambientglow.dashboard

import com.example.ambientglow.AirCarry
import com.example.ambientglow.AirColor
import com.example.ambientglow.AirFlow
import com.example.ambientglow.AirGust
import com.example.ambientglow.ArrivalMode
import com.example.ambientglow.EdgeColor
import com.example.ambientglow.EdgeGlow
import com.example.ambientglow.EdgeMotion
import com.example.ambientglow.EdgeWidth
import com.example.ambientglow.FireColor
import com.example.ambientglow.FireFlames
import com.example.ambientglow.FireSparks
import com.example.ambientglow.FireWake
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

    private val fire = GlowSettings(
        arrival = ArrivalMode.LOCK_SCREEN,
        fireFlames = FireFlames.BLAZE,
        fireColor = FireColor.NATURAL,
        fireSparks = FireSparks.FEW,
        fireWake = FireWake.BURN,
    )

    @Test
    fun fireSaysFlamesColourWakeAndSparks() {
        assertEquals(
            Phrasing(
                R.string.fire_summary_sparks,
                listOf(
                    R.string.fire_flames_blaze_phrase,
                    R.string.fire_color_natural_phrase,
                    R.string.fire_wake_burn_phrase,
                    R.string.fire_sparks_few_phrase,
                ),
            ),
            firePhrasing(fire),
        )
    }

    @Test
    fun fireLeavesOutSparksWhenThereAreNone() {
        assertEquals(
            Phrasing(
                R.string.fire_summary,
                listOf(R.string.fire_flames_inferno_phrase, R.string.fire_color_app_phrase, R.string.fire_wake_coals_phrase),
            ),
            firePhrasing(fire.copy(fireFlames = FireFlames.INFERNO, fireColor = FireColor.APP, fireSparks = FireSparks.OFF, fireWake = FireWake.COALS)),
        )
    }

    @Test
    fun fireOnABlackScreenHasNoCharToBurnOpen() {
        for (mode in listOf(ArrivalMode.BLACK, ArrivalMode.MESSAGE)) {
            assertEquals(R.string.fire_wake_burn_black_phrase, firePhrasing(fire.copy(arrival = mode)).phrases[2])
            // Coals and Clean show on black as they do anywhere.
            assertEquals(R.string.fire_wake_coals_phrase, firePhrasing(fire.copy(arrival = mode, fireWake = FireWake.COALS)).phrases[2])
        }
    }

    private val air = GlowSettings(
        airGust = AirGust.GUST,
        airFlow = AirFlow.CURLS,
        airColor = AirColor.CLEAR,
        airCarry = AirCarry.PETALS,
        airBlur = GlassBlur.LIGHT,
        arrival = ArrivalMode.LOCK_SCREEN,
    )

    private val airParts = listOf(R.string.air_gust_gust_phrase, R.string.air_flow_curls_phrase, R.string.air_color_clear_phrase)

    @Test
    fun airSaysStrengthLinesColourWhatItCarriesAndTheBlur() {
        assertEquals(
            Phrasing(R.string.air_summary_carry_blur, airParts + R.string.air_carry_petals_phrase + R.string.glass_blur_light_phrase),
            airPhrasing(air),
        )
    }

    @Test
    fun airLeavesOutWhatIsOff() {
        assertEquals(Phrasing(R.string.air_summary_carry, airParts + R.string.air_carry_petals_phrase), airPhrasing(air.copy(airBlur = GlassBlur.OFF)))
        assertEquals(Phrasing(R.string.air_summary_blur, airParts + R.string.glass_blur_light_phrase), airPhrasing(air.copy(airCarry = AirCarry.NONE)))
        assertEquals(
            Phrasing(
                R.string.air_summary,
                listOf(R.string.air_gust_gale_phrase, R.string.air_flow_vortex_phrase, R.string.air_color_blend_phrase),
            ),
            airPhrasing(
                air.copy(airGust = AirGust.GALE, airFlow = AirFlow.VORTEX, airColor = AirColor.BLEND, airCarry = AirCarry.NONE, airBlur = GlassBlur.OFF),
            ),
        )
    }

    @Test
    fun airOnABlackScreenHasNothingToBlur() {
        for (mode in listOf(ArrivalMode.BLACK, ArrivalMode.MESSAGE)) {
            assertEquals(Phrasing(R.string.air_summary_carry, airParts + R.string.air_carry_petals_phrase), airPhrasing(air.copy(arrival = mode)))
        }
    }

    @Test
    fun aSentenceStartsWithACapitalAndKeepsTheRest() {
        assertEquals("Two lights in the app's colour.", sentence("two lights in the app's colour."))
        assertEquals("LED", sentence("LED"))
        assertEquals("", sentence(""))
    }
}
