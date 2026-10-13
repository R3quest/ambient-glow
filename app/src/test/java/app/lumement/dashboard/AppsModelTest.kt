package app.lumement.dashboard

import app.lumement.FakePrefs
import app.lumement.GlowApps
import app.lumement.GlowPending
import app.lumement.PendingGlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppsModelTest {
    private val green = 0xFF25D366.toInt()
    private val blue = 0xFF1E88E5.toInt()
    private val prefs = FakePrefs()
    private var now = 1_000L
    private val model = AppsModel(prefs) { now }

    @Before
    fun attach() {
        GlowPending.entries.clear()
        model.attach()
    }

    @After
    fun detach() {
        model.detach()
        GlowPending.entries.clear()
    }

    @Test
    fun anAppTheListenerLearnsShowsUpAtOnce() {
        assertTrue(model.current.apps.isEmpty())
        GlowApps.noticed(prefs, "chat", at = 1L, autoColor = green) { "Chat" }
        assertEquals(listOf("chat"), model.current.apps.map { it.pkg })
    }

    @Test
    fun aDetachedModelNoLongerFollowsTheFile() {
        model.current // read once, as the screen would
        model.detach()
        GlowApps.noticed(prefs, "chat", at = 1L, autoColor = green) { "Chat" }
        assertTrue(model.current.apps.isEmpty())
        model.attach()
    }

    @Test
    fun appsAreNewUntilTheScreenIsLeft() {
        model.markViewed()
        now = 2_000L
        GlowApps.noticed(prefs, "chat", at = now, autoColor = green) { "Chat" }
        assertTrue(model.hasNew)
        // Kept across a restart: the next model reads when the screen was left.
        now = 3_000L
        model.markViewed()
        assertFalse(model.hasNew)
        assertEquals(model.viewedAt, AppsModel(prefs).viewedAt)
    }

    @Test
    fun mutingTakesTheAppOffTheLedAndKeepsOthers() {
        GlowPending.put(PendingGlow(key = "1", pkg = "chat", color = green))
        GlowPending.put(PendingGlow(key = "2", pkg = "mail", color = blue))
        model.mute("chat", true, own = true)
        assertEquals(setOf("chat"), model.current.choices.muted)
        assertEquals(listOf("mail"), GlowPending.entries.map { it.pkg })

        model.mute("chat", false, own = true)
        assertTrue(model.current.choices.muted.isEmpty())
    }

    @Test
    fun allOffSwitchesOnlyTheLitAppsAndTakesEachOffTheLed() {
        GlowApps.setMuted(prefs, "mail", true)
        GlowPending.put(PendingGlow(key = "1", pkg = "chat", color = green))
        GlowPending.put(PendingGlow(key = "2", pkg = "shop", color = blue))
        GlowPending.put(PendingGlow(key = "3", pkg = "news", color = blue))

        val switched = model.switchAll(mapOf("chat" to false, "mail" to true, "shop" to false), own = true)

        assertTrue(switched.muted)
        assertEquals(listOf("chat", "shop"), switched.pkgs)
        assertEquals(setOf("chat", "mail", "shop"), model.current.choices.muted)
        // Every switched app's messages come off, not just the first's; an app not shown keeps its.
        assertEquals(listOf("news"), GlowPending.entries.map { it.pkg })
    }

    @Test
    fun undoPutsBackExactlyWhatAllOffChanged() {
        GlowApps.setMuted(prefs, "mail", true)
        val switched = model.switchAll(mapOf("chat" to false, "mail" to true), own = true)

        model.undo(switched, own = true)

        // Mail was off before All off, so it stays off.
        assertEquals(setOf("mail"), model.current.choices.muted)
    }

    @Test
    fun allOnOnceNoneLightsUp() {
        GlowApps.setMuted(prefs, "chat", true)
        GlowApps.setMuted(prefs, "mail", true)

        val switched = model.switchAll(mapOf("chat" to true, "mail" to true), own = true)

        assertFalse(switched.muted)
        assertEquals(listOf("chat", "mail"), switched.pkgs)
        assertTrue(model.current.choices.muted.isEmpty())
    }

    @Test
    fun aNewColourReachesTheWaitingMessagesAndAutoGoesBackToTheIcon() {
        GlowApps.noticed(prefs, "chat", at = 1L, autoColor = green) { "Chat" }
        GlowPending.put(PendingGlow(key = "1", pkg = "chat", color = green))
        val chat = model.current.apps.single()

        assertEquals(blue, model.recolor(chat, blue))
        assertEquals(blue, model.current.apps.single().glow)
        assertEquals(listOf(blue), GlowPending.colors())

        assertEquals(green, model.recolor(chat, null))
        assertEquals(listOf(green), GlowPending.colors())
    }
}
