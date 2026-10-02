package com.example.ambientglow

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat

/**
 * Starts the wake layer.
 *
 * Android 10+ blocks a background NotificationListenerService from calling startActivity().
 * Without SYSTEM_ALERT_WINDOW, the one approved route is a high-importance notification that
 * carries a full-screen intent. The system launches it when the display is off or locked.
 * The bridge notification is cancelled the moment WakeScreenActivity starts, and it also
 * times out on its own, so nothing is left in the shade.
 */
object GlowLauncher {
    const val CHANNEL_ID = "glow_wake"

    /** Notifications on this channel are the only ones from our own package the listener reacts to. */
    const val TEST_CHANNEL_ID = "glow_test"
    const val TEST_DELAY_MS = 5_000L

    /** Heads-up channel for re-posting a message as-is ([ArrivalMode.MESSAGE]). */
    private const val MESSAGE_CHANNEL_ID = "glow_message"

    const val EXTRA_MODE = "com.example.ambientglow.extra.MODE"
    const val EXTRA_PREVIEW = "com.example.ambientglow.extra.PREVIEW"

    private const val BRIDGE_NOTIFICATION_ID = 0xA61
    private const val TEST_NOTIFICATION_ID = 0xA62
    private const val MESSAGE_NOTIFICATION_ID = 0xA64
    private const val MESSAGE_TIMEOUT_MS = 10_000L
    private const val BRIDGE_TIMEOUT_MS = 4_000L
    private const val TEST_TIMEOUT_MS = 10 * 60_000L
    private const val TEST_SECOND_COLOR = 0xFFFF2E93.toInt()
    private const val BRIDGE_REQUEST_CODE = 0
    private const val TEST_REQUEST_CODE = 1

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingTest: Runnable? = null

    // SystemUI fires a full-screen intent only when a notification is ADDED. Re-posting the
    // same id/tag while the old entry lingers is an update and launches nothing, so every
    // bridge gets a fresh tag and the previous one is cancelled first.
    private var lastBridgeTag: String? = null

    /** Key of the message currently re-posted on [MESSAGE_CHANNEL_ID], if any. */
    private var shownMessageKey: String? = null

