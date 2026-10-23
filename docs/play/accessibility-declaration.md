# Accessibility API declaration (draft)

Play Console → App content → Permissions declarations → **Accessibility API**. This is for the
optional service *Lumement effects* (`GlowShield`). It must match the service's own description
on the phone (`shield_description` in `strings.xml`), so if one changes, change the other.

## Is the app an accessibility tool?

**No.** `GlowShield` doesn't set `android:isAccessibilityTool`, and must not: it doesn't help
users with disabilities use their phone. So the declaration below is needed, and so is the
in-app disclosure (see the end).

## Core functionality the API supports

> Lumement is a notification LED for phones that no longer have one. When a message arrives, it
> lights the screen with a short effect in the colour of the app that sent it, then leaves a small
> breathing "LED" on a black screen until the message is read.
>
> The optional "Lumement effects" accessibility service is used only for things an app window
> can't do on a locked phone:
> 1. **Drawing the effect over the lock screen.** For a few seconds when a message arrives
>    (never more than five), it shows the chosen effect in a see-through
>    `TYPE_ACCESSIBILITY_OVERLAY` window above the lit lock screen. The lock screen stays visible
>    and usable underneath. No app window can draw above a visible keyguard.
> 2. **Showing the waiting LED over the lock screen**, in a black overlay window, so the power key
>    opens the lock screen at once and a finger on the sensor unlocks with the fingerprint. While
>    the LED lights the screen, it also covers the status and navigation bars with black for about
>    half a second.
> 3. **Turning the screen off** (`GLOBAL_ACTION_LOCK_SCREEN`) when nothing is left to show: the
>    messages were read on another device, Do Not Disturb came on, the phone was put face down, or
>    the LED's bright minutes are over. It does what the power key would, so the screen isn't left
>    on and draining the battery.
> 4. **The LED on the always-on display:** where the always-on display is on, the LED shows still
>    in a see-through overlay over it.
> 5. **Bringing Lumement's own screen up when a message arrives** on a locked phone: while the
>    service is on, Android lets the app start that screen from the background, so the LED comes
>    straight up in the dark, without the lock screen flashing first. Where the full-screen
>    notification can't be used (its permission not granted, or notifications turned off for
>    Lumement), this is the only way the LED appears.
> 6. **Leaving a lock screen that is being read alone:** for the 2.5 seconds after a message lights
>    the lock screen, before the LED covers it, a 1x1 pixel transparent window is told only that
>    the screen was touched (Android's "touch outside" signal, which carries no position or content
>    for another app's window). A touch keeps the lock screen as it is; nothing about the touch is
>    kept.
>
> The service asks for no accessibility events (no `accessibilityEventTypes`), can't read window
> content (`canRetrieveWindowContent="false"`), and performs no gestures or clicks. The touch
> signal in (6) is a window flag, not an accessibility event, and says only that a touch happened. It never
> reads, collects, stores or sends anything on the screen. Lumement has no internet permission.
> The app works without the service: the effect then plays on Lumement's own screen.

## Is any user data collected through the API?

**No.** The service receives no accessibility events and can't read the screen, so it has
nothing to collect. Nothing leaves the device (the app has no `INTERNET` permission).

## Video

Play asks for a short video (a YouTube link, can be unlisted) that shows the feature and the
in-app disclosure. Record it on the S23 (`adb shell screenrecord`):
1. In setup, the "Effects on the lock screen" step, tapping **Turn on**, and enabling *Lumement
   effects* in Settings.
2. Locking the phone, a test message (the padlock at the top of the dashboard), and the effect
   playing over the lock screen, then the LED.
3. The power key opening the lock screen at once, and a fingerprint unlocking.

## In-app disclosure (prominent disclosure and consent)

Play's policy wants a disclosure inside the app, shown in the normal flow before the user turns
the service on, that says what the service does and gets an explicit yes. Setup's optional step
does this: `setup_shield_title` / `setup_shield_body` ("Plays your effect over the lock screen
when a message arrives… Lumement reads nothing on screen.") with **Turn on** and **Not now**.

The dashboard's other ways in, the Screen tab's note (`arrival_needs_shield`,
`arrival_needs_shield_led`) and the waiting card's always-on display row, show the same
disclosure as a dialog (`ShieldDisclosure` in `dashboard/Access.kt`) with Turn on / Not now
before Settings opens (done 2026-10-23). Setup and the dialog share one text, which names the API,
everything the service does and the screen-off action:

> **Effects on the lock screen**
> Lumement uses Android's accessibility service to draw your effect and the LED over the lock
> screen, to bring its own screen up when a message arrives, to notice that the lit lock screen was
> touched (nothing of where or what), and to turn the screen off when no message is left to show.
> It reads nothing on the screen, collects nothing, and never goes online. Android will warn that it
> could have full control: it uses only these windows and the screen-off action.
> [Not now] [Turn on]
