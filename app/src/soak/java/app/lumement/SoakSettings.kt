package app.lumement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The soak build only (make soak): sets what a battery run needs from adb, as the dashboard would,
 * so a run needs no hand on the phone. The soak build isn't debuggable, so its settings file can't
 * be written over adb. Guarded by DUMP, which adb's shell holds and apps can't.
 *
 *   adb shell am broadcast -n app.lumement/.SoakSettings --es bright_for TEN --es after_bright AOD
 */
class SoakSettings : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        var settings = GlowPrefs.load(context)
        intent.getStringExtra(BRIGHT_FOR)?.let { name -> settings = settings.copy(brightFor = BrightFor.valueOf(name)) }
        intent.getStringExtra(AFTER_BRIGHT)?.let { name -> settings = settings.copy(afterBright = AfterBright.valueOf(name)) }
        GlowPrefs.save(context, settings)
        Log.i(TAG, "brightFor=${settings.brightFor} afterBright=${settings.afterBright}")
        resultData = "brightFor=${settings.brightFor} afterBright=${settings.afterBright}"
    }

    private companion object {
        const val TAG = "LumementSoak"
        const val BRIGHT_FOR = "bright_for"
        const val AFTER_BRIGHT = "after_bright"
    }
}
