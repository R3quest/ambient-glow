#!/usr/bin/env bash
# A battery run on the phone (make soak MODE=lit|aod|idle MINUTES=10): the phone unplugged, adb over
# Wi-Fi (make wifi-adb), the soak build installed. Sets the mode up, then reads the phone only at
# the start and the end, so adb costs next to nothing in between:
# - the battery's charge counter: the average current over the run. It moves in steps (~3.7 mAh on
#   the S23) and is held while the battery is full, so the run starts and ends on a step: whole steps
#   over a known time. Few steps come by while asleep: run idle and aod for an hour.
# - each process's CPU time from /proc: Lumement, SurfaceFlinger, system_server, SystemUI, as % of
#   one core;
# - batterystats, reset at the start: Lumement's wake locks over the run.
# One line per run goes to measurements/soak.tsv (committed); the raw dumps to app/build/soak/.
set -euo pipefail

MODE=${1:-lit}
MINUTES=${2:-10}
NOTE=${NOTE:-}
ADB=${ADB:-adb}
PKG=app.lumement
TSV=measurements/soak.tsv
RAW=app/build/soak
STAMP=$(date +%Y%m%d-%H%M%S)
TAG="soak-$STAMP"

# Every helper reads adb's output to the end: a reader that stops early (grep -m1) breaks the pipe,
# and with pipefail that ends the run.
sh() { "$ADB" shell "$@" | tr -d '\r'; }
fail() { echo "soak: $*" >&2; exit 1; }
wakefulness() { sh dumpsys power | awk -F= '/mWakefulness=/ && !seen {print $2; seen = 1}'; }
held() { [ "$(sh dumpsys power | awk -v tag="'Lumement:$1'" '$2 == tag' | wc -l)" -gt 0 ]; }
counter() { sh dumpsys battery | awk -F': ' '/Charge counter/ && !seen {print $2; seen = 1}'; }
level() { sh dumpsys battery | awk -F': ' '/^  level/ && !seen {print $2; seen = 1}'; }

case "$MODE" in lit | aod | idle) ;; *) fail "MODE is lit, aod or idle, not $MODE" ;; esac
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
trap unpost EXIT

echo "soak: $MODE for $MINUTES min, battery $(level) %"
case "$MODE" in
    lit)
        [ "$aod" = "0" ] || fail "the always-on display is on (aod_mode $aod): the LED would go onto it. Turn it off in Settings for a lit run"
        post
        sleep 20
        held led || fail "the lit LED isn't up (no Lumement:led lock): is GlowShield on, the phone locked?"
        ;;
    aod)
        [ "$aod" != "0" ] && [ "$aod" != "null" ] || fail "the always-on display is off (aod_mode $aod): turn it on in Settings for an aod run"
        post
        sleep 30 # the arrival, then the lock screen's own timeout into the always-on display
        [ "$(wakefulness)" = "Dozing" ] || fail "not dozing after the message ($(wakefulness))"
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
for p in "${procs[@]}"; do pids+=("$(sh pidof "$p" | awk '{print $1}')"); done

# utime + stime, in clock ticks (100 a second), of each pid; "-" for one that is gone.
ticks() {
    for pid in "${pids[@]}"; do
        sh "cat /proc/$pid/stat 2>/dev/null" | sed 's/^.*) //' | awk '{print $12 + $13}' | grep . || echo -
    done
}

# Waits for the charge counter to step from [1], looking every [3] s, for up to [2] s.
step() {
    local from=$1 since
    since=$(date +%s)
    while [ "$(counter)" = "$from" ]; do
        [ $(($(date +%s) - since)) -lt "$2" ] || fail "the charge counter hasn't moved from $from µAh in $2 s"
        sleep "$3"
    done
}

echo "soak: waiting for the charge counter's next step to start on"
step "$(counter)" 3600 5
sh dumpsys batterystats --reset >/dev/null
c0=$(counter)
l0=$(level)
t0=$(date +%s)
mapfile -t k0 < <(ticks)
echo "soak: started at $(date +%H:%M), counter $c0 µAh; back in $MINUTES min"

sleep $((MINUTES * 60))

echo "soak: waiting for the charge counter's next step to end on"
step "$(counter)" 3600 15
c1=$(counter)
l1=$(level)
t1=$(date +%s)
mapfile -t k1 < <(ticks)
sh dumpsys batterystats "$PKG" >"$RAW/$STAMP-$MODE-batterystats.txt"
sh dumpsys power >"$RAW/$STAMP-$MODE-power.txt"
state=$(wakefulness)

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
if [ "$MODE" != lit ]; then
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
