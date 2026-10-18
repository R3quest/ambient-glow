#!/usr/bin/env python3
"""make scenario: messages through the real flow on the phone, then checks they left nothing behind.

The debug build on the phone (make install), adb connected, the phone flat (lift to wake). Each
variant locks the phone, posts adb's test message(s), waits through the arrival, reads them (adb
snoozes them away: the listener sees a removal, as for a message read elsewhere), and then, within
SETTLE seconds of the last read:
- no Lumement:* wake lock is still held (dumpsys power's held locks, not its history);
- the display is back to OFF or DOZE / DOZE_SUSPEND (dumpsys display mScreenState);
- the LED diary counted each message and each read (message, read waiting=N);
- the log since the start has no exception from Lumement (its tag, or an error from its process),
  no crash, ANR or death of its process, and the process is the one it started with.
Variants:
- one: one message, read;
- two: two messages, the first read while the second waits (the LED stays as it was), then the
  second. adb posts every test message as com.android.shell, so they are two messages (two keys)
  from the one app: a second app would need a debug-only hook in the app.
The scenario's window goes to WINDOWS on the phone, so `make diary` leaves its test streaks out.
Exits 1 with what failed. Its parsing is tested on captured text (--selftest, make scripts-test).

    ADB=adb SETTLE=30 ARRIVAL=15 scripts/scenario.py [one|two|all]
    scripts/scenario.py --selftest
"""
import os
import re
import shlex
import subprocess
import sys
import time

PKG = "app.lumement"
SHELL_PKG = "com.android.shell"
# Kept on the phone (adb's own scratch space), so `make diary` finds it from any checkout.
WINDOWS = "/data/local/tmp/lumement-scenarios.txt"
# A test message read is snoozed for ten years: `cmd notification` has no cancel.
SNOOZE_MS = 315360000000
QUIET_SCREENS = ("OFF", "DOZE", "DOZE_SUSPEND")
FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")


class Fail(Exception):
    pass


def say(message):
    print(f"scenario: {message}", flush=True)


# ---- Parsing: what the phone prints, as plain functions on its text ----------------------------

def held_locks(power):
    """(type, tag) of every wake lock held now: dumpsys power's "Wake Locks: size=N" block only,
    not the release history or the wake lock log further down, which list long-gone ones."""
    out = []
    inside = False
    for line in power.splitlines():
        if line.startswith("Wake Locks: size="):
            inside = True
            continue
        if not inside:
            continue
        if not line.startswith(" ") or not line.strip():
            break
        m = re.match(r"\s+(\S+)\s+'([^']*)'", line)
        if m:
            out.append((m.group(1), m.group(2)))
    return out


def lumement_locks(power):
    return [tag for _, tag in held_locks(power) if tag.startswith("Lumement:")]


def screen_state(display):
    """The panel as the display power controller has it: OFF, ON, DOZE, DOZE_SUSPEND."""
    m = re.search(r"^\s*mScreenState=(\w+)", display, re.M)
    return m.group(1) if m else None


def led_up(locks, screen):
    """The LED shows: lit (its screen lock held) or on the always-on display."""
    return "Lumement:led" in locks or screen in ("DOZE", "DOZE_SUSPEND")


