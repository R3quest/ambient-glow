package com.example.ambientglow

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.os.BundleCompat
import androidx.palette.graphics.Palette

/**
 * The colour a message glows in: the dominant brand colour of the sender's app icon, adjusted
 * so it reads on a black panel. Worked out once per app and then remembered, so a chat that
 * posts every few seconds costs a map lookup, not an icon load and a palette pass. One small
 * bitmap, allocated once ([prepare]) and reused for every icon, so extraction triggers no
 * per-notification bitmap allocation or GC pressure. Main thread only; [release] frees the
 * bitmap and forgets the colours (an updated app icon is picked up on the next connection).
 */
internal class BrandColors(private val context: Context) {
    private var iconBitmap: Bitmap? = null
    private var iconCanvas: Canvas? = null
    private val savedBounds = Rect()
    private val byPackage = HashMap<String, Int>()

    fun prepare() {
        obtainIconCanvas()
    }

    fun release() {
        byPackage.clear()
        iconBitmap?.recycle()
        iconBitmap = null
        iconCanvas = null
    }

    fun of(sbn: StatusBarNotification): Int {
        val accent = sbn.notification.color.takeIf { it != Notification.COLOR_DEFAULT && Color.alpha(it) != 0 }
        // Test messages carry their own colour so the locked test shows the colour loop.
        if (sbn.packageName == context.packageName && accent != null) return accent
        byPackage[sbn.packageName]?.let { return it }
        val icon = applicationInfoFor(sbn)?.let(::loadIcon) ?: return legibleOnBlack(accent ?: DEFAULT_GLOW_COLOR)

        val palette = Palette.from(renderIcon(icon))
            .resizeBitmapArea(ICON_SIZE_PX * ICON_SIZE_PX) // already small: skip the internal rescale copy
            .maximumColorCount(PALETTE_COLORS)
            .generate()
        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.dominantSwatch
            ?: palette.mutedSwatch

        // Without a swatch the colour depends on this message's accent, so it isn't remembered.
        val rgb = swatch?.rgb ?: return legibleOnBlack(accent ?: DEFAULT_GLOW_COLOR)
        if (byPackage.size >= MAX_REMEMBERED) byPackage.clear()
        return legibleOnBlack(rgb).also { byPackage[sbn.packageName] = it }
    }

    /**
     * The posting app's ApplicationInfo. Notification.Builder embeds it in the extras, which
     * works without package visibility; the <queries> launcher entry covers the rest.
     */
    private fun applicationInfoFor(sbn: StatusBarNotification): ApplicationInfo? =
        BundleCompat.getParcelable(sbn.notification.extras, EXTRA_APP_INFO, ApplicationInfo::class.java)
            ?: try {
                context.packageManager.getApplicationInfo(sbn.packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }

    private fun loadIcon(appInfo: ApplicationInfo): Drawable? = try {
        context.packageManager.getApplicationIcon(appInfo)
    } catch (_: Exception) {
        null
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
        const val ICON_SIZE_PX = 96
        const val PALETTE_COLORS = 12
        const val MIN_LUMINANCE = 0.12
        const val DARK_LIFT = 0.42f

        /** Apps whose colour is remembered; past this the map starts over (a few bytes each). */
        const val MAX_REMEMBERED = 64

        /** Notification.EXTRA_BUILDER_APPLICATION_INFO (hidden constant, stable since API 24). */
        const val EXTRA_APP_INFO = "android.appInfo"
    }
}
