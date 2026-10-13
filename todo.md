# Before release

## Name: Lumement
- [ ] **Domain**: register `lumement.app` (unregistered on 2026-10-09). The app ID `app.lumement` mirrors it, and the listing's privacy policy can live at `lumement.app/privacy`.
- [ ] **Trademark check**: search "Lumement" on EUIPO and USPTO before the listing goes live. Play had no app by that name on 2026-10-09.
- [x] **Logo and launcher icon**: the Friendly Owl (board 10 on the Lumement Logo canvas) is the launcher icon, themed icon and notification glyph.
- [ ] **Play listing icon**: Play wants a 512×512 PNG; export it from the owl icon SVG on the canvas.
- [ ] **Wordmark**: still to design. Keep "Lum" and "ement" readable as parts, so it isn't misread as "Lumament" or "lu-meme-nt".
- [ ] **Old install on the S23**: `app.lumement` installs as a new app. Uninstall the old Ambient Glow (`com.example.ambientglow`) by hand, then grant setup again in Lumement.

## Play Store
- [ ] **Real signing key**: release builds are debug-signed (`signingConfig` in `app/build.gradle.kts`). Add a release `signingConfig`, and keep the keystore out of git.
- [ ] **Create the premium product in Play Console**: a one-time product with ID `premium` (`PRODUCT_ID` in `dashboard/PremiumModel.kt`), with a price. Until it exists, the unlock pill and the "Unlock for €X" button stay hidden.
- [ ] **Internal testing track**: upload a signed `make bundle`, add a licence tester, then check:
  - the price shows (`UNLOCK · €X` under a premium element)
  - buying works, and the purchase is acknowledged (`billing acknowledged 0` in logcat): Play refunds unacknowledged purchases after 3 days
  - a refund takes premium back on the next dashboard open
  - a pending payment (a slow card) unlocks once it completes
