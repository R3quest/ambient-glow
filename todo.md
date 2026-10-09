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
- [ ] **Review card**: Play only shows it for an app installed from Play, so test it from the internal track. It's asked once and never in premium's way: not during the trial, a week after the trial ended if not bought, otherwise a week after the first app was heard from (a buyer usually gets it on the next open). Never on the open that shows the trial-over card. To ask again on a test phone: `adb shell run-as <package> cat shared_prefs/ambient_glow.xml`, then clear `review_asked` (reinstalling doesn't).
- [ ] **Locked swatch → purchase sheet**: after the trial (`make install TRIAL=over`), tapping a dimmed colour opens Play's purchase sheet. That needs the `premium` product, so it can't be seen before then.
- [ ] **Which element sells**: the app collects no data, so use Play Console's store listing experiments: A/B the feature graphic and first screenshot (Air vs Fire vs Earth) and see which gets more installs. If Fire clearly wins, consider making it the default first element (`firstElement` in `GlowSettings.kt`). Air is the default now because it keeps the lock screen readable and calm on every message.
- [ ] **Android 12 and lower**: there, every element plays as the same gradient fallback, so new installs start on Water and premium shows little. Decide whether to sell premium there at all, or say in the listing that the elements need Android 13+.
- [ ] **Store listing / data safety**: say premium is a one-time purchase, and that the app has no internet permission (the Play Store app handles the purchase).

## Premium trial on the device
- [ ] **Trial start**: with Fire, Air or Earth selected, tap the padlock and lock the phone. After the effect plays:
  `adb shell run-as com.example.ambientglow cat shared_prefs/ambient_glow_premium.xml` should show `trial_start`.
- [x] **Trial end**: `make install-trial-over` (or `make install TRIAL=untried|1..7|over` for any stage). Checked on the S23 on 2026-10-09: the over card, "TRIAL OVER" on the element card, locked swatches. Still to see: a message arriving as Water with that build (padlock test). Run `make install` afterwards to go back to the real clock.
- [ ] **Clash fix**: seen on the S23's real apps: ECOVACS HOME shows "Kept apart from Telegram" and glows cyan. Still to see: the LED blinking two different colours with both waiting.
- [ ] **Own phone**: `make install-premium`, so its trial never runs out.

## Every release
- [ ] `make check`: lint, tests, both builds, and `make permissions` (fails if the APK asks for INTERNET).
- [ ] Never ship a `PREMIUM=1` build to Play (`make bundle` refuses it).
