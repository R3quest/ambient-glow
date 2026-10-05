package com.example.ambientglow

import android.util.Log

/**
 * Lock-flow trace for `adb logcat -s AmbientGlow`. Debug builds only: the message is a lambda
 * behind a compile-time flag, so release builds never build it, nor make the binder calls
 * (isInteractive, isKeyguardLocked) many messages read.
 */
internal object GlowLog {
    const val TAG = "AmbientGlow"

    inline fun d(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }
}
