# Claude Code Engineering Guidelines: Ambient Glow

This file serves as the permanent system configuration and architectural authority for the "Ambient Glow" codebase. Every file creation, modification, and optimization step must strictly adhere to these parameters.

## 🔋 1. Core Philosophy: Zero-Idle Battery Profile
- **Headless & Reactive:** Ambient Glow must consume exactly 0% CPU cycles when the device is idle.
- **No Background Loops:** No long-running coroutines, infinite `while` loops, or persistent background threads while the panel is dark. The only loops are frame-driven animations (the LED breath, the arrival effect) that run while the glow screen is lit, and they end with it.
- **Event-Driven Only:** The app only wakes up when the system calls it: `onNotificationPosted`/`onNotificationRemoved`, screen on/off, unlock, display changes. Timers are short, bounded one-shots on a `Handler`.
- **Hardware Release:** Every wake lock is acquired with a timeout (or released when the LED leaves the front). Once all waiting messages are read, the glow screen finishes and the CPU falls back to deep sleep.

## 🛡️ 2. Privacy & Permission Boundary
- **Minimal Surface Area:** Request the absolute fewest permissions required by the OS. No internet, no telemetry.
- **Forbidden Permissions:** Do NOT introduce `SYSTEM_ALERT_WINDOW` (Draw over other apps). This avoids heavy window manager overhead and prevents the app from being killed by aggressive OEM battery managers (like Samsung's App Power Management). No `QUERY_ALL_PACKAGES` either: the manifest's `<queries>` covers launcher apps (app icons, and the Apps screen's list of other apps).
- **Approved Permissions:**
  - `android.permission.WAKE_LOCK` (To cleanly illuminate the physical panel).
  - `android.permission.POST_NOTIFICATIONS` (The bridge notification that carries the full-screen intent; Android 13+ runtime grant).
  - `android.permission.USE_FULL_SCREEN_INTENT` (The only background-launch path Android allows without `SYSTEM_ALERT_WINDOW`).
  - `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` (System-enforced access boundary).
  - `android.permission.BIND_ACCESSIBILITY_SERVICE` — optional, user-enabled `GlowShield` only: `TYPE_ACCESSIBILITY_OVERLAY` windows for two short moments — the see-through arrival effect over the lit lock screen (no app window can draw above a visible keyguard), and a black cover for the ~0.5 s LED wake hand-over, because One UI draws its status/navigation bars above every app window during that moment. No event types, no window content.

## 🚀 3. High-Performance Execution & Memory Rules
- **Avoid GC Trashing:** During `onNotificationPosted`, converting an app icon to a bitmap can trigger aggressive Garbage Collection (GC) pauses if done inefficiently. `BrandColors` reuses one 96 px bitmap per listener connection; keep it that way.
- **Lock-Screen Windows:** The glow screen is a `ComponentActivity` that switches `setShowWhenLocked` at runtime to cover or uncover the lock screen in place. It never arms `setTurnScreenOn` except as the full-screen-intent fallback; `PanelWaker` (a short screen wake lock) does every wake.
- **Draw-Phase Discipline:** Build brushes, shaders, strokes and paths once in `drawWithCache`; per frame only read the clock and move what was built (shader uniforms, local matrices, alpha). Never allocate in `onDraw*`. Animated values are read in the draw or layout phase, so a frame never recomposes. Runtime shaders (AGSL) are Android 13+; every one has a gradient fallback below that.
- **Timing Is a Signal:** The live effect and the LED keep real time (`RealTimeMotion`) whatever the animator duration scale. Dashboard chrome follows the system scale (`GlowMotion`).

## 📁 4. Architecture Requirements
- **Build System:** compileSdk 37, targetSdk 35, minSdk 26, Kotlin DSL (`.gradle.kts`) with a version catalog.
- **UI Framework:** Jetpack Compose using the modern Bill of Materials (BOM) management.
- **Color Extraction:** `androidx.palette:palette-ktx` for ultra-fast, high-precision extraction.
- **R8 Optimization:** ProGuard rules must enforce aggressive shrinking and obfuscation to keep the runtime DEX footprint as tiny as possible. Log through `GlowLog.d { "..." }`: the lambda sits behind `BuildConfig.DEBUG`, so release builds never build the message or run the reads (binder calls) inside it.

## 🗺️ 5. Code Map
- **Settings:** `GlowSettings.kt` (every option, as `Labeled` enums, and the `GlowSettings` defaults), `GlowPrefs.kt` (storage).
- **Runtime state:** `GlowSession.kt` — `GlowPending` (unread messages), `GlowSession` (listener ↔ glow screen link), `WakeMode`.
- **Apps:** `GlowApps.kt` (its own prefs file, which the listener learns apps into: muted/colour choices, look-alikes, launcher-list search). Kept apart from `GlowPrefs`, because the dashboard saves settings whole.
- **Wake path:** `NotificationWakerService` (filters messages with `MessageFilter.kt`, skips muted apps, `GlowApps` colour or `BrandColors`) → `GlowLauncher` (bridge notification + full-screen intent) → `WakeScreenActivity` (the lock flow state machine) drawing `GlowScreen.kt` (the LED round, the arrival on black). `Power.kt`: `PanelWaker`, `DarkHold`, `inCall`.
- **Glow screen helpers:** `Led.kt` (breath curve, `LedDot`), `LedWindow.kt` (bars, display modes), `RelightLimiter.kt`.
- **Effect:** `ArrivalEffect.kt` (timeline + drawing), `CrestShader.kt`, `FrostShader.kt` (Water), `FireWave.kt` + `FireShader.kt` (Fire: timeline, palette, shader), `AirWave.kt` + `AirShader.kt` (Air: timeline, whirl, palette, shader), `EarthWave.kt` + `EarthShader.kt` (Earth: timeline, impact, shake, palette, shader), `EdgeLight.kt` + `ElementFrame.kt` + `HandOff.kt` (Edge Frame: comet heads, the frame made of the element, its last light flying to the LED), `GlassHaze.kt`, with `ShaderBrushes.kt`, `Oklab.kt`, `GlowMath.kt`, `GlowRenderer.kt` (geometry, `GlowGraphic`).
- **Overlay:** `GlowShield` (accessibility overlay: effect over the lock screen, wake cover, blur).
- **Dashboard:** `MainActivity` → `dashboard/` (`Dashboard`, `AppsModel` + `AppsScreen` + `AppRow` (the Apps screen from the header), `Access`, `EffectCard`, `ElementCard` + `WaterOptions`, `FireOptions`, `AirOptions`, `EarthOptions` (built from `GlyphTiles`), `LedCard`, `LedShowcase`, `PhoneMock`), built from `ui/components/` (`Controls`, `Chips`) and `ui/theme/`.

## 🧩 6. Conventions
- **Adding a setting:** a field with its default in `GlowSettings`, and one line each in `GlowPrefs.load` and `GlowPrefs.save`. Options are enums implementing `Labeled`, so `ChipRow` lists them as they are. If the arrival effect reads it, check `forPreview()`.
- **Pure logic goes where it can be tested:** curves, geometry, rate limits and colour maths are plain functions or small classes with unit tests in `app/src/test`. Run `make test`; `make check` runs lint, tests and both builds.
- **Shared visuals live once:** the LED's look (`LedDot`, `ledBreathAt`), the camera position (`ScreenGeometry.lens`), motion specs (`GlowMotion`) and dashboard controls (`ui/components`) are reused by the glow screen, the overlay and the dashboard previews alike.
