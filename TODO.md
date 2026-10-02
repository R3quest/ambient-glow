# Ambient Glow — MVP TODO List

Build a simple, lightweight utility called "Ambient Glow" that wakes up the screen and shows a colorful notification ring or dot whenever a new message arrives.

## 🎛️ Step 1: Simple UI & Settings Dashboard (MainActivity.kt)
- [ ] Create a clean, modern single-screen dashboard using Jetpack Compose.
- [ ] **Permission Checker:** Add a card that shows whether the app has permission to see notifications (ACTIVE or PENDING).
- [ ] **Grant Permission Button:** Add a button that takes the user directly to Android's Notification Listener settings screen.
- [ ] **Style Selection:** Add a simple selector to choose the notification style:
    - **Edge Frame:** A colorful border around the entire screen.
    - **Camera Ring:** A neat circle around the top front-facing camera.
    - **Custom Dot:** A solid dot indicator.
- [ ] **Position Sliders:** If "Custom Dot" is chosen, show a Horizontal Slider and a Vertical Slider so the user can easily position the dot anywhere on their screen.
- [ ] **Save Settings:** Automatically save the user's chosen style and dot coordinates into standard `SharedPreferences`.
- [ ] **Test Button:** Add a "Test Preview" button that immediately pops up the notification screen so the user can check its placement.

## ⚡ Step 2: Smart Notification Reader (NotificationWakerService.kt)
- [ ] Create a background service that extends `NotificationListenerService`.
- [ ] **Filter Out Noise:** Ignore ongoing notifications (like music players or download progress bars) so the screen only wakes for real, new messages.
- [ ] **Color Extraction:** Get the app icon of the incoming notification (e.g., WhatsApp, Messages) and use the `AndroidX Palette API` to automatically find its dominant brand color.
- [ ] **Pass the Data:** Fetch the user's saved preferences (Style choice and Dot position coordinates).
- [ ] Launch the visual wake screen, passing along the extracted color and placement settings.

## 🎨 Step 3: Visual Notification Screen (WakeScreenActivity.kt)
- [ ] Create a full-screen, completely transparent activity layer.
- [ ] **Wake the Screen:** Use Android's native `setTurnScreenOn(true)` and `setShowWhenLocked(true)` features to safely light up the phone display even if it is locked.
- [ ] **Pulsing Animation:** Use a lightweight Compose animation loop to make the graphic gently pulse (fade in and out) to grab the user's attention.
- [ ] **Draw the Graphic:** Use a Jetpack Compose `Canvas` to draw the chosen style using the app's extracted brand color:
    - Draw a 4dp border ring around the edges of the screen.
    - Draw a clean ring cutout centered around the camera punch-hole location.
    - Draw a solid filled dot matching the precise slider positions from the settings.
- [ ] **Auto-Close Timer:** Use a simple 3.5-second timer (`Handler`) to automatically close this screen (`finish()`) so the phone immediately goes back to sleep and saves battery.

## 📦 Step 4: Simple Project Configuration
- [ ] Set up `build.gradle.kts` targeting Android 14+ with Jetpack Compose and `androidx.palette:palette-ktx:1.0.0`.
- [ ] Set up `AndroidManifest.xml` requesting only `android.permission.WAKE_LOCK`. Register the background service and make sure the wake activity is set up to look transparent and hidden from the "recent apps" history screen.

