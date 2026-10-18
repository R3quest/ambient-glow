#!/usr/bin/env bash
# Self-test of soak_lib.sh (make scripts-test) on a scripted adb: a stall and a lost phone tried
# again, a remote command's failure not, and the charge counter's step never taken from a failed
# read. No phone needed.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
fake=$(mktemp -d)
trap 'rm -rf "$fake"' EXIT

# The fake adb answers from $fake/plan, one line per call: "stall", "offline", "rc N" or the text
# to print (with a \r, as adb's pty adds one); each call is counted in $fake/calls.
cat >"$fake/adb" <<'FAKE'
#!/usr/bin/env bash
dir=$(dirname "$0")
echo x >>"$dir/calls"
line=$(head -n 1 "$dir/plan")
sed -i 1d "$dir/plan"
case "$line" in
    stall) sleep 5 ;;
    offline) echo "adb: device offline" >&2; exit 1 ;;
    rc\ *) exit "${line#rc }" ;;
    *) printf '%s\r\n' "$line" ;;
esac
FAKE
chmod +x "$fake/adb"

ADB=$fake/adb
ADB_TIMEOUT=1
ADB_RETRY_S=0
fail() { echo "fail: $*" >&2; exit 1; }
# shellcheck source=soak_lib.sh
. "$here/soak_lib.sh"

plan() { printf '%s\n' "$@" >"$fake/plan"; : >"$fake/calls"; }
calls() { wc -l <"$fake/calls"; }
check() { [ "$1" = "$2" ] || { echo "soak_lib_test: $3: got '$1', want '$2'" >&2; exit 1; }; }

plan stall "mWakefulness=Asleep"
check "$(sh dumpsys power 2>/dev/null)" "mWakefulness=Asleep" "a stalled call is tried again, its \\r gone"
check "$(calls)" 2 "a stalled call is tried again"

plan offline offline "level: 80"
check "$(sh dumpsys battery 2>/dev/null)" "level: 80" "a lost phone is tried again"

plan "rc 1"
rc=0
sh pidof app.lumement >/dev/null 2>&1 || rc=$?
check "$rc:$(calls)" "1:1" "a remote command's failure comes back once, as it is"

plan stall stall stall
rc=0
sh dumpsys battery >/dev/null 2>&1 || rc=$?
check "$rc:$(calls)" "125:3" "three stalls give up"

# step: from 3700000, a failed read (offline three times) and a held one are no step; 3696300 is.
plan offline offline offline "  Charge counter: 3700000" "  Charge counter: 3696300"
step 3700000 30 0 2>/dev/null
check "$(calls)" 5 "the step waited past the failed read"

plan "  Charge counter: 3700000"
check "$(counter_read)" 3700000 "the counter read"
plan "  level: 80"
rc=0
(counter_read >/dev/null 2>&1) || rc=$?
check "$rc" 1 "no counter in the output stops the run"

echo "soak_lib_test.sh ok"
