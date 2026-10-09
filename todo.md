# Before release

## Play Store
- [ ] **Change `applicationId`**: Play rejects `com.example.*`. Set the real one in `app/build.gradle.kts` (and `namespace` if wanted). It installs as a new app, so setup grants (notification access, full-screen wake, the GlowShield accessibility service) have to be given again.
- [ ] **Real signing key**: release builds are debug-signed (`signingConfig` in `app/build.gradle.kts`). Add a release `signingConfig`, and keep the keystore out of git.
- [ ] **Create the premium product in Play Console**: a one-time product with ID `premium` (`PRODUCT_ID` in `dashboard/PremiumModel.kt`), with a price. Until it exists, the unlock pill and the "Unlock for €X" button stay hidden.
- [ ] **Internal testing track**: upload a signed `make bundle`, add a licence tester, then check:
  - the price shows (`UNLOCK · €X` under a premium element)
  - buying works, and the purchase is acknowledged (`billing acknowledged 0` in logcat): Play refunds unacknowledged purchases after 3 days
  - a refund takes premium back on the next dashboard open
  - a pending payment (a slow card) unlocks once it completes
- [ ] **Store listing / data safety**: say premium is a one-time purchase, and that the app has no internet permission (the Play Store app handles the purchase).

## Premium trial on the device
- [ ] **Trial start**: with Fire, Air or Earth selected, tap the padlock and lock the phone. After the effect plays:
  `adb shell run-as com.example.ambientglow cat shared_prefs/ambient_glow_premium.xml` should show `trial_start`.
- [ ] **Trial end**: check the "Your premium trial is over" card, the dimmed colour swatches, and that a premium element arrives as Water. (No shortcut yet: either wait out the week, or add a debug-only way to move `trial_start` back.)
- [ ] **Clash fix**: two apps with look-alike icon colours (e.g. WhatsApp and Spotify). The later one shows "Kept apart from …" and the LED blinks two different colours.
- [ ] **Own phone**: `make install-premium`, so its trial never runs out.

## Every release
- [ ] `make check`: lint, tests, both builds, and `make permissions` (fails if the APK asks for INTERNET).
- [ ] Never ship a `PREMIUM=1` build to Play (`make bundle` refuses it).
