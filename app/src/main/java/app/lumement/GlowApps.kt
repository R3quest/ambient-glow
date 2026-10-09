package app.lumement

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import java.text.Collator
import java.text.Normalizer

/**
 * An app that has sent a message: its name, when it was first and last heard from, the colour
 * its icon gives ([autoColor]), and what the user chose: [muted], or a [color] of their own.
 * [apart] is set by [settled] when its icon's colour is taken.
 */
@Immutable
data class GlowApp(
    val pkg: String,
    val label: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val autoColor: Int,
    val color: Int? = null,
    val muted: Boolean = false,
    val apart: Apart? = null,
) {
    /** What its messages glow in: the user's colour, else its icon's, unless that is taken. */
    val glow: Int get() = color ?: apart?.color ?: autoColor
}

/** The colour an app glows in instead of its icon's, which glows like [from]'s (a label). */
@Immutable
data class Apart(val color: Int, val from: String)

/**
 * What the user chose for every app, heard from or not, by package: one can be muted or given a
 * colour from the phone's app list before it ever sends a message, and is listed so when it does.
 */
@Immutable
data class AppChoices(val muted: Set<String> = emptySet(), val colors: Map<String, Int> = emptyMap())

/** Everything the Apps screen shows, read from the file in one pass: the [apps] heard from, and the [choices]. */
@Immutable
data class AppsSnapshot(val apps: List<GlowApp> = emptyList(), val choices: AppChoices = AppChoices())

/**
 * The apps the Apps screen lists first, learned by the listener as their messages arrive: only
 * apps that actually send messages, so that list stays short. Every app lights up until it is
 * muted, so a new app is never missed; the rest of the phone's apps can be muted ahead of time.
 *
 * Its own file, apart from [GlowPrefs]: the dashboard saves settings whole, and must not write
 * over what the listener learned in the meantime. One key per fact per app, so the listener's
 * check on each message is a map lookup, and it writes only when something changed.
 */
object GlowApps {
    private const val FILE = "lumement_apps"

    // Per app, the key prefix and then its package. SEEN lists it.
    private const val SEEN = "seen:"
    private const val FIRST = "first:"
    private const val LABEL = "label:"
    private const val AUTO = "auto:"
    private const val COLOR = "color:"
    private const val MUTED = "muted:"

    /** When the Apps screen was last left: apps first heard from after it are new. */
    private const val KEY_VIEWED = "viewed_at"

    /** A busy chat re-stamps when it was last heard at most this often: a write an hour, not a message. */
    private const val SEEN_STEP_MS = 60 * 60 * 1000L

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isMuted(prefs: SharedPreferences, pkg: String): Boolean = prefs.getBoolean(MUTED + pkg, false)

    /** The colour the user gave [pkg], or null to use its icon's. */
    fun colorOf(prefs: SharedPreferences, pkg: String): Int? =
        if (prefs.contains(COLOR + pkg)) prefs.getInt(COLOR + pkg, DEFAULT_GLOW_COLOR) else null

    /**
     * What [pkg]'s messages glow in, its icon giving [auto]: as [settled] works it out among every
     * app heard from, the user's colours only with premium ([own]). Reads the whole file, so it
     * is for a message the listener lets through, not for every notification.
     */
    fun glowOf(prefs: SharedPreferences, pkg: String, auto: Int, own: Boolean): Int {
        val app = settled(read(prefs).apps, own).firstOrNull { it.pkg == pkg }
        return app?.glow ?: colorOf(prefs, pkg)?.takeIf { own } ?: auto
    }

    /**
     * A message from [pkg] at [at]: lists it the first time ([label] is only read then), and keeps
     * when it was last heard and its icon's colour up to date. Writes nothing when nothing changed.
     */
    fun noticed(prefs: SharedPreferences, pkg: String, at: Long, autoColor: Int, label: () -> String) {
        val known = prefs.contains(SEEN + pkg)
        val seen = prefs.getLong(SEEN + pkg, 0L)
        val stamp = !known || at - seen >= SEEN_STEP_MS
        val recolor = !known || prefs.getInt(AUTO + pkg, 0) != autoColor
        if (!stamp && !recolor) return
        prefs.edit {
            if (!known) {
                putLong(FIRST + pkg, at)
                putString(LABEL + pkg, label())
            }
            if (stamp) putLong(SEEN + pkg, maxOf(seen, at))
            if (recolor) putInt(AUTO + pkg, autoColor)
        }
    }

