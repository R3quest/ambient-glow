#!/usr/bin/env python3
"""make edge: Lumement's edge cases on emulators, run in parallel, one thread per device.

    ADB=adb scripts/edge.py [serial ...] [--only case1,case2] [--skip case3]
    scripts/edge.py --selftest

With no serial, every connected emulator. Each is set up first (scripts/emulator_setup.sh).
Android 8 and 9 can't post adb's test messages (only the monkey runs there); Android 10 has no
`cmd notification list`, `snooze` or `set_dnd` (its cases that need them are skipped or limited).

Each case starts from a clean, locked, dark phone with no test messages, runs, checks its own
expectations, then the common ones: no crash/ANR/death of app.lumement in the log, and once its
messages are read, no Lumement wake lock left and the display back to OFF/DOZE.
"""
import os
import re
import subprocess
import sys
import threading
import time

ADB = os.environ.get("ADB") or os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk")), "platform-tools", "adb")
PKG = "app.lumement"
SNOOZE = 315360000000
PIN = "1234"


class Dev:
    def __init__(self, serial):
        self.serial = serial
        self.sdk = int(self.sh("getprop ro.build.version.sdk").strip() or 0)
        helptext = self.sh("cmd notification help 2>&1")
        self.posts = " post" in helptext
        self.lists = " list" in helptext
        self.dnd = "set_dnd" in helptext
        self.tags = []

    def sh(self, cmd, timeout=120):
        try:
            r = subprocess.run([ADB, "-s", self.serial, "shell", cmd], capture_output=True, text=True, timeout=timeout)
            return r.stdout
        except subprocess.TimeoutExpired:
            return ""

    def emu(self, *args):
        subprocess.run([ADB, "-s", self.serial, "emu", *args], capture_output=True, text=True, timeout=30)

    # ---- the phone ----
    def screen(self):
        m = re.search(r"mScreenState=(\w+)", self.sh("dumpsys display | grep -m1 mScreenState"))
        return m.group(1) if m else "?"

    def locks(self):
        out = self.sh("dumpsys power")
        held, inside = [], False
        for line in out.splitlines():
            if line.strip().startswith("Wake Locks: size="):
                inside = True
                continue
            if inside:
                if not line.startswith("  ") or not line.strip():
                    break
                m = re.search(r"'(Lumement:[^']+)'", line)
                if m:
                    held.append(m.group(1))
        return held

    def sleep_phone(self):
        self.sh("input keyevent KEYCODE_SLEEP")

    def wake(self):
        self.sh("input keyevent KEYCODE_WAKEUP")

    def keyguard_up(self):
        """The keyguard showing, occluded (the glow screen over it) or not; False once unlocked."""
        m = re.search(r"\bshowing=(\w+)", self.sh("dumpsys window policy"))
        return m is None or m.group(1) == "true"

    def unlock(self):
        """Unlocked with the PIN, as the user does: a tap first (on the LED, its face or window, it
        opens the lock screen, which then takes the PIN; on the lock screen it does nothing). A slow
        emulator may drop the PIN while its bouncer comes up: tried again, up to three times.
        Raises if it never unlocks, so a case fails as the test's, not the app's."""
        for _ in range(3):
            self.sh(f"input keyevent KEYCODE_WAKEUP; sleep 0.8; input tap 540 1300; sleep 1.5; wm dismiss-keyguard; sleep 2; input text {PIN}; input keyevent KEYCODE_ENTER")
            if wait(lambda: not self.keyguard_up(), 8):
                time.sleep(1)
                return
            self.sleep_phone()
            time.sleep(2)
        raise RuntimeError("the test couldn't unlock the phone")

    def post(self, tag, text="edge case"):
        # Unique per post: a key once snoozed (read) snoozes every later post under it.
        tag = f"{tag}-{time.time_ns() // 1_000_000 % 10**9}"
        self.tags.append(tag)
        self.sh(f"cmd notification post -t Edge {tag} '{text}' >/dev/null")

    def read(self, tag):
        self.sh(f"cmd notification snooze --for {SNOOZE} '0|com.android.shell|2020|{tag}|2000' >/dev/null 2>&1")

    def read_all(self):
        if not self.lists:
            # Android 10: no list or snooze; the shell cancels its own (INotificationManager.cancelAllNotifications).
            self.sh("service call notification 1 s16 com.android.shell i32 0 >/dev/null")
        # adb's test messages, and the app's own (its dashboard's test button: a monkey presses it).
        for line in self.sh("cmd notification list").splitlines():
            if "|com.android.shell|2020|" in line or re.search(r"\|app\.lumement\|265[89]\|", line):
                key = line.strip()
                self.sh(f"cmd notification snooze --for {SNOOZE} '{key}' >/dev/null 2>&1")
        # And whatever else Lumement waits on (a missed call from the call case): its own list.
        import base64
        prefs = self.sh(f"run-as {PKG} cat shared_prefs/lumement_pending.xml")
        m = re.search(r'name="pending">([^<]*)', prefs)
        if m:
            for entry in m.group(1).split("&#10;"):
                raw = entry.split(",")[0]
                try:
                    key = base64.b64decode(raw + "=" * (-len(raw) % 4), altchars=b"-_").decode()
                except Exception:
                    continue
                self.sh(f"cmd notification snooze --for {SNOOZE} '{key}' >/dev/null 2>&1")
        self.tags = []

    def log(self):
        return self.sh("logcat -d -v time", timeout=60)

    def lum(self, log):
        return [l for l in log.splitlines() if "D/Lumement" in l]

    def pid(self):
        return self.sh(f"pidof {PKG}").strip()


