# Lumement R8 rules. AndroidX and Compose ship their own consumer rules, so nothing here
# keeps library classes: everything not reachable is shrunk away.

# Aggressive code shrinking optimizations
-repackageclasses 'app.lumement.internal'
-allowaccessmodification

# Strip any stray Log.d from release builds (GlowLog is already compiled out by BuildConfig.DEBUG)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# Play Billing's telemetry is excluded from the build (see app/build.gradle.kts); billing catches
# its absence and skips logging.
-dontwarn com.google.android.datatransport.**
