#!/usr/bin/env python3
"""Reads the LED diary (debug builds: LedDiary, make diary) and says what it means for the defaults.

A streak starts with the first message while none is open. It ends at the user's first look (a
peek on the LED, an unlock, or the screen coming on without Lumement or a new message lighting
it), or when everything was read elsewhere first. Then, by the rules on the plan page:
- Bright LED: 5 min if 80 % of first looks come within 5 minutes; otherwise 10; 15 only if 10
  minutes covers less than 70 %.
- Restart cap: the bright time that covers 95 % of streaks (their spread from first to last
  message, plus the bright phase), rounded up to 10 minutes, never above 30.
And how long One UI kept its always-on display up with the LED on it, by what ended it.
A reboot starts the elapsed clock over: what was open then is left out. So are the windows `make
scenario` noted (--skip): its test messages are no one's looks.

    scripts/diary.py led-diary.txt [--skip scenarios.txt]
    scripts/diary.py --selftest
"""
import math
import os
import sys

# A screen-on this soon after Lumement lit the screen (wake), started (launch) or a message came is
# theirs, not the user's.
OURS_S = 5.0


def parse(lines):
    """(elapsed s, wall ms, event, detail) per line; a "boot" event where the elapsed clock went
    back (a reboot). A line that isn't one (the last one half written as the app appends, an error
    from run-as instead of the file) is left out."""
    events = []
    last = None
    for line in lines:
        parts = line.split(maxsplit=3)
        if len(parts) < 3 or not (parts[0].isdigit() and parts[1].isdigit()):
            continue
        wall, elapsed, event = int(parts[0]), int(parts[1]), parts[2]
        if last is not None and elapsed < last:
            events.append((elapsed / 1000.0, wall, "boot", ""))
        last = elapsed
        events.append((elapsed / 1000.0, wall, event, parts[3] if len(parts) > 3 else ""))
    return events


def windows(lines):
    """(start ms, end ms) of each window `make scenario` noted ("start end label")."""
    out = []
    for line in lines:
        parts = line.split()
        if len(parts) >= 2 and parts[0].isdigit() and parts[1].isdigit():
            out.append((int(parts[0]), int(parts[1])))
    return out


def outside(events, spans):
    return [e for e in events if not any(start <= e[1] <= end for start, end in spans)]


def streaks(events):
    """(first message, last message before the end, end, how it ended, messages), in seconds; whether
    one is still open; how many a reboot cut short (their times span two elapsed clocks)."""
    out = []
    open_ = None
    lit_at = -1e9
    cut = 0
    for t, _, event, detail in events:
        if event == "boot":
            cut += open_ is not None
            open_ = None
            lit_at = -1e9
            continue
        if event in ("wake", "launch"):
            lit_at = t
        if event == "message":
            lit_at = t
            if open_ is None:
                open_ = [t, t, 1]
            else:
                open_[1] = t
                open_[2] += 1
            continue
        if open_ is None:
            continue
        looked = event in ("peek", "unlock") or (event == "screen-on" and t - lit_at > OURS_S)
        if looked:
            out.append((open_[0], open_[1], t, "looked", open_[2]))
            open_ = None
        elif event == "read" and detail.strip() == "waiting=0":
            out.append((open_[0], open_[1], t, "read", open_[2]))
            open_ = None
    return out, open_ is not None, cut


def aod_spells(events):
    """(seconds on the always-on display, what ended it)."""
    out = []
    on = None
    for t, _, event, detail in events:
        if event == "boot":
            on = None
        elif event == "aod-on":
            on = t
        elif event == "aod-off" and on is not None:
            out.append((t - on, detail.strip() or "other"))
            on = None
    return out


def share(values, limit):
    return sum(1 for v in values if v <= limit) / len(values) if values else float("nan")


def percentile(values, p):
    if not values:
        return float("nan")
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, math.ceil(p * len(ordered)) - 1)]


def bright_default(delays):
    if share(delays, 300) >= 0.8:
        return 5
    return 10 if share(delays, 600) >= 0.7 else 15


def cap_default(spreads, bright_min):
    if not spreads:
        return 30
    return min(30, math.ceil((percentile(spreads, 0.95) / 60 + bright_min) / 10) * 10)