def problems(log, allow_death=False):
    out = []
    lines = log.splitlines()
    for i, l in enumerate(lines):
        if "FATAL EXCEPTION" in l and any(PKG in x for x in lines[i:i + 3]):
            out.append("crash: " + " | ".join(x.strip() for x in lines[i:i + 6]))
        elif re.search(rf"ANR in {re.escape(PKG)}\b", l):
            out.append("ANR: " + l.strip())
        elif not allow_death and re.search(rf"Process {re.escape(PKG)} \(pid \d+\) has died", l):
            out.append("died: " + l.strip())
    return out


def wait(cond, seconds, step=1.0):
    end = time.time() + seconds
    while time.time() < end:
        if cond():
            return True
        time.sleep(step)
    return cond()


# ---- cases: each returns a list of failures (strings) ----

def led_up(d):
    return "Lumement:led" in d.locks() and d.screen() == "ON"


def message_while_locked(d, tag):
    d.sleep_phone()
    time.sleep(1.5)
    d.post(tag)


def c_burst(d):
    """Eight messages within a second, the phone locked: one glow screen, the LED lit, then all read."""
    f = []
    d.sleep_phone()
    time.sleep(1.5)
    for i in range(8):
        d.post(f"burst{i}")
    if not wait(lambda: led_up(d), 15):
        f.append(f"LED not lit after the burst (screen={d.screen()} locks={d.locks()})")
    creates = sum("act onCreate" in l for l in d.lum(d.log()))
    if creates > 2:
        f.append(f"{creates} glow screens created for one burst")
    return f


def c_in_use_then_lock(d):
    """A message on the unlocked phone in use: no glow screen; then locked: the LED lights."""
    f = []
    d.unlock()
    d.post("inuse")
    time.sleep(4)
    if any("act onCreate" in l for l in d.lum(d.log())):
        f.append("a glow screen came over the phone in use")
    d.sleep_phone()
    if not wait(lambda: led_up(d), 15):
        f.append(f"LED not lit after locking (screen={d.screen()} locks={d.locks()})")
    return f


def c_dnd(d):
    """Do Not Disturb on: a message neither wakes nor lights; a message waiting goes dark under it, and back after."""
    if not d.dnd:
        return None
    f = []
    d.sleep_phone()
    time.sleep(1.5)
    d.sh("cmd notification set_dnd priority")
    time.sleep(1)
    d.post("dnd1")
    time.sleep(5)
    if d.screen() == "ON" or "Lumement:led" in d.locks():
        f.append(f"woke or lit in Do Not Disturb (screen={d.screen()} locks={d.locks()})")
    # What it held back shows once it ends: the LED, no effect.
    d.sh("logcat -c; cmd notification set_dnd off")
    if not wait(lambda: led_up(d), 15):
        f.append(f"a message held back by Do Not Disturb: no LED once it ended (screen={d.screen()} locks={d.locks()})")
    elif played_arrival(d):
        f.append("a message held back by Do Not Disturb played an arrival once it ended")
    d.read_all()
    wait(lambda: "Lumement:led" not in d.locks(), 15)
    message_while_locked(d, "dnd2")
    if not wait(lambda: led_up(d), 15):
        f.append("LED not lit before Do Not Disturb")
    d.sh("cmd notification set_dnd priority")
    if not wait(lambda: "Lumement:led" not in d.locks(), 10):
        f.append(f"LED still holding the screen in Do Not Disturb (locks={d.locks()})")
    d.sh("cmd notification set_dnd off")
    return f


def c_unlock_while_lit(d):
    """Unlocked with the LED lit: the glow screen steps aside and lets go of the screen."""
    f = []
    message_while_locked(d, "unlock1")
    if not wait(lambda: led_up(d), 15):
        return ["LED not lit"]
    d.unlock()
    if not wait(lambda: "Lumement:led" not in d.locks(), 8):
        f.append(f"screen still held over the unlocked phone (locks={d.locks()})")
    return f


def c_power_while_lit(d):
    """Power with the LED lit: the lock screen, then the screen off at its own timeout (or the LED back)."""
    f = []
    message_while_locked(d, "power1")
    if not wait(lambda: led_up(d), 15):
        return ["LED not lit"]
    d.sh("input keyevent KEYCODE_POWER")
    # A slow emulator takes up to 3 s to finish the sleep the lock screen is lit after.
    if not wait(lambda: d.screen() == "ON" and "Lumement:led" not in d.locks(), 10):
        f.append(f"power on the LED didn't open the lock screen (screen={d.screen()} locks={d.locks()})")
    return f


