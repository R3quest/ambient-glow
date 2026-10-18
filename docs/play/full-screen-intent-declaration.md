# Full-screen intent declaration (draft)

Play Console → App content → **Full-screen intent**. Lumement declares
`USE_FULL_SCREEN_INTENT` in its manifest. On Android 14 and up, Play grants it at install only
to apps whose core functionality is **calling** or **alarms**.

## The answer to give

**Does your app use the full-screen intent permission?** Yes.

**Core functionality:** neither calling nor alarms. Pick the option saying the app's core
functionality isn't in the listed categories, if the form offers it. Play then doesn't grant
the permission at install, and the user grants it in the app's setup. That's honest, and it's
the flow Lumement already has:
- On Android 14+, setup has a "Full-screen wake" step (`access_fsi_title`: "Android 14+ asks you
  to confirm that Lumement may turn the display on"). It checks
  `NotificationManager.canUseFullScreenIntent()` and opens
  `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` for the app.
- Setup doesn't count as finished without it, and the dashboard re-checks it each time it
  comes back to the front.

Don't claim it as an alarm or calling app. Play can audit that and remove the app.

## If the form asks why (free text)

> Lumement replaces the notification LED that phones no longer have. When a message arrives on a
> locked phone with the screen off, it has to bring its LED screen up over the lock screen.
> Android allows a background app to do that only through a full-screen intent, because Lumement
> doesn't use "display over other apps". The notification that carries it is silent, goes
> straight to Lumement's own screen, and is cancelled as soon as that screen starts (it also
> times out after 4 seconds), so nothing stays in the shade. The user grants the permission in
> setup. Lumement has no internet permission and collects no data.

## How the app uses it (for reference)

`GlowLauncher.launchFromBackground` posts the bridge notification on the silent, high-importance
channel `glow_wake`, category `REMINDER`, with `setFullScreenIntent(…, true)` and
`setTimeoutAfter(4 s)`. `WakeScreenActivity` cancels it as it starts. With *Lumement effects*
(the accessibility service) on, most wakes start the screen directly and don't use the
full-screen intent (`GlowLauncher.startInTheDark`). The full-screen intent is the fallback.
