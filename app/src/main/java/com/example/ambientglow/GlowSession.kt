package com.example.ambientglow

import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One unread message the LED is waiting on, and the brand colour it blinks in. [newestAt] is the
 * timestamp of the newest message it carried, so an update can be told apart from a new message.
 */
@Immutable
data class PendingGlow(val key: String, val color: Int, val newestAt: Long = 0L)

/**
 * The newest message for [ArrivalMode.MESSAGE]. [systemPopsUp]: the system shows it as a heads-up
 * itself (its channel peeks, even if the screen was off); otherwise the glow screen re-posts it
 * once the black panel is up.
 */
class PendingMessage(val sbn: StatusBarNotification, val systemPopsUp: Boolean)

/**
 * Unread messages the LED is waiting on: the single source of truth shared by the listener
 * (writes) and the glow screen (observes). Memory only, main thread only, never persisted.
 * An entry leaves only when its notification is dismissed or cleared by the app (read).
 */
object GlowPending {
    /** Newest first. Snapshot state, so the glow screen recomposes when it changes. */
    val entries = mutableStateListOf<PendingGlow>()

    val isEmpty: Boolean get() = entries.isEmpty()

    /** The newest message's colour, or the default when nothing is waiting. */
    val newestColor: Int get() = entries.firstOrNull()?.color ?: DEFAULT_GLOW_COLOR

    /** The newest message, for [ArrivalMode.MESSAGE] only; dropped once it is read or dismissed. */
    var message: PendingMessage? by mutableStateOf(null)

    fun put(entry: PendingGlow) {
        entries.removeAll { it.key == entry.key }
        entries.add(0, entry)
    }

    fun remove(key: String): Boolean {
        if (message?.sbn?.key == key) message = null
        return entries.removeAll { it.key == key }
    }

    /** Distinct colours in arrival order (newest first): one breath each in the LED's round. */
    fun colors(): List<Int> = entries.map { it.color }.distinct()
}

/**
 * How the glow screen opens.
 * - WAKE: get on top of the lock screen without covering it, so the system lock screen shows
 *   the message with its own notifications (and light the panel if it is off).
 * - LED: cover the lock screen with the black panel and the blinking LED.
 * - ARRIVAL: like LED, but first play the effect or show the message on the black panel
 *   ([ArrivalMode.onBlack]).
 */
enum class WakeMode { WAKE, LED, ARRIVAL }

/**
 * In-process link between the listener and a live glow screen (both on the main thread of
 * one process). While the glow screen is on top of the lock screen it handles every lock-flow
 * change itself; the listener only takes over when there is no glow screen on top.
 */
object GlowSession {
    interface Host {
        /** The glow screen is alive but behind other apps (the phone was unlocked). */
        val isAway: Boolean

        /** A new message arrived while the glow screen is on top of the lock screen. */
        fun onNewMessage()

        /** A waiting message was read or dismissed. */
        fun onPendingChanged()
    }

    var host: Host? = null
        private set

    fun attach(host: Host) {
        this.host = host
    }

    fun detach(host: Host) {
        if (this.host === host) this.host = null
    }
}