    fun wakeIntent(context: Context, mode: WakeMode, preview: Boolean = false): Intent =
        Intent(context, WakeScreenActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
            .putExtra(EXTRA_MODE, mode.name)
            .putExtra(EXTRA_PREVIEW, preview)

    /** Foreground-only path, used by the dashboard's LED preview. */
    fun launchPreview(context: Context) {
        context.startActivity(wakeIntent(context, WakeMode.LED, preview = true))
    }

    fun ensureChannel(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.channel_name))
                .setDescription(context.getString(R.string.channel_description))
                .setSound(null, null)
                .setVibrationEnabled(false)
                .setLightsEnabled(false)
                .setShowBadge(false)
                .build(),
        )
        // Silent: the original message already rang; this one only brings back its pop-up.
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(MESSAGE_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.message_channel_name))
                .setDescription(context.getString(R.string.message_channel_description))
                .setSound(null, null)
                .setVibrationEnabled(false)
                .setLightsEnabled(false)
                .setShowBadge(false)
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(TEST_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.test_channel_name))
                .setDescription(context.getString(R.string.test_channel_description))
                .setSound(null, null)
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build(),
        )
    }

    /** True when alerts are allowed and the bridge channel still has heads-up importance. */
    fun canPostBridge(context: Context): Boolean = canPeek(context, CHANNEL_ID)

    private fun canPeek(context: Context, channelId: String): Boolean {
        if (!canPost(context)) return false
        val channel = NotificationManagerCompat.from(context).getNotificationChannelCompat(channelId)
            ?: return true
        return channel.importance >= NotificationManagerCompat.IMPORTANCE_HIGH
    }

    private fun canPost(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Background path, used by [NotificationWakerService]. Returns false if the bridge is blocked. */
    fun launchFromBackground(context: Context, mode: WakeMode, color: Int): Boolean {
        ensureChannel(context)
        if (!canPostBridge(context)) {
            GlowLog.d("bridge blocked mode=$mode")
            return false
        }
        GlowLog.d("bridge post mode=$mode")

        lastBridgeTag?.let { NotificationManagerCompat.from(context).cancel(it, BRIDGE_NOTIFICATION_ID) }
        val tag = "bridge-${SystemClock.elapsedRealtime()}"
        lastBridgeTag = tag

        val pending = PendingIntent.getActivity(
            context,
            BRIDGE_REQUEST_CODE,
            wakeIntent(context, mode),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Not setSilent(): NotificationCompat implements that with a silenced group, and
        // SystemUI suppresses full-screen intents for group-silenced children. The channel
        // already has no sound and no vibration.
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_glow)
            .setColor(color)
            .setContentTitle(context.getString(R.string.bridge_title))
            .setContentText(context.getString(R.string.bridge_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // WAKE ends on the system lock screen: keep the bridge itself out of its list.
            .setVisibility(
                if (mode == WakeMode.WAKE) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC,
            )
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setTimeoutAfter(BRIDGE_TIMEOUT_MS)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        post(context, BRIDGE_NOTIFICATION_ID, notification, tag)
        return true
    }

    /**
     * [ArrivalMode.MESSAGE] when [original] arrived with the screen off: the system never pops a
     * message up then, so once the black panel is up it is re-posted as it is on a heads-up
     * channel, and the system draws its own pop-up: the sender's layout, avatar and actions, and
     * the lock-screen privacy (redacted while content is hidden). Cancelled when the dot takes
     * over, and it times out on its own, so it never stays in the shade.
     */
    fun showMessage(context: Context, original: StatusBarNotification) {
        ensureChannel(context)
        if (!canPeek(context, MESSAGE_CHANNEL_ID)) {
            GlowLog.d("message pop-up blocked")
            return
        }
        val copy = try {
            Notification.Builder.recoverBuilder(context, original.notification)
                .setChannelId(MESSAGE_CHANNEL_ID)
                // Its own entry: no group (a silent group would hold the pop-up back), no
                // conversation shortcut or bubble (those belong to the sender app).
                .setGroup(null)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_ALL)
                .setShortcutId(null)
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setBubbleMetadata(null) }
                .setOnlyAlertOnce(false)
                .setLocalOnly(true)
                .setTimeoutAfter(MESSAGE_TIMEOUT_MS)
                .build()
        } catch (e: RuntimeException) {
            GlowLog.d("message pop-up not rebuilt: $e")
            return
        }
        GlowLog.d("message pop-up post")
        shownMessageKey = original.key
        post(context, MESSAGE_NOTIFICATION_ID, copy)
    }

    /** Removes the re-posted message; with [originalKey], only if it is that message's copy. */
    fun dismissMessage(context: Context, originalKey: String? = null) {
        val shown = shownMessageKey ?: return
        if (originalKey != null && originalKey != shown) return
        NotificationManagerCompat.from(context).cancel(MESSAGE_NOTIFICATION_ID)
        shownMessageKey = null
    }

    fun dismissBridge(context: Context) {
        val tag = lastBridgeTag ?: return
        NotificationManagerCompat.from(context).cancel(tag, BRIDGE_NOTIFICATION_ID)
        lastBridgeTag = null
    }

    /**
     * Posts two realistic chat messages [TEST_DELAY_MS] from now, in two colours, so the user
     * can lock the phone and watch the full listener → colour → privacy → full-screen-intent
     * path and then the LED colour loop. They stay until dismissed, like real messages.
     * One main-thread callback; the process stays alive because the system binds our listener.
     * Returns false if notifications are blocked.
     */
    fun scheduleTestNotification(context: Context): Boolean {
        val app = context.applicationContext
        ensureChannel(app)
        if (!canPost(app)) return false
        pendingTest?.let(mainHandler::removeCallbacks)
        val task = Runnable {
            pendingTest = null
            if (canPost(app)) {
                post(app, TEST_NOTIFICATION_ID, buildTestNotification(app, 0))
                post(app, TEST_NOTIFICATION_ID + 1, buildTestNotification(app, 1))
            }
        }
        pendingTest = task
        mainHandler.postDelayed(task, TEST_DELAY_MS)
        return true
    }

    private fun buildTestNotification(context: Context, index: Int): Notification {
        val senderName = context.getString(if (index == 0) R.string.test_sender else R.string.test_sender_second)
        val messageText = context.getString(if (index == 0) R.string.test_message else R.string.test_message_second)
        val sender = Person.Builder().setName(senderName).build()
        val me = Person.Builder().setName(context.getString(R.string.test_me)).build()
        val open = PendingIntent.getActivity(
            context,
            TEST_REQUEST_CODE + index,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, TEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_glow)
            .setColor(if (index == 0) DEFAULT_GLOW_COLOR else TEST_SECOND_COLOR)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setStyle(
                NotificationCompat.MessagingStyle(me)
                    .addMessage(messageText, System.currentTimeMillis(), sender),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(TEST_TIMEOUT_MS)
            .build()
    }

    // Callers verify permission through canPost()/canPostBridge() first.
    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, notification: Notification, tag: String? = null) {
        NotificationManagerCompat.from(context).notify(tag, id, notification)
    }
}

/**
 * Lights the panel without launching anything: the screen comes on showing whatever is on top,
 * the system lock screen or the LED, exactly as it was arranged while the screen was dark.
 * A screen wake lock with ACQUIRE_CAUSES_WAKEUP is still honoured for apps targeting SDK 35
 * (the TURN_SCREEN_ON requirement is not enabled for any released target SDK). It auto-releases,
 * and ON_AFTER_RELEASE leaves the normal lock-screen timeout in charge afterwards.
 */
object PanelWaker {
    private const val HOLD_MS = 1_000L

    @Suppress("DEPRECATION")
    fun wake(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isInteractive) return
        GlowLog.d("panel wake")
        power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "AmbientGlow:panel",
        ).apply { setReferenceCounted(false) }.acquire(HOLD_MS)
    }
}

/**
 * Keeps the CPU up across a short dark gap (screen just went off, wake due in a few hundred ms).
 * Handler time stops while the CPU is suspended, so without this the panel could stay dark.
 */
object DarkHold {
    private const val HOLD_MS = 2_000L
    private var lock: PowerManager.WakeLock? = null

    fun acquire(context: Context) {
        val held = lock ?: context.applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AmbientGlow:dark")
            .apply { setReferenceCounted(false) }
            .also { lock = it }
        held.acquire(HOLD_MS)
    }
}
