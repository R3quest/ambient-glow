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

    scripts/diary.py led-diary.txt
    scripts/diary.py --selftest
"""
import math
import sys

# A screen-on this soon after Lumement lit the screen (wake), started (launch) or a message came is
# theirs, not the user's.
OURS_S = 5.0


def parse(lines):
    events = []
    for line in lines:
        parts = line.split(maxsplit=3)
        if len(parts) < 3:
            continue
        wall, elapsed, event = int(parts[0]), int(parts[1]), parts[2]
        events.append((elapsed / 1000.0, wall, event, parts[3] if len(parts) > 3 else ""))
    return events


def streaks(events):
    """(first message, last message before the end, end, how it ended, messages), in seconds."""
    out = []
    open_ = None
    lit_at = -1e9
    for t, _, event, detail in events:
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
    return out, open_ is not None


def aod_spells(events):
    """(seconds on the always-on display, what ended it)."""
    out = []
    on = None
    for t, _, event, detail in events:
        if event == "aod-on":
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


def report(events):
    s, still_open = streaks(events)
    looked = [x for x in s if x[3] == "looked"]
    delays = [end - first for first, _, end, how, _ in looked]
    spreads = [last - first for first, last, _, _, _ in s]
    lines = [f"streaks: {len(s)} ({len(looked)} looked at, {len(s) - len(looked)} read elsewhere first)"
             + ("; one still open, not counted" if still_open else "")]
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
    s, still_open = streaks(parse(lines))
    assert not still_open
    assert [(round((e - f) / 60), how, n) for f, _, e, how, n in s] == [(2, "looked", 1), (12, "looked", 2), (5, "read", 1)], s
    assert aod_spells(parse(lines)) == [(300.0, "gone")], aod_spells(parse(lines))
    assert bright_default([60] * 8 + [700] * 2) == 5
    assert bright_default([60] * 6 + [500] * 2 + [900] * 2) == 10
    assert bright_default([60] * 6 + [900] * 4) == 15
    assert cap_default([0, 60, 120], 10) == 20
    assert cap_default([3000], 10) == 30
    print("diary.py selftest ok")


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        selftest()
    elif len(sys.argv) == 2:
        with open(sys.argv[1]) as f:
            print(report(parse(f)))
    else:
        sys.exit(__doc__)
