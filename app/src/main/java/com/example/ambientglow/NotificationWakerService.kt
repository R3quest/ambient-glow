package com.example.ambientglow

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat

/**
 * Purely reactive: it runs only when the system calls onNotificationPosted/Removed or the
 * screen turns on/off, does a few milliseconds of synchronous work, and returns. No threads,
 * coroutines or polling; the only timer is a short screen-off debounce.
 *
 * The glow screen, once on top of the lock screen, runs the lock flow itself (see
 * WakeScreenActivity). This service only puts it there, through the full-screen intent, when
 * there is no glow screen on top: the first message, or after the phone was unlocked.
 */
class NotificationWakerService : NotificationListenerService() {

    private val power by lazy(LazyThreadSafetyMode.NONE) { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy(LazyThreadSafetyMode.NONE) { getSystemService(KeyguardManager::class.java) }
    private val audio by lazy(LazyThreadSafetyMode.NONE) { getSystemService(AudioManager::class.java) }

    // Held only until the glow screen is up; acquire(timeout) guarantees release.
    private val wakeLock by lazy(LazyThreadSafetyMode.NONE) {
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
    }

    // Its icon bitmap is allocated per listener connection and reused for every message.
    private val brandColors by lazy(LazyThreadSafetyMode.NONE) { BrandColors(this) }
    private val ranking = Ranking()

    private var lastWakeAt = 0L

    // Screen-off → relight the LED while messages are still unread. The short delay lets the
    // keyguard settle first.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val relightLed = Runnable { relightIfWaiting() }
    private var screenReceiverRegistered = false
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GlowLog.d { "svc ${intent.action?.substringAfterLast('.')} pending=${GlowPending.entries.size}" }
            mainHandler.removeCallbacks(relightLed)
            when (intent.action) {
                // A glow screen on top of the lock screen handles its own screen-off. With nothing
                // waiting there is no LED to relight: let the CPU sleep at once.
                Intent.ACTION_SCREEN_OFF -> if (!GlowPending.isEmpty && GlowSession.host?.isAway != false) {
                    DarkHold.acquire(context) // keep the CPU up until the relight runs
                    mainHandler.postDelayed(relightLed, RELIGHT_DELAY_MS)
                }
                // Don't cancel the wake bridge here: another app (or the bridge itself) may have
                // lit the panel a moment after it was posted, before the system launched it. The
                // glow screen removes it when it starts, and it times out on its own.
                Intent.ACTION_SCREEN_ON -> Unit
            }
        }
    }

    override fun onListenerConnected() {
        GlowLauncher.ensureChannel(this)
        GlowPrefs.warm(this)
        brandColors.prepare()
        pruneStalePending()
        if (!screenReceiverRegistered) {
            registerScreenEvents(screenEvents)
            screenReceiverRegistered = true
        }
    }

    override fun onListenerDisconnected() {
        releaseScreenReceiver()
        brandColors.release()
        requestRebind(ComponentName(this, NotificationWakerService::class.java))
    }

    override fun onDestroy() {
        releaseScreenReceiver()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val ranked = rankingMap?.getRanking(sbn.key, ranking) == true
        if (!isRealMessage(sbn, if (ranked) ranking else null)) return

        // A silent update to a message we are already waiting on: refresh it, don't re-wake.
        // WhatsApp (and others) set ONLY_ALERT_ONCE on every chat notification and re-post the
        // same key for each new message in that chat, so a newer message still counts as new.
        val newestAt = newestMessageAt(sbn.notification)
        val previous = GlowPending.entries.firstOrNull { it.key == sbn.key }
        val quietUpdate = sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 &&
            previous != null && newestAt <= previous.newestAt

        val color = brandColors.of(sbn)
        GlowPending.put(PendingGlow(key = sbn.key, color = color, newestAt = newestAt))
        val host = GlowSession.host
        GlowLog.d {
            "svc posted quiet=$quietUpdate interactive=${power.isInteractive} " +
                "locked=${keyguard.isKeyguardLocked} host=${host != null} away=${host?.isAway}"
        }
        if (quietUpdate) return

        val arrival = GlowPrefs.load(this).arrival
        if (arrival == ArrivalMode.MESSAGE) {
            // The system pops a message up by itself for a channel that peeks; only otherwise does
            // the glow screen re-post it. One UI shows that heads-up even when the screen was off
            // (once our wake lights the panel) and for alert-once chat updates, so a copy doubles it.
            val systemPopsUp = ranked && ranking.importance >= NotificationManager.IMPORTANCE_HIGH
            GlowPending.message = PendingMessage(sbn, systemPopsUp)
        }

        // Glow screen on top of the lock screen: it lights the lock screen and plays the effect.
        if (host != null && !host.isAway) {
            host.onNewMessage()
            return
        }
        // Unlocked and in use: the heads-up is enough; the LED waits for screen-off.
        if (power.isInteractive && !keyguard.isKeyguardLocked) return
        if (audio.inCall) return
        if (SystemClock.elapsedRealtime() - lastWakeAt < WAKE_DEBOUNCE_MS) return
        // Put the glow screen on top of the lock screen (lighting the panel if it is off), or
        // with a black arrival and the screen off, straight into the black panel.
        if (launch(wakeModeFor(arrival, power.isInteractive), color)) lastWakeAt = SystemClock.elapsedRealtime()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        // Dismissed, or cleared by its app after being read: the only way an LED entry ends.
        GlowLauncher.dismissMessage(this, sbn.key)
        if (!GlowPending.remove(sbn.key)) return
        GlowSession.host?.onPendingChanged()
    }

    private fun relightIfWaiting() {
        if (power.isInteractive || audio.inCall || GlowPending.isEmpty) return
        if (GlowSession.host?.isAway == false) return // it came back on top in the meantime
        // With a delayed auto-lock the phone is still unlocked here; the glow screen would only
        // step aside again. The next wake or message (on the lock screen) brings it back.
        if (!keyguard.isKeyguardLocked) return
        pruneStalePending()
        val newest = GlowPending.entries.firstOrNull() ?: return
        // Only reached when the glow screen was not on top (the phone had been unlocked), so
        // Android shows the lock screen for a moment before the LED covers it.
        GlowLog.d { "svc relight LED via full-screen intent" }
        launch(WakeMode.LED, newest.color)
    }

    /**
     * Drops entries whose notification is gone, in case a removal was missed (e.g. during a rebind).
     * Asks only for the waiting keys: the full list would parcel every notification in the shade,
     * pictures included, on each relight.
     */
    private fun pruneStalePending() {
        if (GlowPending.isEmpty) return
        val keys = Array(GlowPending.entries.size) { GlowPending.entries[it].key }
        val active = try {
            getActiveNotifications(keys)?.mapTo(HashSet()) { it.key } ?: return
        } catch (_: SecurityException) {
            return // not connected yet
        }
        GlowPending.entries.removeAll { it.key !in active }
    }

    private fun launch(mode: WakeMode, color: Int): Boolean {
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        val launched = GlowLauncher.launchFromBackground(this, mode, color)
        if (!launched) wakeLock.release()
        return launched
    }

    private fun releaseScreenReceiver() {
        mainHandler.removeCallbacks(relightLed)
        if (screenReceiverRegistered) {
            unregisterReceiver(screenEvents)
            screenReceiverRegistered = false
        }
    }

    /** Lets through only new, user-facing alerts; skips media, progress, services and silent posts. */
    private fun isRealMessage(sbn: StatusBarNotification, ranking: Ranking?): Boolean {
        val notification = sbn.notification
        if (sbn.packageName == packageName && notification.channelId != GlowLauncher.TEST_CHANNEL_ID) return false
        if (sbn.isOngoing) return false

        val flags = notification.flags
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (notification.category in IGNORED_CATEGORIES) return false

        if (ranking != null) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
            if (!ranking.matchesInterruptionFilter()) return false // respects Do Not Disturb
        }
        return true
    }

    /**
     * Timestamp of the newest message in a MessagingStyle notification, else its `when`. Reads
     * the raw message bundles; no MessagingStyle objects are rebuilt.
     */
    private fun newestMessageAt(notification: Notification): Long {
        val messages = BundleCompat.getParcelableArray(
            notification.extras,
            Notification.EXTRA_MESSAGES,
            Bundle::class.java,
        )
        var newest = 0L
        messages?.forEach { message ->
            if (message is Bundle) newest = maxOf(newest, message.getLong(KEY_MESSAGE_TIME))
        }
        return if (newest > 0L) newest else notification.`when`
    }

    private companion object {
        const val WAKE_LOCK_TAG = "AmbientGlow:wake"
        const val WAKE_LOCK_TIMEOUT_MS = 4_000L
        /** Lets the sleep transition (and keyguard lock) finish before waking the panel again. */
        const val RELIGHT_DELAY_MS = 400L

        /** Notification.MessagingStyle.Message's timestamp key inside each EXTRA_MESSAGES bundle. */
        const val KEY_MESSAGE_TIME = "time"

        val IGNORED_CATEGORIES = setOf(
            NotificationCompat.CATEGORY_TRANSPORT,
            NotificationCompat.CATEGORY_PROGRESS,
            NotificationCompat.CATEGORY_SERVICE,
            NotificationCompat.CATEGORY_SYSTEM,
            NotificationCompat.CATEGORY_NAVIGATION,
            NotificationCompat.CATEGORY_STOPWATCH,
            NotificationCompat.CATEGORY_WORKOUT,
            NotificationCompat.CATEGORY_LOCATION_SHARING,
        )
    }
}