# logcat -v time: "10-18 09:47:24.342 D/Lumement(30999): svc posted ..."
LOG_LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+) ([VDIWEFA])/([^(]*)\(\s*(\d+)\): ?(.*)$")
# "java.lang.IllegalStateException: …", "OutOfMemoryError", AndroidRuntime's "FATAL EXCEPTION";
# not a bare "Error -19", which the system's surface code logs from every app's process.
THROWN = re.compile(r"[\w$]+(Exception|Error)\b|FATAL EXCEPTION")
DIED = re.compile(rf"ANR in {re.escape(PKG)}\b|Process {re.escape(PKG)} \(pid \d+\) has died|Killing \d+:{re.escape(PKG)}/")


def log_lines(text):
    for raw in text.splitlines():
        m = LOG_LINE.match(raw)
        if m:
            stamp, level, tag, pid, message = m.groups()
            yield raw, level, tag.strip(), int(pid), message


def lumement_pids(log):
    return {pid for _, _, tag, pid, _ in log_lines(log) if tag == "Lumement"}


def log_problems(log, pids):
    """Lines that say Lumement failed: an exception under its tag, an error with an exception from
    its process (a crash is AndroidRuntime's, from the crashing process), or the system reporting
    an ANR or the death of its process."""
    out = []
    for raw, level, tag, pid, message in log_lines(log):
        if tag == "Lumement" and THROWN.search(message):
            out.append(raw)
        elif pid in pids and level in "EF" and THROWN.search(message):
            out.append(raw)
        elif DIED.search(message):
            out.append(raw)
    return out


def pending_in_log(log, pid):
    """The waiting count the flow last logged ("… pending=N") from process [pid], or None."""
    last = None
    for _, _, tag, line_pid, message in log_lines(log):
        m = re.search(r"\bpending=(\d+)", message)
        if tag == "Lumement" and line_pid == pid and m:
            last = int(m.group(1))
    return last


def diary_events(text, since_ms=0):
    """(wall ms, event, detail) of each LED diary line at or after [since_ms]."""
    out = []
    for line in text.splitlines():
        parts = line.split(maxsplit=3)
        if len(parts) < 3 or not parts[0].isdigit():
            continue
        wall = int(parts[0])
        if wall >= since_ms:
            out.append((wall, parts[2], parts[3].strip() if len(parts) > 3 else ""))
    return out


def diary_waiting(text):
    """The waiting count of the diary's last message or read, or None."""
    for _, event, detail in reversed(diary_events(text)):
        m = re.fullmatch(r"waiting=(\d+)", detail)
        if event in ("message", "read") and m:
            return int(m.group(1))
    return None


def shell_key(listing, tag):
    """The key of adb's test message [tag] in `cmd notification list` (user|pkg|id|tag|uid)."""
    for line in listing.splitlines():
        parts = line.strip().split("|")
        if len(parts) == 5 and parts[1] == SHELL_PKG and parts[3] == tag:
            return line.strip()
    return None


# ---- The phone ----------------------------------------------------------------------------------

class Phone:
    """adb, every call with a timeout: adb stalls now and then while the phone is in deep sleep
    (over Wi-Fi above all), and a stalled read is tried once more."""

    def __init__(self, adb):
        self.adb = adb

    def run(self, *args, timeout=60, retry=True):
        for attempt in (1, 2):
            try:
                # stdin closed: adb shell would otherwise read the terminal, eating what is typed.
                r = subprocess.run([self.adb, *args], stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=timeout)
            except FileNotFoundError:
                raise Fail(f"no adb at {self.adb}: set ADB=…")
            except subprocess.TimeoutExpired:
                if retry and attempt == 1:
                    say(f"adb didn't answer in {timeout} s (the phone in deep sleep?): once more")
                    continue
                raise Fail(f"adb {' '.join(args)[:60]} didn't answer in {timeout} s")
            if r.returncode != 0 and re.match(r"(adb: |error: )", r.stderr):
                raise Fail(f"adb: {r.stderr.strip()}")
            return r.stdout.replace("\r", "")

    def shell(self, command, retry=True):
        return self.run("shell", command, retry=retry)

    def now(self):
        """The phone's clock: (epoch seconds, "MM-dd HH:mm:ss.000" for logcat -T)."""
        out = self.shell("date '+%s %m-%d %H:%M:%S'")
        m = re.fullmatch(r"(\d+) (\d\d-\d\d \d\d:\d\d:\d\d)", out.strip())
        if not m:
            raise Fail(f"the phone's clock reads '{out.strip()}'")
        return int(m.group(1)), f"{m.group(2)}.000"

    def pid(self):
        out = self.shell(f"pidof {PKG}").split()
        return int(out[0]) if out else None

    def preflight(self):
        if self.run("get-state", timeout=20).strip() != "device":
            raise Fail("no phone on adb (make devices)")
        if "ok" not in self.shell(f"run-as {PKG} true && echo ok"):
            raise Fail("the debug build isn't installed (make install): the scenario reads its log and LED diary")
        if f"{PKG}/" not in self.shell("settings get secure enabled_notification_listeners"):
            raise Fail("Lumement has no notification access: turn it on in the app")
        if self.shell("settings get global zen_mode").strip() not in ("0", "null"):
            raise Fail("Do not disturb is on: Lumement rests under it; turn it off")
        pid = self.pid()
        if pid is None:
            raise Fail("Lumement isn't running: open it once (make launch)")
        waiting = pending_in_log(self.shell("logcat -d -v time -s Lumement"), pid)
        if waiting is None:
            waiting = diary_waiting(self.diary())
        if waiting:
            raise Fail(f"{waiting} message(s) waiting for the LED: read them first, so the scenario's streak is its own")
        if "Lumement:led" in self.locks():
            raise Fail("the lit LED is up (Lumement:led held): read what is waiting first")

    def post(self, tag, title, text, asleep=False):
        post = f"cmd notification post -S bigtext -t {shlex.quote(title)} {shlex.quote(tag)} {shlex.quote(text)} >/dev/null"
        # Never tried twice: a post that stalled may still have gone through.
        self.shell(f"input keyevent KEYCODE_SLEEP; sleep 0.5; {post}" if asleep else post, retry=False)

    def read(self, tag):
        key = shell_key(self.shell("cmd notification list"), tag) or f"0|{SHELL_PKG}|2020|{tag}|2000"
        self.shell(f"cmd notification snooze --for {SNOOZE_MS} {shlex.quote(key)} >/dev/null 2>&1", retry=False)
        if shell_key(self.shell("cmd notification list"), tag):
            raise Fail(f"the test message {tag} is still posted after reading it (snooze)")

    def locks(self):
        return lumement_locks(self.shell("dumpsys power"))

    def screen(self):
        return screen_state(self.shell("dumpsys display"))

    def diary(self):
        return self.shell(f"run-as {PKG} tail -n 200 files/led-diary.txt")

    def log_since(self, since):
        return self.shell(f"logcat -d -v time -T {shlex.quote(since)}")

    def note_window(self, start_s, end_s, label):
        self.shell(f"echo {start_s * 1000} {end_s * 1000} {shlex.quote(label)} >> {WINDOWS}")

    def sleep(self, seconds):
        time.sleep(seconds)


# ---- The scenario -------------------------------------------------------------------------------

def wait_for(phone, what, seconds, step=2):
    """Polls [what] (truthy when done) every [step] s until it is, [seconds] of waiting at most."""
    waited = 0
    while True:
        got = what()
        if got or waited >= seconds:
            return got
        phone.sleep(step)
        waited += step


def counted(phone, since_ms, event, detail=None, at_least=1):
    """The LED diary has [at_least] [event]s (with [detail]) since [since_ms]."""
    events = diary_events(phone.diary(), since_ms)
    return sum(1 for _, e, d in events if e == event and (detail is None or d == detail)) >= at_least


def scenario(phone, variant, arrival_s=15, settle_s=30):
    """Runs [variant] ("one" or "two") on [phone]; returns the problems found (none: passed)."""
    phone.preflight()
    start_s, since = phone.now()
    since_ms = start_s * 1000
    pid0 = phone.pid()
    tags = [f"lumement-scenario-{start_s}-{n}" for n in (1, 2)][: 2 if variant == "two" else 1]
    unread = []
    problems = []
    try:
        say(f"{variant}: locking the phone, then a message")
        phone.post(tags[0], "Scenario", "A test message from make scenario", asleep=True)
        unread.append(tags[0])
        if not wait_for(phone, lambda: counted(phone, since_ms, "message"), 20):
            raise Fail("Lumement didn't count the message (no 'message' in its LED diary): "
                       "is com.android.shell muted on its Apps screen?")
        phone.sleep(arrival_s)
        locks, screen = phone.locks(), phone.screen()
        say(f"{variant}: after the arrival, display {screen}, locks {locks or 'none'}")
        if variant == "two":
            phone.post(tags[1], "Scenario 2", "A second test message from make scenario")
            unread.append(tags[1])
            if not wait_for(phone, lambda: counted(phone, since_ms, "message", at_least=2), 20):
                raise Fail("Lumement didn't count the second message")
            phone.sleep(arrival_s)
            locks, screen = phone.locks(), phone.screen()
            phone.read(unread.pop(0))
            if not wait_for(phone, lambda: counted(phone, since_ms, "read", "waiting=1"), 10):
                problems.append("the LED diary has no 'read waiting=1' after the first read")
            phone.sleep(3)
            locks_after, screen_after = phone.locks(), phone.screen()
            say(f"{variant}: one read, one waiting: display {screen_after}, locks {locks_after or 'none'}")
            if led_up(locks, screen) and not led_up(locks_after, screen_after):
                problems.append(f"the LED went away with a message still waiting (display {screen_after}, locks {locks_after or 'none'})")
        read_at = time.monotonic()
        phone.read(unread.pop(0))
        if not wait_for(phone, lambda: counted(phone, since_ms, "read", "waiting=0"), 10):
            problems.append("the LED diary has no 'read waiting=0' after the last read")
        state = {}

        def settled():
            state["locks"], state["screen"] = phone.locks(), phone.screen()
            return not state["locks"] and state["screen"] in QUIET_SCREENS

        if wait_for(phone, settled, settle_s):
            say(f"{variant}: settled {time.monotonic() - read_at:.0f} s after the last read: display {state['screen']}, no Lumement lock")
        else:
            if state["locks"]:
                problems.append(f"Lumement wake lock(s) still held {settle_s} s after the last read: {', '.join(state['locks'])}")
            if state["screen"] not in QUIET_SCREENS:
                problems.append(f"the display is {state['screen']} {settle_s} s after the last read, not OFF or DOZE")
        log = phone.log_since(since)
        pid1 = phone.pid()
        found = log_problems(log, {p for p in (pid0, pid1) if p} | lumement_pids(log))
        if found:
            problems.append("the log has trouble from Lumement:\n    " + "\n    ".join(found[:12]))
        if pid0 != pid1:
            problems.append(f"Lumement's process changed during the scenario ({pid0} → {pid1}): it died or was restarted")
    finally:
        for tag in unread:
            try:
                phone.read(tag)
            except Fail as e:
                say(f"couldn't read {tag}: {e}")
        try:
            phone.note_window(start_s - 1, phone.now()[0] + 2, f"scenario-{variant}")
        except Fail as e:
            say(f"couldn't note the scenario's window for make diary: {e}")
    return problems


def main(args):
    variants = {"one": ["one"], "two": ["two"], "all": ["one", "two"]}.get(args[0] if args else "all")
    if variants is None or len(args) > 1:
        sys.exit(__doc__)
    phone = Phone(os.environ.get("ADB") or "adb")
    arrival_s = int(os.environ.get("ARRIVAL") or 15)
    settle_s = int(os.environ.get("SETTLE") or 30)
    failed = False
    for variant in variants:
        try:
            problems = scenario(phone, variant, arrival_s, settle_s)
        except Fail as e:
            problems = [str(e)]
        for p in problems:
            say(f"{variant}: FAIL: {p}")
        if problems:
            failed = True
        else:
            say(f"{variant}: ok")
    sys.exit(1 if failed else 0)


# ---- Self-test ----------------------------------------------------------------------------------

def fixture(name):
    with open(os.path.join(FIXTURES, name)) as f:
        return f.read()


class FakePhone(Phone):
    """The phone as the scenario sees it, scripted: messages counted in a diary, a lock held while
    any wait, and [leak] keeping it after the last read."""

    def __init__(self, leak=False, dark_while_waiting=False):
        super().__init__("adb")
        self.clock = 1_792_300_000
        self.posted = []
        self.diary_text = ""
        self.leak = leak
        self.dark_while_waiting = dark_while_waiting
        self.windows = []

    def note(self, event, detail):
        self.diary_text += f"{self.clock * 1000} 0 {event} {detail}\n"

    def preflight(self):
        pass

    def now(self):
        return self.clock, "10-18 09:47:24.000"

    def pid(self):
        return 30999

    def post(self, tag, title, text, asleep=False):
        self.posted.append(tag)
        self.note("message", f"waiting={len(self.posted)}")

    def read(self, tag):
        if tag in self.posted:
            self.posted.remove(tag)
            self.note("read", f"waiting={len(self.posted)}")

    def locks(self):
        lit = self.posted and not (self.dark_while_waiting and len(self.posted) == 1)
        return ["Lumement:led"] if lit or (self.leak and not self.posted) else []

    def screen(self):
        return "ON" if self.locks() else "OFF" if self.dark_while_waiting else "DOZE"

    def diary(self):
        return self.diary_text

    def log_since(self, since):
        return fixture("logcat.txt")

    def note_window(self, start_s, end_s, label):
        self.windows.append((start_s, end_s, label))

    def sleep(self, seconds):
        self.clock += int(seconds)


def selftest():
    # The fixtures are the S23's own output (2026-10-18, One UI 8.5), cut to what the parsers read.
    power = fixture("dumpsys-power.txt")
    # Held now: the system's two; the Lumement:led lines below are its release history.
    assert held_locks(power) == [("DOZE_WAKE_LOCK", "dream:doze"), ("DRAW_WAKE_LOCK", "charging_info")], held_locks(power)
    assert lumement_locks(power) == []
    leaked = power.replace(
        "Wake Locks: size=2\n",
        "Wake Locks: size=3\n  SCREEN_BRIGHT_WAKE_LOCK           'Lumement:led' ACQ=-4m2s (uid=10405 isFrozen=false pid=30999 displayId=0 lock=f84c675)\n",
    )
    assert lumement_locks(leaked) == ["Lumement:led"], lumement_locks(leaked)
    assert held_locks("Wake Locks: size=0\n\nSuspend Blockers: size=5\n  PowerManagerService.Booting: ref count=0 []\n") == []

    assert screen_state(fixture("dumpsys-display.txt")) == "DOZE"
    assert screen_state("  mScreenState=ON\n") == "ON" and screen_state("nothing") is None
    assert led_up([], "DOZE") and led_up(["Lumement:led"], "ON") and not led_up([], "OFF")

    log = fixture("logcat.txt")
    assert lumement_pids(log) == {30999}
    # The surface's "Error -19" from Lumement's process, logged as E, is the system's, not a failure.
    assert log_problems(log, {30999}) == [], log_problems(log, {30999})
    assert pending_in_log(log, 30999) == 1 and pending_in_log(log, 1) is None
    crash = log + "\n".join([
        "10-18 09:52:30.001 E/AndroidRuntime(30999): FATAL EXCEPTION: main",
        "10-18 09:52:30.001 E/AndroidRuntime(30999): Process: app.lumement, PID: 30999",
        "10-18 09:52:30.001 E/AndroidRuntime(30999): java.lang.IllegalStateException: no window",
        "10-18 09:52:30.120 I/ActivityManager( 3327): Process app.lumement (pid 30999) has died: fg  TOP (165,2381)",
        "10-18 09:52:31.000 E/ActivityManager( 3327): ANR in app.lumement (app.lumement/.WakeScreenActivity)",
        "10-18 09:52:31.500 W/Lumement(31002): led draw failed: java.lang.NullPointerException",
        "10-18 09:52:32.000 E/OtherApp( 4242): java.lang.IllegalStateException: not ours",
    ]) + "\n"
    found = log_problems(crash, {30999})
    assert len(found) == 5 and not any("OtherApp" in line for line in found), found

    diary = "1792232972103 622248083 message waiting=1\n1792232975911 622251891 read waiting=0\n17922329 torn\n"
    assert diary_events(diary, 1792232975000) == [(1792232975911, "read", "waiting=0")]
    assert diary_waiting(diary) == 0 and diary_waiting("1 2 message waiting=3\n1 3 screen-on\n") == 3
    assert diary_waiting("") is None

    listing = "-1|com.android.systemui|2114586824|charging_state|10041\n0|com.android.shell|2020|lumement-scenario-1-1|2000\n"
    assert shell_key(listing, "lumement-scenario-1-1") == "0|com.android.shell|2020|lumement-scenario-1-1|2000"
    assert shell_key(listing, "lumement-scenario-1-2") is None

    # The flow end to end on a scripted phone: clean, a lock left behind, the LED gone too soon.
    out = sys.stdout
    sys.stdout = open(os.devnull, "w")
    try:
        for variant in ("one", "two"):
            phone = FakePhone()
            assert scenario(phone, variant, 1, 4) == [], variant
            assert phone.posted == [] and phone.windows[0][2] == f"scenario-{variant}"
            problems = scenario(FakePhone(leak=True), variant, 1, 4)
            assert len(problems) == 2 and "Lumement:led" in problems[0], problems
        problems = scenario(FakePhone(dark_while_waiting=True), "two", 1, 4)
        assert len(problems) == 1 and "went away" in problems[0], problems
    finally:
        sys.stdout.close()
        sys.stdout = out
    print("scenario.py selftest ok")


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        selftest()
    else:
        main(sys.argv[1:])
