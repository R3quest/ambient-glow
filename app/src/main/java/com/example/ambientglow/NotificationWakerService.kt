package com.example.ambientglow

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.os.BundleCompat
import androidx.palette.graphics.Palette

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

    // One small bitmap, allocated per listener connection and reused for every icon, so
    // colour extraction triggers no per-notification bitmap allocation or GC pressure.
    private var iconBitmap: Bitmap? = null
    private var iconCanvas: Canvas? = null
    private val savedBounds = Rect()
    private val ranking = Ranking()

    private var lastWakeAt = 0L

    // Screen-off → relight the LED while messages are still unread. The short delay lets the
    // keyguard settle first.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val relightLed = Runnable { relightIfWaiting() }
    private var screenReceiverRegistered = false
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GlowLog.d("svc ${intent.action?.substringAfterLast('.')} pending=${GlowPending.entries.size}")
            mainHandler.removeCallbacks(relightLed)
            when (intent.action) {
                // A glow screen on top of the lock screen handles its own screen-off.
                Intent.ACTION_SCREEN_OFF -> if (GlowSession.host?.isAway != false) {
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
        obtainIconCanvas()
        pruneStalePending()
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                screenEvents,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenReceiverRegistered = true
        }
    }

    override fun onListenerDisconnected() {
        releaseScreenReceiver()
        iconBitmap?.recycle()
        iconBitmap = null
        iconCanvas = null
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

        val icon = applicationInfoFor(sbn)?.let { loadIcon(it) }
        val color = extractBrandColor(sbn, icon)
        GlowPending.put(PendingGlow(key = sbn.key, color = color, newestAt = newestAt))
        val host = GlowSession.host
        GlowLog.d(
            "svc posted quiet=$quietUpdate interactive=${power.isInteractive} " +
                "locked=${keyguard.isKeyguardLocked} host=${host != null} away=${host?.isAway}",
        )
        if (quietUpdate) return

        val arrival = GlowPrefs.load(this).arrival
        if (arrival == ArrivalMode.MESSAGE) {
            // The system pops a message up by itself only while the screen is on, for a channel
            // that peeks, and not for an alert-once update; otherwise the glow screen re-posts it.
            val systemPopsUp = power.isInteractive &&
                ranked && ranking.importance >= NotificationManager.IMPORTANCE_HIGH &&
                !(sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && previous != null)
            GlowPending.message = PendingMessage(sbn, systemPopsUp)
        }

        // Glow screen on top of the lock screen: it lights the lock screen and plays the effect.
        if (host != null && !host.isAway) {
            host.onNewMessage()
            return
        }
        // Unlocked and in use: the heads-up is enough; the LED waits for screen-off.
        if (power.isInteractive && !keyguard.isKeyguardLocked) return
        if (inCall()) return
        if (SystemClock.elapsedRealtime() - lastWakeAt < WAKE_DEBOUNCE_MS) return
        // Put the glow screen on top of the lock screen without covering it (and light the
        // panel if it is off), so every later switch can happen in place. With a black arrival
        // and the screen off, light it straight into the black panel instead.
        val black = !power.isInteractive && arrival.onBlack
        if (launch(if (black) WakeMode.ARRIVAL else WakeMode.WAKE, color)) lastWakeAt = SystemClock.elapsedRealtime()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        // Dismissed, or cleared by its app after being read: the only way an LED entry ends.
        GlowLauncher.dismissMessage(this, sbn.key)
        if (!GlowPending.remove(sbn.key)) return
        GlowSession.host?.onPendingChanged()
    }

    private fun relightIfWaiting() {
        if (power.isInteractive || inCall() || GlowPending.isEmpty) return
        if (GlowSession.host?.isAway == false) return // it came back on top in the meantime
        // With a delayed auto-lock the phone is still unlocked here; the glow screen would only
        // step aside again. The next wake or message (on the lock screen) brings it back.
        if (!keyguard.isKeyguardLocked) return
        pruneStalePending()
        val newest = GlowPending.entries.firstOrNull() ?: return
        // Only reached when the glow screen was not on top (the phone had been unlocked), so
        // Android shows the lock screen for a moment before the LED covers it.
        GlowLog.d("svc relight LED via full-screen intent")
        launch(WakeMode.LED, newest.color)
    }

    /** Drops entries whose notification is gone, in case a removal was missed (e.g. during a rebind). */
    private fun pruneStalePending() {
        if (GlowPending.isEmpty) return
        val active = try {
            activeNotifications?.mapTo(HashSet()) { it.key } ?: return
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

    /** Proximity blanks the screen during calls; never light up over a call. */
    private fun inCall(): Boolean = audio.mode != AudioManager.MODE_NORMAL

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

    // ------------------------------------------------------------------------------------
    // Brand colour
    // ------------------------------------------------------------------------------------

    /**
     * The posting app's ApplicationInfo. Notification.Builder embeds it in the extras, which
     * works without package visibility; the <queries> launcher entry covers the rest.
     */
    private fun applicationInfoFor(sbn: StatusBarNotification): ApplicationInfo? =
        BundleCompat.getParcelable(sbn.notification.extras, EXTRA_APP_INFO, ApplicationInfo::class.java)
            ?: try {
                packageManager.getApplicationInfo(sbn.packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }

    private fun loadIcon(appInfo: ApplicationInfo): Drawable? = try {
        packageManager.getApplicationIcon(appInfo)
    } catch (_: Exception) {
        null
    }

    /** Dominant brand colour of the sender's app icon, adjusted so it reads on a black panel. */
    private fun extractBrandColor(sbn: StatusBarNotification, icon: Drawable?): Int {
        val accent = sbn.notification.color.takeIf { it != Notification.COLOR_DEFAULT && Color.alpha(it) != 0 }
        // Test messages carry their own colour so the locked test shows the colour loop.
        if (sbn.packageName == packageName && accent != null) return accent
        if (icon == null) return legibleOnBlack(accent ?: DEFAULT_GLOW_COLOR)
        val bitmap = renderIcon(icon)

        val palette = Palette.from(bitmap)
            .resizeBitmapArea(ICON_SIZE_PX * ICON_SIZE_PX) // already small: skip the internal rescale copy
            .maximumColorCount(PALETTE_COLORS)
            .generate()
        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.dominantSwatch
            ?: palette.mutedSwatch

        return legibleOnBlack(swatch?.rgb ?: accent ?: DEFAULT_GLOW_COLOR)
    }

    /** Draws [icon] into the shared icon bitmap (no allocation) and returns that bitmap. */
    private fun renderIcon(icon: Drawable): Bitmap {
        val canvas = obtainIconCanvas()
        val bitmap = checkNotNull(iconBitmap)
        bitmap.eraseColor(Color.TRANSPARENT)
        icon.copyBounds(savedBounds)
        icon.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
        icon.draw(canvas)
        icon.bounds = savedBounds
        return bitmap
    }

    private fun obtainIconCanvas(): Canvas {
        iconCanvas?.let { return it }
        val bitmap = createBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
        iconBitmap = bitmap
        return Canvas(bitmap).also { iconCanvas = it }
    }

    private fun legibleOnBlack(color: Int): Int {
        val opaque = ColorUtils.setAlphaComponent(color, 0xFF)
        return if (ColorUtils.calculateLuminance(opaque) < MIN_LUMINANCE) {
            ColorUtils.blendARGB(opaque, Color.WHITE, DARK_LIFT)
        } else {
            opaque
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "AmbientGlow:wake"
        const val WAKE_LOCK_TIMEOUT_MS = 4_000L
        /** Lets the sleep transition (and keyguard lock) finish before waking the panel again. */
        const val RELIGHT_DELAY_MS = 400L
        const val ICON_SIZE_PX = 96
        const val PALETTE_COLORS = 12
        const val MIN_LUMINANCE = 0.12
        const val DARK_LIFT = 0.42f

        /** Notification.EXTRA_BUILDER_APPLICATION_INFO (hidden constant, stable since API 24). */
        const val EXTRA_APP_INFO = "android.appInfo"

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
