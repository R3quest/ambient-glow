package com.example.ambientglow

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFilterTest {

    @Test
    fun aPlainMessageWakesRankedOrNot() {
        assertTrue(isAlert(ongoing = false, flags = 0, category = NotificationCompat.CATEGORY_MESSAGE))
        assertTrue(isAlert(ongoing = false, flags = 0, category = null))
        assertTrue(
            isAlert(
                ongoing = false,
                flags = Notification.FLAG_AUTO_CANCEL,
                category = NotificationCompat.CATEGORY_MESSAGE,
                importance = NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        assertTrue(isAlert(ongoing = false, flags = 0, category = null, importance = NotificationManager.IMPORTANCE_HIGH))
    }

    @Test
    fun ongoingServicesAndSummariesNeverWake() {
        assertFalse("ongoing", isAlert(ongoing = true, flags = 0, category = null))
        assertFalse("foreground service", isAlert(ongoing = false, flags = Notification.FLAG_FOREGROUND_SERVICE, category = null))
        assertFalse("group summary", isAlert(ongoing = false, flags = Notification.FLAG_GROUP_SUMMARY, category = null))
    }

    @Test
    fun stateCategoriesNeverWake() {
        for (category in IGNORED_CATEGORIES) {
            assertFalse(category, isAlert(ongoing = false, flags = 0, category = category))
        }
        for (category in listOf(NotificationCompat.CATEGORY_EMAIL, NotificationCompat.CATEGORY_SOCIAL, NotificationCompat.CATEGORY_REMINDER)) {
            assertTrue(category, isAlert(ongoing = false, flags = 0, category = category))
        }
    }

    @Test
    fun aQuietChannelOrDoNotDisturbHoldsItBack() {
        assertFalse(isAlert(ongoing = false, flags = 0, category = null, importance = NotificationManager.IMPORTANCE_LOW))
        assertFalse(isAlert(ongoing = false, flags = 0, category = null, importance = NotificationManager.IMPORTANCE_MIN))
        assertFalse(
            "filtered by Do Not Disturb",
            isAlert(ongoing = false, flags = 0, category = null, importance = NotificationManager.IMPORTANCE_HIGH, allowedNow = false),
        )
    }

    @Test
    fun anAlertOnceRepostIsQuietUnlessItCarriesANewerMessage() {
        val once = Notification.FLAG_ONLY_ALERT_ONCE
        assertTrue("same message again", isQuietUpdate(once, newestAt = 1_000L, waitingNewestAt = 1_000L))
        assertTrue("an older message", isQuietUpdate(once, newestAt = 900L, waitingNewestAt = 1_000L))
        assertFalse("a newer message in the same chat", isQuietUpdate(once, newestAt = 1_001L, waitingNewestAt = 1_000L))
        assertFalse("nothing waiting under its key", isQuietUpdate(once, newestAt = 1_000L, waitingNewestAt = null))
        assertFalse("alerts every time", isQuietUpdate(0, newestAt = 1_000L, waitingNewestAt = 1_000L))
    }
}