def fresh_message_lights(d, tag):
    """All read, the phone dark, then a new message: the LED lights. Proves the listener and flow work."""
    d.read_all()
    time.sleep(3)
    d.sleep_phone()
    wait(lambda: d.screen() in ("OFF", "DOZE", "DOZE_SUSPEND") and "Lumement:led" not in d.locks(), 20)
    d.post(tag)
    return wait(lambda: led_up(d), 15)


def kill_app(d):
    """kill -9 as root (run-as can't see the app's process on every version), then the shell user again."""
    pid = d.pid()
    subprocess.run([ADB, "-s", d.serial, "root"], capture_output=True, timeout=30)
    subprocess.run([ADB, "-s", d.serial, "wait-for-device"], capture_output=True, timeout=60)
    time.sleep(2)
    d.sh(f"kill -9 {pid}")
    subprocess.run([ADB, "-s", d.serial, "unroot"], capture_output=True, timeout=30)
    time.sleep(3)
    subprocess.run([ADB, "-s", d.serial, "wait-for-device"], capture_output=True, timeout=60)
    time.sleep(2)
    return pid


def c_process_death(d):
    """The process dies with a message waiting: it comes back with the waiting message and its bright phase, so the
    LED is back at the next sleep; and a new message lights it."""
    f = []
    message_while_locked(d, "death1")
    if not wait(lambda: led_up(d), 15):
        return ["LED not lit"]
    before = kill_app(d)
    if not wait(lambda: d.pid() not in ("", before), 60, step=2):
        f.append(f"process not back within 60 s (pid {d.pid() or 'none'}, was {before})")
    elif not wait(lambda: any("svc restored" in l for l in d.lum(d.log())), 30, step=2):
        f.append("the listener didn't restore the waiting message")
    else:
        d.sleep_phone()
        if not wait(lambda: led_up(d), 15):
            f.append(f"LED not back in its bright phase after the process died (screen={d.screen()} locks={d.locks()})")
    if not fresh_message_lights(d, "death2"):
        f.append(f"no LED for a new message after the process came back (screen={d.screen()} locks={d.locks()})")
    return f


def c_low_battery(d):
    """Below 20 %: no screen held for the LED."""
    f = []
    # The emulator's own battery: Lumement reads the capacity off it, which `dumpsys battery set` doesn't change.
    d.emu("power", "ac", "off")
    d.emu("power", "capacity", "15")
    try:
        message_while_locked(d, "lowbat")
        time.sleep(12)
        if "Lumement:led" in d.locks():
            f.append(f"LED holds the screen at 15 % (locks={d.locks()})")
    finally:
        d.emu("power", "capacity", "100")
        d.emu("power", "ac", "on")
    return f


def c_battery_saver(d):
    """Battery Saver: no screen held for the LED; off again, the next message lights it."""
    f = []
    # Battery Saver can't come on while charging.
    d.emu("power", "ac", "off")
    # The unplug takes a moment to reach the system: asked again until it holds.
    if not wait(lambda: "currently: ON" in d.sh("cmd power set-mode 1 2>/dev/null; settings put global low_power 1; sleep 0.5; dumpsys power | grep 'Battery Saver is currently'"), 10):
        d.emu("power", "ac", "on")
        return ["couldn't turn Battery Saver on"]
    try:
        message_while_locked(d, "saver")
        time.sleep(12)
        if "Lumement:led" in d.locks():
            f.append(f"LED holds the screen in Battery Saver (locks={d.locks()})")
    finally:
        d.sh("cmd power set-mode 0 2>/dev/null; settings put global low_power 0")
        d.emu("power", "ac", "on")
    return f


def c_call(d):
    """A call rings over the lit LED: it makes way; the call ends: the LED is back or the lock screen sleeps."""
    f = []
    message_while_locked(d, "call1")
    if not wait(lambda: led_up(d), 15):
        return ["LED not lit"]
    d.emu("gsm", "call", "5551234")
    time.sleep(5)
    log = d.log()
    if not any(("making way" in l or "LED window making way" in l or "showLockScreen" in l or "onPause" in l) for l in d.lum(log)):
        f.append("nothing made way for the ringing call")
    end_calls(d)
    time.sleep(4)
    return f


def c_landscape(d):
    """In use in landscape, then locked: the LED lights upright, nothing breaks."""
    f = []
    d.unlock()
    d.sh("settings put system accelerometer_rotation 0; settings put system user_rotation 1")
    try:
        time.sleep(1.5)
        d.post("land1")
        time.sleep(2)
        d.sleep_phone()
        if not wait(lambda: led_up(d), 15):
            f.append(f"LED not lit after locking in landscape (screen={d.screen()})")
    finally:
        d.sh("settings put system user_rotation 0")
    return f


def c_shield_off(d):
    """GlowShield turned off with the LED lit: the LED's face takes over, nothing crashes; on again."""
    f = []
    message_while_locked(d, "shield1")
    if not wait(lambda: led_up(d), 15):
        return ["LED not lit"]
    d.sh("settings put secure enabled_accessibility_services ''")
    time.sleep(4)
    if d.screen() == "ON" and "Lumement:led" not in d.locks():
        f.append("screen on, nothing holding it for the LED after the shield went")
    d.sh(f"settings put secure enabled_accessibility_services {PKG}/{PKG}.GlowShield; settings put secure accessibility_enabled 1")
    time.sleep(2)
    return f


