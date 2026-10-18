# Data safety form (draft answers)

Play Console → App content → **Data safety**. In Play's terms, data is "collected" when it is
sent off the device, and "shared" when it goes to a third party. Lumement has no `INTERNET`
permission (`make permissions` fails the build if any library brings it in). Everything it keeps
stays in its private storage on the phone.

## Data collection and security

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not asked once the answer above is No. Nothing is transmitted. |
| Do you provide a way for users to request that their data is deleted? | Not asked. On the phone: uninstalling, or Settings → Apps → Lumement → Storage → Clear data, deletes everything Lumement keeps. |

With "No", Play shows **"No data collected"** and **"No data shared with third parties"** on the listing.

## Why "No" is right: what Lumement handles, and where it goes

| What | Where it stays | Leaves the phone? |
| --- | --- | --- |
| Notifications (through notification access): which app, its category and flags, its ranking, and the message timestamps in `EXTRA_MESSAGES` | Read in memory, to decide whether it's a new message and which colour to glow | No. Message text is never read, stored or sent. |
| The sending app's icon | Read in memory to work out its colour (Palette) | No |
| Apps that have messaged you: package name, label, first and last time heard from, icon colour, your mute and colour choices (`lumement_apps`) | App-private storage | No. It's also excluded from Android backup and device transfer (`data_extraction_rules.xml`). |
| Messages waiting: notification key, app, colour, time (`lumement_pending`) | App-private storage, removed as they're read | No (excluded from backup) |
| Settings, premium state and the trial's start time | App-private storage | No (excluded from backup) |
| "New message" pop-up (an optional screen mode) | The message is re-posted *on the phone* as a system notification for at most 10 seconds, with your lock-screen privacy applied | No |

## Google Play libraries in the app

- **Play Billing** (premium, a one-time purchase). The app talks to the Play Store app on the
  phone over IPC. Google Play processes the payment under Google's own privacy policy, and
  Lumement never sees card or account details. The library's telemetry module
  (`com.google.android.datatransport`) is excluded from the build, and that's what keeps
  `INTERNET` out. Lumement keeps only whether premium is owned.
- **Play In-App Review**: the system's review card, also through the Play Store app.

Play's Data safety help ("Provide information for Google Play's Data safety section", read
2026-10-18) defines collecting as "transmitting data from your app off a user's device". Data
only processed locally on the device isn't declared. Data that a payment service such as Google
Play's billing system collects directly from the user, and that the app never accesses, isn't
declared either. Lumement fits both: payment details never reach it, and the one thing it learns
from Play, whether premium is owned, stays in its private storage and is never sent anywhere.
If a reviewer disagrees, the place it would land is **Financial info → Purchase history**,
collected, not shared, required for app functionality. Re-read the help page before submitting,
because it changes.

## Permissions to expect questions about

`WAKE_LOCK`, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT` (its own declaration:
`full-screen-intent-declaration.md`), the notification listener (granted by the user in
Settings), and the accessibility service (its own declaration: `accessibility-declaration.md`).
None of them collects data.
