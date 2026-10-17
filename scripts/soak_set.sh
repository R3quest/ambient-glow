#!/usr/bin/env bash
# The battery set behind the defaults (make soak-set): every route the LED takes, one run after
# another, ~4.5 hours with the phone unplugged and left alone. The always-on display is switched
# for each run as it needs, and put back as it was when the set ends.
# - idle, 60 min: the floor (adb, radios) every other run sits on;
# - lit, 10 min, twice: what a bright minute costs;
# - hybrid BRIGHT=OFF, 60 min, twice: the still LED on the always-on display, the tail;
# - hybrid BRIGHT=TEN, 60 min: the shipped flow end to end, to check the parts add up.
set -euo pipefail
ADB=${ADB:-adb}
sh() { "$ADB" shell "$@" | tr -d '\r'; }
log() { echo "[$(date +%H:%M)] soak-set: $*"; }

aod_was=$(sh settings get system aod_mode)
new_was=$(sh settings get system aod_show_for_new_noti)
restore() {
    sh settings put system aod_mode "$aod_was"
    sh settings put system aod_show_for_new_noti "$new_was"
    log "the always-on display put back (aod_mode $aod_was, for new notifications $new_was)"
}
trap restore EXIT

run() {
    log "$*"
    ADB="$ADB" BRIGHT="${BRIGHT:-TEN}" NOTE="soak-set" scripts/soak.sh "$@"
}

# A lit run's bright phase outlives it while other messages wait: let it run out, so the next run
# starts dark. 16 minutes covers the longest phase (Bright LED 15).
wait_dark() {
    local i
    for i in $(seq 64); do
        # Held now: the tag as the line's second field, as soak.sh reads it (the history lists old ones too).
        [ "$(sh dumpsys power | awk -v tag="'Lumement:led'" '$2 == tag' | wc -l)" = 0 ] && return 0
        sleep 15
    done
    log "the lit LED is still up after 16 minutes"
    exit 1
}

sh settings put system aod_mode 0
run idle 60
run lit 10
run lit 10
wait_dark

sh settings put system aod_mode 1
sh settings put system aod_show_for_new_noti 1
BRIGHT=OFF run hybrid 60
BRIGHT=OFF run hybrid 60
BRIGHT=TEN run hybrid 60
log "done: see measurements/soak.tsv"