def c_listener_off(d):
    """Listener access off and on again with a message waiting: no crash, the LED comes back."""
    f = []
    comp = f"{PKG}/{PKG}.NotificationWakerService"
    message_while_locked(d, "lis1")
    if not wait(lambda: led_up(d), 15):
        return ["LED not lit"]
    d.sh(f"cmd notification disallow_listener {comp}")
    time.sleep(3)
    d.sh(f"cmd notification allow_listener {comp}")
    time.sleep(3)
    if not fresh_message_lights(d, "lis2"):
        f.append(f"no LED for a new message after the listener returned (screen={d.screen()} locks={d.locks()})")
    return f


def c_no_post_permission(d):
    """Android 13+: notifications permission revoked; a message still brings the LED (GlowShield's dark start)."""
    if d.sdk < 33:
        return None
    f = []
    d.sh(f"pm revoke {PKG} android.permission.POST_NOTIFICATIONS")
    # A revoke kills the process: the listener has to be back before the message.
    wait(lambda: f"{PKG}/{PKG}.NotificationWakerService}} (user 0)" in d.sh("dumpsys notification"), 30, step=2)
    time.sleep(2)
    try:
        message_while_locked(d, "noperm")
        if not wait(lambda: led_up(d), 15):
            f.append(f"no LED without the notifications permission (screen={d.screen()} locks={d.locks()})")
    finally:
        d.sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    return f


def c_no_full_screen(d):
    """Android 14+: no full-screen grant, as on every Play install. Setup's alerts step passes and it asks
    for the full-screen wake (it once asked for alerts again and again); a message still brings the LED."""
    if d.sdk < 34:
        return None
    f = []
    d.sh(f"appops set {PKG} USE_FULL_SCREEN_INTENT deny")
    try:
        d.unlock()
        d.sh(f"am start -n {PKG}/.MainActivity >/dev/null")
        time.sleep(4)
        screen = d.sh("uiautomator dump /sdcard/lumement-ui.xml >/dev/null 2>&1; cat /sdcard/lumement-ui.xml; rm -f /sdcard/lumement-ui.xml")
        if "Full-screen wake" not in screen:
            f.append("setup without the full-screen grant doesn't ask for it" + (" (asks for alerts)" if "Wake alerts" in screen else ""))
        d.sh("input keyevent KEYCODE_HOME")
        message_while_locked(d, "nofsi")
        if not wait(lambda: led_up(d), 15):
            f.append(f"no LED without the full-screen grant (screen={d.screen()} locks={d.locks()})")
    finally:
        d.sh(f"appops set {PKG} USE_FULL_SCREEN_INTENT allow")
    return f


def c_big_text(d):
    """Font and display size at their largest: the dashboard and the LED still come up."""
    f = []
    d.sh("settings put system font_scale 2.0; wm density 560")
    try:
        d.unlock()
        d.sh(f"am start -n {PKG}/.MainActivity >/dev/null")
        time.sleep(5)
        d.sh("input swipe 540 1600 540 600 300; input swipe 540 1600 540 600 300")
        time.sleep(2)
        message_while_locked(d, "big1")
        if not wait(lambda: led_up(d), 15):
            f.append("LED not lit at the largest sizes")
    finally:
        d.sh("settings put system font_scale 1.0; wm density reset")
    return f


def c_monkey(d):
    """Random taps and swipes on the dashboard: no crash, no ANR."""
    f = []
    d.unlock()
    d.sh(f"am start -n {PKG}/.MainActivity >/dev/null")
    time.sleep(3)
    out = d.sh(f"monkey -p {PKG} --pct-syskeys 0 --pct-appswitch 0 --throttle 40 -s 7 1500 2>&1", timeout=300)
    if "// CRASH" in out or "NOT RESPONDING" in out or "Events injected: 1500" not in out:
        f.append("monkey: " + " | ".join(l for l in out.splitlines() if "CRASH" in l or "NOT RESPONDING" in l or "injected" in l)[:400])
    d.sh("input keyevent KEYCODE_HOME")
    return f


def c_lock_screen_awake(d):
    """A message with the lock screen lit (woken, not unlocked): the glow screen comes over it, then the LED."""
    f = []
    d.sleep_phone()
    time.sleep(1.5)
    d.wake()
    time.sleep(1.5)
    d.post("awake1")
    if not wait(lambda: led_up(d), 15):
        f.append(f"LED not lit for a message over the lit lock screen (screen={d.screen()} locks={d.locks()})")
    return f


