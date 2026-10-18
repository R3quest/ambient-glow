# Battery measurements

`make soak MODE=lit|aod|idle|hybrid MINUTES=… NOTE=…` (see `scripts/soak.sh`) appends one line per
run to `soak.tsv`; `make soak-set` runs the defaults' set in one go (~4.5 h). The raw dumps of each
run (batterystats, power) are in `raw/`, kept locally only: they list every app on the test phone.
Every adb call has a timeout (60 s, a stall tried twice more): adb over Wi-Fi stalls now and then
while the phone is in deep sleep.

Modes (2026-10-17, for the bright phase): `lit` (the always-on display off, the lit LED for the whole
run, 10 minutes at most), `aod` (the LED straight onto the always-on display, still), `hybrid` (the LED
as shipped: `BRIGHT=OFF|FIVE|TEN|FIFTEEN` then `AFTER=AOD|OFF`, the always-on display set to Show for
new notifications, the message posted as the measured window starts), `idle`. The soak build sets
Bright LED and Then for each run through its own receiver (`app/src/soak`, `SoakSettings`) and puts the
phone's back afterwards. A sleeping run's end state is read off the display (`mScreenState`), since
wakefulness says Dozing even with the always-on display off.

When people look, for the defaults, comes from the LED diary instead (`make diary`, debug builds
only; see `claude.md`). It leaves out what a reboot cut short, and the windows `make scenario` noted
on the phone (`/data/local/tmp/lumement-scenarios.txt`): test messages are no one's looks.

## make scenario: nothing left behind

`make scenario` (`scripts/scenario.py`, debug build, the phone flat, nothing waiting) locks the
phone, posts adb's test message, waits through the arrival and reads it (snoozed away, a removal as
the listener sees it). Within `SETTLE` seconds (30) no `Lumement:*` wake lock may still be held, the
display must be back to OFF or DOZE, the LED diary must have counted the message and the read, and
the log since the start must have no exception, crash, ANR or death from Lumement. `SCENARIO=two`
reads one of two messages first: the LED must stay as it was until the second is read. adb posts as
`com.android.shell` only, so the two come from the one app. It exits 1 with what failed; its parsing
is self-tested on the S23's captured output (`make scripts-test`).

## How a run measures

- Galaxy S23 (One UI 8.5), unplugged, adb over Wi-Fi (`make wifi-adb`), the `soak` build (the
  release build as it ships, plus adb's test messages).
- Current: the battery's charge counter, from one step to another (~3.7 mAh steps; held while
  full). batterystats' discharge reads the same counter and agreed in every run.
- CPU: each process's time from `/proc`, as % of one core.
- Every figure includes Wi-Fi adb, the same in every run: compare runs with each other, not with a
  phone in a pocket.

## Results, 2026-10-12

| Run | mA | CPU, % of a core | What the phone did |
|---|---|---|---|
| Lit LED, AOD off, sleep watch 16 ms | 175, 189 | Lumement 5.8, SurfaceFlinger 9.4, system_server 5.2, SystemUI 0.6 | awake, screen on, never asleep |
| Lit LED, sleep watch 50 ms | 183 | Lumement 5.3, SurfaceFlinger 9.7, system_server 4.4 | the same: 16 ms costs nothing measurable |
| LED breathing on the AOD (Show always, lit room) | 130 | SurfaceFlinger 8.2, system_server 3.9 | dozing; deep doze only 3.7 of 133 mAh: `Lumement:dark` held 54 of 61 min, the draw lock 38.5 min (1244 breaths) |
| AOD alone (Show always, lit room, DND: no LED) | 88 | Lumement 0.0, system_server 1.3 | dozing; deep doze 78 of 96 mAh |

What it says:

- The lit LED costs the phone ~189 mA: the screen in its normal (awake) mode, the system awake.
- The LED on the AOD costs ~130 mA: 42 mA over the AOD alone, almost all of it the breath keeping
  the phone out of deep doze and the panel taking frames.
- Lumement's own CPU is a small share in every mode; the cost is the system's.

Seen on the way:

- In low light One UI switched the AOD off by itself after ~10 min (burn-in protection), and the
  flow's "the AOD went away, light the LED" woke the phone into the lit LED (todo: overnight).
- Lumement's CPU in the AOD run: `/proc` 8.4 %, batterystats 26.4 % (they agreed on the lit run).
  Not explained yet; the mA figure is the one both sources agree on.
