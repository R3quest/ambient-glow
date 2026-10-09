package com.example.ambientglow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumTest {
    private val day = 24 * 60 * 60 * 1000L
    private val start = 1_000 * day

    @Test
    fun untilAMessagePlaysItTheTrialHasNotStarted() {
        assertEquals(PremiumState.Untried, premiumState(owned = false, trialStart = 0L, now = start))
        assertTrue(PremiumState.Untried.unlocked)
    }

    @Test
    fun theWeekCountsDownFromTheFirstMessage() {
        assertEquals(PremiumState.Trial(TRIAL_DAYS), premiumState(false, start, start))
        assertEquals(PremiumState.Trial(TRIAL_DAYS), premiumState(false, start, start + 1))
        assertEquals(PremiumState.Trial(TRIAL_DAYS - 1), premiumState(false, start, start + day))
        assertEquals(PremiumState.Trial(1), premiumState(false, start, start + TRIAL_DAYS * day - 1))
    }

    @Test
    fun afterTheWeekItIsOver() {
        val over = premiumState(false, start, start + TRIAL_DAYS * day)
        assertEquals(PremiumState.Over, over)
        assertFalse(over.unlocked)
    }

    @Test
    fun aClockSetBackGivesNoExtraDays() {
        assertEquals(PremiumState.Trial(TRIAL_DAYS), premiumState(false, start, start - 30 * day))
    }

    @Test
    fun boughtIsOwnedWhateverTheTrial() {
        assertEquals(PremiumState.Owned, premiumState(true, 0L, start))
        assertEquals(PremiumState.Owned, premiumState(true, start, start + 100 * day))
    }

    @Test
    fun afterTheTrialAPremiumElementPlaysAsWaterAndTheChoiceIsKept() {
        val fire = GlowSettings(element = SpawnElement.FIRE, fireFlames = FireFlames.INFERNO)
        assertEquals(fire, fire.playable(unlocked = true))
        val played = fire.playable(unlocked = false)
        assertEquals(SpawnElement.WATER, played.element)
        assertEquals(FireFlames.INFERNO, played.fireFlames)
        val water = GlowSettings(element = SpawnElement.WATER)
        assertEquals(water, water.playable(unlocked = false))
    }

    @Test
    fun premiumPlaysOnlyWhenItsElementPlays() {
        assertTrue(GlowSettings(element = SpawnElement.AIR).playsPremium)
        assertFalse(GlowSettings(element = SpawnElement.WATER).playsPremium)
        assertFalse(GlowSettings(element = SpawnElement.AIR, spawn = false).playsPremium)
        assertFalse(GlowSettings(element = SpawnElement.AIR, arrival = ArrivalMode.LED_ONLY).playsPremium)
    }
}
