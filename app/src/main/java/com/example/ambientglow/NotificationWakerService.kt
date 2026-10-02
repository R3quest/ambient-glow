package com.example.ambientglow

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.palette.graphics.Palette

/**
 * Purely reactive: it runs only when the system calls onNotificationPosted, does a few
 * milliseconds of synchronous work, and returns. There are no threads, coroutines or timers.
 */
class NotificationWakerService : NotificationListenerService() {

    private val power by lazy(LazyThreadSafetyMode.NONE) { getSystemService(PowerManager::class.java) }

    // Held only until the wake layer is on screen; acquire(timeout) guarantees release.
    private val wakeLock by lazy(LazyThreadSafetyMode.NONE) {
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
    }

    // One small bitmap, allocated per listener connection and reused for every icon, so
    // colour extraction triggers no per-notification bitmap allocation or GC pressure.
    private var iconBitmap: Bitmap? = null
    private var iconCanvas: Canvas? = null
    private val savedBounds = Rect()
    private val ranking = Ranking()

    private var lastGlowAt = 0L

    override fun onListenerConnected() {
        GlowLauncher.ensureChannel(this)
        obtainIconCanvas()
    }

    override fun onListenerDisconnected() {
        iconBitmap?.recycle()
        iconBitmap = null
        iconCanvas = null
        requestRebind(ComponentName(this, NotificationWakerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (!isRealMessage(sbn, rankingMap)) return

        // Glows only light a dark panel. If the screen is already on, the user can see it.
        if (power.isInteractive) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastGlowAt < GLOW_DURATION_MS) return

        val request = GlowRequest.of(GlowPrefs.load(this), extractBrandColor(sbn))
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        if (GlowLauncher.launchFromBackground(this, request)) {
            lastGlowAt = now
        } else {
            wakeLock.release()
        }
    }

    /** Lets through only new, user-facing alerts; skips media, progress, services and silent posts. */
    private fun isRealMessage(sbn: StatusBarNotification, rankingMap: RankingMap?): Boolean {
        if (sbn.packageName == packageName) return false
        if (sbn.isOngoing) return false

        val notification = sbn.notification
        val flags = notification.flags
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (notification.category in IGNORED_CATEGORIES) return false

        if (rankingMap != null && rankingMap.getRanking(sbn.key, ranking)) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
            if (!ranking.matchesInterruptionFilter()) return false // respects Do Not Disturb
        }
        return true
    }

    /** Dominant brand colour of the sender's app icon, adjusted so it reads on a black panel. */
    private fun extractBrandColor(sbn: StatusBarNotification): Int {
        val accent = sbn.notification.color.takeIf { it != Notification.COLOR_DEFAULT && Color.alpha(it) != 0 }

        val icon = try {
            packageManager.getApplicationIcon(sbn.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val canvas = obtainIconCanvas()
        val bitmap = iconBitmap
        if (icon == null || bitmap == null) return legibleOnBlack(accent ?: DEFAULT_GLOW_COLOR)

        bitmap.eraseColor(Color.TRANSPARENT)
        icon.copyBounds(savedBounds)
        icon.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
        icon.draw(canvas)
        icon.bounds = savedBounds

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
        const val ICON_SIZE_PX = 96
        const val PALETTE_COLORS = 12
        const val MIN_LUMINANCE = 0.12
        const val DARK_LIFT = 0.42f

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
