# DLNA MediaRenderer fixture: gmrender-resurrect decodes the stream with GStreamer (MP3/FLAC) into a clocked fakesink.
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends gmediarender gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
    gstreamer1.0-tools ca-certificates && rm -rf /var/lib/apt/lists/*
ENTRYPOINT ["gmediarender"]