def c_lock_delay(d):
    """A message in the lock delay (the screen timed out, the phone locks 5 s later): no wake into the
    unlocked phone; the LED once it has locked."""
    f = []
    timeout = d.sh("settings get system screen_off_timeout").strip()
    delay = d.sh("settings get secure lock_screen_lock_after_timeout").strip()
    d.sh("settings put system screen_off_timeout 10000; settings put secure lock_screen_lock_after_timeout 5000")
    try:
        d.unlock()
        if not wait(lambda: d.screen() != "ON", 40):
            return ["the screen never timed out"]
        time.sleep(1)
        if d.keyguard_up():
            return None  # this Android locked at once: nothing to test
        d.post("delay1")
        woke = wait(lambda: d.screen() == "ON" and not d.keyguard_up(), 3, step=0.3)
        if woke:
            f.append("woke into the unlocked phone in the lock delay")
        if not wait(lambda: led_up(d) and d.keyguard_up(), 20):
            f.append(f"LED not lit once locked (screen={d.screen()} locks={d.locks()} keyguard={d.keyguard_up()})")
    finally:
        d.sh(f"settings put system screen_off_timeout {timeout if timeout != 'null' else 60000}")
        if delay in ("", "null"):
            d.sh("settings delete secure lock_screen_lock_after_timeout")
        else:
            d.sh(f"settings put secure lock_screen_lock_after_timeout {delay}")
    return f


def played_arrival(d):
    return any("arrival up" in l or "act arrival done" in l for l in d.lum(d.log()))


def c_long_lock_delay(d):
    """A message in a lock delay too long to hold the CPU for (35 s here; a minute or more on phones): once
    it has locked, the LED for it, no effect (it once stayed dark until the next wake). Its look is an
    inexact alarm, which Android may run up to 3/4 of its lead late."""
    f = []
    timeout = d.sh("settings get system screen_off_timeout").strip()
    delay = d.sh("settings get secure lock_screen_lock_after_timeout").strip()
    d.sh("settings put system screen_off_timeout 10000; settings put secure lock_screen_lock_after_timeout 35000")
    try:
        d.unlock()
        if not wait(lambda: d.screen() != "ON", 40):
            return ["the screen never timed out"]
        time.sleep(1)
        if d.keyguard_up():
            return None  # this Android locked at once: nothing to test
        d.sh("logcat -c")
        d.post("longdelay1")
        if wait(lambda: d.screen() == "ON" and not d.keyguard_up(), 3, step=0.3):
            f.append("woke into the unlocked phone in the lock delay")
        if not wait(lambda: led_up(d) and d.keyguard_up(), 90, step=2):
            f.append(f"LED not lit once locked (screen={d.screen()} locks={d.locks()} keyguard={d.keyguard_up()})")
        elif played_arrival(d):
            f.append("an arrival played long after the message")
    finally:
        d.sh(f"settings put system screen_off_timeout {timeout if timeout != 'null' else 60000}")
        if delay in ("", "null"):
            d.sh("settings delete secure lock_screen_lock_after_timeout")
        else:
            d.sh(f"settings put secure lock_screen_lock_after_timeout {delay}")
    return f


def c_gone_at_once(d):
    """A message read elsewhere a moment after it came (the glow screen still starting): nothing left lit or held."""
    d.sleep_phone()
    time.sleep(1.5)
    d.post("gone1")
    time.sleep(0.7)
    d.read_all()
    # The common checks after the case say whether everything let go.
    time.sleep(3)
    return []


def c_lock_cycles(d):
    """Unlocked and locked again and again with a message waiting: an unlock has seen it (the bright
    phase over), so nothing is held after the last lock; one glow screen at most; a new message lights it."""
    f = []
    message_while_locked(d, "cycle1")
    if not wait(lambda: led_up(d), 15):
        return ["LED not lit"]
    for _ in range(4):
        d.unlock()
        time.sleep(1)
        d.sleep_phone()
        time.sleep(2.5)
    if not wait(lambda: "Lumement:led" not in d.locks() and d.screen() != "ON", 20):
        f.append(f"screen held after the last lock, the message seen (screen={d.screen()} locks={d.locks()})")
    # The stack's own entries ("Hist #n"): a record is also named elsewhere, finished ones included.
    live = set(re.findall(r"Hist +#\d+: ActivityRecord\{(\w+) u\d+ app\.lumement/\.WakeScreenActivity", d.sh("dumpsys activity activities")))
    if len(live) > 1:
        f.append(f"{len(live)} glow screens alive")
    d.post("cycle2")
    if not wait(lambda: led_up(d), 15):
        f.append(f"LED not lit for a new message after the cycles (screen={d.screen()} locks={d.locks()})")
    return f


def c_config_while_lit(d):
    """Night mode, font and display size changed with the LED lit: it stays lit, nothing breaks."""
    f = []
    message_while_locked(d, "conf1")
    if not wait(lambda: led_up(d), 15):
        return ["LED not lit"]
    try:
        d.sh("cmd uimode night yes")
        time.sleep(2)
        d.sh("settings put system font_scale 1.3")
        time.sleep(2)
        d.sh("wm density 480")
        time.sleep(3)
        if not led_up(d):
            f.append(f"LED gone after the configuration changes (screen={d.screen()} locks={d.locks()})")
    finally:
        d.sh("cmd uimode night no; settings put system font_scale 1.0; wm density reset")
    time.sleep(2)
    return f


