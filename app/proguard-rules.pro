# Ambient Glow ProGuard/R8 Optimization Rules
# Designed to minimize APK footprint and maximize startup execution performance.

# Jetpack Compose core optimizations
-keepclassmembers class * extends androidx.compose.ui.node.ModifierNodeElement {
    <init>(...);
}

# AndroidX Palette API preservation
-keep class androidx.palette.graphics.Palette { *; }
-keep class androidx.palette.graphics.Palette$Swatch { *; }

# Aggressive code shrinking optimizations
-repackageclasses 'com.example.ambientglow.internal'
-allowaccessmodification

# Eliminate logging/telemetry system overhead entirely from production release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
