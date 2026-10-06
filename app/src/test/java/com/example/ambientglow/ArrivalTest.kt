package com.example.ambientglow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalTest {

    @Test
    fun onlyJustTheLedSkipsTheEffect() {
        ArrivalMode.entries.forEach { mode ->
            assertEquals(mode.name, mode != ArrivalMode.LED_ONLY, mode.playsEffect)
        }
    }

    @Test
    fun onlyTheLockScreenModeLightsTheLockScreen() {
        ArrivalMode.entries.forEach { mode ->
            assertEquals(mode.name, mode != ArrivalMode.LOCK_SCREEN, mode.onBlack)
        }
    }

    @Test
    fun withTheScreenOnEveryModeOpensOnTheLockScreen() {
        // The user may be unlocking: nothing covers what they are looking at.
        ArrivalMode.entries.forEach { mode ->
            assertEquals(mode.name, WakeMode.WAKE, wakeModeFor(mode, screenOn = true))
        }
    }

    @Test
    fun withTheScreenOffEachModeLightsItsOwnWay() {
        assertEquals(WakeMode.WAKE, wakeModeFor(ArrivalMode.LOCK_SCREEN, screenOn = false))
        assertEquals(WakeMode.ARRIVAL, wakeModeFor(ArrivalMode.BLACK, screenOn = false))
        assertEquals(WakeMode.ARRIVAL, wakeModeFor(ArrivalMode.MESSAGE, screenOn = false))
        // No effect to play: straight into the LED.
        assertEquals(WakeMode.LED, wakeModeFor(ArrivalMode.LED_ONLY, screenOn = false))
    }

    @Test
    fun waterIsTheFreeElementAndTheOnlyGlassWave() {
        SpawnElement.entries.forEach { element ->
            val settings = GlowSettings(element = element)
            assertEquals(element.name, element == SpawnElement.WATER, settings.glass)
            assertEquals(element.name, element != SpawnElement.WATER, element.premium)
        }
    }

    @Test
    fun fireHasALookOfItsOwnAndIsTheOnlyFireWave() {
        assertTrue(SpawnElement.FIRE.ready)
        SpawnElement.entries.forEach { element ->
            assertEquals(element.name, element == SpawnElement.FIRE, GlowSettings(element = element).fire)
        }
    }

    @Test
    fun airHasALookOfItsOwnAndIsTheOnlyAirWave() {
        assertTrue(SpawnElement.AIR.ready)
        SpawnElement.entries.forEach { element ->
            assertEquals(element.name, element == SpawnElement.AIR, GlowSettings(element = element).air)
        }
    }

    @Test
    fun onlyWaterAndAirBlurTheScreenAndEachByItsOwnChoice() {
        val blurred = GlowSettings(glassBlur = GlassBlur.STRONG, airBlur = GlassBlur.LIGHT)
        assertEquals(GlassBlur.STRONG, blurred.copy(element = SpawnElement.WATER).hazeBlur)
        assertEquals(GlassBlur.LIGHT, blurred.copy(element = SpawnElement.AIR).hazeBlur)
        // Air's blur always rides the band under its front, whatever Water's area is set to.
        assertEquals(GlassArea.WAVE, blurred.copy(element = SpawnElement.AIR, glassArea = GlassArea.SCREEN).hazeArea)
        for (element in listOf(SpawnElement.FIRE, SpawnElement.EARTH)) assertFalse(element.name, blurred.copy(element = element).hazes)
        assertFalse(blurred.copy(element = SpawnElement.AIR, airBlur = GlassBlur.OFF).hazes)
        assertFalse(blurred.copy(element = SpawnElement.AIR, spawn = false).hazes)
    }

    @Test
    fun aFreshInstallPlaysTheFreeElement() {
        val defaults = GlowSettings()
        assertEquals(SpawnElement.WATER, defaults.element)
        assertFalse(defaults.element.premium)
        assertTrue(defaults.element.ready)
    }
}
