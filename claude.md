# Claude Code Engineering Guidelines: Ambient Glow

This file serves as the permanent system configuration and architectural authority for the "Ambient Glow" codebase. Every file creation, modification, and optimization step must strictly adhere to these parameters.

## 🔋 1. Core Philosophy: Zero-Idle Battery Profile
- **Headless & Reactive:** Ambient Glow must consume exactly 0% CPU cycles when the device is idle.
- **No Background Loops:** Absolutely no long-running coroutines, infinite `while` loops, or persistent background threads.
- **Event-Driven Only:** The app must only wake up when the Android Operating System fires the `onNotificationPosted` callback.
- **Hardware Release:** Once the notification light effect has run for its specified duration, the app must aggressively release all locks, finish its activities, and allow the CPU to fall back into a low-power deep sleep state immediately.

## 🛡️ 2. Privacy & Permission Boundary
- **Minimal Surface Area:** Request the absolute fewest permissions required by the OS.
- **Forbidden Permissions:** Do NOT introduce `SYSTEM_ALERT_WINDOW` (Draw over other apps). This avoids heavy window manager overhead and prevents the app from being killed by aggressive OEM battery managers (like Samsung's App Power Management).
- **Approved Permissions:**
  - `android.permission.WAKE_LOCK` (To cleanly illuminate the physical panel).
  - `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` (System-enforced access boundary).
  - `android.permission.BIND_ACCESSIBILITY_SERVICE` — optional, user-enabled `GlowShield` only: `TYPE_ACCESSIBILITY_OVERLAY` windows for two short moments — the see-through arrival effect over the lit lock screen (no app window can draw above a visible keyguard), and a black cover for the ~0.5 s LED wake hand-over, because One UI draws its status/navigation bars above every app window during that moment. No event types, no window content.

## 🚀 3. High-Performance Execution & Memory Rules
- **Avoid GC Trashing:** During `onNotificationPosted`, converting an app icon to a bitmap can trigger aggressive Garbage Collection (GC) pauses if done inefficiently. Reuse configurations and ensure width/height are constrained.
- **Hardware-Accelerated Windows:** To light up the screen, use a transparent `ComponentActivity` configured with `setShowWhenLocked(true)` and `setTurnScreenOn(true)`. This instructs the system's low-level Window Manager to turn on the screen natively without drawing resource-heavy overlays over other apps.
- **No Complex Canvas Work:** Keep layouts to flat Jetpack Compose modifiers (`Box`, `border`). Do not allocate new styling objects inside the draw phase.

## 📁 4. Architecture Requirements
- **Build System:** Android 14+ target SDK (API 34 or higher) using Kotlin DSL (`.gradle.kts`).
- **UI Framework:** Jetpack Compose using the modern Bill of Materials (BOM) management.
- **Color Extraction:** `androidx.palette:palette-ktx` for ultra-fast, high-precision extraction.
- **R8 Optimization:** ProGuard rules must enforce aggressive shrinking and obfuscation to keep the runtime DEX footprint as tiny as possible.
