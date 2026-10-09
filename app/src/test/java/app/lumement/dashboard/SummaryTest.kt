package app.lumement.dashboard

import app.lumement.AirCarry
import app.lumement.AirColor
import app.lumement.AirFlow
import app.lumement.AirGust
import app.lumement.ArrivalMode
import app.lumement.EarthColor
import app.lumement.EarthDebris
import app.lumement.EarthForce
import app.lumement.EarthForm
import app.lumement.EdgeColor
import app.lumement.EdgeGlow
import app.lumement.EdgeMaterial
import app.lumement.EdgeMotion
import app.lumement.EdgeWidth
import app.lumement.FireColor
import app.lumement.FireFlames
import app.lumement.FireSparks
import app.lumement.FireWake
import app.lumement.GlassArea
import app.lumement.GlassBlur
import app.lumement.GlassFrost
import app.lumement.GlowSettings
import app.lumement.R
import app.lumement.SpawnElement
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
            edgePhrasing(settings.copy(edgeMaterial = EdgeMaterial.NEON), shaders = true),
        )
    }

    @Test
    fun anElementalFrameSaysWhatItIsMadeOfInPlaceOfItsColour() {
        val settings = GlowSettings(edgeMaterial = EdgeMaterial.ELEMENT, element = SpawnElement.FIRE, edgeColor = EdgeColor.DUO)
        assertEquals(R.string.edge_material_fire_phrase, edgePhrasing(settings, shaders = true).phrases[1])
        assertEquals(R.string.edge_material_water_phrase, edgePhrasing(settings.copy(element = SpawnElement.WATER), shaders = true).phrases[1])
        assertEquals(R.string.edge_material_air_phrase, edgePhrasing(settings.copy(element = SpawnElement.AIR), shaders = true).phrases[1])
        val earth = settings.copy(element = SpawnElement.EARTH)
        // Mended in gold only in its own stone; in the app's colour or crystal it is a glowing crack.
        assertEquals(R.string.edge_material_earth_phrase, edgePhrasing(earth.copy(earthColor = EarthColor.STONE), shaders = true).phrases[1])
        assertEquals(R.string.edge_material_earth_lit_phrase, edgePhrasing(earth.copy(earthColor = EarthColor.CRYSTAL), shaders = true).phrases[1])
    }

    @Test
    fun anElementalFrameThatCantPlaySaysItsNeonColour() {
        val settings = GlowSettings(edgeMaterial = EdgeMaterial.ELEMENT, element = SpawnElement.FIRE, edgeColor = EdgeColor.DUO)
        // No spawn wave to carry the element, or no runtime shaders: it plays as neon, and says so.
        assertEquals(R.string.edge_color_duo_phrase, edgePhrasing(settings.copy(spawn = false), shaders = true).phrases[1])
        assertEquals(R.string.edge_color_duo_phrase, edgePhrasing(settings, shaders = false).phrases[1])
        assertEquals(listOf(R.string.edge_material_needs_spawn), materialBody(settings.copy(spawn = false), shaders = true))
        assertEquals(listOf(R.string.edge_material_needs_shaders), materialBody(settings, shaders = false))
    }

    @Test
    fun anElementalFramePointsToTheElementsColoursExceptWatersWhichHasNone() {
        val settings = GlowSettings(edgeMaterial = EdgeMaterial.ELEMENT)
        assertEquals(
            listOf(R.string.edge_material_fire_body, R.string.edge_material_element_colours),
            materialBody(settings.copy(element = SpawnElement.FIRE), shaders = true),
        )
        assertEquals(listOf(R.string.edge_material_water_body), materialBody(settings.copy(element = SpawnElement.WATER), shaders = true))
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
    fun airSaysStrengthLinesColourWhatItCarriesAndTheSmear() {
        assertEquals(
            Phrasing(R.string.air_summary_carry_blur, airParts + R.string.air_carry_petals_phrase + R.string.air_smear_light_phrase),
            airPhrasing(air),
        )
    }

    @Test
    fun airLeavesOutWhatIsOff() {
        assertEquals(Phrasing(R.string.air_summary_carry, airParts + R.string.air_carry_petals_phrase), airPhrasing(air.copy(airBlur = GlassBlur.OFF)))
        assertEquals(Phrasing(R.string.air_summary_blur, airParts + R.string.air_smear_light_phrase), airPhrasing(air.copy(airCarry = AirCarry.NONE)))
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
    fun airOnABlackScreenHasNothingToSmear() {
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

    private val earth = GlowSettings(
        earthForce = EarthForce.QUAKE,
        earthForm = EarthForm.SPIRES,
        earthColor = EarthColor.STONE,
        earthDebris = EarthDebris.DUST,
    )

    @Test
    fun earthSaysForceGroundColourAndWhatItThrowsUp() {
        assertEquals(
            Phrasing(
                R.string.earth_summary_debris,
                listOf(
                    R.string.earth_force_quake_phrase,
                    R.string.earth_form_spires_phrase,
                    R.string.earth_color_stone_phrase,
                    R.string.earth_debris_dust_phrase,
                ),
            ),
            earthPhrasing(earth),
        )
    }

    @Test
    fun earthLeavesOutWhatItThrowsUpWhenNothing() {
        assertEquals(
            Phrasing(
                R.string.earth_summary,
                listOf(R.string.earth_force_tremor_phrase, R.string.earth_form_faults_phrase, R.string.earth_color_crystal_phrase),
            ),
            earthPhrasing(
                earth.copy(earthForce = EarthForce.TREMOR, earthForm = EarthForm.FAULTS, earthColor = EarthColor.CRYSTAL, earthDebris = EarthDebris.NONE),
            ),
        )
    }
}
