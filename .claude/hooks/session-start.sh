#!/bin/bash
# SessionStart hook for Claude Code cloud sessions: installs the toolchain CI uses (.github/workflows/ci.yml) so
# scripts/wsl-gradle.sh can build, run the JVM unit tests and lint. Safe to re-run; every step skips work already done.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}"

# Keep in step with ci.yml / release.yml.
NDK_VERSION=28.2.13676358
BUILD_TOOLS=35.0.0
CMAKE_VERSION=3.22.1
JBR_NAME=jbrsdk-21.0.11-linux-x64-b1163.116
JBR_SHA256=097bf328b5bf5a236c095b8c8e5204c0eda91e5baa06b37383a7754b10671d8b
CMDLINE_TOOLS_ZIP=commandlinetools-linux-16111833_latest.zip
CMDLINE_TOOLS_SHA1=e025545c62a8e64c7559119566a569fb1dec5f60

# Same default locations as scripts/wsl-gradle.sh and native/build-ffmpeg.sh.
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export FP_JBR_HOME="$HOME/jdks/$JBR_NAME"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# JetBrains JDK 21 (SDK build): gradle/gradle-daemon-jvm.properties asks for it.
if [ ! -x "$FP_JBR_HOME/bin/javac" ]; then
  echo "Installing $JBR_NAME"
  curl -fsSL -o "$tmp/jbr.tar.gz" "https://cache-redirector.jetbrains.com/intellij-jbr/$JBR_NAME.tar.gz"
  echo "$JBR_SHA256  $tmp/jbr.tar.gz" | sha256sum -c -
  rm -rf "$FP_JBR_HOME"
  mkdir -p "$FP_JBR_HOME"
  tar -xzf "$tmp/jbr.tar.gz" -C "$FP_JBR_HOME" --strip-components=1
fi

# Android command-line tools (sdkmanager).
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "Installing Android command-line tools"
  curl -fsSL -o "$tmp/cmdline-tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  echo "$CMDLINE_TOOLS_SHA1  $tmp/cmdline-tools.zip" | sha1sum -c -
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/ct"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  mv "$tmp/ct/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

# SDK packages: sdkmanager is a no-op for packages already installed, but it is slow, so check first.
packages=()
[ -f "$ANDROID_HOME/platforms/android-34/android.jar" ] || packages+=("platforms;android-34")
[ -f "$ANDROID_HOME/build-tools/$BUILD_TOOLS/source.properties" ] || packages+=("build-tools;$BUILD_TOOLS")
[ -f "$ANDROID_HOME/ndk/$NDK_VERSION/source.properties" ] || packages+=("ndk;$NDK_VERSION")
[ -x "$ANDROID_HOME/cmake/$CMAKE_VERSION/bin/cmake" ] || packages+=("cmake;$CMAKE_VERSION")
[ -x "$ANDROID_HOME/platform-tools/adb" ] || packages+=("platform-tools")
if [ ${#packages[@]} -gt 0 ]; then
  echo "Installing Android SDK packages: ${packages[*]}"
  export JAVA_HOME="$FP_JBR_HOME"
  yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true
  "$SDKMANAGER" --install "${packages[@]}" > /dev/null
fi

# Plain ./gradlew (without scripts/wsl-gradle.sh) also finds the SDK and the JetBrains JDK.
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
mkdir -p "$HOME/.gradle"
touch "$HOME/.gradle/gradle.properties"
grep -q '^org.gradle.java.installations.paths=' "$HOME/.gradle/gradle.properties" \
  || echo "org.gradle.java.installations.paths=$FP_JBR_HOME" >> "$HOME/.gradle/gradle.properties"

# FFmpeg static libraries for libfpnative (skipped when already built, as the Gradle task does).
if [ ! -f native/out/arm64-v8a/lib/libavcodec.a ] || [ ! -f native/out/x86_64/lib/libavcodec.a ]; then
  echo "Building FFmpeg (native/build-ffmpeg.sh)"
  bash native/build-ffmpeg.sh
fi

# Session environment for Claude's shell.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export ANDROID_HOME=\"$ANDROID_HOME\""
    echo "export ANDROID_SDK_ROOT=\"$ANDROID_HOME\""
    echo "export FP_JBR_HOME=\"$FP_JBR_HOME\""
    echo "export JAVA_HOME=\"$FP_JBR_HOME\""
    echo "export PATH=\"$FP_JBR_HOME/bin:$ANDROID_HOME/platform-tools:\$PATH\""
  } >> "$CLAUDE_ENV_FILE"
fi

# Warm the Gradle wrapper and dependency caches so the first build in the session is fast. A compile error on the
# current branch must not break the session start, so this step never fails the hook. Maven Central sometimes answers
# 429 (Too Many Requests) through the shared egress; Gradle keeps what it already downloaded, so a retry finishes it.
echo "Warming Gradle caches"
for attempt in 1 2 3; do
  if bash scripts/wsl-gradle.sh --no-daemon -q :app:compileDebugUnitTestKotlin > "$tmp/gradle-warmup.log" 2>&1; then
    break
  fi
  if [ "$attempt" -eq 3 ]; then
    echo "Gradle warm-up failed (non-fatal):"
    grep -v '^w: ' "$tmp/gradle-warmup.log" | tail -20
  else
    sleep 10
  fi
done

echo "Session setup complete"