def c_trim_memory(d):
    """The system short of memory with the LED lit: it stays lit."""
    f = []
    message_while_locked(d, "trim1")
    if not wait(lambda: led_up(d), 15):
        return ["LED not lit"]
    for level in ("RUNNING_CRITICAL", "COMPLETE"):
        d.sh(f"am send-trim-memory {PKG} {level} 2>&1")
        time.sleep(2)
    if not led_up(d):
        f.append(f"LED gone after trim-memory (screen={d.screen()} locks={d.locks()})")
    return f


def c_pocket(d):
    """Covered with the LED lit (proximity near): the panel off, then the CPU let go; uncovered: the LED is back."""
    f = []
    message_while_locked(d, "pocket1")
    if not wait(lambda: led_up(d), 15):
        return ["LED not lit"]
    time.sleep(6)  # the arrival over
    try:
        d.emu("sensor", "set", "proximity", "0")
        if not wait(lambda: d.screen() != "ON", 6):
            f.append(f"panel still on with the phone covered (locks={d.locks()})")
        # COVERED_MS (10 s): put away, the CPU free.
        if not wait(lambda: "Lumement:led" not in d.locks(), 20):
            f.append(f"still holding on, covered for 20 s (locks={d.locks()})")
    finally:
        d.emu("sensor", "set", "proximity", "5")
    if not wait(lambda: led_up(d), 10):
        f.append(f"LED not back when uncovered (screen={d.screen()} locks={d.locks()})")
    return f


def c_face_down(d):
    """Laid face down with the LED lit: the phone sleeps; turned over: the LED is back. Only where there's
    a tilt detector (the app watches face down only with one; emulators have none)."""
    if "tilt_detector" not in d.sh("dumpsys sensorservice | grep -i tilt"):
        return None
    f = []
    message_while_locked(d, "face1")
    if not wait(lambda: led_up(d), 15):
        return ["LED not lit"]
    time.sleep(6)
    try:
        d.emu("sensor", "set", "acceleration", "0:0:-9.8")
        if not wait(lambda: d.screen() != "ON" and "Lumement:led" not in d.locks(), 15):
            f.append(f"still lit face down (screen={d.screen()} locks={d.locks()})")
    finally:
        d.emu("sensor", "set", "acceleration", "0:9.8:0.5")
    if not wait(lambda: led_up(d), 10):
        f.append(f"LED not back when turned over (screen={d.screen()} locks={d.locks()})")
    return f


def c_doze(d):
    """Deep Doze (unplugged, still, dark for a long time): a message still lights the LED."""
    f = []
    d.emu("power", "ac", "off")
    d.sleep_phone()
    time.sleep(2)
    out = d.sh("dumpsys deviceidle force-idle deep 2>&1 || dumpsys deviceidle force-idle 2>&1")
    try:
        if "IDLE" not in out.upper():
            return [f"couldn't force Doze: {out.strip()[:120]}"]
        d.post("doze1")
        if not wait(lambda: led_up(d), 15):
            f.append(f"no LED for a message in Doze (screen={d.screen()} locks={d.locks()})")
    finally:
        d.sh("dumpsys deviceidle unforce")
        d.emu("power", "ac", "on")
    return f


def c_background_restricted(d):
    """Battery set to Restricted for Lumement (and, on 12+, the restricted standby bucket): a message still lights it."""
    f = []
    d.sh(f"appops set {PKG} RUN_ANY_IN_BACKGROUND ignore")
    if d.sdk >= 31:
        d.sh(f"am set-standby-bucket {PKG} restricted")
    try:
        time.sleep(2)
        message_while_locked(d, "restr1")
        if not wait(lambda: led_up(d), 15):
            f.append(f"no LED with the battery Restricted (screen={d.screen()} locks={d.locks()})")
    finally:
        d.sh(f"appops set {PKG} RUN_ANY_IN_BACKGROUND allow")
        if d.sdk >= 31:
            d.sh(f"am set-standby-bucket {PKG} active")
    return f


def c_force_stop(d):
    """Force stop (App info) with a message waiting: the listener and GlowShield come back, or say so."""
    f = []
    message_while_locked(d, "stop1")
    if not wait(lambda: led_up(d), 15):
        return ["LED not lit"]
    d.sh(f"am force-stop {PKG}")
    time.sleep(3)
    if "Lumement:led" in d.locks():
        f.append("a wake lock outlived the force stop")
    if not fresh_message_lights(d, "stop2"):
        bound = f"{PKG}/{PKG}.NotificationWakerService" in d.sh("dumpsys notification | grep -A3 'Live notification listeners'")
        f.append(f"no LED for a new message after a force stop (listener bound={bound}, pid={d.pid() or 'none'})")
    return f


def c_app_update(d):
    """The app updated with the LED lit: the listener is back and the waiting message lights the LED again."""
    f = []
    message_while_locked(d, "upd1")
    if not wait(lambda: led_up(d), 15):
        return ["LED not lit"]
    apk = os.environ.get("APK", "app/build/outputs/apk/debug/app-debug.apk")
    r = subprocess.run([ADB, "-s", d.serial, "install", "-r", apk], capture_output=True, text=True, timeout=180)
    if "Success" not in r.stdout:
        return [f"install failed: {(r.stdout + r.stderr).strip()[:200]}"]
    # The old process's window went with it: the lock screen until the phone sleeps, then the LED.
    wait(lambda: any("svc restored" in l for l in d.lum(d.log())), 30, step=2)
    d.sleep_phone()
    if not wait(lambda: led_up(d), 30, step=2):
        f.append(f"LED not back after the update (screen={d.screen()} locks={d.locks()} pid={d.pid() or 'none'})")
    return f


