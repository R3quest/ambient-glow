# Battery measurements

`make soak MODE=lit|aod|idle MINUTES=… NOTE=…` (see `scripts/soak.sh`) appends one line per run to
`soak.tsv`. The raw dumps of each run (batterystats, power) are in `raw/`, kept locally only: they
list every app on the test phone.

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