    /** The apps heard from and every choice, in one pass over the file (it copies the whole map). */
    fun read(prefs: SharedPreferences): AppsSnapshot {
        val all = prefs.all
        val apps = ArrayList<GlowApp>()
        val muted = HashSet<String>()
        val colors = HashMap<String, Int>()
        for ((key, value) in all) {
            when {
                key.startsWith(SEEN) -> {
                    val pkg = key.removePrefix(SEEN)
                    val seen = value as? Long ?: 0L
                    apps += GlowApp(
                        pkg = pkg,
                        label = all[LABEL + pkg] as? String ?: pkg,
                        firstSeen = all[FIRST + pkg] as? Long ?: seen,
                        lastSeen = seen,
                        autoColor = all[AUTO + pkg] as? Int ?: DEFAULT_GLOW_COLOR,
                        color = all[COLOR + pkg] as? Int,
                        muted = all[MUTED + pkg] as? Boolean ?: false,
                    )
                }
                key.startsWith(MUTED) -> muted += key.removePrefix(MUTED)
                key.startsWith(COLOR) && value is Int -> colors[key.removePrefix(COLOR)] = value
            }
        }
        return AppsSnapshot(apps, AppChoices(muted, colors))
    }

    fun setMuted(prefs: SharedPreferences, pkg: String, muted: Boolean) = prefs.edit {
        if (muted) putBoolean(MUTED + pkg, true) else remove(MUTED + pkg)
    }

    /** [color] null goes back to the icon's colour. */
    fun setColor(prefs: SharedPreferences, pkg: String, color: Int?) = prefs.edit {
        if (color == null) remove(COLOR + pkg) else putInt(COLOR + pkg, color)
    }

    fun viewedAt(prefs: SharedPreferences): Long = prefs.getLong(KEY_VIEWED, 0L)

    fun markViewed(prefs: SharedPreferences, at: Long) = prefs.edit { putLong(KEY_VIEWED, at) }
}

/**
 * Glows closer than this (OKLab distance) are too alike to tell apart in a small dot at a glance:
 * WhatsApp and Spotify's greens (0.074), Signal and Messenger's blues (0.041). Every colour a user
 * can pick ([APP_COLORS]) is further than this from every other, so picking one always resolves it.
 */
internal const val LOOK_ALIKE_DISTANCE = 0.1f

/** A colour an app can be given instead of its icon's. */
@Immutable
internal data class AppColor(@param:StringRes val name: Int, val color: Int)

/**
 * The colours an app can be given, in hue order: each bright on black, and no two of them
 * look-alikes. White last, for an app that should simply stand apart.
 */
internal val APP_COLORS: List<AppColor> = listOf(
    AppColor(R.string.sample_red, 0xFFFF4B33.toInt()),
    AppColor(R.string.sample_orange, 0xFFFF8A1F.toInt()),
    AppColor(R.string.sample_yellow, 0xFFFFD60A.toInt()),
    AppColor(R.string.sample_green, 0xFF25D366.toInt()),
    AppColor(R.string.sample_cyan, DEFAULT_GLOW_COLOR),
    AppColor(R.string.sample_blue, 0xFF1E88E5.toInt()),
    AppColor(R.string.sample_violet, 0xFF8B5CF6.toInt()),
    AppColor(R.string.sample_pink, 0xFFFF2E93.toInt()),
    AppColor(R.string.sample_white, 0xFFF2F5FA.toInt()),
)

/**
 * Lit apps whose glow can't be told from another lit app's: each mapped to the closest such app.
 * On the LED they would also share one blink, since it blinks once per distinct colour. Each
 * colour is taken into OKLab once; the pairs then only measure.
 */
internal fun lookAlikes(apps: List<GlowApp>): Map<String, GlowApp> {
    val lit = apps.filterNot { it.muted }
    val labs = lit.map { toOklab(Color(it.glow)) }
    val result = HashMap<String, GlowApp>()
    for (i in lit.indices) {
        var closest = -1
        var nearest = LOOK_ALIKE_DISTANCE
        for (j in lit.indices) {
            if (i == j) continue
            val distance = oklabDistance(labs[i], labs[j])
            if (distance < nearest) {
                nearest = distance
                closest = j
            }
        }
        if (closest >= 0) result[lit[i].pkg] = lit[closest]
    }
    return result
}

/**
 * [apps] as their messages glow. The user's own colours count only with premium ([own]); without
 * it each app has its icon's. An app whose icon colour can't be told from that of a lit app
 * heard from before it ([LOOK_ALIKE_DISTANCE]) glows [Apart], in the nearest of [APP_COLORS] that
 * can be: the swatch one would pick by hand, so the LED tells every app apart, premium or not.
 * Earlier apps keep their colours, so a new app never changes how a known one glows. The user's
 * colours are never moved: a clash they made is theirs to see ([lookAlikes]) and fix. Every
 * colour is taken into OKLab once.
 */
