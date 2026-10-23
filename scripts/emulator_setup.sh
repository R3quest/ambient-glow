#!/bin/sh
# Sets up an emulator for `make scenario` and `make edge`, as a user would have it: the debug build
# installed, a PIN lock screen (1234), notification access and Lumement effects on, notifications
# (Android 13+) and full-screen intents (14+) granted, and unlocked once since boot (the app's data
# is readable only then). Emulators only: it sets a PIN, so it refuses a real phone.
#
#   ADB=adb ANDROID_SERIAL=emulator-5554 scripts/emulator_setup.sh
set -eu

ADB=${ADB:-adb}
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
PKG=app.lumement
PIN=1234

sh_() { "$ADB" shell "$@" | tr -d '\r'; }

if [ "$(sh_ getprop ro.kernel.qemu)" != 1 ] && [ "$(sh_ getprop ro.boot.qemu)" != 1 ]; then
    echo "emulator_setup: ${ANDROID_SERIAL:-the device} isn't an emulator; it would set a PIN on it" >&2
    exit 2
fi
sdk=$(sh_ getprop ro.build.version.sdk)

"$ADB" install -r "$APK" >/dev/null
sh_ "locksettings set-pin $PIN >/dev/null 2>&1 || true"

listener=$PKG/$PKG.NotificationWakerService
if sh_ "cmd notification help 2>&1" | grep -q allow_listener; then
    sh_ "cmd notification allow_listener $listener"
else
    # Android 8: no cmd notification; the setting itself (kept with what is there).
    current=$(sh_ settings get secure enabled_notification_listeners)
    case "$current" in
        *"$listener"*) ;;
        null | "") sh_ settings put secure enabled_notification_listeners "$listener" ;;
        *) sh_ settings put secure enabled_notification_listeners "$current:$listener" ;;
    esac
fi
sh_ settings put secure enabled_accessibility_services "$PKG/$PKG.GlowShield"
sh_ settings put secure accessibility_enabled 1
# Emulators stay awake on the charger (a developer option, on in CI's): a phone's lock screen times out.
sh_ settings put global stay_on_while_plugged_in 0
if [ "$sdk" -ge 33 ]; then sh_ pm grant $PKG android.permission.POST_NOTIFICATIONS; fi
if [ "$sdk" -ge 34 ]; then sh_ appops set $PKG USE_FULL_SCREEN_INTENT allow; fi

# Unlocked once: the app's files are readable to run-as only after the first unlock since boot.
sh_ "input keyevent KEYCODE_WAKEUP; sleep 1; wm dismiss-keyguard; sleep 1; input text $PIN; input keyevent KEYCODE_ENTER; sleep 2"
sh_ "input keyevent KEYCODE_SLEEP"
echo "emulator_setup: ${ANDROID_SERIAL:-emulator} (Android API $sdk) ready"
