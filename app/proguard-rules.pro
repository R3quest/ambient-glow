# Ambient Glow R8 rules. AndroidX and Compose ship their own consumer rules, so nothing here
# keeps library classes: everything not reachable is shrunk away.

# Aggressive code shrinking optimizations
-repackageclasses 'com.example.ambientglow.internal'
-allowaccessmodification

# Strip any stray Log.d from release builds (GlowLog is already compiled out by BuildConfig.DEBUG)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