internal fun settled(apps: List<GlowApp>, own: Boolean): List<GlowApp> {
    val plain = if (own) apps else apps.map { if (it.color == null) it else it.copy(color = null) }
    val lit = plain.filterNot { it.muted }.sortedWith(compareBy<GlowApp> { it.firstSeen }.thenBy { it.pkg })
    val swatches = APP_COLORS.map { it.color to toOklab(Color(it.color)) }
    val taken = ArrayList<Pair<GlowApp, FloatArray>>(lit.size)
    val moved = HashMap<String, Apart>()
    fun clash(lab: FloatArray) = taken.firstOrNull { oklabDistance(it.second, lab) < LOOK_ALIKE_DISTANCE }?.first
    for (app in lit) {
        val lab = toOklab(Color(app.glow))
        val other = if (app.color == null) clash(lab) else null
        if (other == null) {
            taken += app to lab
            continue
        }
        val free = swatches.sortedBy { oklabDistance(it.second, lab) }.firstOrNull { clash(it.second) == null }
        if (free == null) {
            taken += app to lab // nothing left to move it to: flagged as a look-alike instead
            continue
        }
        moved[app.pkg] = Apart(free.first, other.label)
        taken += app to free.second
    }
    if (moved.isEmpty()) return plain
    return plain.map { app -> moved[app.pkg]?.let { app.copy(apart = it) } ?: app }
}

/**
 * Look-alikes the Apps screen warns about: among the [heard] apps, and the [others] the user gave
 * a colour on purpose. The others' icon colours are left out: they would flag dozens of apps that
 * may never send a thing.
 */
internal fun lookAlikesAmong(heard: List<GlowApp>, others: List<LauncherApp>, choices: AppChoices): Map<String, GlowApp> {
    val chosen = others.mapNotNull { other -> choices.colors[other.pkg]?.let { other.toGlowApp(autoColor = it, choices) } }
    return lookAlikes(heard + chosen)
}

/** Lit apps first, then muted; each most recently heard from first. */
internal fun appOrder(apps: List<GlowApp>): List<String> =
    apps.sortedWith(compareBy<GlowApp> { it.muted }.thenByDescending { it.lastSeen }).map { it.pkg }

/**
 * [apps] in the [order] they were put in when the list was opened, so a row doesn't jump away
 * from under a finger as it is muted. Apps that arrived since go on top, most recent first.
 */
internal fun inOrder(apps: List<GlowApp>, order: List<String>): List<GlowApp> {
    val rank = order.withIndex().associate { it.value to it.index }
    val (placed, arrived) = apps.partition { it.pkg in rank }
    return arrived.sortedByDescending { it.lastSeen } + placed.sortedBy { rank.getValue(it.pkg) }
}

/** First heard from since the Apps screen was last left; nothing is new on the very first visit. */
internal fun GlowApp.isNewSince(viewedAt: Long): Boolean = viewedAt > 0L && firstSeen > viewedAt

/** An app on the phone's launcher, as the Apps screen's "Other apps" lists it. */
@Immutable
internal data class LauncherApp(val pkg: String, val label: String)

/** [this] app, not heard from yet, as a row lists it: [autoColor] its icon's, and what the user chose. */
internal fun LauncherApp.toGlowApp(autoColor: Int, choices: AppChoices) = GlowApp(
    pkg = pkg,
    label = label,
    firstSeen = 0L,
    lastSeen = 0L,
    autoColor = autoColor,
    color = choices.colors[pkg],
    muted = pkg in choices.muted,
)

/** Launcher apps that haven't sent a message ([heard] are listed above them), A to Z. */
internal fun otherApps(launcher: List<LauncherApp>, heard: Collection<String>): List<LauncherApp> {
    val listed = heard.toHashSet()
    val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    return launcher.filterNot { it.pkg in listed }.sortedWith(compareBy(collator) { it.label })
}

/** Apps whose name contains [query], ignoring case and accents ("cafe" finds "Café"). */
internal fun List<LauncherApp>.matching(query: String): List<LauncherApp> {
    val key = searchKey(query.trim())
    if (key.isEmpty()) return this
    return filter { searchKey(it.label).contains(key) }
}

private val ACCENTS = Regex("\\p{Mn}+")

private fun searchKey(text: String): String =
    ACCENTS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()
