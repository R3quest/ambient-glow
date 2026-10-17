package app.lumement

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Debug builds only: a diary of what the LED does and when the user looks, kept in a local file
 * ([FILE] in the app's files), to set the defaults from (`make diary`, scripts/diary.py). One line
 * per event: wall-clock ms, elapsed ms, the event, its detail. Times and events only, no app names
 * or message content, and nothing leaves the phone. Release builds compile it away, as [GlowLog].
 */
internal object LedDiary {
    const val FILE = "led-diary.txt"

    fun note(context: Context, event: String, detail: String = "") {
        if (!BuildConfig.DEBUG) return
        runCatching {
            File(context.applicationContext.filesDir, FILE)
                .appendText("${System.currentTimeMillis()} ${SystemClock.elapsedRealtime()} $event $detail".trimEnd() + "\n")
        }
    }
}
