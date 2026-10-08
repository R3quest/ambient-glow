package com.example.ambientglow.dashboard

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.ambientglow.AppsSnapshot
import com.example.ambientglow.GlowApp
import com.example.ambientglow.GlowApps
import com.example.ambientglow.GlowPending
import com.example.ambientglow.GlowSession
import com.example.ambientglow.isNewSince

/**
 * The Apps screen's state and what it does, kept apart from the dashboard: the apps the listener
 * has learned and the user's choices, live from [GlowApps]' file; which apps are new since the
 * screen was last left; and muting and colouring, which also reach the messages already waiting
 * on the LED. Main thread only, like the listener that writes the file.
 */
@Stable
internal class AppsModel(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Bumped by every write to the file. The snapshot is read again only when someone reads it
    // after a bump, so a new app's four keys cost one read, not four.
    private var version by mutableIntStateOf(0)
    private val snapshot by derivedStateOf {
        version
        GlowApps.read(prefs)
    }

    // Held here: SharedPreferences keeps its listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }

    val current: AppsSnapshot get() = snapshot

    /** When the Apps screen was last left: apps first heard from after it carry a NEW tag. */
    var viewedAt by mutableLongStateOf(GlowApps.viewedAt(prefs))
        private set

    /** Apps have arrived since the Apps screen was last left: its button carries a dot. */
    val hasNew: Boolean get() = snapshot.apps.any { it.isNewSince(viewedAt) }

    fun attach() = prefs.registerOnSharedPreferenceChangeListener(listener)

    fun detach() = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    /** The Apps screen was left (or the dashboard with it): what was new there is seen. */
    fun markViewed() {
        val now = clock()
        GlowApps.markViewed(prefs, now)
        viewedAt = now
    }

    /** Muting also takes the app's waiting messages off the LED at once, as if they were read. */
    fun mute(pkg: String, muted: Boolean) {
        GlowApps.setMuted(prefs, pkg, muted)
        if (muted && GlowPending.removeApp(pkg)) GlowSession.host?.onPendingChanged()
    }

    /**
     * Gives [app] [color] (null: its icon's again). Its waiting messages blink in it from the next
     * breath. Returns the colour it now glows in.
     */
    fun recolor(app: GlowApp, color: Int?): Int {
        GlowApps.setColor(prefs, app.pkg, color)
        val glow = color ?: app.autoColor
        GlowPending.recolor(app.pkg, glow)
        return glow
    }
}

/** The dashboard's [AppsModel], listening to the file while the dashboard is up. */
@Composable
internal fun rememberAppsModel(): AppsModel {
    val context = LocalContext.current
    val model = remember(context) { AppsModel(GlowApps.prefs(context)) }
    DisposableEffect(model) {
        model.attach()
        onDispose { model.detach() }
    }
    return model
}