- [ ] **Review card**: Play only shows it for an app installed from Play, so test it from the internal track. It's asked once and never in premium's way: not during the trial, a week after the trial ended if not bought, otherwise a week after the first app was heard from (a buyer usually gets it on the next open). Never on the open that shows the trial-over card. To ask again on a test phone: `adb shell run-as <package> cat shared_prefs/lumement.xml`, then clear `review_asked` (reinstalling doesn't).
- [ ] **Premium page**: the header's sparkle opens it. With the product live, it shows "Unlock for €X" instead of "Google Play can't sell premium right now"; after buying, the sparkle leaves the header.
- [ ] **Promise check**: the Premium page says "New elements are included as they come". Keep it only if every future element really goes to existing buyers.
- [ ] **Locked swatch → purchase sheet**: after the trial (`make install TRIAL=over`), tapping a dimmed colour opens Play's purchase sheet. That needs the `premium` product, so it can't be seen before then.
- [ ] **Which element sells**: the app collects no data, so use Play Console's store listing experiments: A/B the feature graphic and first screenshot (Air vs Fire vs Earth) and see which gets more installs. If Fire clearly wins, consider making it the default first element (`firstElement` in `GlowSettings.kt`). Air is the default now because it keeps the lock screen readable and calm on every message.
- [ ] **Android 12 and lower**: there, every element plays as the same gradient fallback, so new installs start on Water and premium shows little. Decide whether to sell premium there at all, or say in the listing that the elements need Android 13+.
- [ ] **Store listing / data safety**: say premium is a one-time purchase, and that the app has no internet permission (the Play Store app handles the purchase).

## Premium trial on the device
- [ ] **Trial start**: with Fire, Air or Earth selected, tap the padlock and lock the phone. After the effect plays:
  `adb shell run-as app.lumement cat shared_prefs/lumement_premium.xml` should show `trial_start`.
- [x] **Trial end**: `make install-trial-over` (or `make install TRIAL=untried|1..7|over` for any stage). Checked on the S23 on 2026-10-09: the over card, "TRIAL OVER" on the element card, locked swatches. Still to see: a message arriving as Water with that build (padlock test). Run `make install` afterwards to go back to the real clock.
- [ ] **Clash fix**: seen on the S23's real apps: ECOVACS HOME shows "Kept apart from Telegram" and glows cyan. Still to see: the LED blinking two different colours with both waiting.
- [ ] **Own phone**: `make install-premium`, so its trial never runs out.

## Every release
- [ ] `make check`: lint, tests, both builds, and `make permissions` (fails if the APK asks for INTERNET).
- [ ] Never ship a `PREMIUM=1` build to Play (`make bundle` refuses it).

## Stability, battery, tests
- [x] **Shield lost mid-session**: when GlowShield is unbound, it removes the LED window but doesn't tell the glow screen, which keeps holding the screen-on lock, so the lock screen stays lit until a power press. Add a callback from `GlowShield.release()` (for example `onShieldGone()`) that puts the LED's own black face back in front.
- [x] **Keep waiting messages across process death**: the waiting list is memory only, so a Play update or One UI killing the process silently drops the LED for unread messages. Save the waiting keys (key, app, colour) to a small prefs file, and on reconnect keep only the ones still in the shade.
- [x] **Update the accessibility description**: `shield_description` still talks about the removed blink, and it doesn't mention the focused LED window. Rewrite it to match what the service does now; Play's accessibility review checks this text.
- [ ] **Play declarations**: fill in the full-screen intent declaration and the accessibility API declaration in Play Console, along with the release signing key.
- [ ] **No per-frame allocation in `LedSurface.paint`**: create `CanvasDrawScope`, `Size` and `Density` once and reuse them. Moving LED materials repaint 30 times a second, possibly all night.
- [x] **CI**: `.github/workflows/check.yml` runs `make check` (lint, tests, both builds, the INTERNET guard) on every push and pull request; first run passed on GitHub 2026-10-12 (7 min).
- [ ] **Decide: the 16 ms sleep check under the lit LED** (~3.5% of a core): no power-key event can replace it. Tested 2026-10-12 on the S23 with physical presses: accessibility key filtering gets volume keys but never the power key. Left to choose: 16 ms (1 in 8 presses glitch) or 50 ms (2 in 5). Measured 2026-10-12 (make soak, lit LED, 15 min each): 16 ms 189 mA, 50 ms 183 mA; two 16 ms runs differed by 14 mA (175, 189), so the check's cost is within run-to-run noise. Recommendation: keep 16 ms.
- [x] **Measure the AOD LED's CPU hold**: measured 2026-10-12 (make soak, S23, lit room, AOD Show always, 1 h each): the AOD alone 88 mA, with the LED breathing on it 130 mA. The LED adds 42 mA (~1.1 % of the battery an hour): `Lumement:dark` held 54 of 61 min, so the phone is almost never in deep doze (3.7 of 133 mAh against 78 of 96), and the draw lock keeps the panel taking frames for every breath.
- [x] **The old-LED route for AOD-off users: One UI's AOD "Show for new notifications"**: since 2026-10-12 the flow reads `aod_show_for_new_noti`, so a sleep with messages waiting is expected to doze, and waits through the display's OFF for One UI's always-on display (~0.1 s after SCREEN_OFF) instead of relighting; the listener's 4 s check still lights the LED if none comes. On the S23: a message, its arrival, the lock screen's own sleep, One UI's AOD, the LED breathing on it (~130 mA measured for that mode, against 189 lit). Still open: how long One UI keeps it (5+ min seen), its low-light switch-off in this mode, and setup suggesting this mode to AOD-off users.
- [x] **After a glance, respect One UI's AOD**: decided 2026-10-13 (user): with "Show for new notifications" the LED lives on One UI's always-on display alone (`ledOnAodOnly`). When One UI shows no AOD (the message seen once the phone was woken, low light, its timeout), the LED is dark too: no lit LED, no 4 s dark-then-wake. A new message brings the AOD and the LED back. A black arrival sleeps into the AOD afterwards. Scenarios in `LockFlowTest`; to confirm on the phone.
- [x] **Setup suggests the AOD route**: an optional last setup step on One UI with the always-on display off and GlowShield on: "LED on the always-on display", a battery glyph in the cyan tile, opens Lock screen and AOD (Android's lock-screen settings; Samsung's own AOD page needs a Samsung permission). Offered once, like the shield. The Screen tab has a section of its own, "02 · While a message waits": the always-on display and Lumement effects, each lit when on, what that means for the LED, and a way to its Settings page; with the AOD off on One UI a line says which mode saves battery.
- [ ] **Decide: the LED on the always-on display, breathing or still**: decided 2026-10-12 to keep breathing. The still version (rises once, the panel holds it in DOZE_SUSPEND, no CPU or draw lock, an idle alarm moves it every 10 min) is parked on branch `experiment/still-aod-led`, checked on the S23; it would save most of the 42 mA the breath costs on the AOD.
- [x] **Extract `LockFlow` from `WakeScreenActivity`**: the lock flow is plain Kotlin over a `LockPort`, tested on `FakePhone` (`LockFlowTest`: 17 scenarios, each old bug reintroduced fails one). Next: a scenario for every lock-flow bug as it is found.
- [x] **Wake-lock audit test**: a test that reads the source and fails if any `acquire(` has no timeout, apart from `Lumement:led` and `Lumement:pocket`.
- [ ] **`make scenario`**: posts a test message over adb, locks the phone, reads the message, then checks `dumpsys power` that no `Lumement:*` lock is still held.
- [ ] **`make soak MODE=lit|aod`**: scripts the 10-minute charge-counter measurement and logs the result for each release.
- [ ] **Pixel / stock Android pass**: check the constants taken from One UI (`TAKEOVER_MS`, `SLEEP_CANCEL_DELAY_MS`) and the whole lock flow on a non-Samsung phone or emulator.
- [ ] **targetSdk 37**: before raising it, test that the panel wake (`ACQUIRE_CAUSES_WAKEUP` in `PanelWaker`) still works.
- [ ] **Brightness events on the lock-screen display listener**: keep the subscription only if a measurement shows it helps catch the press earlier; otherwise drop it.
- [ ] **Decide: LED brightness latch**: One UI's auto-brightness sticks at its 0 lux level under the lit LED, so the dot stays dim.

## Later: separate topic
- [ ] **Decide: lit LED overnight with the AOD off**: today one unread message keeps the screen on all night. Proposal to check:
  - on the charger, the LED stays lit
  - with Battery Saver on, no lit LED
  - on battery, one written-down limit on how long it stays lit with nobody touching the phone
- [ ] **The always-on display going away at night**: measured 2026-10-12 on the S23 ("Show always"), One UI switched the AOD off by itself after ~10 min in low light, as burn-in protection (`REASON_PREVENT_BURN_IN_EXECUTED`, `display_time_low_light`, DOZE_AOD → DOZE, screen state OFF). Our flow reads "the AOD went away" as "light the LED instead" (`onDisplayChanged`: `aodLit` and not dozing → `relightLed`) and woke the phone into the lit LED 190 ms later. So an AOD user in a dark room gets the lit LED all night too, overriding One UI's own choice. Part of the overnight decision.
- [ ] **Build the overnight rule**: once decided, as a pure function with unit tests, the same pattern as `ledWindowWanted`.
