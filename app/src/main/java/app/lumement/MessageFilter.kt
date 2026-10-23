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
 * The phone's own posts: the platform's ("Configure physical keyboard" as a keyboard connects, USB,
 * storage) and SystemUI's (battery low, a screenshot). They tell of its state, never a message, and
 * some stay up as long as the state does: the LED stayed lit for a keyboard (seen on the emulators,
 * whose keyboard counts as one).
 */
internal fun fromTheSystem(pkg: String): Boolean = pkg == "android" || pkg == "com.android.systemui"

/**
 * A post that asks for the whole screen: a ringing call, a ringing alarm, anything with a
 * full-screen intent ([fullScreen]). On an awake phone, which the lit LED keeps it, the system shows
 * it as a pop-up instead (One UI's incoming call: measured on the S23), and the LED's window, an
 * accessibility overlay above every pop-up, would hide it; so the window makes way while it shows.
 * An alarm only while [ongoing]: Samsung Clock's "upcoming alarm" notice (category alarm, not
 * ongoing) stays up for the 30 minutes before every alarm, and the window made way for all of it,
 * the LED's face covering the lock screen and the fingerprint off; its ringing alarm is an ongoing
 * foreground-service post with no full-screen intent (measured on the S23). A call likewise only
 * while it is live: Android has a call's post ring through a full-screen intent or run as an
 * ongoing or [foreground] service, and a missed call, which some dialers file as a call too, is
 * neither; it would hold the LED back for as long as it stays in the shade.
 */
internal fun takesScreen(category: String?, fullScreen: Boolean, ongoing: Boolean, foreground: Boolean = false): Boolean =
    fullScreen ||
        (category == Notification.CATEGORY_CALL && (ongoing || foreground)) ||
        (category == Notification.CATEGORY_ALARM && ongoing)

/**
 * A silent update to a message the LED already waits on: refresh it, don't re-wake. WhatsApp (and
 * others) set ONLY_ALERT_ONCE on every chat notification and re-post the same key for each new
 * message in that chat, so a newer message ([newestAt] past [waitingNewestAt]) still counts as new.
 * [waitingNewestAt] is null when nothing under that key is waiting.
 */
internal fun isQuietUpdate(flags: Int, newestAt: Long, waitingNewestAt: Long?): Boolean =
    flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && waitingNewestAt != null && newestAt <= waitingNewestAt

/** One message of a MessagingStyle post: when, and whether the phone's user wrote it ([mine]). */
internal class MessageTime(val at: Long, val mine: Boolean)

/**
 * When the newest message from someone else came: of a chat's [messages], or [postedAt] (its
 * `when`) for a post with none. The user's own replies don't count: replying from the notification,
 * the app posts the chat again with the reply added, and as a new message it played the arrival
 * effect and started a bright phase for a chat just answered. With only the user's, 0: nothing new.
 */
internal fun newestIncomingAt(messages: List<MessageTime>?, postedAt: Long): Long =
    if (messages.isNullOrEmpty()) postedAt else messages.filterNot { it.mine }.maxOfOrNull { it.at } ?: 0L

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
