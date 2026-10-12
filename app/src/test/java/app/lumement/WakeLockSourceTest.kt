package app.lumement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The battery promise, checked in the source: every wake lock is acquired with a timeout, except
 * the two the lit LED holds while it is in front, which have their own release path. A new wake
 * lock, or one acquired for good, fails here until it is looked at and listed.
 */
class WakeLockSourceTest {

    private val sources: Map<String, String> =
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.associate { it.name to it.readText() }

    /** Each wake lock by tag, and why it may be held: a timeout, or what lets go of it. */
    private val known = mapOf(
        "Lumement:wake" to "listener, until the glow screen is up (timeout)",
        "Lumement:dark" to "DarkHold, across a short dark gap (timeout)",
        "Lumement:panel" to "PanelWaker, lights the panel (timeout)",
        "Lumement:timeout" to "PanelWaker.restartTimeout, acquired and released at once",
        "Lumement:aod" to "the always-on display's draw lock, per breath (timeout)",
        "Lumement:led" to "the lit LED: released when it leaves the front (releaseKeepOn)",
        "Lumement:pocket" to "the lit LED's proximity panel-off: released with it (releaseKeepOn)",
    )

    /** Held with no timeout, while the LED is in front. */
    private val heldForGood = setOf("keepPanelOn", "pocketDark")

    @Test
    fun theSourcesAreFound() {
        assertTrue("run from the app module", sources.size > 20)
    }

    @Test
    fun everyWakeLockIsKnown() {
        // The tag is the call's second argument: a string, or a constant naming one.
        val tags = sources.values.flatMap { source ->
            Regex("newWakeLock\\(\\s*[^,]+,\\s*(\"[^\"]+\"|[A-Z_]+)").findAll(source).map { match ->
                val tag = match.groupValues[1]
                if (tag.startsWith("\"")) tag.trim('"') else Regex("const val $tag = \"([^\"]+)\"").find(source)?.groupValues?.get(1) ?: tag
            }.toList()
        }.toSet()
        assertEquals("a new wake lock: add it to `known` with why it is let go of", known.keys, tags)
    }

    @Test
    fun onlyTheLitLedHoldsALockWithoutATimeout() {
        val bare = sources.flatMap { (file, source) ->
            source.lines().withIndex()
                .filter { (_, line) -> Regex("\\.acquire\\(\\)").containsMatchIn(line) }
                .filter { (_, line) -> heldForGood.none { it in line } }
                .map { (i, line) -> "$file:${i + 1}: ${line.trim()}" }
        }
        assertTrue("acquired with no timeout:\n${bare.joinToString("\n")}", bare.isEmpty())
    }

    @Test
    fun whatIsHeldForGoodIsLetGoOf() {
        val activity = sources.getValue("WakeScreenActivity.kt")
        heldForGood.forEach { lock ->
            assertTrue("$lock is never released", Regex("$lock\\b[^\\n]*\\.release\\(|\\b$lock\\?\\.let \\{ if \\(it\\.isHeld\\) it\\.release\\(").containsMatchIn(activity))
        }
    }
}
