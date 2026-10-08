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
import androidx.core.os.BundleCompat
import com.example.ambientglow.dashboard.GrantReturn

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
    private val apps by lazy(LazyThreadSafetyMode.NONE) { GlowApps.prefs(this) }
    private val ranking = Ranking()

    private var lastWakeAt = 0L

    // The watchdog: a few seconds after the screen went off with messages waiting, the LED is up
    // or rightly dark. Whatever slipped through, it is lit then.
    private val checkLed = Runnable {
        val host = GlowSession.host
        if (host != null && !host.isAway) host.ensureLed() else relightIfWaiting()
    }

    // Unlocked when the screen went off (a lock delay): relight once it has locked, waiting once.
    private var lockWait = LockWait.NONE
    private val relightOnceLocked = Runnable {
        lockWait = LockWait.DONE
        relightIfWaiting()
    }

    // A relight that bailed out for a call, tried again once it is over.
    private val afterCall by lazy(LazyThreadSafetyMode.NONE) { AfterCall(this, ::relightIfWaiting) }

    // Screen-off → relight the LED while messages are still unread. The short delay lets the
    // keyguard settle first.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val relightLed = Runnable { relightIfWaiting() }
    private var screenReceiverRegistered = false
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GlowLog.d { "svc ${intent.action?.substringAfterLast('.')} pending=${GlowPending.entries.size}" }
            mainHandler.removeCallbacks(relightLed)
            mainHandler.removeCallbacks(checkLed)
            mainHandler.removeCallbacks(relightOnceLocked)
            lockWait = LockWait.NONE
            when (intent.action) {
                // A glow screen on top of the lock screen handles its own screen-off; with none, the
                // listener relights. With nothing waiting there is no LED: let the CPU sleep at once.
                // Nor while Do Not Disturb rests it.
                Intent.ACTION_SCREEN_OFF -> if (!GlowPending.isEmpty && !GlowSession.resting) {
                    DarkHold.acquire(context, LED_CHECK_MS + 1_000L) // up until the relight and the check have run
                    if (GlowSession.host?.isAway != false) mainHandler.postDelayed(relightLed, RELIGHT_DELAY_MS)
                    mainHandler.postDelayed(checkLed, LED_CHECK_MS)
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
        GlowSession.resting = restsUnder(currentInterruptionFilter)
        brandColors.prepare()
        learnApps()
        pruneStalePending()
        if (!screenReceiverRegistered) {
            registerScreenEvents(screenEvents)
            screenReceiverRegistered = true
        }
        GrantReturn.granted(this, GrantReturn.Grant.LISTENER)
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
        // Every message keeps its app on the Apps screen, muted or not; a muted one stops here.
        val auto = brandColors.of(sbn)
        val pkg = sbn.packageName
        if (pkg != packageName) {
            GlowApps.noticed(apps, pkg, sbn.postTime, auto) { brandColors.labelOf(sbn) }
            if (GlowApps.isMuted(apps, pkg)) return
        }

        val newestAt = newestMessageAt(sbn.notification)
        val previous = GlowPending.entries.firstOrNull { it.key == sbn.key }
        val quietUpdate = isQuietUpdate(sbn.notification.flags, newestAt, previous?.newestAt)

        val color = GlowApps.colorOf(apps, pkg) ?: auto
        GlowPending.put(PendingGlow(key = sbn.key, pkg = pkg, color = color, newestAt = newestAt))
        val host = GlowSession.host
        GlowLog.d {
            "svc posted quiet=$quietUpdate interactive=${power.isInteractive} " +
                "locked=${keyguard.isKeyguardLocked} host=${host != null} away=${host?.isAway}"
        }
        if (quietUpdate) return

        val arrival = arrivalFor(GlowPrefs.load(this).arrival, GlowSession.resting)
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

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        val resting = restsUnder(interruptionFilter)
        if (resting == GlowSession.resting) return
        GlowSession.resting = resting
        GlowLog.d { "svc ${if (resting) "rest" else "rest over"} (filter $interruptionFilter) pending=${GlowPending.entries.size}" }
        val host = GlowSession.host
        if (host != null && !host.isAway) {
            host.onRestChanged()
        } else if (!resting) {
            relightIfWaiting() // no glow screen on top: back through the full-screen intent
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        // Dismissed, or cleared by its app after being read: the only way an LED entry ends.
        GlowLauncher.dismissMessage(this, sbn.key)
        if (!GlowPending.remove(sbn.key)) return
        GlowSession.host?.onPendingChanged()
    }

    private fun relightIfWaiting() {
        val plan = relightPlan(
            screenOn = power.isInteractive,
            waiting = !GlowPending.isEmpty,
            resting = GlowSession.resting,
            hostOnTop = GlowSession.host?.isAway == false,
            inCall = audio.inCall,
            locked = keyguard.isKeyguardLocked,
        )
        when (plan) {
            Relight.NONE -> return
            Relight.AFTER_CALL -> {
                afterCall.arm()
                return
            }
            // The glow screen over an unlocked phone would only step aside again: wait for the lock.
            Relight.AFTER_LOCK -> {
                awaitLock()
                return
            }
            Relight.NOW -> Unit
        }
        pruneStalePending()
        val newest = GlowPending.entries.firstOrNull() ?: return
        // Only reached when the glow screen was not on top (the phone had been unlocked). Through
        // the full-screen intent Android shows the lock screen for a moment before the LED covers
        // it; started in the dark ([GlowLauncher.startInTheDark]) it doesn't.
        GlowLog.d { "svc relight LED" }
        launch(WakeMode.LED, newest.color)
    }

    /**
     * The screen went off unlocked: with a lock delay (One UI: 5 s after the screen times out) it
     * locks a little later, with nothing to tell us. Wait that long, then relight. Once per screen-off:
     * a phone that stays unlocked (Smart Lock) isn't waited on again. A delay past
     * [MAX_LOCK_WAIT_MS] isn't held out for; the next wake brings the LED back.
     */
    private fun awaitLock() {
        if (lockWait != LockWait.NONE) return
        val delay = lockDelayMs()
        if (delay > MAX_LOCK_WAIT_MS) {
            lockWait = LockWait.DONE
            return
        }
        lockWait = LockWait.WAITING
        GlowLog.d { "svc unlocked: relight once it locks, in ${delay + LOCK_MARGIN_MS} ms" }
        DarkHold.acquire(this, delay + LOCK_MARGIN_MS + 1_000L) // Handler time stops while the CPU sleeps
        mainHandler.postDelayed(relightOnceLocked, delay + LOCK_MARGIN_MS)
    }

    /** How long after the screen goes off the phone locks; a hidden but readable setting. */
    private fun lockDelayMs(): Long = try {
        android.provider.Settings.Secure.getLong(contentResolver, LOCK_AFTER_TIMEOUT)
    } catch (_: Exception) {
        DEFAULT_LOCK_DELAY_MS
    }

    private enum class LockWait { NONE, WAITING, DONE }

    /**
     * Lists the apps whose messages are in the shade now, so the Apps screen isn't empty before the
     * next message (on a fresh install, or the first run after an update). Once per connection:
     * the full list parcels every notification in the shade.
     */
    private fun learnApps() {
        val active = try {
            activeNotifications ?: return
        } catch (_: SecurityException) {
            return // not connected yet
        }
        val rankingMap = currentRanking
        for (sbn in active) {
            if (sbn.packageName == packageName) continue
            val ranked = rankingMap?.getRanking(sbn.key, ranking) == true
            if (!isRealMessage(sbn, if (ranked) ranking else null)) continue
            GlowApps.noticed(apps, sbn.packageName, sbn.postTime, brandColors.of(sbn)) { brandColors.labelOf(sbn) }
        }
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
        // Into the black LED face from a dark screen: straight in, while still dark, so the lock
        // screen never shows first. If it didn't arrive, the full-screen intent as ever.
        if (mode != WakeMode.WAKE && !power.isInteractive && GlowLauncher.startInTheDark(this, mode)) {
            mainHandler.postDelayed({
                val host = GlowSession.host
                if (host == null || host.isAway) {
                    GlowLog.d { "svc dark start didn't arrive: full-screen intent" }
                    if (!GlowLauncher.launchFromBackground(this, mode, color)) wakeLock.release()
                }
            }, DARK_START_CHECK_MS)
            return true
        }
        val launched = GlowLauncher.launchFromBackground(this, mode, color)
        if (!launched) wakeLock.release()
        return launched
    }

    private fun releaseScreenReceiver() {
        mainHandler.removeCallbacks(relightLed)
        mainHandler.removeCallbacks(checkLed)
        mainHandler.removeCallbacks(relightOnceLocked)
        afterCall.cancel()
        if (screenReceiverRegistered) {
            unregisterReceiver(screenEvents)
            screenReceiverRegistered = false
        }
    }

    /** Lets through only new, user-facing alerts ([isAlert]); of our own, only the test messages. */
    private fun isRealMessage(sbn: StatusBarNotification, ranking: Ranking?): Boolean {
        val notification = sbn.notification
        if (sbn.packageName == packageName && notification.channelId != GlowLauncher.TEST_CHANNEL_ID) return false
        return isAlert(
            ongoing = sbn.isOngoing,
            flags = notification.flags,
            category = notification.category,
            importance = ranking?.importance,
            allowedNow = ranking?.matchesInterruptionFilter() ?: true,
        )
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

        /** The watchdog looks this long after the screen went off: past every relight's own delay. */
        const val LED_CHECK_MS = 4_000L

        /** Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT (hidden), in ms; One UI's default is 5 s. */
        const val LOCK_AFTER_TIMEOUT = "lock_screen_lock_after_timeout"
        const val DEFAULT_LOCK_DELAY_MS = 5_000L
        const val LOCK_MARGIN_MS = 600L
        const val MAX_LOCK_WAIT_MS = 30_000L

        /** A dark start that hasn't brought the glow screen on top by now isn't coming. */
        const val DARK_START_CHECK_MS = 800L
        /** Lets the sleep transition (and keyguard lock) finish before waking the panel again. */
        const val RELIGHT_DELAY_MS = 400L

        /** Notification.MessagingStyle.Message's timestamp key inside each EXTRA_MESSAGES bundle. */
        const val KEY_MESSAGE_TIME = "time"
    }
}