def set_pref(d, key, value):
    """One of the app's settings (shared_prefs/lumement.xml) to [value], or out with None; the process
    killed so it reads them afresh. Returns the old value."""
    path = "shared_prefs/lumement.xml"
    xml = d.sh(f"run-as {PKG} cat {path}")
    if "<map" not in xml:
        xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
    m = re.search(rf'\s*<string name="{key}">([^<]*)</string>', xml)
    old = m.group(1) if m else None
    if m:
        xml = xml.replace(m.group(0), "")
    if value is not None:
        xml = xml.replace("</map>", f'    <string name="{key}">{value}</string>\n</map>')
    subprocess.run([ADB, "-s", d.serial, "shell", f"run-as {PKG} sh -c 'cat > {path}'"], input=xml, text=True, capture_output=True, timeout=30)
    kill_app(d)
    wait(lambda: d.pid() != "", 60, step=2)
    wait(lambda: f"{PKG}/{PKG}.NotificationWakerService" in d.sh("dumpsys notification | grep -A3 'Live notification listeners'"), 30, step=2)
    time.sleep(3)
    return old


def c_bright_off(d):
    """No bright minutes: a message plays, then the LED makes way at once (no screen held), and the next one plays again."""
    f = []
    old = set_pref(d, "bright_for", "OFF")
    try:
        message_while_locked(d, "boff1")
        time.sleep(3)
        if not wait(lambda: "Lumement:led" not in d.locks() and d.screen() != "ON", 40):
            f.append(f"screen still held with no bright minutes (screen={d.screen()} locks={d.locks()})")
        message_while_locked(d, "boff2")
        if not wait(lambda: d.screen() == "ON", 10):
            f.append("a second message didn't wake the phone")
    finally:
        set_pref(d, "bright_for", old)
    return f


def c_bright_cap(d):
    """Five bright minutes: past them, the LED lets go of the screen (the battery's guard). Slow: about 6 minutes."""
    f = []
    old = set_pref(d, "bright_for", "FIVE")
    try:
        message_while_locked(d, "bcap1")
        if not wait(lambda: led_up(d), 15):
            return ["LED not lit"]
        time.sleep(5 * 60 - 15)
        if not led_up(d):
            f.append(f"LED out before its five minutes (screen={d.screen()} locks={d.locks()})")
        if not wait(lambda: "Lumement:led" not in d.locks() and d.screen() != "ON", 60):
            f.append(f"screen still held a minute past the five (screen={d.screen()} locks={d.locks()})")
    finally:
        set_pref(d, "bright_for", old)
    return f


def c_reboot(d):
    """Rebooted: nothing crashes before the first unlock; once unlocked and locked again, a message lights the LED."""
    f = []
    subprocess.run([ADB, "-s", d.serial, "reboot"], capture_output=True, timeout=60)
    time.sleep(10)
    subprocess.run([ADB, "-s", d.serial, "wait-for-device"], capture_output=True, timeout=300)
    wait(lambda: d.sh("getprop sys.boot_completed").strip() == "1", 240, step=3)
    time.sleep(15)
    # Not unlocked yet: Lumement's files are locked away; a message now finds no listener (Android's rule).
    d.post("boot0")
    time.sleep(8)
    f += [p + " (before the first unlock)" for p in problems(d.log(), allow_death=True)]
    d.read_all()
    d.unlock()
    time.sleep(3)
    if not fresh_message_lights(d, "boot1"):
        f.append(f"no LED for a message after the reboot (screen={d.screen()} locks={d.locks()})")
    return f


CASES = [
    ("burst", c_burst), ("in-use-then-lock", c_in_use_then_lock), ("dnd", c_dnd),
    ("unlock-while-lit", c_unlock_while_lit), ("power-while-lit", c_power_while_lit),
    ("process-death", c_process_death), ("low-battery", c_low_battery), ("battery-saver", c_battery_saver),
    ("call", c_call), ("landscape", c_landscape), ("shield-off", c_shield_off), ("listener-off", c_listener_off),
    ("no-post-permission", c_no_post_permission), ("no-full-screen", c_no_full_screen), ("big-text", c_big_text), ("monkey", c_monkey),
    ("lock-screen-awake", c_lock_screen_awake), ("lock-delay", c_lock_delay), ("long-lock-delay", c_long_lock_delay), ("gone-at-once", c_gone_at_once), ("lock-cycles", c_lock_cycles),
    ("config-while-lit", c_config_while_lit), ("trim-memory", c_trim_memory), ("pocket", c_pocket),
    ("face-down", c_face_down), ("doze", c_doze), ("background-restricted", c_background_restricted),
    ("force-stop", c_force_stop), ("app-update", c_app_update), ("bright-off", c_bright_off),
    ("bright-cap", c_bright_cap), ("reboot", c_reboot),
]

