# Store listing (draft)

Play Console → Grow → Store presence → Main store listing. The limits are Play's: app name
30 characters, short description 80, full description 4000 (each is counted at the end). Every
claim here is checked against the code as of 2026-10-18. Things deliberately *not* claimed are
listed at the end. The price comes from Play, so the copy says "one-time" and never a number.

## App name (≤ 30)

```
Lumement: Notification LED
```

## Short description (≤ 80)

```
The notification LED your phone lost, in each app's colour. No internet, no ads.
```

## Full description (≤ 4000)

```
Phones lost their notification LED. Lumement brings it back on your OLED screen.

When a message arrives, the screen lights with a short effect in the colour of the app that sent it. Then the screen goes black, and a small LED breathes until you read the message: once for each app that's waiting. Glance at your phone and you know.

THE LED
• A dot wherever you like, a ring round the camera, or a thin frame round the screen's edge
• Each app glows in its own icon's colour, picked out automatically
• Two apps that look alike are kept apart, so you can tell them from one another
• Press the power key and the lock screen is there at once; your fingerprint unlocks straight away (with Lumement effects on)

THE EFFECT
• LED Beacon lights up where your LED waits, then becomes it. Edge Frame runs light round the screen's edge, then into your LED
• Water: a clear wave of light that rolls out of the camera, free for everyone
• Choose what the screen shows: the lock screen with the effect over it, a black screen, just the new message, or only the LED

KIND TO YOUR BATTERY
• Does nothing between messages: no background work, no polling, no CPU while it waits
• The screen stays lit with the LED for 5, 10 or 15 minutes after a message (your choice, or off), never more than 30 minutes until you look, and never in Battery Saver or below 20%
• Then the phone sleeps. With the always-on display set to show for new notifications, the LED stays on it, still (with Lumement effects on)
• Read the message on your computer or watch, turn on Do Not Disturb, or put the phone face down, and the screen goes off by itself (with Lumement effects on)

PRIVATE BY DESIGN
• No internet permission. Lumement can't send anything anywhere
• It never reads, stores or shares what your messages say. It only notices which app sent one
• No accounts, no ads, no analytics, no tracking
• Choose which apps light up, and mute the rest

PREMIUM, PAID ONCE
Fire, Air and Earth: three more elements for the effect and the Edge Frame, each with options of its own. Plus your own colour for any app.
• Try it free for 7 days, counted from the first message it plays for
• One purchase through Google Play, no subscription
• After the trial, new messages arrive as Water until you unlock it, and your choices are kept
• The LED, every screen choice, LED Beacon and Edge Frame, Water, and which apps light up are always free

GOOD TO KNOW
• Needs Android 8.0 or later. The elements' full looks need Android 13 or later; on older phones every element plays as a simpler glow
• Made and measured on a Samsung Galaxy S23. The always-on display LED is set up for One UI's always-on display
• Setup asks for notification access (to notice new messages), alerts (Android only lets an app wake the screen through a silent alert, which closes itself at once), and on Android 14 and later, full-screen wake
• Optional: Lumement effects, an accessibility service that draws the effect and the LED over the lock screen and turns the screen off when nothing is left to show. It receives no accessibility events and can't read your screen. Android will warn that it could have full control; Lumement reads nothing
```

## Category and tags

- Category: **Personalization**. Tags: notification LED, always-on display, AMOLED,
  notifications, customization.
- Contact email: [the support address]. Website: `https://lumement.app`. Privacy policy:
  `https://lumement.app/privacy`.

## Graphics

- App icon: `icon-512.png` (from `make_icon.py`).
- Feature graphic 1024 × 500: still to make. A suggestion: the owl on `owl_night` (#0E0A1C) at
  the left, and a black phone with a cyan ring round the camera at the right.
- Phone screenshots (2-8, 16:9 or 9:16, 1080 px and up): the LED waiting on black, the effect
  over the lock screen, the Edge Frame, the dashboard's Look tab, the Apps list, and Premium.

## Deliberately not claimed

- **"New elements are included as they come"** (the Premium screen, `premium_elements_body`, says
  it). It's an open product decision, so the listing doesn't promise it until it's decided.
- **Battery numbers.** The mA figures in `measurements/` are from one S23. "No CPU while it
  waits" is the design (claude.md §1) and it's what the soaks show. A percentage-per-hour claim
  would vary by phone.
- **Works on every phone.** The always-on display LED and the fit of the camera ring were tuned on
  One UI. Other phones get the LED and effects, but this hasn't been measured on them.
- **Premium on Android 12 and lower.** Selling it there is an open question. The listing says
  what those phones get.

## Character counts

Checked with `python3 -I -c` on the blocks above: name 26, short description 80, full
description 3,178.
