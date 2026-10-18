# Sourced by soak.sh and soak_set.sh: adb shell with a timeout, and the charge counter read and
# waited on. Needs ADB and a fail function; ADB_TIMEOUT (60 s) and ADB_RETRY_S (5 s) are for the
# self-test (soak_lib_test.sh).

# adb shell, every call with a timeout: over Wi-Fi adb stalls now and then while the phone is in
# deep sleep, and a run left hanging measures nothing. A stalled call, or adb losing the phone, is
# tried twice more a few seconds apart; the remote command's own exit code and stderr come back as
# they are. Never reads stdin: under timeout, a read from the terminal stops adb (SIGTTIN) until
# the timeout ends it. ADB_TRIES (3) is how many times, sh_once's 1.
sh() {
    local try rc out err why tries=${ADB_TRIES:-3}
    err=$(mktemp)
    for ((try = 1; try <= tries; try++)); do
        rc=0
        out=$(timeout "${ADB_TIMEOUT:-60}" "$ADB" shell "$@" </dev/null 2>"$err") || rc=$?
        if [ "$rc" = 124 ] || grep -qE '^(adb: |error: )' "$err"; then
            why=stalled
            [ "$rc" = 124 ] || why=$(head -n 1 "$err")
            echo "adb: no answer to '$*' ($why; try $try of $tries)" >&2
            [ "$try" = "$tries" ] || sleep "${ADB_RETRY_S:-5}"
            continue
        fi
        cat "$err" >&2
        rm -f "$err"
        [ -z "$out" ] || printf '%s\n' "$out" | tr -d '\r'
        return "$rc"
    done
    rm -f "$err"
    echo "adb: the phone didn't answer '$*' ($tries time(s))" >&2
    return 125
}

# sh for a call with an effect on the phone (a test message, a key): tried once only, as one that
# stalled may still have gone through. A second post is a new message to Lumement (a new message
# time), whose bright phase would start over inside the measured window; a second key wakes the
# phone again.
sh_once() { ADB_TRIES=1 sh "$@"; }

counter() { sh dumpsys battery | awk -F': ' '/Charge counter/ && !seen {print $2; seen = 1}'; }

# The charge counter in µAh, a whole number; anything else (a failed read) stops the run, as a
# misread start or end would skew its mA.
counter_read() {
    local c
    c=$(counter) || true
    [[ "$c" =~ ^[0-9]+$ ]] || fail "the charge counter reads '$c', not µAh"
    echo "$c"
}

# Waits for the charge counter to step from [1], looking every [3] s, for up to [2] s. A read that
# fails is no step: the run starts and ends on one.
step() {
    local from=$1 since now
    since=$(date +%s)
    while :; do
        now=$(counter) || now=
        [[ "$now" =~ ^[0-9]+$ ]] && [ "$now" != "$from" ] && return 0
        [ $(($(date +%s) - since)) -lt "$2" ] || fail "the charge counter hasn't moved from $from µAh in $2 s"
        sleep "$3"
    done
}
