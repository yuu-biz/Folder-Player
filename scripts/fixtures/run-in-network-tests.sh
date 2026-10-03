#!/usr/bin/env bash
# Runs JVM tests that must be on the fixture Docker network (SSDP multicast for DLNA) inside a JDK container.
# Usage: scripts/fixtures/run-in-network-tests.sh [TestClass ...]   (default: the DLNA renderer integration test)
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT="$(pwd)"
CLASSES=("$@")
[ ${#CLASSES[@]} -eq 0 ] && CLASSES=(com.wing.folderplayer.cast.DlnaRendererIntegrationTest)
CP=$(bash scripts/wsl-gradle.sh -q printUnitTestClasspath | sed -n 's/^CP=//p')
[ -n "$CP" ] || { echo "classpath not found" >&2; exit 1; }
docker run --rm --network fp-fixtures \
  -v "$HOME:$HOME:ro" -v "$ROOT:$ROOT:ro" -w "$ROOT/app" \
  eclipse-temurin:21-jre \
  java -Dfp.dlna.renderer="FP Fixture Renderer" -Dfp.net.smb.host=samba -Dfp.net.webdav.url=http://webdav/dav \
       -Dfp.fixture.dir="$ROOT/build/fixture" -cp "$CP" org.junit.runner.JUnitCore "${CLASSES[@]}"