# The process dies in these by design (killed, stopped, replaced, rebooted).
DIES = {"process-death", "force-stop", "app-update", "bright-off", "bright-cap", "reboot"}


def end_calls(d):
    """The simulated call over, answered or not: a call left up keeps the LED dark (rightly), and every case after fails."""
    d.emu("gsm", "cancel", "5551234")
    d.sh("input keyevent KEYCODE_ENDCALL")
    wait(lambda: "mCallState=0" in d.sh("dumpsys telephony.registry | grep -m1 mCallState"), 10)


def clean(d):
    end_calls(d)
    d.read_all()
    d.sh("cmd notification set_dnd off; cmd power set-mode 0 2>/dev/null; settings put global low_power 0")
    d.emu("power", "ac", "on")
    d.emu("power", "capacity", "100")
    d.sleep_phone()
    wait(lambda: "Lumement:led" not in d.locks() and d.screen() in ("OFF", "DOZE", "DOZE_SUSPEND"), 20)
    d.sh("logcat -c")


def run(d, only, results):
    for name, case in CASES:
        if only and name not in only:
            continue
        needs_post = name not in ("monkey",)
        if needs_post and not d.posts:
            results.append((d.serial, d.sdk, name, "skip", "no `cmd notification post` here"))
            continue
        clean(d)
        try:
            f = case(d)
        except Exception as e:  # the case itself broke
            f = [f"case raised {e!r}"]
        if f is None:
            results.append((d.serial, d.sdk, name, "skip", "not on this Android"))
            continue
        log = d.log()
        f += problems(log, allow_death=(name in DIES))
        if f:
            with open(os.path.join(LOGS, f"fail-{d.serial}-{name}.log"), "w") as out:
                out.write(log)
        d.read_all()
        settled = wait(lambda: "Lumement:led" not in d.locks() and "Lumement:pocket" not in d.locks(), 20)
        if not settled:
            f.append(f"locks still held 20 s after all read: {d.locks()}")
        d.sleep_phone()
        if not wait(lambda: d.screen() in ("OFF", "DOZE", "DOZE_SUSPEND"), 20):
            f.append(f"display {d.screen()} 20 s after all read and asleep")
        results.append((d.serial, d.sdk, name, "FAIL" if f else "ok", "; ".join(f)))
        print(f"{d.serial} (API {d.sdk}) {name}: {'FAIL ' + '; '.join(f) if f else 'ok'}", flush=True)


LOGS = os.environ.get("EDGE_LOGS", "/tmp")


def selftest():
    log = "\n".join([
        "10-23 21:00:00.000 E/AndroidRuntime( 123): FATAL EXCEPTION: main",
        "10-23 21:00:00.000 E/AndroidRuntime( 123): Process: app.lumement, PID: 123",
        "10-23 21:00:01.000 I/ActivityManager( 99): Process app.lumement (pid 123) has died: fg TOP",
        "10-23 21:00:02.000 E/ActivityManager( 99): ANR in app.lumement",
        "10-23 21:00:03.000 E/AndroidRuntime( 456): FATAL EXCEPTION: main",
        "10-23 21:00:03.000 E/AndroidRuntime( 456): Process: com.other, PID: 456",
    ])
    found = problems(log)
    assert len(found) == 3 and found[0].startswith("crash") and found[1].startswith("died") and found[2].startswith("ANR"), found
    assert len(problems(log, allow_death=True)) == 2
    assert problems("10-23 D/Lumement( 1): no One UI blur on this phone") == []
    print("edge: selftest ok")
    return 0


def main(argv):
    if argv == ["--selftest"]:
        return selftest()
    only = skip = None
    if "--only" in argv:
        i = argv.index("--only")
        only = set(argv[i + 1].split(","))
        argv = argv[:i] + argv[i + 2:]
    if "--skip" in argv:
        i = argv.index("--skip")
        skip = set(argv[i + 1].split(","))
        argv = argv[:i] + argv[i + 2:]
    if not argv:
        out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
        argv = [l.split()[0] for l in out.splitlines() if l.startswith("emulator-") and l.split()[-1] == "device"]
    if not argv:
        print("edge: no emulator connected")
        return 2
    os.makedirs(LOGS, exist_ok=True)
    setup = os.path.join(os.path.dirname(os.path.abspath(__file__)), "emulator_setup.sh")
    for serial in argv:
        r = subprocess.run([setup], env={**os.environ, "ANDROID_SERIAL": serial, "ADB": ADB}, capture_output=True, text=True)
        if r.returncode != 0:
            print(f"edge: setting up {serial} failed: {r.stdout}{r.stderr}")
            return 2
    if skip:
        only = {name for name, _ in CASES if (only is None or name in only) and name not in skip}
    devs = [Dev(s) for s in argv]
    results = []
    threads = [threading.Thread(target=run, args=(d, only, results)) for d in devs]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    print("\n==== summary")
    for serial, sdk, name, status, detail in sorted(results, key=lambda r: (r[1], r[2])):
        print(f"API {sdk:>2} {serial} {name:20} {status:4} {detail}")
    return 1 if any(r[3] == "FAIL" for r in results) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