def report(events, skipped=0):
    s, still_open, cut = streaks(events)
    looked = [x for x in s if x[3] == "looked"]
    delays = [end - first for first, _, end, how, _ in looked]
    spreads = [last - first for first, last, _, _, _ in s]
    lines = [f"streaks: {len(s)} ({len(looked)} looked at, {len(s) - len(looked)} read elsewhere first)"
             + ("; one still open, not counted" if still_open else "")
             + (f"; {cut} cut short by a reboot, not counted" if cut else "")]
    if skipped:
        lines.append(f"left out: {skipped} events in make scenario's windows")
    if delays:
        lines.append("first look within 5 / 10 / 15 min: "
                     + " / ".join(f"{share(delays, m * 60):.0%}" for m in (5, 10, 15))
                     + f"; median {percentile(delays, 0.5) / 60:.1f} min")
        lines.append(f"messages per streak: median {percentile([x[4] for x in s], 0.5)}, most {max(x[4] for x in s)}")
        bright = bright_default(delays)
        lines.append(f"suggested Bright LED: {bright} min; restart cap: {cap_default(spreads, bright)} min")
    spells = aod_spells(events)
    for why in sorted({w for _, w in spells}):
        times = [d for d, w in spells if w == why]
        lines.append(f"on the always-on display, ended by {why}: {len(times)}, median {percentile(times, 0.5) / 60:.1f} min, longest {max(times) / 60:.1f} min")
    return "\n".join(lines)


def selftest():
    # Three streaks: looked at after 2 and 12 minutes (a lit relight and an arrival in between
    # don't count as looks), and one read elsewhere first; one stretch on the always-on display.
    m = 60_000
    lines = [
        f"0 {0 * m} message waiting=1", f"0 {0 * m + 900} launch WAKE", f"0 {0 * m + 1500} screen-on",
        f"0 {2 * m} peek power",
        f"0 {20 * m} message waiting=1", f"0 {21 * m} message waiting=2", f"0 {21 * m + 2000} screen-on",
        f"0 {25 * m} wake", f"0 {25 * m + 500} screen-on", f"0 {26 * m} aod-on", f"0 {31 * m} aod-off gone",
        f"0 {32 * m} screen-on",
        f"0 {50 * m} message waiting=1", f"0 {55 * m} read waiting=0",
    ]
    s, still_open, cut = streaks(parse(lines))
    assert not still_open and not cut
    assert [(round((e - f) / 60), how, n) for f, _, e, how, n in s] == [(2, "looked", 1), (12, "looked", 2), (5, "read", 1)], s
    assert aod_spells(parse(lines)) == [(300.0, "gone")], aod_spells(parse(lines))
    assert bright_default([60] * 8 + [700] * 2) == 5
    assert bright_default([60] * 6 + [500] * 2 + [900] * 2) == 10
    assert bright_default([60] * 6 + [900] * 4) == 15
    assert cap_default([0, 60, 120], 10) == 20
    assert cap_default([3000], 10) == 30
    # A reboot (elapsed back near 0) with a streak and an always-on display spell open: both left
    # out, not stretched across the two clocks; a torn last line and run-as's error are skipped.
    rebooted = parse([
        f"0 {500 * m} message waiting=1", f"0 {501 * m} aod-on", "run-as: package not debuggable: app.lumement",
        f"0 {2 * m} screen-on", f"0 {3 * m} aod-off woken", f"0 {4 * m} peek power",
        f"0 {10 * m} message waiting=1", f"0 {11 * m} unlock", "1792232972103 6222",
    ])
    s, still_open, cut = streaks(rebooted)
    assert [(round((e - f) / 60), how) for f, _, e, how, _ in s] == [(1, "looked")] and cut == 1 and not still_open, s
    assert aod_spells(rebooted) == [], aod_spells(rebooted)
    # make scenario's window leaves its streak out, and only it.
    walled = parse([f"{w} {w} message waiting=1" for w in (1000, 5000)] + ["5500 5500 read waiting=0", "9000 9000 unlock"])
    kept = outside(walled, windows(["4000 6000 scenario-one", "junk"]))
    assert [(w, e) for _, w, e, _ in kept] == [(1000, "message"), (9000, "unlock")], kept
    print("diary.py selftest ok")


if __name__ == "__main__":
    args = sys.argv[1:]
    if args == ["--selftest"]:
        selftest()
    elif len(args) == 1 or (len(args) == 3 and args[1] == "--skip"):
        with open(args[0]) as f:
            events = parse(f)
        spans = []
        if len(args) == 3 and os.path.exists(args[2]):
            with open(args[2]) as f:
                spans = windows(f)
        kept = outside(events, spans)
        print(report(kept, len(events) - len(kept)))
    else:
        sys.exit(__doc__)
