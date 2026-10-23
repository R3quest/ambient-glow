package app.lumement

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PendingStoreTest {

    private val chat = PendingGlow(key = "0|com.chat|7|null|10123", pkg = "com.chat", color = 0xFF25D366.toInt(), newestAt = 1_700_000_000_000L)
    private val mail = PendingGlow(key = "0|com.mail|1|tag, with\nodd\tchars ✉|10200", pkg = "com.mail", color = 0xFF1A73E8.toInt())

    @Before
    fun clean() {
        GlowPending.entries.clear()
    }

    @After
    fun forget() {
        GlowPending.keepIn(FakePrefs())
        GlowPending.entries.clear()
    }

    @Test
    fun entriesReadBackAsTheyWereWritten() {
        assertEquals(listOf(chat, mail), decodePending(encodePending(listOf(chat, mail))))
        assertEquals(emptyList<PendingGlow>(), decodePending(encodePending(emptyList())))
        assertEquals(emptyList<PendingGlow>(), decodePending(null))
    }

    @Test
    fun aLineThatDoesNotReadIsSkipped() {
        val text = encodePending(listOf(chat)) + "\nnot,an,entry\n" + encodePending(listOf(mail)) + "\n!!,??,x,y"
        assertEquals(listOf(chat, mail), decodePending(text))
    }

    /** A chat re-posting with nothing new (ONLY_ALERT_ONCE) doesn't take the LED's lead, or lower its newest time. */
    @Test
    fun aQuietUpdateKeepsItsPlaceAndItsNewestTime() {
        GlowPending.put(chat)
        GlowPending.put(mail)
        GlowPending.put(chat.copy(newestAt = chat.newestAt - 60_000L), quiet = true)

        assertEquals(listOf(mail, chat), GlowPending.entries.toList())
        assertEquals(mail.color, GlowPending.newestColor)
    }

    @Test
    fun everyChangeIsKept() {
        val prefs = FakePrefs()
        GlowPending.keepIn(prefs)
        GlowPending.put(mail)
        GlowPending.put(chat)
        assertEquals(listOf(chat, mail), decodePending(prefs.getString("pending", null)))

        GlowPending.recolor("com.chat", 0xFFFF0000.toInt())
        assertEquals(0xFFFF0000.toInt(), decodePending(prefs.getString("pending", null)).first().color)

        GlowPending.remove(chat.key)
        GlowPending.removeApp("com.mail")
        assertNull("nothing waiting: nothing kept", prefs.getString("pending", null))
    }

    @Test
    fun anUpdateToTheNewestMessageWritesNothing() {
        val prefs = FakePrefs()
        GlowPending.keepIn(prefs)
        GlowPending.put(mail)
        GlowPending.put(chat)
        val written = prefs.getString("pending", null)

        GlowPending.put(chat.copy(newestAt = chat.newestAt + 5_000L))
        assertEquals("same message on top, same colour", written, prefs.getString("pending", null))

        GlowPending.put(mail.copy(newestAt = 42L))
        assertEquals("the other app's message comes on top", listOf(mail.key, chat.key), decodePending(prefs.getString("pending", null)).map { it.key })
    }

    @Test
    fun aNewProcessGetsBackWhatIsStillUnread() {
        val prefs = FakePrefs(mapOf("pending" to encodePending(listOf(chat, mail))))
        GlowPending.keepIn(prefs)
        var asked: List<String>? = null

        val restored = GlowPending.restore { keys ->
            asked = keys
            setOf(mail.key) // the chat was read while the process was gone
        }

        assertTrue(restored)
        assertEquals(listOf(chat.key, mail.key), asked)
        assertEquals(listOf(mail), GlowPending.entries.toList())
        assertEquals("the read one is gone from the file too", listOf(mail), decodePending(prefs.getString("pending", null)))
    }

    @Test
    fun nothingComesBackWhenTheShadeCannotBeRead() {
        val kept = encodePending(listOf(chat))
        val prefs = FakePrefs(mapOf("pending" to kept))
        GlowPending.keepIn(prefs)

        assertFalse(GlowPending.restore { null })
        assertTrue(GlowPending.isEmpty)
        assertEquals("left for the next try", kept, prefs.getString("pending", null))
    }

    @Test
    fun allReadMeanwhileBringsNothingBack() {
        val prefs = FakePrefs(mapOf("pending" to encodePending(listOf(chat, mail))))
        GlowPending.keepIn(prefs)

        assertFalse(GlowPending.restore { emptySet() })
        assertTrue(GlowPending.isEmpty)
        assertNull(prefs.getString("pending", null))
    }

    @Test
    fun aListAlreadyHereIsNewerThanTheFile() {
        val prefs = FakePrefs(mapOf("pending" to encodePending(listOf(chat))))
        GlowPending.keepIn(prefs)
        GlowPending.entries.add(mail) // a reconnect within the same process

        assertFalse(GlowPending.restore { error("not asked: nothing to bring back") })
        assertEquals(listOf(mail), GlowPending.entries.toList())
        assertEquals(listOf(mail), decodePending(prefs.getString("pending", null)))
    }

    /** The phone's own posts were kept as messages before (a keyboard's notice kept the LED lit): an update lets them go. */
    @Test
    fun aNewProcessLetsTheSystemsOwnPostsGo() {
        val keyboard = PendingGlow(key = "-1|android|19|null|1000", pkg = "android", color = 0xFF888888.toInt())
        GlowPending.keepIn(FakePrefs(mapOf("pending" to encodePending(listOf(keyboard, chat)))))

        assertTrue(GlowPending.restore { keys -> keys.toSet() })

        assertEquals(listOf(chat), GlowPending.entries.toList())
    }

    @Test
    fun missedRemovalsAreDroppedAndForgotten() {
        val prefs = FakePrefs()
        GlowPending.keepIn(prefs)
        GlowPending.put(mail)
        GlowPending.put(chat)

        assertTrue("one went", GlowPending.keepOnly(setOf(chat.key)))
        assertFalse("none left to go", GlowPending.keepOnly(setOf(chat.key)))

        assertEquals(listOf(chat), GlowPending.entries.toList())
        assertEquals(listOf(chat), decodePending(prefs.getString("pending", null)))
    }

    @Test
    fun allReadEndsTheStreak() {
        // However the last message went (read, dismissed, pruned, its app muted), the next one starts a new streak.
        GlowSession.bright = BrightStreak().onMessage(now = 0L, brightForMs = BrightFor.TEN.ms)
        GlowSession.allRead()
        assertEquals(BrightStreak(), GlowSession.bright)
    }
}
