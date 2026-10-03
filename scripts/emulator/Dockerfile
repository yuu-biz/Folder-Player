# Runs the SDK emulator (mounted from the host SDK) headless with KVM. adb is exposed through socat on 0.0.0.0:5557.
FROM ubuntu:24.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    libgl1 libegl1 libpulse0 libnss3 libxcomposite1 libxcursor1 libxdamage1 libxi6 libxtst6 libxrandr2 \
    libasound2t64 libdbus-1-3 libxkbfile1 libxkbcommon0 libxkbcommon-x11-0 libfontconfig1 libbsd0 socat iproute2 \
    && rm -rf /var/lib/apt/lists/*
COPY run.sh /run.sh
RUN chmod +x /run.sh
ENTRYPOINT ["/run.sh"]
