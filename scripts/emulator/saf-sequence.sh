#!/usr/bin/env bash
# SAF sequence on one device: pick the .nomedia folder through DocumentsUI, wait until the system has written the
# persisted grant (urigrants.xml is flushed ~10 s after a change), reboot the device, then verify the grant/playback and
# the revoke → re-pick path. Output goes next to the other instrumentation evidence.
#   saf-sequence.sh <serial>
set -euo pipefail
cd "$(dirname "$0")/../.."
S="$1"
ADB="$HOME/android-sdk/platform-tools/adb"
A() { "$ADB" -s "$S" "$@"; }
I=scripts/emulator/instrument.sh
SDK=$(A shell getprop ro.build.version.sdk | tr -d '\r')
OUT="build/device-results/instrumentation/api$SDK-$(echo "$S" | tr ':.' '__')"
mkdir -p "$OUT"
LOG="$OUT/SafTest_sequence.txt"
: > "$LOG"
say() { echo "$*" | tee -a "$LOG"; }

bash $I "$S" perms ALL >/dev/null
say "$(bash $I "$S" run "SafTest#a_pickNomediaFolderThroughSystemPicker")"
sleep 20
say "grants for the app in urigrants.xml before reboot: $(A shell su 0 cat /data/system/urigrants.xml 2>/dev/null | grep -c 'folderplayer.fork' || true)"
say "uptime before reboot: $(A shell uptime | tr -d '\r')"
A reboot
sleep 20
for _ in $(seq 1 60); do
  [ "$(A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  "$ADB" connect "$S" >/dev/null 2>&1 || true
  sleep 5
done
sleep 10
say "uptime after reboot: $(A shell uptime | tr -d '\r')"
say "$(bash $I "$S" run "SafTest#b_grantSurvivesRestartAndPlays")"
say "$(bash $I "$S" run "SafTest#c_revokedGrantIsReportedAndRepickRestoresSameSource")"
say "$(bash $I "$S" run "SafTest#d_trickyNamesThroughSaf")"
! grep -qE '^(FAIL|SKIP)' "$LOG"
