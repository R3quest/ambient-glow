#!/usr/bin/env bash
# A battery run on the phone (make soak MODE=lit|aod|idle|hybrid MINUTES=10): the phone unplugged, adb over
# Wi-Fi (make wifi-adb), the soak build installed. Sets the mode up, then reads the phone only at
# the start and the end, so adb costs next to nothing in between:
# - the battery's charge counter: the average current over the run. It moves in steps (~3.7 mAh on
#   the S23) and is held while the battery is full, so the run starts and ends on a step: whole steps
#   over a known time. Few steps come by while asleep: run idle and aod for an hour.
# - each process's CPU time from /proc: Lumement, SurfaceFlinger, system_server, SystemUI, as % of
#   one core;
# - batterystats, reset at the start: Lumement's wake locks over the run.
# One line per run goes to measurements/soak.tsv (committed); the raw dumps to measurements/raw/
# (kept locally, gitignored: they list every app on the phone).
#
# The modes, each with the LED's bright phase (Bright LED) and what follows it (Then) set for it
# through the soak build's own receiver (SoakSettings), and put back as they were afterwards:
# - lit: the always-on display off, the lit LED for the whole run (Bright LED 15 min, so a run is
#   10 minutes at most: the waits for the charge counter's steps come around it);
# - aod: the LED straight onto the always-on display (Bright LED off), still;
# - hybrid: the LED as shipped, BRIGHT (OFF, FIVE, TEN, FIFTEEN; TEN) then AFTER (AOD, OFF; AOD), the
#   always-on display set to Show for new notifications; the message is posted as the measured
#   window starts, so its bright phase is in it;
# - idle: nothing waiting, asleep.
set -euo pipefail

MODE=${1:-lit}
MINUTES=${2:-10}
NOTE=${NOTE:-}
BRIGHT=${BRIGHT:-TEN}
AFTER=${AFTER:-AOD}
ADB=${ADB:-adb}
PKG=app.lumement
TSV=measurements/soak.tsv
RAW=measurements/raw
STAMP=$(date +%Y%m%d-%H%M%S)
TAG="soak-$STAMP"

# Every helper reads adb's output to the end: a reader that stops early (grep -m1) breaks the pipe,
# and with pipefail that ends the run. sh (adb shell with a timeout), counter, counter_read, step.
fail() { echo "soak: $*" >&2; exit 1; }
# shellcheck source=soak_lib.sh
. "$(dirname "$0")/soak_lib.sh"
wakefulness() { sh dumpsys power | awk -F= '/mWakefulness=/ && !seen {print $2; seen = 1}'; }
held() { [ "$(sh dumpsys power | awk -v tag="'Lumement:$1'" '$2 == tag' | wc -l)" -gt 0 ]; }
level() { sh dumpsys battery | awk -F': ' '/^  level/ && !seen {print $2; seen = 1}'; }
# The display itself: OFF, ON, or DOZE / DOZE_SUSPEND (the always-on display). Wakefulness says
# Dozing even with the always-on display off, so it can't tell.
screen() { sh dumpsys display | awk -F= '/mScreenState=/ && !seen {print $2; seen = 1}'; }
dozing() { case "$1" in DOZE | DOZE_SUSPEND) return 0 ;; *) return 1 ;; esac; }
# Bright LED and Then through SoakSettings; prints them as set ("brightFor=TEN afterBright=AOD").
soakset() { sh am broadcast -n "$PKG/.SoakSettings" "$@" | sed -n 's/.*data="\(.*\)".*/\1/p'; }

case "$MODE" in lit | aod | idle | hybrid) ;; *) fail "MODE is lit, aod, idle or hybrid, not $MODE" ;; esac
[[ "$MINUTES" =~ ^[1-9][0-9]*$ ]] || fail "MINUTES is a whole number of minutes, not $MINUTES"
mkdir -p "$RAW" "$(dirname "$TSV")"

# ---- The phone as a run needs it ----------------------------------------------------------------
battery=$(sh dumpsys battery)
grep -qE "(AC|USB|Wireless) powered: true" <<<"$battery" && fail "the phone is charging: unplug it (adb over Wi-Fi: make wifi-adb first)"
aod=$(sh settings get system aod_mode)

