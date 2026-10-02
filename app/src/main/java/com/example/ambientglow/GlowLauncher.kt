package com.example.ambientglow

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
    private const val BRIDGE_NOTIFICATION_ID = 0xA61
    private const val BRIDGE_TIMEOUT_MS = 4_000L
    private const val BRIDGE_REQUEST_CODE = 0

    fun wakeIntent(context: Context, request: GlowRequest): Intent =
        request.writeTo(
            Intent(context, WakeScreenActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            ),
        )

    /** Foreground-only path, used by the dashboard's Test Preview. */
    fun launchPreview(context: Context, request: GlowRequest) {
        context.startActivity(wakeIntent(context, request))
    }

    fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.channel_name))
            .setDescription(context.getString(R.string.channel_description))
            .setSound(null, null)
            .setVibrationEnabled(false)
            .setLightsEnabled(false)
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** True when alerts are allowed and the bridge channel still has heads-up importance. */
    fun canPostBridge(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val channel = manager.getNotificationChannelCompat(CHANNEL_ID) ?: return true
        return channel.importance >= NotificationManagerCompat.IMPORTANCE_HIGH
    }

    /** Background path, used by [NotificationWakerService]. Returns false if the bridge is blocked. */
    fun launchFromBackground(context: Context, request: GlowRequest): Boolean {
        ensureChannel(context)
        if (!canPostBridge(context)) return false

        val pending = PendingIntent.getActivity(
            context,
            BRIDGE_REQUEST_CODE,
            wakeIntent(context, request),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Not setSilent(): NotificationCompat implements that with a silenced group, and
        // SystemUI suppresses full-screen intents for group-silenced children. The channel
        // already has no sound and no vibration.
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_glow)
            .setColor(request.color)
            .setContentTitle(context.getString(R.string.bridge_title))
            .setContentText(context.getString(R.string.bridge_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setTimeoutAfter(BRIDGE_TIMEOUT_MS)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        postBridge(context, notification)
        return true
    }

    fun dismissBridge(context: Context) {
        NotificationManagerCompat.from(context).cancel(BRIDGE_NOTIFICATION_ID)
    }

    // Permission is verified by canPostBridge() right before this is called.
    @SuppressLint("MissingPermission")
    private fun postBridge(context: Context, notification: android.app.Notification) {
        NotificationManagerCompat.from(context).notify(BRIDGE_NOTIFICATION_ID, notification)
    }
}
