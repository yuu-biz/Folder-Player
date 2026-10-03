#!/usr/bin/env bash
# Runs the Gradle wrapper inside WSL/Linux with the toolchain used for this fork.
# Usage: scripts/wsl-gradle.sh <gradle args...>
# Override locations with ANDROID_HOME / FP_JBR_HOME if your layout differs.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# gradle/gradle-daemon-jvm.properties pins a JetBrains JDK 21; the JBR *SDK* build (jbrsdk-21.0.11-linux-x64-b1163.116.tar.gz,
# sha256 097bf328b5bf5a236c095b8c8e5204c0eda91e5baa06b37383a7754b10671d8b) satisfies it. The runtime-only JBR does not.
export FP_JBR_HOME="${FP_JBR_HOME:-$HOME/jdks/jbrsdk-21.0.11-linux-x64-b1163.116}"
export JAVA_HOME="$FP_JBR_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
exec ./gradlew -Porg.gradle.java.installations.paths="$FP_JBR_HOME" "$@"
