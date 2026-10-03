#!/usr/bin/env bash
# Runs instrumentation tests on a device/emulator and stores the raw output as evidence.
#   instrument.sh <serial> install                 install app + test APKs (built with assembleDebug assembleDebugAndroidTest)
#   instrument.sh <serial> perms <ALL|AUDIO_ONLY|PARTIAL|NONE>
#   instrument.sh <serial> run <Class[#method]> [tag] [-e key value ...]
#   instrument.sh <serial> push-fixture
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT="$(pwd)"
ADB="$HOME/android-sdk/platform-tools/adb"
S="$1"; shift
A() { "$ADB" -s "$S" "$@"; }
PKG=com.wing.folderplayer.fork
SDK=$(A shell getprop ro.build.version.sdk | tr -d '\r')
OUT="$ROOT/build/device-results/instrumentation/api$SDK-$(echo "$S" | tr ':.' '__')"
case "$1" in
  install)
    A install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
    A install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
    echo "installed on $S (API $SDK)"
    ;;
  run-each)
    # One process per test method (like Android Test Orchestrator): no state leaks between methods.
    CLS="$2"; shift 2
    METHODS=$(grep -oE "@Test fun [A-Za-z0-9_]+" "app/src/androidTest/java/com/wing/folderplayer/$CLS.kt" | awk '{print $3}')
    RC=0
    for m in $METHODS; do bash "$0" "$S" run "$CLS#$m" "$@" || RC=1; done
    exit $RC
    ;;
  push-fixture)
    A shell rm -rf /sdcard/Music/fixture
    A shell mkdir -p /sdcard/Music
    A push build/fixture/fixture /sdcard/Music/ >/dev/null
    A shell content call --uri content://media/external/file --method scan_volume --arg external_primary >/dev/null 2>&1 || true
    echo "fixture pushed"
    ;;
  perms)
    STATE="$2"
    A shell am force-stop $PKG
    if [ "$SDK" -ge 33 ]; then
      for p in READ_MEDIA_AUDIO READ_MEDIA_IMAGES READ_MEDIA_VISUAL_USER_SELECTED POST_NOTIFICATIONS; do
        A shell pm revoke $PKG android.permission.$p >/dev/null 2>&1 || true
      done
      case "$STATE" in
        ALL) A shell pm grant $PKG android.permission.READ_MEDIA_AUDIO; A shell pm grant $PKG android.permission.READ_MEDIA_IMAGES ;;
        AUDIO_ONLY) A shell pm grant $PKG android.permission.READ_MEDIA_AUDIO ;;
        # Partial photo access exists from API 34; on 33 the grant fails and images stay denied.
        PARTIAL) A shell pm grant $PKG android.permission.READ_MEDIA_AUDIO; A shell pm grant $PKG android.permission.READ_MEDIA_VISUAL_USER_SELECTED 2>/dev/null || true ;;
        NONE) ;;
      esac
      A shell pm grant $PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    else
      A shell pm revoke $PKG android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
      case "$STATE" in ALL|AUDIO_ONLY|PARTIAL) A shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE ;; esac
    fi
    echo "permissions set: $STATE"
    ;;
  run)
    CLS="$2"; shift 2
    TAG="${1:-}"
    [ -n "$TAG" ] && [ "${TAG#-}" = "$TAG" ] && shift || TAG=""
    mkdir -p "$OUT"
    NAME="$(echo "$CLS" | sed 's/.*\.//; s/#/_/')${TAG:+-$TAG}"
    set +e
    A shell am instrument -w -r "$@" -e class "com.wing.folderplayer.$CLS" $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/$NAME.txt" 2>&1
    A logcat -d -s FpTest:I > "$OUT/$NAME.fptest.txt" 2>&1
    A logcat -c
    set -e
    if grep -q "AssumptionViolatedException" "$OUT/$NAME.txt"; then
      echo "SKIP $NAME (assumption not met - not a pass)"; grep -m3 "AssumptionViolatedException" "$OUT/$NAME.txt"; exit 2
    elif grep -q "^OK (" "$OUT/$NAME.txt"; then echo "PASS $NAME ($(grep '^OK (' "$OUT/$NAME.txt"))"; else
      echo "FAIL $NAME"; grep -E "INSTRUMENTATION_STATUS: stack=|^FAILURES|Tests run|AssumptionViolated|INSTRUMENTATION_RESULT" "$OUT/$NAME.txt" | head -8; exit 1; fi
    ;;
esac
