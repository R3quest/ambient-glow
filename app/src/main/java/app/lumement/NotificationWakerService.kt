package app.lumement

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Display
import androidx.core.content.edit
import androidx.core.os.BundleCompat
import app.lumement.dashboard.GrantReturn

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
    private val displays by lazy(LazyThreadSafetyMode.NONE) { getSystemService(DisplayManager::class.java) }

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

    // Unlocked when the screen went off (a lock delay): relight once it has locked, looking only at
    // the moments [lockChecks] gives (nothing tells us when it locks).
    private var lockWait = LockWait.NONE
    private var lockLooks: List<Long> = emptyList()
    private var lockLook = 0
    private var lockWaitFrom = 0L
    private var screenOffAt = 0L
    private val relightOnceLocked = Runnable {
        lockLook++
        if (keyguard.isKeyguardLocked || lockLook >= lockLooks.size) {
            lockWait = LockWait.DONE
            if (arrivesOnceLocked && keyguard.isKeyguardLocked) arriveOnceLocked() else relightIfWaiting()
            arrivesOnceLocked = false
        } else {
            nextLockLook()
        }
    }

    // A new message came in the lock delay: once locked, it arrives as it would have then, not as
    // a relight (which keeps off an always-on display shown for new notifications only).
    private var arrivesOnceLocked = false

    private fun nextLockLook() {
        val wait = lockWaitFrom + lockLooks[lockLook] - SystemClock.elapsedRealtime()
        DarkHold.acquire(this, wait + 1_000L) // Handler time stops while the CPU sleeps
        mainHandler.postDelayed(relightOnceLocked, wait.coerceAtLeast(0L))
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
            arrivesOnceLocked = false
            // A glow screen on top of the lock screen handles its own screen-off; with none, the
            // listener relights. With nothing waiting there is no LED: let the CPU sleep at once.
            // Nor while Do Not Disturb rests it.
            val screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            LedDiary.note(context, if (screenOff) "screen-off" else "screen-on")
            val relights = screenOff && !GlowPending.isEmpty && !GlowSession.resting
            // Unless this sleep is expected to go off ([GlowSession.expectsAod]), it may show the
            // always-on display: the relight waits for it to doze, to put the LED on it with no
            // wake ([noteSleepState]). If it never dozes, the check below lights the LED as ever.
            val expectsAod = if (screenOff) GlowSession.expectsAod(context) else null
            relightAtDoze = relights && GlowSession.host?.isAway != false && expectsAod != false
            if (screenOff) {
                screenOffAt = SystemClock.elapsedRealtime()
                GlowLog.d { "svc sleep expected: always-on display $expectsAod" }
                watchSleep()
            } else {
                stopWatchingSleep()
            }
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (relights) {
                    DarkHold.acquire(context, LED_CHECK_MS + 1_000L) // up until the relight and the check have run
                    if (GlowSession.host?.isAway != false && !relightAtDoze) mainHandler.postDelayed(relightLed, RELIGHT_DELAY_MS)
                    mainHandler.postDelayed(checkLed, LED_CHECK_MS)
                }
                // Don't cancel the wake bridge here: another app (or the bridge itself) may have
                // lit the panel a moment after it was posted, before the system launched it. The
                // glow screen removes it when it starts, and it times out on its own. Messages
                // waiting: the always-on display's window up while the screen is on.
                Intent.ACTION_SCREEN_ON -> if (!GlowPending.isEmpty) GlowShield.prepareAod()
            }
        }
    }

    /**
     * Where the phone's sleeps go: the always-on display, or off ([GlowSession.sleepDozes]), for
     * the glow screen to choose between the LED on it and the lit LED. Watched from SCREEN_OFF
     * until the phone is awake again, as each display change comes: going to sleep from an
     * unlocked phone, One UI turns the panel off first and dozes it ~0.3 s later; from the lock
     * screen it keeps the panel on on its way (measured on the S23). Once it has dozed, this sleep
     * showed the always-on display, whatever follows (a pocket, its own timeout). Not a sleep
     * cancelled at once (awake again by now), nor one put away (the proximity sensor keeps the
     * always-on display off).
     */
    private fun watchSleep() {
        if (power.isInteractive || GlowSession.host?.isPutAway == true) return
        sleepDozed = false
        DarkHold.acquire(this, SLEEP_SETTLE_HOLD_MS)
        if (!sleepWatching) {
            sleepWatching = true
            displays.registerDisplayListener(sleepWatch, mainHandler)
        }
        noteSleepState()
    }

    /** This sleep has dozed: it showed the always-on display. */
    private var sleepDozed = false

    /** With no glow screen on top, the LED is relit once this sleep dozes: onto the always-on display. */
    private var relightAtDoze = false

    /** [sleepWatch] is registered: from SCREEN_OFF until awake again, or dozed. */
    private var sleepWatching = false

    private fun noteSleepState() {
        if (sleepDozed) return
        // Still lit, on its way: the display's next change says. The last sleep's answer stands
        // meanwhile, so a sleep cancelled before it settles (awake again) doesn't wipe it.
        val shows = sleepShowsAod(displays.getDisplay(Display.DEFAULT_DISPLAY)?.state) ?: return
        if (shows) {
            sleepDozed = true
            stopWatchingSleep()
            if (relightAtDoze) {
                relightAtDoze = false
                mainHandler.removeCallbacks(relightLed)
                mainHandler.post(relightLed)
            }
        }
        if (GlowSession.sleepDozes != shows) {
            GlowLog.d { "svc sleep: always-on display ${if (shows) "showed" else "off"}" }
        }
        GlowSession.sleepDozes = shows
        GlowSession.sleepDozesSetting = aodSwitches(contentResolver)
    }

    private val sleepWatch = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            if (power.isInteractive) stopWatchingSleep() else noteSleepState()
        }
    }

    private fun stopWatchingSleep() {
        if (!sleepWatching) return
        sleepWatching = false
        displays.unregisterDisplayListener(sleepWatch)
    }

    override fun onListenerConnected() {
        GlowLauncher.ensureChannel(this)
        GlowPrefs.warm(this)
        GlowSession.resting = restsUnder(interruptionFilterNow())
        brandColors.prepare()
        // One look at the shade for both: the full list parcels every notification in it.
        val active = activeNow()
        noteScreenTakers(active)
        learnApps(active)
        GlowPending.keepIn(getSharedPreferences(PENDING_FILE, MODE_PRIVATE), bootCount())
        // A new process (an app update, or the system had killed it): the messages still unread
        // wait on the LED again, in the bright phase they had, and it lights as the screen-off
        // would have had it.
        val restored = GlowPending.restore(::stillShowing)
        if (restored) GlowSession.bright = GlowPending.keptBright()
        pruneStalePending()
        if (!screenReceiverRegistered) {
            registerScreenEvents(screenEvents)
            screenReceiverRegistered = true
        }
        GrantReturn.granted(this, GrantReturn.Grant.LISTENER)
        if (restored) GlowLog.d { "svc restored ${GlowPending.entries.size} waiting" }
        // Messages posted while the listener was away arrive as new ones would have: they light it.
        if (catchUp(active)) return
        // Restored by a new process, or kept by this one through a rebind, which cancelled the
        // screen-off's relights ([releaseScreenReceiver]): lit as the screen-off would have had it.
        if (!GlowPending.isEmpty) relightIfWaiting()
    }

    override fun onListenerDisconnected() {
        stopWatchingSleep()
        releaseScreenReceiver()
        brandColors.release()
        requestRebind(ComponentName(this, NotificationWakerService::class.java))
    }

    override fun onDestroy() {
        releaseScreenReceiver()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        noteScreenTaker(sbn)
        val ranked = rankingMap?.getRanking(sbn.key, ranking) == true
        if (!isRealMessage(sbn, if (ranked) ranking else null)) return
        noteHeard(sbn.postTime)
        // Every message keeps its app on the Apps screen, muted or not; a muted one stops here.
        val auto = brandColors.of(sbn)
        val pkg = sbn.packageName
        if (pkg != packageName) {
            GlowApps.noticed(apps, pkg, sbn.postTime, auto) { brandColors.labelOf(sbn) }
            if (GlowApps.isMuted(apps, pkg)) return
        }

        val messages = messagesOf(sbn.notification)
        val newestAt = newestIncomingAt(messages, sbn.notification.`when`)
        val previous = GlowPending.entries.firstOrNull { it.key == sbn.key }
        val quietUpdate = isQuietUpdate(sbn.notification.flags, newestAt, previous?.newestAt, chat = !messages.isNullOrEmpty())

        val unlocked = Premium.unlocked(this)
        val color = if (pkg == packageName) auto else GlowApps.glowOf(apps, pkg, auto, own = unlocked)
        GlowPending.put(PendingGlow(key = sbn.key, pkg = pkg, color = color, newestAt = newestAt), quiet = quietUpdate)
        // In use as it arrives (no screen-on to come): the always-on display's window up now.
        if (power.isInteractive) GlowShield.prepareAod()
        val host = GlowSession.host
        GlowLog.d {
            "svc posted quiet=$quietUpdate interactive=${power.isInteractive} " +
                "locked=${keyguard.isKeyguardLocked} host=${host != null} away=${host?.isAway}"
        }
        if (quietUpdate) return

        val settings = GlowPrefs.load(this)
        // A new message: the LED's bright phase starts over, within the streak's time ([BrightStreak]);
        // the phone in use, a new streak ([brightAfterMessage]).
        val inUse = power.isInteractive && !keyguard.isKeyguardLocked
        GlowSession.bright = brightAfterMessage(GlowSession.bright, SystemClock.elapsedRealtime(), settings.brightFor.ms, inUse)
        // One UI's always-on display for it, at the next sleep; not for one read on the phone in use.
        if (!inUse) GlowSession.aodDue = true
        LedDiary.note(this, "message", "waiting=${GlowPending.entries.size}")
        val arrival = arrivalFor(settings.arrival, GlowSession.resting)
        // Premium's trial starts with the first message it plays for: its element, or an app's own colour.
        val premium = unlocked && messageShowsPremium(settings, arrival, GlowShield.isOn, GlowApps.colorOf(apps, pkg) != null)
        if (arrival == ArrivalMode.MESSAGE) {
            // The system pops a message up by itself for a channel that peeks; only otherwise does
            // the glow screen re-post it. One UI shows that heads-up even when the screen was off
            // (once our wake lights the panel) and for alert-once chat updates, so a copy doubles it.
            val systemPopsUp = ranked && ranking.importance >= NotificationManager.IMPORTANCE_HIGH
            GlowPending.message = PendingMessage(sbn, systemPopsUp)
        }

        // Glow screen on top of the lock screen: it lights the lock screen and plays the effect.
        if (host != null && !host.isAway) {
            if (premium) Premium.played(this)
            host.onNewMessage()
            return
        }
        // Unlocked and in use: the heads-up is enough; the LED waits for screen-off.
        if (inUse) return
        // In a call: dark, the LED comes once the call is over ([relightPlan]'s AFTER_CALL); no
        // glow screen may be left to do it, as one that finished let go of its own call watch.
        if (audio.inCall) {
            relightIfWaiting()
            return
        }
        // Dark but not locked yet (the lock delay after a screen-off): a wake now would light the
        // unlocked phone, and the glow screen would only step aside. The LED once it locks.
        if (!power.isInteractive && !keyguard.isKeyguardLocked) {
            arrivesOnceLocked = true
            relightIfWaiting()
            return
        }
        if (SystemClock.elapsedRealtime() - lastWakeAt < WAKE_DEBOUNCE_MS) return
        // Put the glow screen on top of the lock screen (lighting the panel if it is off), or
        // with a black arrival and the screen off, straight into the black panel.
        if (launch(wakeModeFor(arrival, power.isInteractive), color)) {
            lastWakeAt = SystemClock.elapsedRealtime()
            if (premium) Premium.played(this)
        }
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
        if (GlowSession.screenTakers.remove(sbn.key)) GlowSession.host?.onScreenTakersChanged()
        if (!GlowPending.remove(sbn.key)) return
        LedDiary.note(this, "read", "waiting=${GlowPending.entries.size}")
        if (GlowPending.isEmpty) GlowSession.allRead()
        GlowSession.host?.onPendingChanged()
    }

    /** Keeps [GlowSession.screenTakers] in step with [sbn], a post or an update to one; tells the glow screen of a change. */
    private fun noteScreenTaker(sbn: StatusBarNotification) {
        val takes = isScreenTaker(sbn)
        val changed = if (takes) GlowSession.screenTakers.add(sbn.key) else GlowSession.screenTakers.remove(sbn.key)
        if (!changed) return
        GlowLog.d { "svc screen taker ${if (takes) "up" else "gone"} ${sbn.packageName} ${sbn.notification.category}" }
        GlowSession.host?.onScreenTakersChanged()
    }

    /** Another app's post that wants the screen ([takesScreen]). */
    private fun isScreenTaker(sbn: StatusBarNotification): Boolean =
        sbn.packageName != packageName && takesScreen(
            sbn.notification.category,
            fullScreen = sbn.notification.fullScreenIntent != null,
            ongoing = sbn.isOngoing,
            foreground = sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
        )

    /**
     * [GlowSession.screenTakers] afresh from the shade at a connection ([active]): posts and
     * removals while the listener was away never came, so a ringing call that ended meanwhile
     * would hold the LED's window back for good. The glow screen hears of a change. Kept as it
     * was when the system can't say (null).
     */
    private fun noteScreenTakers(active: Array<StatusBarNotification>?) {
        val takers = active?.filter(::isScreenTaker)?.mapTo(HashSet()) { it.key } ?: return
        if (takers == GlowSession.screenTakers) return
        GlowSession.screenTakers.clear()
        GlowSession.screenTakers.addAll(takers)
        GlowLog.d { "svc screen takers afresh: ${takers.size}" }
        GlowSession.host?.onScreenTakersChanged()
    }

    /**
     * Everything in the shade, or null if the system can't say: it refuses a listener it no
     * longer counts as connected (unbound again already, or access just turned off).
     */
    private fun activeNow(): Array<StatusBarNotification>? = try {
        activeNotifications
    } catch (_: SecurityException) {
        null
    }

    /** Do Not Disturb's filter now, or unknown (it doesn't rest the LED) when the system refuses, as [activeNow]. */
    private fun interruptionFilterNow(): Int = try {
        currentInterruptionFilter
    } catch (_: SecurityException) {
        INTERRUPTION_FILTER_UNKNOWN
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
        val onAod = !power.isInteractive && dozing(displays.getDisplay(Display.DEFAULT_DISPLAY)?.state)
        if (onAod) {
            // Asleep on the always-on display, the LED goes onto it, with no wake. Not where it
            // goes out after its bright phase, nor where One UI shows it for new notifications only
            // ([ledOnAodOnly]): a relight here (after an unlock, Do Not Disturb, the watchdog) is for
            // messages One UI counts as seen, and a doze now may be another screen of One UI's.
            if (GlowPrefs.load(this).afterBright != AfterBright.AOD || ledOnAodOnly(contentResolver)) return
            // The LED on the always-on display is GlowShield's window: without it there is none, and
            // the only launch left lit the screen in the always-on display's place.
            if (!GlowShield.isOn) return
        } else if (!litLedAllowed(SystemClock.elapsedRealtime(), GlowSession.bright, { batterySaver }, { batteryPct() })) {
            // The lit LED only in its bright phase: after it, dark until the next message.
            return
        }
        GlowLog.d { "svc relight LED${if (onAod) " on the always-on display" else ""}" }
        launch(if (onAod) WakeMode.AOD else WakeMode.LED, newest.color)
    }

    /**
     * The message that came in the lock delay, now that the phone has locked: the glow screen as
     * for a new message (its arrival, then the lit LED in the phase it started), unless something
     * rightly keeps the LED dark or a glow screen is up already; those go as a relight would.
     */
    private fun arriveOnceLocked() {
        val plan = relightPlan(
            screenOn = power.isInteractive,
            waiting = !GlowPending.isEmpty,
            resting = GlowSession.resting,
            hostOnTop = GlowSession.host?.isAway == false,
            inCall = audio.inCall,
            locked = true,
        )
        if (plan != Relight.NOW) {
            relightIfWaiting()
            return
        }
        GlowLog.d { "svc locked: the message from the lock delay arrives" }
        val arrival = arrivalFor(GlowPrefs.load(this).arrival, GlowSession.resting)
        if (launch(wakeModeFor(arrival, power.isInteractive), GlowPending.newestColor)) lastWakeAt = SystemClock.elapsedRealtime()
    }

    /**
     * The screen went off unlocked: with a lock delay (One UI: 5 s after the screen times out) it
     * locks a little later, with nothing to tell us. Look at the moments [lockChecks] gives, then
     * relight. Once per screen-off: a phone that stays unlocked (Smart Lock) isn't waited on again.
     */
    private fun awaitLock() {
        if (lockWait != LockWait.NONE) return
        lockLooks = lockChecks(lockDelayMs())
        lockWait = if (lockLooks.isEmpty()) LockWait.DONE else LockWait.WAITING
        if (lockLooks.isEmpty()) return
        lockLook = 0
        // [lockChecks] counts from the screen-off; a new process didn't see it: from now.
        lockWaitFrom = if (screenOffAt > 0L) screenOffAt else SystemClock.elapsedRealtime()
        GlowLog.d { "svc unlocked: relight once it locks, looking at $lockLooks ms" }
        nextLockLook()
    }

    /** Which boot this is, to tell its elapsed times from an earlier boot's; -1 if unknown. */
    private fun bootCount(): Int = try {
        android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }

    /** How long after the screen goes off the phone locks (a hidden but readable setting), or null. */
    private fun lockDelayMs(): Long? = try {
        android.provider.Settings.Secure.getLong(contentResolver, LOCK_AFTER_TIMEOUT)
    } catch (_: Exception) {
        null
    }

    private enum class LockWait { NONE, WAITING, DONE }

    /**
     * Lists the apps whose messages are in the shade now ([active]), so the Apps screen isn't empty
     * before the next message (on a fresh install, or the first run after an update). Once per
     * connection: the full list parcels every notification in the shade.
     */
    private fun learnApps(active: Array<StatusBarNotification>?) {
        active ?: return
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
        if (!GlowPending.keepOnly(stillShowing(GlowPending.entries.map { it.key }) ?: return)) return
        // Read unseen: as if their removals had come. Some left, the still LED on the always-on
        // display is drawn again without them; it kept their colours until its next step.
        if (GlowPending.isEmpty) GlowSession.allRead()
        GlowSession.host?.onPendingChanged()
    }

    /** The newest message heard, by its post time, kept with the waiting ones: where a reconnect's [catchUp] starts. */
    private fun noteHeard(at: Long) {
        val prefs = getSharedPreferences(PENDING_FILE, MODE_PRIVATE)
        if (at > prefs.getLong(KEY_HEARD_AT, 0L)) prefs.edit { putLong(KEY_HEARD_AT, at) }
    }

    /**
     * Takes the messages posted while the listener was away ([missedWhileAway]) as they would have
     * been taken; true if any now waits (they light the LED as a new message does). The first
     * connect only marks where the next one starts.
     */
    private fun catchUp(active: Array<StatusBarNotification>?): Boolean {
        active ?: return false
        val prefs = getSharedPreferences(PENDING_FILE, MODE_PRIVATE)
        val heardAt = if (prefs.contains(KEY_HEARD_AT)) prefs.getLong(KEY_HEARD_AT, 0L) else null
        if (heardAt == null) prefs.edit { putLong(KEY_HEARD_AT, System.currentTimeMillis()) }
        val waiting = GlowPending.entries.mapTo(HashSet()) { it.key }
        val missed = missedWhileAway(active.associate { it.key to it.postTime }, heardAt, waiting, System.currentTimeMillis())
        if (missed.isEmpty()) return false
        val byKey = active.associateBy { it.key }
        val rankingMap = currentRanking
        for (key in missed) byKey[key]?.let { onNotificationPosted(it, rankingMap) }
        val caught = GlowPending.entries.any { it.key in missed }
        if (caught) GlowLog.d { "svc caught up with messages posted while away" }
        return caught
    }

    /** Which of [keys] are still in the shade, or null if the system can't say yet. */
    private fun stillShowing(keys: List<String>): Set<String>? = try {
        getActiveNotifications(keys.toTypedArray())?.mapTo(HashSet()) { it.key }
    } catch (_: SecurityException) {
        null // not connected yet
    }

    private fun launch(mode: WakeMode, color: Int): Boolean {
        LedDiary.note(this, "launch", mode.name)
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        // The full-screen intent wakes the panel: the LED lit there, not on the always-on display.
        // So onto the always-on display only by the dark start; and where the bridge can't be
        // posted ([GlowLauncher.canPostBridge]: no full-screen grant, notifications denied, its
        // channel turned down), the dark start is the only way in, the glow screen lighting the
        // panel itself. Notifications denied (Android 13+), no LED came at all (an emulator, 2026-10-23).
        val woken = if (mode == WakeMode.AOD) WakeMode.LED else mode
        val bridge = GlowLauncher.canPostBridge(this)
        // Into the black LED face from a dark screen: straight in, while still dark, so the lock
        // screen never shows first. If it didn't arrive, the full-screen intent as ever.
        val dark = (mode != WakeMode.WAKE && !power.isInteractive) || !bridge
        if (dark && GlowLauncher.startInTheDark(this, mode)) {
            mainHandler.postDelayed({
                val host = GlowSession.host
                if (host == null || host.isAway) {
                    if (mode == WakeMode.AOD || !bridge) {
                        GlowLog.d { "svc dark start didn't arrive: left dark" }
                        wakeLock.release()
                    } else {
                        GlowLog.d { "svc dark start didn't arrive: full-screen intent" }
                        if (!GlowLauncher.launchFromBackground(this, woken, color)) wakeLock.release()
                    }
                }
            }, DARK_START_CHECK_MS)
            return true
        }
        if (mode == WakeMode.AOD) {
            wakeLock.release()
            return false
        }
        val launched = GlowLauncher.launchFromBackground(this, woken, color)
        if (!launched) wakeLock.release()
        return launched
    }

    private fun releaseScreenReceiver() {
        mainHandler.removeCallbacks(relightLed)
        mainHandler.removeCallbacks(checkLed)
        mainHandler.removeCallbacks(relightOnceLocked)
        afterCall.cancel()
        // The waits those were: left set, a rebind's screen-off would think them still under way.
        lockWait = LockWait.NONE
        arrivesOnceLocked = false
        relightAtDoze = false
        if (screenReceiverRegistered) {
            unregisterReceiver(screenEvents)
            screenReceiverRegistered = false
        }
    }

    /** Lets through only new, user-facing alerts ([isAlert]); of our own, only the test messages. */
    private fun isRealMessage(sbn: StatusBarNotification, ranking: Ranking?): Boolean {
        val notification = sbn.notification
        if (sbn.packageName == packageName && notification.channelId != GlowLauncher.TEST_CHANNEL_ID) return false
        if (fromTheSystem(sbn.packageName)) return false
        // Its app paused (Focus mode, an app timer): the shade hides it, and the LED would light for nothing to see.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ranking?.isSuspended == true) return false
        return isAlert(
            ongoing = sbn.isOngoing,
            flags = notification.flags,
            category = notification.category,
            importance = ranking?.importance,
            allowedNow = ranking?.matchesInterruptionFilter() ?: true,
        )
    }

    /**
     * A MessagingStyle notification's messages, each with its time and whether the user wrote it
     * (for [newestIncomingAt]); null for any other. Reads the raw message bundles; no MessagingStyle
     * objects are rebuilt. The user's own carry no sender: MessagingStyle writes one only for others.
     */
    private fun messagesOf(notification: Notification): List<MessageTime>? =
        BundleCompat.getParcelableArray(notification.extras, Notification.EXTRA_MESSAGES, Bundle::class.java)
            ?.mapNotNull { message ->
                (message as? Bundle)?.let {
                    MessageTime(it.getLong(KEY_MESSAGE_TIME), mine = MESSAGE_SENDER_KEYS.none(it::containsKey))
                }
            }

    private companion object {
        const val WAKE_LOCK_TAG = "Lumement:wake"
        const val WAKE_LOCK_TIMEOUT_MS = 4_000L

        /** [GlowPending]'s file, so the waiting messages outlive the process. */
        const val PENDING_FILE = "lumement_pending"

        /** In it: the post time of the newest message heard ([noteHeard]). */
        const val KEY_HEARD_AT = "heard_at"

        /** The watchdog looks this long after the screen went off: past every relight's own delay. */
        const val LED_CHECK_MS = 4_000L

        /** Upper bound on the CPU held from SCREEN_OFF while the display settles, off or into the always-on display (~0.3 s). */
        const val SLEEP_SETTLE_HOLD_MS = 2_000L

        /** Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT (hidden), in ms. */
        const val LOCK_AFTER_TIMEOUT = "lock_screen_lock_after_timeout"

        /** A dark start that hasn't brought the glow screen on top by now isn't coming. */
        const val DARK_START_CHECK_MS = 800L
        /** Lets the sleep transition (and keyguard lock) finish before waking the panel again. */
        const val RELIGHT_DELAY_MS = 400L

        /** Notification.MessagingStyle.Message's timestamp key inside each EXTRA_MESSAGES bundle. */
        const val KEY_MESSAGE_TIME = "time"

        /** Its sender's keys (the platform's and NotificationCompat's), written only for a message from someone else. */
        val MESSAGE_SENDER_KEYS = listOf("sender", "sender_person", "person")
    }
}

/**
 * The app was updated: the system stopped it and dropped its listener. Android 8.0 doesn't always
 * bind it again (seen on an emulator, 2026-10-23: no message heard, so no LED, until a reboot);
 * asked here, it does. A listener bound already is left as it is, and one not allowed stays off.
 */
class ListenerRebind : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { NotificationListenerService.requestRebind(ComponentName(context, NotificationWakerService::class.java)) }
            .onFailure { GlowLog.d { "svc rebind after the update refused: $it" } }
    }
}
