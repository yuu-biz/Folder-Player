#!/usr/bin/env bash
# Tool check: native libraries in the APK are 16 KB page aligned (ELF LOAD p_align) and stored uncompressed /
# zip-aligned for 16 KB pages. Writes build/device-results/16k-check.txt.
#   check-16k.sh [apk]     default app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail
cd "$(dirname "$0")/.."
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
SDK="$HOME/android-sdk"
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
NDK="$SDK/ndk/28.2.13676358"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
OUT=build/device-results/16k-check.txt
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
{
  echo "# $(date -u +%FT%TZ) commit $(git rev-parse --short HEAD) apk $APK sha256 $(sha256sum "$APK" | cut -d' ' -f1)"
  echo "## zipalign -c -P 16 -v 4"
  "$BT/zipalign" -c -P 16 -v 4 "$APK" | grep -E "lib/|Verification" || true
  unzip -q -o "$APK" 'lib/*' -d "$TMP"
  RC=0
  for so in "$TMP"/lib/*/*.so; do
    abi=$(basename "$(dirname "$so")")
    echo "## $abi/$(basename "$so")"
    "$READELF" -lW "$so" | awk '/LOAD/ {print "LOAD align " $NF}'
    if "$READELF" -lW "$so" | awk '/LOAD/ {print $NF}' | grep -vqx '0x4000'; then echo "NOT 16KB aligned"; RC=1; fi
  done
  echo "result: $([ $RC -eq 0 ] && echo PASS || echo FAIL)"
} | tee "$OUT"
grep -q "result: PASS" "$OUT"
