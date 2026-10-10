package app.lumement

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat

// ---------------------------------------------------------------------------------------------
// Which posts wake the phone. Every wake goes through these rules, so they are plain functions of
// what the listener reads off a notification, tested as they stand.
// ---------------------------------------------------------------------------------------------

/**
 * A new, user-facing alert: not ongoing (media, progress, calls), not a foreground service or a
 * group summary, and not in a category that only reports a state. When the system ranked it, it
 * must also alert ([importance] at least default) and be let through Do Not Disturb ([allowedNow]).
 * Unranked, those two are not known and don't hold it back.
 */
internal fun isAlert(
    ongoing: Boolean,
    flags: Int,
    category: String?,
    importance: Int? = null,
    allowedNow: Boolean = true,
): Boolean {
    if (ongoing) return false
    if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
    if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
    if (category in IGNORED_CATEGORIES) return false
    if (importance != null && importance < NotificationManager.IMPORTANCE_DEFAULT) return false
    return allowedNow
}

/**
 * A post that asks for the whole screen: a ringing call, an alarm, anything with a full-screen
 * intent ([fullScreen]). On an awake phone, which the lit LED keeps it, the system shows it as a
 * pop-up instead (One UI's incoming call: measured on the S23), and the LED's window, an
 * accessibility overlay above every pop-up, would hide it; so the window makes way while it shows.
 */
internal fun takesScreen(category: String?, fullScreen: Boolean): Boolean =
    fullScreen || category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_ALARM

/**
 * A silent update to a message the LED already waits on: refresh it, don't re-wake. WhatsApp (and
 * others) set ONLY_ALERT_ONCE on every chat notification and re-post the same key for each new
 * message in that chat, so a newer message ([newestAt] past [waitingNewestAt]) still counts as new.
 * [waitingNewestAt] is null when nothing under that key is waiting.
 */
internal fun isQuietUpdate(flags: Int, newestAt: Long, waitingNewestAt: Long?): Boolean =
    flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && waitingNewestAt != null && newestAt <= waitingNewestAt

/** Categories that report a state rather than bring a message. */
internal val IGNORED_CATEGORIES = setOf(
    NotificationCompat.CATEGORY_TRANSPORT,
    NotificationCompat.CATEGORY_PROGRESS,
    NotificationCompat.CATEGORY_SERVICE,
    NotificationCompat.CATEGORY_SYSTEM,
    NotificationCompat.CATEGORY_NAVIGATION,
    NotificationCompat.CATEGORY_STOPWATCH,
    NotificationCompat.CATEGORY_WORKOUT,
    NotificationCompat.CATEGORY_LOCATION_SHARING,
)
