#!/usr/bin/env bash
# Ferb's component tests on the connected phone (debug build), in the order some of them need:
# the instrumented tests, then a list kept across a killed process, the permission fallbacks with
# permissions revoked (and restored as they were), a reminder firing after the process is killed,
# and one rescheduled after a reinstall. Take /tmp/ferb-validate.lock first (shared with Ferb Link).
# QUIET=1: only what runs in the background, for a phone in use: no notifications (they'd sound),
# so the reminder-firing checks are left out. ANDROID_SERIAL picks the phone (e.g. wireless adb).
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
./gradlew --no-daemon -q installDebug installDebugAndroidTest || exit 1
adb shell pm grant $APP android.permission.POST_NOTIFICATIONS

# 1. Every instrumented test (the special ones skip themselves); quiet: not the one that fires a notification.
if [ "${QUIET:-0}" = 1 ]; then instrument -e notClass $T.RemindersDeviceTest#aReminderFiresDoneAndSnooze; else instrument; fi
result $? "instrumented tests"

# 2. A list across a killed process.
instrument -e class $T.ListsPersistDeviceTest#writeBeforeKill -e persist write
not_in_use
adb shell am force-stop $APP
instrument -e class $T.ListsPersistDeviceTest#readAfterKill; result $? "list kept across a killed process"

# 3. Permission fallbacks: revoked, then restored as they were.
perms="android.permission.READ_CONTACTS android.permission.READ_CALENDAR android.permission.POST_NOTIFICATIONS"
had=""
not_in_use
for p in $perms; do adb shell dumpsys package $APP | grep -q "$p: granted=true" && had="$had $p"; adb shell pm revoke $APP $p; done
instrument -e class $T.PermissionsDeviceTest; result $? "fallbacks with permissions revoked"
for p in $perms; do adb shell pm grant $APP $p; done
instrument -e class $T.PermissionsDeviceTest; result $? "no fallbacks with permissions granted"
for p in $perms; do case " $had " in *" $p "*) ;; *) adb shell pm revoke $APP $p ;; esac; done
adb shell pm grant $APP android.permission.POST_NOTIFICATIONS

if [ "${QUIET:-0}" = 1 ]; then echo "$pass passed, $fail failed (quiet: reminder notifications left out)"; [ "$fail" = 0 ]; exit; fi

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
