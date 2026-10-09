package app.lumement

import android.util.Log

/**
 * Lock-flow trace for `adb logcat -s Lumement`. Debug builds only: the message is a lambda
 * behind a compile-time flag, so release builds never build it, nor make the binder calls
 * (isInteractive, isKeyguardLocked) many messages read.
 */
internal object GlowLog {
    const val TAG = "Lumement"

    inline fun d(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }
}
