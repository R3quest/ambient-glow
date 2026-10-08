# Ambient Glow

Ambient Glow is an ultra-lightweight, high-performance, and privacy-first Android notification utility specifically engineered to complement modern AMOLED displays (such as Samsung devices using "Detailed" notification popup styles).

## 🌟 Key Architectural Achievements
- **Zero Background Idle Drain:** Operates entirely headlessly. It uses zero background CPU cycles while waiting for messages.
- **No Intrusive Overlays:** Does not require the heavy `SYSTEM_ALERT_WINDOW` ("Draw over other apps") permission. A full-screen-intent notification puts the glow screen on top of the lock screen, which then switches `setShowWhenLocked` in place and lights the panel with a short, timed wake lock. An optional accessibility service (`GlowShield`) draws the effect over the lit lock screen; it reads no events and no window content.
- **Per-App Control:** Every app that notifies you is listed automatically: switch off the ones you don't care about, or give any app its own color (look-alike colors are flagged, since the LED would blink once for both). Any other app on the phone can be set up before it ever sends a message.
- **Smart Brand Color Matching:** Extracts the dominant brand color from the sending app's icon via the **AndroidX Palette API**, plays the chosen new-message effect (LED Beacon or Edge Frame, riding a Water, Fire, Air or Earth wave) in it, then leaves a breathing notification LED that blinks once per waiting app until every message is read.
- **Completely Offline:** Zero tracking, telemetry, internet permissions, or external data dependencies.

## 🏗️ Technical Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 36 (Android 16)
- **Key Libraries:** `androidx.palette:palette-ktx`; AGSL runtime shaders on Android 13+, with gradient fallbacks below

## 🛠️ Installation & Setup for Developers
1. Clone the repository.
2. Open the project inside Android Studio.
3. Build and deploy to your device.
4. Open the app and follow setup: notifications (the wake bridge), notification access, full-screen wake (Android 14+), and optionally the lock-screen effect (accessibility).

`make help` lists the build shortcuts; `make check` runs lint, unit tests and both builds. See `claude.md` for the architecture and conventions.
