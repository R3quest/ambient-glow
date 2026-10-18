package app.lumement.dashboard

import app.lumement.PremiumState
import org.junit.Assert.assertEquals
import org.junit.Test

class PremiumOfferTest {
    @Test
    fun whilePlayIsAskedThePageWaitsRatherThanSaysItCantSell() {
        // The dashboard has just opened: Play is connecting, no price yet.
        assertEquals(PremiumOffer.ASKING, premiumOffer(PremiumState.Untried, price = null, PlayShop.ASKING, canBuy = true))
        assertEquals(PremiumOffer.ASKING, premiumOffer(PremiumState.Trial(3), price = null, PlayShop.ASKING, canBuy = true))
    }

    @Test
    fun oncePlayHasAPriceItSells() {
        assertEquals(PremiumOffer.BUY, premiumOffer(PremiumState.Over, price = "€2.99", PlayShop.SELLING, canBuy = true))
    }

    @Test
    fun playSayingNoOrNoWayToShowItsSheetIsUnavailable() {
        assertEquals(PremiumOffer.UNAVAILABLE, premiumOffer(PremiumState.Over, price = null, PlayShop.CLOSED, canBuy = true))
        assertEquals(PremiumOffer.UNAVAILABLE, premiumOffer(PremiumState.Over, price = "€2.99", PlayShop.SELLING, canBuy = false))
        assertEquals(PremiumOffer.UNAVAILABLE, premiumOffer(PremiumState.Over, price = null, PlayShop.ASKING, canBuy = false))
    }

    @Test
    fun ownedSaysSoWhateverPlayIsDoing() {
        for (shop in PlayShop.entries) {
            assertEquals(PremiumOffer.OWNED, premiumOffer(PremiumState.Owned, price = null, shop, canBuy = true))
        }
    }
}
