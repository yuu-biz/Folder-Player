#!/bin/sh
# usage: run.sh <avd-name> ; expects $HOME (with android-sdk and .android) mounted at the same path
set -e
AVD="$1"
SDK="$HOME/android-sdk"
socat TCP-LISTEN:5557,fork,reuseaddr TCP:127.0.0.1:5555 &
exec "$SDK/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
  -accel on -memory 2048 -port 5554 -no-metrics