post() {
    sh cmd notification post -S messaging --conversation Soak --message "Soak:$TAG" -t Soak "$TAG" soak >/dev/null
}
unpost() {
    sh cmd notification snooze --for 315360000000 "'0|com.android.shell|2020|$TAG|2000'" >/dev/null 2>&1 || true
}
# The phone's own Bright LED and Then, put back when the run ends.
was=$(soakset)
[ -n "$was" ] || fail "the soak build's settings receiver didn't answer: is the soak build installed?"
cleanup() {
    unpost
    soakset --es bright_for "$(sed 's/.*brightFor=\([A-Z]*\).*/\1/' <<<"$was")" --es after_bright "$(sed 's/.*afterBright=\([A-Z]*\).*/\1/' <<<"$was")" >/dev/null
}
trap cleanup EXIT

echo "soak: $MODE for $MINUTES min, battery $(level) %"
case "$MODE" in
    lit)
        # "null": never switched on on this phone, so off.
        [ "$aod" = "0" ] || [ "$aod" = "null" ] || fail "the always-on display is on (aod_mode $aod): the LED would go onto it. Turn it off in Settings for a lit run"
        [ "$MINUTES" -le 10 ] || fail "a lit run is 10 minutes at most: the LED's longest bright phase is 15, and the waits for the charge counter's steps come around the run"
        soakset --es bright_for FIFTEEN >/dev/null
        post
        sleep 20
        held led || fail "the lit LED isn't up (no Lumement:led lock): is GlowShield on, the phone locked?"
        ;;
    aod)
        [ "$aod" != "0" ] && [ "$aod" != "null" ] || fail "the always-on display is off (aod_mode $aod): turn it on in Settings for an aod run"
        soakset --es bright_for OFF --es after_bright AOD >/dev/null
        post
        sleep 30 # the arrival, then the lock screen's own timeout into the always-on display
        dozing "$(screen)" || fail "not on the always-on display after the message (display $(screen))"
        ;;
    hybrid)
        [ "$aod" != "0" ] && [ "$aod" != "null" ] && [ "$(sh settings get system aod_show_for_new_noti)" = "1" ] ||
            fail "a hybrid run needs the always-on display on, set to Show for new notifications"
        log=$(soakset --es bright_for "$BRIGHT" --es after_bright "$AFTER")
        echo "soak: $log"
        held led && fail "the lit LED is up: messages are waiting; read them first"
        [ "$(wakefulness)" = "Awake" ] && sh input keyevent KEYCODE_SLEEP
        sleep 10
        [ "$(wakefulness)" != "Awake" ] || fail "the phone is still awake"
        ;;
    idle)
        held led && fail "the lit LED is up: messages are waiting; read them first"
        [ "$(wakefulness)" = "Awake" ] && sh input keyevent KEYCODE_POWER
        sleep 10
        [ "$(wakefulness)" != "Awake" ] || fail "the phone is still awake"
        ;;
esac

# ---- The run ------------------------------------------------------------------------------------
procs=("$PKG" surfaceflinger system_server com.android.systemui)
pids=()
# One that isn't running has no pid (pidof fails): "-" in the log, rather than the run ending here.
for p in "${procs[@]}"; do pids+=("$(sh pidof "$p" | awk '{print $1}' || true)"); done

# utime + stime, in clock ticks (100 a second), of each pid; "-" for one that is gone.
ticks() {
    for pid in "${pids[@]}"; do
        sh "cat /proc/$pid/stat 2>/dev/null" | sed 's/^.*) //' | awk '{print $12 + $13}' | grep . || echo -
    done
}

echo "soak: waiting for the charge counter's next step to start on"
c=$(counter_read)
step "$c" 3600 5
sh dumpsys batterystats --reset >/dev/null
# A hybrid run's message arrives as it starts, so its bright phase and the handover are in it.
[ "$MODE" = hybrid ] && post
c0=$(counter_read)
l0=$(level)
t0=$(date +%s)
mapfile -t k0 < <(ticks)
echo "soak: started at $(date +%H:%M), counter $c0 µAh; back in $MINUTES min"

sleep $((MINUTES * 60))

echo "soak: waiting for the charge counter's next step to end on"
c=$(counter_read)
step "$c" 3600 15
c1=$(counter_read)
l1=$(level)
t1=$(date +%s)
mapfile -t k1 < <(ticks)
sh dumpsys batterystats "$PKG" >"$RAW/$STAMP-$MODE-batterystats.txt"
sh dumpsys power >"$RAW/$STAMP-$MODE-power.txt"
state="$(wakefulness) / $(screen)"

secs=$((t1 - t0))

mA=$(awk -v a="$c0" -v b="$c1" -v s="$secs" 'BEGIN {printf "%.0f", (a - b) / 1000 / (s / 3600)}')
cpu=()
for i in "${!procs[@]}"; do
    cpu+=("$(awk -v a="${k0[$i]}" -v b="${k1[$i]}" -v s="$secs" 'BEGIN {if (a == "-" || b == "-") print "-"; else printf "%.1f", (b - a) / s}')")
