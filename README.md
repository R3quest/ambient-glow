# Ambient Glow

Ambient Glow is an ultra-lightweight, high-performance, and privacy-first Android notification utility specifically engineered to complement modern AMOLED displays (such as Samsung devices using "Detailed" notification popup styles).

## 🌟 Key Architectural Achievements
- **Zero Background Idle Drain:** Operates entirely headlessly. It uses zero background CPU cycles while waiting for messages.
- **No Intrusive Overlays:** Does not require the heavy `SYSTEM_ALERT_WINDOW` ("Draw over other apps") permission. Instead, it utilizes native window-manager flags (`setShowWhenLocked`, `setTurnScreenOn`) to illuminate the physical panel safely.
- **Smart Brand Color Matching:** Automatically intercepts incoming alerts, extracts the dominant brand color from the originating application's icon via the **AndroidX Palette API**, and flashes a color-matched border ring.
- **Completely Offline:** Zero tracking, telemetry, internet permissions, or external data dependencies.

## 🏗️ Technical Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 35 (Android 15)
- **Key Libraries:** `androidx.palette:palette-ktx`

## 🛠️ Installation & Setup for Developers
1. Clone the repository.
2. Open the project inside Android Studio.
3. Build and deploy to your device.
4. Open the app and tap **"Authorize Ambient Glow"** to grand Notification Listener permissions.
