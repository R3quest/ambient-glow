# Releasing Lumement on Google Play

What it takes to put a build on Play, and what was checked before the first one. The Play
Console paperwork (declarations, Data safety, privacy policy, store listing, icon) is drafted in
[`play/`](play/). What raising `targetSdk` to 37 would change is in
[`targetsdk-37.md`](targetsdk-37.md).

## How the builds are signed

| Build | Signed with | For |
| --- | --- | --- |
| `make debug`, `make install` | debug key | our phones |
| `make release`, `make install-release`, `make release-premium` | debug key | testing the R8 build, testers |
| `make soak`, `make soak-set` | debug key | battery runs |
| `make bundle` (`bundleRelease`) | **upload key**, required | the Play upload |

Only the bundle uses the upload key, even when the key is on the machine. Every APK is signed
with the same debug key, so any of them installs over any other and keeps the notification
access and the Lumement effects (accessibility) grants. An APK signed with the upload key
couldn't do that, and it couldn't update a Play install either: with Play App Signing, Play
re-signs what users download with its own app signing key.
Gradle decides that per run, so it refuses a run that builds the bundle and a release APK
together (`./gradlew bundleRelease assembleRelease`): build each on its own.

Without the key, `make release` (and so `make check` and CI) still builds, with one warning line.
`make bundle` fails and says how to provide the key. It also refuses `PREMIUM=1` and `TRIAL=…`,
in the Makefile and again in Gradle, so `./gradlew bundleRelease -Ppremium=true` can't build a
premium-for-everyone bundle either.

## Making the upload key (once)

Do this on your own machine. Never put the key or its passwords in the repository:
`*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `*.pepk` and `keystore.properties` are gitignored.

```sh
mkdir -p ~/keys/lumement && chmod 700 ~/keys/lumement
keytool -genkeypair -v \
  -keystore ~/keys/lumement/upload.jks -storetype PKCS12 \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Lumement upload key"
```

`keytool` asks for a password. With PKCS12, the store password and the key password are the
same. Store the password in your password manager, and keep a backup of `upload.jks` somewhere
other than this machine. If the upload key is lost, Play can reset it, but you have to ask
(Play Console → Test and release → App integrity → App signing → Request upload key reset).
The app signing key itself is Google's and can't be lost.

Then point the build at it, in `keystore.properties` at the project root (next to `settings.gradle.kts`):

```properties
storeFile=/home/you/keys/lumement/upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

A relative `storeFile` is read from the project root.

### CI, or no file

Set these environment variables instead. The build reads each value from `keystore.properties`
first, then from the environment:

| Variable | Value |
| --- | --- |
| `LUMEMENT_KEYSTORE` | path to `upload.jks` |
| `LUMEMENT_KEYSTORE_PASSWORD` | store password |
| `LUMEMENT_KEY_ALIAS` | `upload` |
| `LUMEMENT_KEY_PASSWORD` | key password |

On GitHub Actions, keep the keystore as a base64 secret, decode it into `$RUNNER_TEMP` in a
step, and pass the passwords from secrets. The `check` workflow doesn't need any of this, because
it builds APKs only.

## The first upload

1. **Create the app** in the Play Console, package `app.lumement`, free, with in-app purchases.
2. **Play App Signing:** keep the default, where Google generates and holds the app signing key.
   The first bundle you upload registers your upload key.