done
stats="$RAW/$STAMP-$MODE-batterystats.txt"
locks=$(grep -oE "Wake lock Lumement:[a-z]+[^:]*: [^)]*\)" "$stats" | sed 's/Wake lock //' | tr '\n' ';' || true)
# batterystats' own view of the same run, as a cross-check: its discharge (the same counter) and
# Lumement's CPU time (its uid's "Total cpu time" after the Lumement wake locks).
bs_mA=$(awk -v s="$secs" '/^  Discharge: / {printf "%.0f", $2 / (s / 3600); exit}' "$stats" || true)
bs_cpu=$(awk -v s="$secs" '
    # "1m 29s 862ms" → ms
    function ms(t,   tok, n, i, v) {
        n = split(t, tok, " "); v = 0
        for (i = 1; i <= n; i++) {
            if (tok[i] ~ /ms$/) v += tok[i] + 0
            else if (tok[i] ~ /h$/) v += tok[i] * 3600000
            else if (tok[i] ~ /m$/) v += tok[i] * 60000
            else if (tok[i] ~ /s$/) v += tok[i] * 1000
        }
        return v
    }
    /Wake lock Lumement:led|Wake lock Lumement:aod|Wake lock Lumement:dark/ { ours = 1 }
    ours && /Total cpu time: u=/ { split($0, p, /u=| s=/); printf "%.1f", (ms(p[2]) + ms(p[3])) / 10 / s; exit }
' "$stats" || true)

# A sleeping run (aod, idle) that had the screen on measured something else as well: a message
# arriving (lights the lock screen, by design), a hand on it, or One UI dropping the always-on
# display in low light (burn-in protection) and the lit LED taking over. Flagged, for a look.
# Each mode ends where it should: the lit LED still up, or the LED on the always-on display.
case "$MODE" in
    lit) held led || NOTE="CHECK: the lit LED wasn't up at the end${NOTE:+; $NOTE}" ;;
    aod) dozing "$(screen)" || NOTE="CHECK: not on the always-on display at the end ($(screen))${NOTE:+; $NOTE}" ;;
    hybrid)
        NOTE="Bright LED $BRIGHT, then $AFTER${NOTE:+; $NOTE}"
        if [ "$AFTER" = AOD ] && ! dozing "$(screen)"; then NOTE="CHECK: not on the always-on display at the end ($(screen)); $NOTE"; fi
        ;;
esac

if [ "$MODE" != lit ] && [ "$MODE" != hybrid ]; then
    on=$(awk '/^  Screen on: / {print $3, $4, $5; exit}' "$stats")
    on_ms=$(awk -v t="$on" 'BEGIN { n = split(t, tok, " "); v = 0
        for (i = 1; i <= n; i++) { if (tok[i] ~ /ms$/) v += tok[i] + 0; else if (tok[i] ~ /h$/) v += tok[i] * 3600000
            else if (tok[i] ~ /m$/) v += tok[i] * 60000; else if (tok[i] ~ /s$/) v += tok[i] * 1000 }
        print v }')
    if [ "${on_ms:-0}" -gt 5000 ]; then
        NOTE="CHECK: the screen was on for $on${NOTE:+; $NOTE}"
        echo "soak: CHECK: the screen was on for $on of the run: a message arriving, a hand, or the AOD dropped (see $stats)"
    fi
fi

[ -f "$TSV" ] || printf 'date\tcommit\tmode\tminutes\tmA\tmA (batterystats)\tlumement %%\tlumement %% (batterystats)\tsurfaceflinger %%\tsystem_server %%\tsystemui %%\tbattery\tend state\twake locks\tnote\n' >"$TSV"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s→%s\t%s\t%s\t%s\n' \
    "$(date '+%Y-%m-%d %H:%M')" "$(git rev-parse --short HEAD)$(git diff --quiet HEAD -- app/src/main || echo +)" "$MODE" \
    "$((secs / 60))" "$mA" "${bs_mA:--}" "${cpu[0]}" "${bs_cpu:--}" "${cpu[1]}" "${cpu[2]}" "${cpu[3]}" "$l0" "$l1" "$state" "$locks" "$NOTE" >>"$TSV"

echo "soak: $MODE $((secs / 60)) min: $mA mA; CPU % of a core: lumement ${cpu[0]}, surfaceflinger ${cpu[1]}, system_server ${cpu[2]}, systemui ${cpu[3]}"
echo "soak: wake locks: ${locks:-none}"
echo "soak: line added to $TSV"
