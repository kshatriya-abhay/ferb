#!/usr/bin/env bash
# Ferb's component tests on the connected phone (debug build), in the order some of them need:
# the instrumented tests, then a list kept across a killed process, the permission fallbacks with
# permissions revoked (and restored as they were, on any exit), a reminder firing after the process is killed,
# and one rescheduled after a reinstall. Take /tmp/ferb-validate.lock first (shared with Ferb Link).
# QUIET=1: only what runs in the background, for a phone in use: the tests marked @InFront (a
# screen in front, a notification, the mic, a volume change) and the reminder-firing checks are
# left out (review P2-171). ANDROID_SERIAL picks the phone (e.g. wireless adb).
set -uo pipefail
cd "$(dirname "$0")/.."
APP=dev.abhay.assistant
RUN="$APP.test/androidx.test.runner.AndroidJUnitRunner"
T=dev.abhay.assistant
pass=0; fail=0
result() { if [ "$1" = 0 ]; then pass=$((pass + 1)); echo "PASS $2"; else fail=$((fail + 1)); echo "FAIL $2"; fi; }
instrument() { # args… → 0 if all tests passed
  out=$(adb shell am instrument -w "$@" "$RUN" 2>&1)
  echo "$out" | grep -E "^OK|^FAILURES|^Tests run|Error|Exception:" | head -5 >&2
  echo "$out" | grep -q "^OK"
}
# Installs, force-stops and revoked permissions end Ferb's process: never while the user has it open.
not_in_use() {
  for i in $(seq 1 60); do
    adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q "$APP" || return 0
    [ "$i" = 1 ] && echo "Ferb is open on the phone: waiting" >&2
    sleep 10
  done
  echo "Ferb stayed open: stopping" >&2; exit 2
}
notified() { adb shell dumpsys notification --noredact | grep -q "Ferb device test"; }

not_in_use
./gradlew --no-daemon -q :app:installDebug :app:installDebugAndroidTest || exit 1

# The user's grants, read before anything changes them, and put back however the script ends
# (review P2-167: notifications were granted before they were read, and Ctrl-C left grants revoked).
# The dump is captured, then matched: piped into grep -q under pipefail, adb's SIGPIPE once grep had
# matched counted a granted permission as not granted, and the restore revoked it (review P2-168).
perms="android.permission.READ_CONTACTS android.permission.READ_CALENDAR android.permission.POST_NOTIFICATIONS"
dump=$(adb shell dumpsys package $APP) || { echo "Couldn't read Ferb's permissions: stopping" >&2; exit 2; }
grep -q "runtime permissions:" <<< "$dump" || { echo "No runtime permissions in Ferb's dump: stopping" >&2; exit 2; }
had=""
for p in $perms; do grep -q "$p: granted=true" <<< "$dump" && had="$had $p"; done
restore() {
  for p in $perms; do
    case " $had " in *" $p "*) adb shell pm grant $APP $p ;; *) adb shell pm revoke $APP $p ;; esac
  done
}
trap restore EXIT
trap 'exit 130' INT TERM
adb shell pm grant $APP android.permission.POST_NOTIFICATIONS

# 1. Every instrumented test (the special ones skip themselves); quiet: none the user would notice.
if [ "${QUIET:-0}" = 1 ]; then instrument -e notAnnotation $T.InFront; else instrument; fi
result $? "instrumented tests"

# 2. A list across a killed process.
# The write is checked, and the read fails without it (review P2-175: a failed write read as "kept").
wrote=0; instrument -e class $T.ListsPersistDeviceTest#writeBeforeKill -e persist write || wrote=1
not_in_use
adb shell am force-stop $APP
if [ "$wrote" = 0 ]; then instrument -e class $T.ListsPersistDeviceTest#readAfterKill -e persist read; result $? "list kept across a killed process"
else result 1 "list written before the kill"; fi

# 3. Permission fallbacks: revoked, then restored as they were.
not_in_use
for p in $perms; do adb shell pm revoke $APP $p; done
instrument -e class $T.PermissionsDeviceTest -e expect revoked; result $? "fallbacks with permissions revoked"
for p in $perms; do adb shell pm grant $APP $p; done
instrument -e class $T.PermissionsDeviceTest -e expect granted; result $? "no fallbacks with permissions granted"
# Notifications stay on for the reminder checks below; every grant is put back on exit.

if [ "${QUIET:-0}" = 1 ]; then echo "$pass passed, $fail failed (quiet: @InFront tests and reminder notifications left out)"; [ "$fail" = 0 ]; exit; fi

# 4. A reminder fires after the process is killed.
instrument -e class $T.RemindersDeviceTest#scheduleForLater -e later 1
adb shell am kill $APP
ok=1; for i in $(seq 1 30); do sleep 5; notified && { ok=0; break; }; done
result $ok "reminder fired after the process was killed"
instrument -e class $T.RemindersDeviceTest#exactAlarmsAreAllowed >/dev/null 2>&1 # its cleanup removes the test reminder

# 5. A reminder rescheduled after a reinstall (an app update clears its alarms).
instrument -e class $T.RemindersDeviceTest#scheduleForLater -e later 1
adb install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
ok=1; for i in $(seq 1 30); do sleep 5; notified && { ok=0; break; }; done
result $ok "reminder fired after a reinstall"
instrument -e class $T.RemindersDeviceTest#exactAlarmsAreAllowed >/dev/null 2>&1

echo "$pass passed, $fail failed"
[ "$fail" = 0 ]
