#!/usr/bin/env bash
# Usage: scripts/fixtures/servers.sh up|down|props
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
CERTS="$ROOT/build/fixture-servers/certs"
case "${1:-up}" in
  up)
    mkdir -p "$CERTS"
    if [ ! -f "$CERTS/ftps.pem" ]; then
      openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=ftps-fixture" \
        -keyout "$CERTS/key.pem" -out "$CERTS/cert.pem" >/dev/null 2>&1
      cat "$CERTS/key.pem" "$CERTS/cert.pem" > "$CERTS/ftps.pem"
    fi
    docker compose up -d --build
    ;;
  down) docker compose down -v ;;
  props)
    # System properties for the JVM protocol tests (gradle -D... are forwarded by app/build.gradle.kts).
    FP=$(openssl x509 -in "$CERTS/cert.pem" -outform DER | sha256sum | cut -d' ' -f1)
    echo "-Dfp.smb.host=127.0.0.1 -Dfp.smb.port=14445 -Dfp.ftp.host=127.0.0.1 -Dfp.webdav.url=http://127.0.0.1:18580/dav -Dfp.ftps.pin=$FP"
    ;;
esac
