package app.lumement

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
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

    /**
     * Replying from the notification, the app posts the chat again with the reply added: the
     * user's own message is no new one, and the update stays quiet.
     */
    @Test
    fun theUsersOwnReplyBringsNoNewMessage() {
        val incoming = MessageTime(1_000L, mine = false)
        val reply = MessageTime(5_000L, mine = true)
        assertEquals(1_000L, newestIncomingAt(listOf(incoming, reply), postedAt = 5_000L))
        assertTrue(isQuietUpdate(Notification.FLAG_ONLY_ALERT_ONCE, newestIncomingAt(listOf(incoming, reply), 5_000L), waitingNewestAt = 1_000L))
        assertEquals("a newer one from them is new", 6_000L, newestIncomingAt(listOf(incoming, reply, MessageTime(6_000L, mine = false)), 6_000L))
        assertEquals("no messages: its when", 7_000L, newestIncomingAt(null, postedAt = 7_000L))
        assertEquals("only the user's: nothing new", 0L, newestIncomingAt(listOf(reply), postedAt = 5_000L))
    }

    /**
     * The listener away (an update, a crash): what was posted meanwhile, after the newest message
     * heard and not too old, is taken at the reconnect; nothing at the first connect.
     */
    @Test
    fun postsMissedWhileAwayAreTakenAtTheReconnect() {
        val now = 100 * 60_000L
        val posts = mapOf("old" to now - 60_000L, "gap" to now - 2_000L, "gap2" to now - 1_000L, "stale" to now - CATCH_UP_MS - 1, "waits" to now - 500L)
        val heardAt = now - 30_000L
        assertEquals(listOf("gap", "gap2"), missedWhileAway(posts, heardAt, waiting = setOf("waits"), now = now))
        assertEquals("first connect: no mark, nothing", emptyList<String>(), missedWhileAway(posts, null, emptySet(), now))
        assertEquals("all heard", emptyList<String>(), missedWhileAway(posts, now, emptySet(), now))
    }

    /** The platform's and SystemUI's posts tell of the phone's state ("Configure physical keyboard", battery low), never a message. */
    @Test
    fun thePhonesOwnPostsAreNoMessages() {
        assertTrue(fromTheSystem("android"))
        assertTrue(fromTheSystem("com.android.systemui"))
        assertFalse("adb's test messages", fromTheSystem("com.android.shell"))
        assertFalse(fromTheSystem("com.google.android.apps.messaging"))
        assertFalse(fromTheSystem("com.samsung.android.messaging"))
    }

    @Test
    fun callsAlarmsAndFullScreenPostsTakeTheScreen() {
        assertTrue("call in progress", takesScreen(Notification.CATEGORY_CALL, fullScreen = false, ongoing = true))
        assertTrue("call as a service", takesScreen(Notification.CATEGORY_CALL, fullScreen = false, ongoing = false, foreground = true))
        assertTrue("ringing call", takesScreen(Notification.CATEGORY_CALL, fullScreen = true, ongoing = false))
        assertFalse("missed call filed as a call", takesScreen(Notification.CATEGORY_CALL, fullScreen = false, ongoing = false))
        assertFalse("missed call", takesScreen(Notification.CATEGORY_MISSED_CALL, fullScreen = false, ongoing = false))
        assertTrue("ringing alarm", takesScreen(Notification.CATEGORY_ALARM, fullScreen = false, ongoing = true))
        assertFalse("upcoming alarm", takesScreen(Notification.CATEGORY_ALARM, fullScreen = false, ongoing = false))
        assertTrue("full-screen intent", takesScreen(null, fullScreen = true, ongoing = false))
        assertFalse("message", takesScreen(Notification.CATEGORY_MESSAGE, fullScreen = false, ongoing = false))
        assertFalse("plain", takesScreen(null, fullScreen = false, ongoing = false))
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
