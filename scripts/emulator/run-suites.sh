#!/usr/bin/env bash
# Runs the instrumentation suites on one device, one process per test method, against the Docker fixture servers
# (emulator on the fp-fixtures network). Results: build/device-results/instrumentation/api<sdk>-<serial>/.
#   run-suites.sh <serial> [Suite ...]        default: all suites below, in this order
set -uo pipefail
cd "$(dirname "$0")/../.."
S="$1"; shift
I=scripts/emulator/instrument.sh
CERTS=build/fixture-servers/certs
PIN=$(openssl x509 -in "$CERTS/cert.pem" -outform DER | sha256sum | cut -d' ' -f1)
ARGS=(-e smb_host samba -e webdav_url http://webdav/dav -e ftp_host ftp -e ftps_pin "$PIN")
SUITES=("$@")
# The WSL VM (~24 GB) also hosts other containers: free the build daemons' memory before running an emulator.
bash scripts/wsl-gradle.sh --stop >/dev/null 2>&1 || true
pkill -f "[K]otlinCompileDaemon" 2>/dev/null || true
[ ${#SUITES[@]} -eq 0 ] && SUITES=(LocalAccessTest MigrationTest SourceRegistryTest NetworkPlaybackTest NativeDecodeTest
  PlaybackServiceTest NotificationSwitchTest RetryExportTest SourceUiTest BrowserUiTest NavigationUiTest LibraryUiTest SyncTagsUiTest
  SafTest AiNfoUiTest FontUiTest LicensesUiTest CastCleanupTest NativeIoErrorTest JapaneseUiTest PlayerSkipTest
  OpenPlayerColdStartTest)
SDK=$("$HOME/android-sdk/platform-tools/adb" -s "$S" shell getprop ro.build.version.sdk | tr -d '\r')
OUT="build/device-results/instrumentation/api$SDK-$(echo "$S" | tr ':.' '__')"
mkdir -p "$OUT"
SUMMARY="$OUT/SUMMARY.txt"
echo "# $(date -u +%FT%TZ) device $S API $SDK commit $(git rev-parse --short HEAD)" >> "$SUMMARY"
# Some emulator images come up without an IPv4 default route; make sure the fixture servers are reachable first.
ADB="$HOME/android-sdk/platform-tools/adb"
for attempt in 1 2 3; do
  if "$ADB" -s "$S" shell "toybox nc -w 3 samba 445 </dev/null && echo up" | grep -q up; then echo "fixture network reachable" >> "$SUMMARY"; break; fi
  echo "fixture network unreachable (attempt $attempt): toggling Wi-Fi" | tee -a "$SUMMARY"
  "$ADB" -s "$S" shell svc wifi disable; sleep 3; "$ADB" -s "$S" shell svc wifi enable; sleep 12
done
RC=0
for suite in "${SUITES[@]}"; do
  case "$suite" in
    LocalAccessTest)
      # Permission states are set from the host between processes.
      # grant → revoke images → partial → deny all → re-grant (pm revoke also kills the app process).
      for st in ALL AUDIO_ONLY PARTIAL NONE REGRANT; do
        p=$st; [ "$st" = REGRANT ] && p=ALL
        bash $I "$S" perms "$p" >/dev/null
        if [ "$p" = ALL ]; then cls=LocalAccessTest; else cls="LocalAccessTest#permissionStateIsDiagnosedAndAudioSurvivesImageDenial"; fi
        bash $I "$S" run "$cls" "$st" -e perm_state "$p" | tee -a "$SUMMARY" | head -1
        [ "${PIPESTATUS[0]}" -eq 0 ] || RC=1
      done ;;
    SafTest) bash scripts/emulator/saf-sequence.sh "$S" | tee -a "$SUMMARY" || RC=1 ;;
    *) bash $I "$S" run-each "$suite" "${ARGS[@]}" | tee -a "$SUMMARY" || RC=1 ;;
  esac
done
echo "exit $RC" >> "$SUMMARY"
exit $RC
