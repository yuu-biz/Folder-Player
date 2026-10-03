#!/usr/bin/env bash
# The Fork installs next to the original APK (different applicationId, own version, own signature) and both start.
#   coexist.sh <serial> <original-app.apk>
set -euo pipefail
cd "$(dirname "$0")/../.."
S="$1"
REF="${2:?path to an APK of the original app}"
FORK=app/build/outputs/apk/debug/app-debug.apk
ADB="$HOME/android-sdk/platform-tools/adb"
A() { "$ADB" -s "$S" "$@"; }
OUT=build/device-results/coexistence.txt
{
  echo "# $(date -u +%FT%TZ) device $S API $(A shell getprop ro.build.version.sdk | tr -d '\r') commit $(git rev-parse --short HEAD)"
  echo "reference $REF sha256 $(sha256sum "$REF" | cut -d' ' -f1)"
  echo "fork      $FORK sha256 $(sha256sum "$FORK" | cut -d' ' -f1)"
  A install -r "$REF" | tail -1
  A install -r -t "$FORK" | tail -1
  echo "## installed packages"
  A shell pm list packages com.wing.folderplayer | sort
  for p in com.wing.folderplayer com.wing.folderplayer.fork; do
    echo "## $p"
    A shell dumpsys package "$p" | grep -E "versionName|versionCode|signatures|pkgFlags" | head -4
    A shell monkey -p "$p" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 4
    echo "top activity: $(A shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' | tr -d '\r')"
    A shell am force-stop "$p"
  done
  echo "## label"
  A shell dumpsys package com.wing.folderplayer.fork | grep -m1 -i "label" || true
} | tee "$OUT"
grep -q "package:com.wing.folderplayer$" "$OUT" && grep -q "package:com.wing.folderplayer.fork" "$OUT"
