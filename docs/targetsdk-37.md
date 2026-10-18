# Raising targetSdk to 37 (Android 17): what it would change

`targetSdk` stays at 36 for now (`app/build.gradle.kts`). `compileSdk` is already 37. This page
lists every Android 17 change that touches something Lumement uses, what we found, and the test
plan to run before raising it.

**Sources** (read 2026-10-18):
[behaviour changes for apps targeting 17](https://developer.android.com/about/versions/17/behavior-changes-17),
[behaviour changes for all apps](https://developer.android.com/about/versions/17/behavior-changes-all),
[features](https://developer.android.com/about/versions/17/features),
[background activity starts](https://developer.android.com/guide/components/activities/background-starts),
[secure BAL](https://developer.android.com/guide/components/activities/secure-bal),
and reporting on Advanced Protection in Android 17
([The Hacker News](https://thehackernews.com/2026/10/android-17-advanced-protection-locks.html),
[Security Affairs](https://securityaffairs.com/189497/security/advanced-protection-mode-in-android-17-prevents-apps-from-misusing-accessibility-services.html)).
Google's pages change between releases, so re-read them before raising the target.

**Deadline:** none yet. Play requires a target within a year of the newest Android release, and
36 is accepted for updates until Play's 2027 deadline (usually 31 August). There's no reason to
raise it before the S23 runs Android 17 and the plan below has passed on it.

## Summary

| Area | What Lumement does | Changes at target 37? | Risk |
| --- | --- | --- | --- |
| Screen wake locks (`PanelWaker`) | `SCREEN_BRIGHT_WAKE_LOCK \| ACQUIRE_CAUSES_WAKEUP \| ON_AFTER_RELEASE`, 1 s | Not in Google's lists. See below. | **Medium: verify on the phone** |
| Other wake locks | `PARTIAL` (with timeouts), `SCREEN_BRIGHT`, `PROXIMITY`, hidden `DRAW_WAKE_LOCK` (128) | No | Low |
| Full-screen intent (`GlowLauncher`) | Bridge notification with `setFullScreenIntent` | No (the Android 14 grant still applies) | Low |
| Background activity start (`startInTheDark`) | `Context.startActivity` while GlowShield is bound | No. The 17 change is to `IntentSender`, which we don't use | Low |
| PendingIntents | Bridge, test messages, AOD step alarm: all immutable and sent by the system | No | Low |
| Accessibility overlay (`GlowShield`) | `TYPE_ACCESSIBILITY_OVERLAY`, `GLOBAL_ACTION_LOCK_SCREEN` | No targetSdk change. **All apps:** Advanced Protection blocks it | Medium (product) |
| Notification listener | Posted/removed, ranking, `EXTRA_MESSAGES` timestamps | No | Low |
| Edge-to-edge, predictive back | Already enforced at 36 | No | None |
| Orientation (`MainActivity` portrait) | Ignored on large screens since 36 | The opt-out goes, but we never used it | None |
| Foreground services | None | No | None |
| Background audio hardening | Only *listens* (mode changes, playback callback) | No: only playback, focus and volume are restricted | Low |
| Lock-free `MessageQueue` | `Handler.postDelayed` / `removeCallbacks` only, no reflection | No | None |
| Static final fields via reflection | None (the only reflection is calls into Samsung's `SemBlurInfo`) | No | None |
| Native code loading | `libandroidx.graphics.path.so` from the APK via `loadLibrary` | No (the rule is about `System.load` of writable files) | None |
| ECH, Certificate Transparency, local network permission | No network at all | No | None |
| Activity-switch (task hijacking) protection | Opt-in (`allowCrossUidActivitySwitchFromBelow`) | No unless we opt in | None |

## The details

### Screen wake: `PanelWaker` and `ACQUIRE_CAUSES_WAKEUP`

Every wake goes through `PanelWaker.wake` (`Power.kt`): a 1-second screen wake lock with
`ACQUIRE_CAUSES_WAKEUP`. `WakeScreenActivity` arms `setTurnScreenOn` only as the full-screen
intent's fallback. Since Android 14 (API 34), the platform has a `TURN_SCREEN_ON` permission
(`Manifest.permission.TURN_SCREEN_ON` is in the API 37 SDK), and a compat change,
`REQUIRE_TURN_SCREEN_ON_PERMISSION`, which makes `ACQUIRE_CAUSES_WAKEUP` need it. On the S23
(Android 16 QPR2), `dumpsys platform_compat` lists that change as enabled from target 10000,
which means for no released target, as the `PanelWaker` comment says.

Neither of Android 17's behaviour-change pages mentions wake locks, `ACQUIRE_CAUSES_WAKEUP` or
`TURN_SCREEN_ON`. So as far as Google documents it, nothing changes at 37. But this is the one
place where an undocumented gate would break the app outright: the panel wouldn't light for a
message, and nothing would crash or log at our level. **First thing to check on an Android 17
build:**

```sh
adb shell dumpsys platform_compat | grep -i -A1 turn_screen
adb shell appops get app.lumement TURN_SCREEN_ON
```

If the change shows `enableSinceTargetSdk=37`, raising the target needs `TURN_SCREEN_ON`. It's
an app-op permission that the user grants in Settings (Special app access → Turn screen on,
where the phone has it). That would be one more setup step like full-screen wake, and claude.md's
permission list would need it added.

The other wake locks: `PARTIAL_WAKE_LOCK` always with a timeout (`DarkHold`, the listener's 4 s),
`SCREEN_BRIGHT_WAKE_LOCK` for the lit LED, `PROXIMITY_SCREEN_OFF_WAKE_LOCK` for the pocket check,
and the hidden `DRAW_WAKE_LOCK` level (128) for the AOD LED's rise. None of them is restricted
at 37. `DRAW_WAKE_LOCK` is a hidden constant, not a hidden API call, so hidden-API lists don't
apply to it. Still, a platform change to which levels apps may use would end the AOD LED, so
it's in the plan below.

### Full-screen intents

There's nothing new in 17. The Android 14 rule still applies: `USE_FULL_SCREEN_INTENT` is
special app access, granted at install only to calling and alarm apps. Setup asks for it
(`AccessStep.FULL_SCREEN`, `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`), and with GlowShield on,
most wakes don't use it. See `release.md` for the gap where the user revokes it later.

### Background activity starts

Android 17 extends background-launch rules to `IntentSender`: `IntentSender.sendIntent()` needs
the sender's opt-in, and `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` is discouraged. Lumement
sends no `IntentSender`. Our two launch paths:
- **The full-screen intent:** a `PendingIntent` that the system sends. It's exempt ("started
  from a `PendingIntent` that was sent by the system").
- **`GlowLauncher.startInTheDark`:** a plain `startActivity` while the system holds GlowShield
  bound (an app bound by a service with background-start permission). The 17 pages don't change
  this. It's already guarded: when it doesn't arrive within `DARK_START_CHECK_MS` (800 ms), the
  full-screen intent goes instead, so a future restriction would degrade gracefully (the lock
  screen showing for a moment first) and not lose the wake.

The in-task activity-switch protection is opt-in at 37 and doesn't apply to us:
`WakeScreenActivity` runs in its own task (`singleInstance`, `taskAffinity=""`).

### Accessibility (GlowShield)

There's no targetSdk change. **For all apps on Android 17:** when the user turns on Advanced
Protection, the system won't let a service that isn't marked `isAccessibilityTool` hold the
accessibility API. GlowShield isn't marked as one, and must not be: it isn't an accessibility
tool, and Play's policy reserves the flag for real ones. Those users get Lumement without the
effects over the lock screen. The app already copes, because everything checks `GlowShield.isOn`:
the effect plays on the glow screen, and the full-screen intent wakes. But the dashboard would
offer "Turn on Lumement effects" and Settings would refuse. Suggested later, in the dashboard
(not done here): on Android 17, if `AdvancedProtectionManager.isAdvancedProtectionEnabled()`,
don't offer the shield, and say why.

### Notification listener

Nothing in 17. (Android 15's redaction of one-time codes for untrusted listeners doesn't matter
to us: we read the app, category, flags, ranking and message timestamps, never the text.)

### Large screens

At 36, Android already ignores `screenOrientation="portrait"` on displays at least 600dp wide
(tablets, a Fold's inner screen), unless the app opts out with
`PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`. Lumement never opted out, so it's already in
the at-37 state. Losing the opt-out changes nothing for us. On a Fold's inner screen the
dashboard can rotate. The LED's place is saved as fractions of the portrait screen, which is the
existing caveat in the manifest comment.

## Test plan before raising the target

On the S23 once it runs Android 17 (One UI 9), or a Pixel / emulator image with API 37 for
everything except One UI's always-on display:

1. Locally (don't commit), set `targetSdk = 37` and update `tools:targetApi` in the manifest.
   Then run `make check`. Lint flags new deprecations, and the unit tests don't depend on the
   target.
2. Check the compat flags: `adb shell dumpsys platform_compat | grep -iE "turn_screen|bal|fsi|full_screen"`,
   and write down every change that shows `enableSinceTargetSdk=37`.
3. **Panel wake, shield off:** lock the phone, wait for the CPU to sleep (30 s and more), and send
   a test message (the dashboard's padlock posts two, 5 s later, or adb's as `scripts/soak.sh`
   does). The screen must light with
   the effect. Then in logcat, look for `panel wake`, and for no `Not allowing` /
   `TURN_SCREEN_ON` lines from `PowerManagerService`.
4. **Panel wake, shield on:** repeat. Logcat should show `dark start mode=…` and no
   `dark start didn't arrive`.
5. **Full-screen intent revoked:** Settings → Special app access → full-screen notifications →
   off. Setup must show the full-screen wake step again.
6. **The bright phase into the AOD:** message, wait out the 10 minutes, and the LED must be on
   the always-on display. Take `adb shell dumpsys power | grep -i lumement` before and after:
   no Lumement lock is left once it's still.
7. **Relight after unlock, Do Not Disturb, face down, a call** (the LockFlowTest scenarios, on
   the phone): no screen lock outlives the session (`dumpsys power`).
8. **Advanced Protection on** (Settings → Security → Advanced Protection): the app without the
   shield still wakes and shows the LED, and turning the shield on is refused by Settings.
9. **Battery:** `make soak MODE=hybrid MINUTES=30` against the last Android 16 run in
   `measurements/`. The idle drain should be unchanged.
10. Only then raise the target in a commit of its own, and update `PanelWaker`'s comment (it
    names SDK 36), claude.md's build line, and the README's Target SDK.
