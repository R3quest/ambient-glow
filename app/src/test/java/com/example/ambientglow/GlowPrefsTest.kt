package com.example.ambientglow

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlowPrefsTest {

    /** Every choice set away from its default, so a field that isn't stored or read shows up. */
    private val changed = GlowSettings(
        style = GlowStyle.EDGE_FRAME,
        dotX = 0.3f,
        dotY = 0.7f,
        dotSize = DotSize.LARGE,
        ledOnCamera = true,
        lensOffsetDp = 1.5f,
        lensOffsetXDp = -2f,
        lensGrowDp = 0.5f,
        ledBrightness = LedBrightness.SOFT,
        arrival = ArrivalMode.LED_ONLY,
        spawn = false,
        element = SpawnElement.EARTH,
        glassBlur = GlassBlur.STRONG,
        glassArea = GlassArea.SCREEN,
        glassFrost = GlassFrost.MILKY,
        fireFlames = FireFlames.INFERNO,
        fireColor = FireColor.APP,
        fireSparks = FireSparks.SHOWER,
        fireWake = FireWake.COALS,
        edgeWidth = EdgeWidth.HEAVY,
        edgeGlow = EdgeGlow.STRONG,
        edgeMotion = EdgeMotion.PULSE,
        edgeColor = EdgeColor.SPECTRUM,
    )

    @Test
    fun theSampleChangesEverySetting() {
        // A new setting must be added to [changed] too, or the round trip can't catch a missing line.
        val defaults = GlowSettings()
        GlowSettings::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                field.isAccessible = true
                assertNotEquals("${field.name} is left at its default", field.get(defaults), field.get(changed))
            }
    }

    @Test
    fun everySettingSurvivesSaveAndLoad() {
        val prefs = FakePrefs()
        GlowPrefs.save(prefs, changed)
        assertEquals(changed, GlowPrefs.load(prefs))
    }

    @Test
    fun aFreshInstallLoadsTheDefaults() {
        assertEquals(GlowSettings(), GlowPrefs.load(FakePrefs()))
    }

    @Test
    fun aRemovedElementLoadsAsWater() {
        // The plain wave was once an element ("NONE", then "PLAIN").
        for (gone in listOf("NONE", "PLAIN")) {
            assertEquals(SpawnElement.WATER, GlowPrefs.load(FakePrefs(mapOf("element" to gone))).element)
        }
    }

    @Test
    fun anUnknownChoiceFallsBackToItsDefault() {
        val loaded = GlowPrefs.load(FakePrefs(mapOf("arrival" to "SOMETHING_NEW", "style" to "")))
        assertEquals(GlowSettings().arrival, loaded.arrival)
        assertEquals(GlowSettings().style, loaded.style)
    }

    @Test
    fun theOldGlassSwitchIsClearedOnSave() {
        val prefs = FakePrefs(mapOf("glass" to true))
        GlowPrefs.save(prefs, GlowPrefs.load(prefs))
        assertFalse(prefs.contains("glass"))
    }

    @Test
    fun anOutOfRangeDotIsKeptOnScreen() {
        val loaded = GlowPrefs.load(FakePrefs(mapOf("dot_x" to 1.4f, "dot_y" to -0.2f)))
        assertEquals(1f, loaded.dotX, 0f)
        assertEquals(0f, loaded.dotY, 0f)
    }
}