3. **In-app product:** Monetize → Products → In-app products → create `premium` (the
   `PRODUCT_ID` in `dashboard/PremiumModel.kt`). It's a one-time purchase that is never consumed.
   The app acknowledges it (Play refunds a purchase that isn't acknowledged within 3 days). Set
   the price. The app shows whatever price Play returns, so nothing in the code needs to change.
4. **Bump the version** in `app/build.gradle.kts`. Every upload needs a higher `versionCode`
   (`versionName` is only what users see).
5. **Build and check the bundle:**
   ```sh
   make check                 # lint, tests, both builds, the no-INTERNET guard
   make bundle                # app/build/outputs/bundle/release/app-release.aab
   jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab | grep -m1 "Signed by"
   keytool -list -v -keystore ~/keys/lumement/upload.jks | grep SHA256   # the same certificate
   ```
6. **Upload it to Internal testing first**, install it from Play on the S23, and check the
   whole path again: setup, a message to the bright phase to the always-on display, buying
   premium with a licence-tester account (Play Console → Settings → License testing), and the
   "restore" path (uninstall, reinstall, and premium comes back from Play).
7. **App content:** fill in the forms from [`play/`](play/):
   - [Accessibility API declaration](play/accessibility-declaration.md) (needs a short video)
   - [Full-screen intent declaration](play/full-screen-intent-declaration.md)
   - [Data safety](play/data-safety.md)
   - [Privacy policy](play/privacy-policy.md), published at `https://lumement.app/privacy`
   - Ads: none. Target audience: adults (18+), because it isn't made for children. Content
     rating: the questionnaire, all "no".
8. **Store listing:** [`play/store-listing.md`](play/store-listing.md), the icon
   [`play/icon-512.png`](play/icon-512.png) (`python3 -I docs/play/make_icon.py` re-renders it
   from the launcher icon), a 1024 × 500 feature graphic, and phone screenshots. These last two
   are still to be made: capture them from the S23 with `adb exec-out screencap -p`.

## Audit before the first release (2026-10-18)

Checked against the release APK and bundle as built on this branch.

**Size.** `app-release.apk` is 1.71 MB (a single 3.1 MB `classes.dex`, plus 4 copies of
`libandroidx.graphics.path.so` from Compose at 7-11 KB each). `app-release.aab` is 4.5 MB, and
Play serves each phone only its own ABI. The native library is 16 KB page aligned (ELF `LOAD`
alignment 0x4000, `zipalign -c -P 16` passes), which Play requires for Android 15+ devices.

**Permissions** (`aapt2 dump permissions`): `WAKE_LOCK`, `POST_NOTIFICATIONS`,
`USE_FULL_SCREEN_INTENT`, `com.android.vending.BILLING`, and AndroidX's own
`app.lumement.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature-level, it protects
`RECEIVER_NOT_EXPORTED` receivers below Android 13). No `INTERNET`, no `ACCESS_NETWORK_STATE`.
`make permissions` guards this on every `make check`.

**Exported components.** All are as they should be:
- `MainActivity`: the launcher entry.
- `NotificationWakerService`: guarded by `BIND_NOTIFICATION_LISTENER_SERVICE`, so only the
  system can bind it.
- `GlowShield`: guarded by `BIND_ACCESSIBILITY_SERVICE`. Its config asks for no event types and
  no window content (`canRetrieveWindowContent="false"`), and it isn't marked
  `isAccessibilityTool` (it isn't one; see the declaration).
- Profile installer receiver (AndroidX): guarded by `DUMP`.

`WakeScreenActivity`, Billing's proxy activities and Play Core's dialog are not exported. Every
receiver registered at runtime is `RECEIVER_NOT_EXPORTED`, except `ACTION_USER_PRESENT` in
`WakeScreenActivity`, which is a protected system broadcast. The AOD LED's 10-minute step alarm is
an explicit-package, immutable `PendingIntent` sent with `setAndAllowWhileIdle`, which needs no
exact-alarm permission. `src/soak`'s `SoakSettings` receiver (exported, `DUMP`) is only in the
soak build.

**Full-screen intent, Android 14+.** Play grants `USE_FULL_SCREEN_INTENT` at install only to
calling and alarm apps, and Lumement is neither, so a Play install starts without it. Setup
already has a step for it on Android 14+: `canUseFullScreenIntent()`, then
`ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. With Lumement effects on, most wakes don't use the
full-screen intent at all (`GlowLauncher.startInTheDark`). Still open: if the user takes
the grant back later, `GlowLauncher.canPostBridge` still says yes, and the bridge posts anyway.
Android shows it as an ordinary heads-up notification for its 4 seconds, and it wakes nothing.
`canPostBridge` should also check `canUseFullScreenIntent()` on Android 14+, so that wake is
skipped like a blocked bridge. The dashboard already shows the missing step the next time it
opens.

**Backup.** Nothing is backed up or moved to a new phone: `allowBackup="false"` and
`fullBackupContent` for Android 11 and lower, and `dataExtractionRules` that exclude every domain
from both cloud backup and device-to-device transfer for Android 12 and up (where
`allowBackup="false"` alone no longer stops a device transfer). This is on purpose:
- `lumement_apps` lists every app that has messaged you, with when it last did. That should
  never leave the phone, even in an encrypted Google backup.
- `lumement_pending` (the messages waiting, by notification key) is meaningless on another phone.
- The LED's place and the camera fit are fractions of *this* screen, and on another phone they
  would land somewhere else.
- Premium's file: ownership comes back from Play on its own (`queryPurchasesAsync` each time the
  dashboard opens). The trial clock not coming back means a reinstall or a new phone starts a new
  7-day trial. That was accepted: restoring it would mean backing up premium's file alone, which
  is a product call and not a defect.

**R8.** The release build is minified and resource-shrunk, with `-repackageclasses` and
`-allowaccessmodification`. No rule is missing:
- The only reflection is on Samsung's platform `SemBlurInfo` (`GlowShield`), and it doesn't
  touch our classes.
- Settings are saved as enum `name`s and read back by comparing against `enumEntries`. R8 never
  renames enum constants' names, and `proguard-android-optimize.txt` keeps `values`/`valueOf`.
  This is why every settings enum shows up in `seeds.txt`, and it's the right amount of keeping.
- Manifest components and `@xml/glow_shield` are kept by AAPT's generated rules.
- No serialization library, no JNI of our own, no `Class.forName` on our own classes.
- Billing, Play Review, Compose and AndroidX bring their own consumer rules. None of them is
  broad (there's no `-dontobfuscate`, and no `-keep class **`).

In the release dex, `GlowLog`'s messages and the LED diary are gone (no `"panel wake"`,
no `led-diary`), as `BuildConfig.DEBUG` intends. It hasn't been run on the S23 from this branch:
`make install-release` and one message round there before the upload is step 6 above.
