package com.example.ambientglow

import android.util.Log

/**
 * Lock-flow trace for `adb logcat -s AmbientGlow`. Debug builds only: proguard-rules.pro strips
 * Log.d from release builds.
 */
internal object GlowLog {
    const val TAG = "AmbientGlow"

    fun d(message: String) {
        Log.d(TAG, message)
    }
}
