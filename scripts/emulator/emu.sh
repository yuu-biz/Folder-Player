#!/usr/bin/env bash
# scripts/emulator/emu.sh start <avd> <hostPort> | stop <avd> | adb <hostPort> <adb args...>
# Emulators run in Docker (KVM device) on the fixture network so they can reach samba/ftp/webdav by name.
set -euo pipefail
cd "$(dirname "$0")"
ADB="$HOME/android-sdk/platform-tools/adb"
case "$1" in
  start)
    AVD="$2"; PORT="$3"
    docker build -q -t fp-emulator . >/dev/null
    docker rm -f "emu-$AVD" >/dev/null 2>&1 || true
    docker run -d --name "emu-$AVD" --device /dev/kvm --network fp-fixtures -e HOME="$HOME" -u "$(id -u):$(id -g)" \
      --group-add "$(stat -c %g /dev/kvm 2>/dev/null || echo 0)" \
      -v "$HOME:$HOME" -p "127.0.0.1:$PORT:5557" fp-emulator "$AVD" >/dev/null
    for i in $(seq 1 120); do
      "$ADB" connect "127.0.0.1:$PORT" >/dev/null 2>&1 || true
      if [ "$("$ADB" -s "127.0.0.1:$PORT" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        echo "booted $AVD on 127.0.0.1:$PORT"; exit 0
      fi
      sleep 5
    done
    echo "boot timeout"; docker logs --tail 30 "emu-$AVD"; exit 1
    ;;
  stop) docker rm -f "emu-$2" >/dev/null 2>&1 || true ;;
  adb) PORT="$2"; shift 2; "$ADB" -s "127.0.0.1:$PORT" "$@" ;;
esac
