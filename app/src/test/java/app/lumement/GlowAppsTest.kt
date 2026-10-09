package app.lumement

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlowAppsTest {
    private val hour = 60 * 60 * 1000L
    private val whatsApp = 0xFF25D366.toInt()
    private val spotify = 0xFF1DB954.toInt()
    private val telegram = 0xFF2AABEE.toInt()

    private fun app(pkg: String, glow: Int, lastSeen: Long = 0L, muted: Boolean = false, firstSeen: Long = lastSeen) =
        GlowApp(pkg = pkg, label = pkg, firstSeen = firstSeen, lastSeen = lastSeen, autoColor = glow, muted = muted)

    @Test
    fun aFirstMessageListsTheAppWithItsNameAndColour() {
        val prefs = FakePrefs()
        GlowApps.noticed(prefs, "chat", at = 1_000L, autoColor = whatsApp) { "Chat" }
        val listed = GlowApps.read(prefs).apps.single()
        assertEquals("chat", listed.pkg)
        assertEquals("Chat", listed.label)
        assertEquals(1_000L, listed.firstSeen)
        assertEquals(1_000L, listed.lastSeen)
        assertEquals(whatsApp, listed.glow)
        assertFalse(listed.muted)
    }

    @Test
    fun aBusyChatWritesAtMostOnceAnHour() {
        val prefs = FakePrefs()
        GlowApps.noticed(prefs, "chat", at = 0L, autoColor = whatsApp) { "Chat" }
        val before = prefs.values.toMap()
        GlowApps.noticed(prefs, "chat", at = hour - 1, autoColor = whatsApp) { error("label read again") }
        assertEquals(before, prefs.values)
        GlowApps.noticed(prefs, "chat", at = hour, autoColor = whatsApp) { error("label read again") }
        assertEquals(hour, GlowApps.read(prefs).apps.single().lastSeen)
    }

    @Test
    fun aNewIconColourIsPickedUpAtOnce() {
        val prefs = FakePrefs()
        GlowApps.noticed(prefs, "chat", at = 0L, autoColor = whatsApp) { "Chat" }
        GlowApps.noticed(prefs, "chat", at = 1L, autoColor = telegram) { "Chat" }
        assertEquals(telegram, GlowApps.read(prefs).apps.single().autoColor)
    }

    @Test
    fun anOlderMessageDoesNotMoveLastSeenBack() {
        val prefs = FakePrefs()
        GlowApps.noticed(prefs, "chat", at = 5 * hour, autoColor = whatsApp) { "Chat" }
        GlowApps.noticed(prefs, "chat", at = 0L, autoColor = telegram) { "Chat" }
        assertEquals(5 * hour, GlowApps.read(prefs).apps.single().lastSeen)
    }

    @Test
    fun muteAndColourRoundTripAndClear() {
        val prefs = FakePrefs()
        GlowApps.noticed(prefs, "chat", at = 0L, autoColor = whatsApp) { "Chat" }
        GlowApps.setMuted(prefs, "chat", true)
        GlowApps.setColor(prefs, "chat", telegram)
        assertTrue(GlowApps.isMuted(prefs, "chat"))
        assertEquals(telegram, GlowApps.colorOf(prefs, "chat"))
        GlowApps.read(prefs).apps.single().let {
            assertTrue(it.muted)
            assertEquals(telegram, it.glow)
        }

        GlowApps.setMuted(prefs, "chat", false)
        GlowApps.setColor(prefs, "chat", null)
        assertFalse(GlowApps.isMuted(prefs, "chat"))
        assertNull(GlowApps.colorOf(prefs, "chat"))
        assertEquals(whatsApp, GlowApps.read(prefs).apps.single().glow)
    }

    @Test
    fun anAppNeverHeardFromIsNotMutedAndHasNoColour() {
        val prefs = FakePrefs()
        assertFalse(GlowApps.isMuted(prefs, "unknown"))
        assertNull(GlowApps.colorOf(prefs, "unknown"))
        assertTrue(GlowApps.read(prefs).apps.isEmpty())
    }

    @Test
    fun brandLookAlikesAreFoundAndNamedBothWays() {
        val alike = lookAlikes(listOf(app("whatsapp", whatsApp), app("spotify", spotify), app("telegram", telegram)))
        assertEquals("spotify", alike["whatsapp"]?.pkg)
        assertEquals("whatsapp", alike["spotify"]?.pkg)
        assertNull(alike["telegram"])
    }

    @Test
    fun aMutedAppIsNoLookAlike() {
        val alike = lookAlikes(listOf(app("whatsapp", whatsApp), app("spotify", spotify, muted = true)))
        assertTrue(alike.isEmpty())
    }

    @Test
    fun noTwoPickableColoursAreLookAlikes() {
        for (a in APP_COLORS) {
            for (b in APP_COLORS) {
                if (a === b) continue
                val distance = oklabDistance(Color(a.color), Color(b.color))
                assertTrue("${a.color.toUInt().toString(16)} vs ${b.color.toUInt().toString(16)}: $distance", distance >= LOOK_ALIKE_DISTANCE)
            }
        }
    }

    @Test
    fun litAppsComeFirstThenTheMostRecent() {
        val order = appOrder(
            listOf(
                app("old", whatsApp, lastSeen = 1L),
                app("muted", whatsApp, lastSeen = 9L, muted = true),
                app("recent", whatsApp, lastSeen = 5L),
            ),
        )
        assertEquals(listOf("recent", "old", "muted"), order)
    }

    @Test
    fun theOrderHoldsWhileOpenAndArrivalsGoOnTop() {
        // "a" was muted while the list was open: it stays where it was.
        val apps = listOf(
            app("a", whatsApp, lastSeen = 3L, muted = true),
            app("b", whatsApp, lastSeen = 2L),
            app("new", whatsApp, lastSeen = 10L),
        )
        assertEquals(listOf("new", "a", "b"), inOrder(apps, listOf("a", "b")).map { it.pkg })
    }

    @Test
    fun appsAreNewOnlySinceAnEarlierVisit() {
        val app = app("chat", whatsApp, firstSeen = 10L)
        assertFalse("first visit", app.isNewSince(0L))
        assertTrue(app.isNewSince(5L))
        assertFalse(app.isNewSince(10L))
    }

    @Test
    fun mutingAnAppTakesItsWaitingMessagesOffTheLed() {
        GlowPending.entries.clear()
        GlowPending.put(PendingGlow(key = "1", pkg = "chat", color = whatsApp))
        GlowPending.put(PendingGlow(key = "2", pkg = "mail", color = telegram))
        GlowPending.put(PendingGlow(key = "3", pkg = "chat", color = whatsApp))

        GlowPending.recolor("chat", telegram)
        assertEquals(listOf(telegram), GlowPending.colors())

        assertTrue(GlowPending.removeApp("chat"))
        assertEquals(listOf("2"), GlowPending.entries.map { it.key })
        assertFalse(GlowPending.removeApp("chat"))
        GlowPending.entries.clear()
    }

    @Test
    fun anAppCanBeChosenForBeforeItsFirstMessageAndArrivesSo() {
        val prefs = FakePrefs()
        GlowApps.setMuted(prefs, "shop", true)
        GlowApps.setColor(prefs, "chat", telegram)
        assertEquals(AppChoices(setOf("shop"), mapOf("chat" to telegram)), GlowApps.read(prefs).choices)
        assertTrue("not listed until it sends a message", GlowApps.read(prefs).apps.isEmpty())

        GlowApps.noticed(prefs, "shop", at = 1L, autoColor = whatsApp) { "Shop" }
        GlowApps.noticed(prefs, "chat", at = 1L, autoColor = whatsApp) { "Chat" }
        val listed = GlowApps.read(prefs).apps.associateBy { it.pkg }
        assertTrue(listed.getValue("shop").muted)
        assertEquals(telegram, listed.getValue("chat").glow)
    }

    @Test
    fun otherAppsLeaveOutTheHeardAndRunAToZ() {
        val launcher = listOf(
            LauncherApp("z", "zoom"),
            LauncherApp("chat", "Chat"),
            LauncherApp("e", "Éclair"),
            LauncherApp("a", "Alarm"),
        )
        assertEquals(listOf("Alarm", "Éclair", "zoom"), otherApps(launcher, heard = listOf("chat")).map { it.label })
    }

    @Test
    fun searchIgnoresCaseAccentsAndSpaces() {
        val apps = listOf(LauncherApp("c", "Café Bar"), LauncherApp("v", "Viber"))
        assertEquals(listOf("c"), apps.matching(" CAFE ").map { it.pkg })
        assertEquals(listOf("v"), apps.matching("ibe").map { it.pkg })
        assertEquals(apps, apps.matching("  "))
        assertTrue(apps.matching("telegram").isEmpty())
    }

    @Test
    fun anOtherAppIsListedWithItsIconColourAndTheUsersChoices() {
        val choices = AppChoices(muted = setOf("shop"), colors = mapOf("chat" to telegram))
        val chat = LauncherApp("chat", "Chat").toGlowApp(autoColor = whatsApp, choices)
        assertEquals(telegram, chat.glow)
        assertEquals(whatsApp, chat.autoColor)
        assertFalse(chat.muted)
        assertTrue(LauncherApp("shop", "Shop").toGlowApp(autoColor = whatsApp, choices).muted)
    }

    @Test
    fun otherAppsCountAsLookAlikesOnlyWhenGivenAColour() {
        val heard = listOf(app("whatsapp", whatsApp))
        val others = listOf(LauncherApp("spotify", "Spotify"), LauncherApp("phone", "Phone"))
        // Spotify's icon is as green as WhatsApp's, but only a colour picked on purpose counts.
        assertTrue(lookAlikesAmong(heard, others, AppChoices()).isEmpty())

        val alike = lookAlikesAmong(heard, others, AppChoices(colors = mapOf("spotify" to spotify)))
        assertEquals("spotify", alike["whatsapp"]?.pkg)
        assertEquals("whatsapp", alike["spotify"]?.pkg)
        assertNull(alike["phone"])
    }

    @Test
    fun aLookAlikeHeardLaterIsKeptApartInTheNearestFreeSwatch() {
        val apps = listOf(app("whatsapp", whatsApp, firstSeen = 1L), app("spotify", spotify, firstSeen = 2L))
        val settled = settled(apps, own = false).associateBy { it.pkg }
        assertNull(settled.getValue("whatsapp").apart)
        val apart = settled.getValue("spotify").apart!!
        assertEquals("whatsapp", apart.from)
        assertTrue(APP_COLORS.any { it.color == apart.color })
        assertTrue(lookAlikes(settled.values.toList()).isEmpty())
    }

    @Test
    fun theEarlierAppKeepsItsColourWhicheverIsListedFirst() {
        val apps = listOf(app("spotify", spotify, firstSeen = 2L), app("whatsapp", whatsApp, firstSeen = 1L))
        val settled = settled(apps, own = false).associateBy { it.pkg }
        assertEquals(whatsApp, settled.getValue("whatsapp").glow)
        assertTrue(settled.getValue("spotify").apart != null)
    }

    @Test
    fun aMutedAppTakesNoColour() {
        val apps = listOf(app("whatsapp", whatsApp, firstSeen = 1L, muted = true), app("spotify", spotify, firstSeen = 2L))
        assertTrue(settled(apps, own = false).all { it.apart == null })
    }

    @Test
    fun ownColoursCountOnlyWithPremiumAndAreNeverMoved() {
        val chosen = app("signal", telegram, firstSeen = 2L).copy(color = whatsApp)
        val apps = listOf(app("whatsapp", whatsApp, firstSeen = 1L), chosen)
        val withPremium = settled(apps, own = true).associateBy { it.pkg }
        assertEquals(whatsApp, withPremium.getValue("signal").glow)
        assertNull(withPremium.getValue("signal").apart)
        // A clash the user made is shown, not fixed for them.
        assertEquals(setOf("whatsapp", "signal"), lookAlikes(withPremium.values.toList()).keys)
        val without = settled(apps, own = false).associateBy { it.pkg }
        assertEquals(telegram, without.getValue("signal").glow)
    }

    @Test
    fun theListenerGetsTheSettledColour() {
        val prefs = FakePrefs()
        GlowApps.noticed(prefs, "whatsapp", at = 1L, autoColor = whatsApp) { "WhatsApp" }
        GlowApps.noticed(prefs, "spotify", at = 2L, autoColor = spotify) { "Spotify" }
        assertEquals(whatsApp, GlowApps.glowOf(prefs, "whatsapp", whatsApp, own = false))
        val apart = GlowApps.glowOf(prefs, "spotify", spotify, own = false)
        assertTrue(oklabDistance(Color(apart), Color(whatsApp)) >= LOOK_ALIKE_DISTANCE)
        GlowApps.setColor(prefs, "spotify", telegram)
        assertEquals(telegram, GlowApps.glowOf(prefs, "spotify", spotify, own = true))
        assertEquals(apart, GlowApps.glowOf(prefs, "spotify", spotify, own = false))
    }
}
