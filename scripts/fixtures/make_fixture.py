#!/usr/bin/env python3
"""Builds the test fixture tree (all content generated here; no third-party media).

Usage (WSL): python3 scripts/fixtures/make_fixture.py [out_dir]
Requires: docker (mwader/static-ffmpeg:7.1, fp-fixture-mac built from tools/Dockerfile.mac), gcc.
Writes <out>/fixture/... plus <out>/extras/ and docs/fork/fixtures/MANIFEST.json.
"""
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "build", "fixture"))
FX = os.path.join(OUT, "fixture")
EXTRAS = os.path.join(OUT, "extras")
TMP = os.path.join(OUT, "tmp")
FFMPEG = "mwader/static-ffmpeg:7.1"

COLORS = {
    "RED": (230, 20, 20), "BLUE": (20, 60, 230), "ORANGE": (250, 140, 0), "MAGENTA": (220, 0, 200),
    "CYAN": (0, 210, 220), "WHITE": (245, 245, 245), "GREEN": (20, 190, 40), "PURPLE": (120, 30, 200),
    "OLIVE": (128, 128, 0), "YELLOW": (250, 230, 0), "PINK": (255, 120, 180), "GREY": (128, 128, 128),
}


def png(path, rgb, mark=0, size=300):
    """Solid colour with `mark` white bars along the top edge as a visible identifier."""
    w = h = size
    rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            bar = mark > 0 and y < 30 and (x // 20) < mark and (x // 10) % 2 == 0
            row += bytes((255, 255, 255)) if bar else bytes(rgb)
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def run(cmd):
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def ffmpeg(*args):
    run(["docker", "run", "--rm", "-v", f"{OUT}:/w", "-w", "/w", FFMPEG, "-hide_banner", "-loglevel", "error", "-y", *args])


def rel(p):
    return os.path.relpath(p, OUT).replace(os.sep, "/")


def to_jpg(png_path, jpg_path):
    ffmpeg("-i", rel(png_path), "-q:v", "3", rel(jpg_path))


def tone(path, seconds, freq, codec_args, extra=()):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    ffmpeg("-f", "lavfi", "-i", f"sine=frequency={freq}:sample_rate=44100:duration={seconds}",
           "-ac", "2", *extra, *codec_args, rel(path))


def id3_frame(fid, body):
    return fid.encode() + struct.pack(">I", len(body)) + b"\x00\x00" + body


def id3_tag(title, artist, album, track, lyrics=None, picture_png=None):
    def text(s):
        return b"\x03" + s.encode("utf-8")
    frames = id3_frame("TIT2", text(title)) + id3_frame("TPE1", text(artist)) + \
        id3_frame("TALB", text(album)) + id3_frame("TRCK", text(track))
    if lyrics:
        frames += id3_frame("USLT", b"\x03eng\x00" + lyrics.encode("utf-8"))
    if picture_png:
        with open(picture_png, "rb") as f:
            pic = f.read()
        frames += id3_frame("APIC", b"\x00image/png\x00\x03cover\x00" + pic)
    size = len(frames)
    ss = bytes([(size >> 21) & 0x7F, (size >> 14) & 0x7F, (size >> 7) & 0x7F, size & 0x7F])
    return b"ID3\x03\x00\x00" + ss + frames


def mp3(path, seconds, freq, tag):
    raw = os.path.join(TMP, os.path.basename(path) + ".raw.mp3")
    tone(raw, seconds, freq, ["-c:a", "libmp3lame", "-b:a", "128k", "-id3v2_version", "0", "-write_xing", "0", "-map_metadata", "-1"])
    with open(raw, "rb") as f:
        audio = f.read()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(tag + audio)


FLAC_TAGGED = ["-c:a", "flac"]
FLAC_PLAIN = ["-c:a", "flac", "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact"]


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(TMP)
    os.makedirs(EXTRAS)
    imgs = os.path.join(TMP, "img")
    for name, rgb in COLORS.items():
        png(os.path.join(imgs, f"{name}.png"), rgb, mark=list(COLORS).index(name) + 1)

    # Album-A: tricky name, cover.jpg (RED) beats folder.png (BLUE); mp3 carries embedded YELLOW art.
    a = os.path.join(FX, "Album-A")
    tone(os.path.join(a, "01 曲 #1+%.flac"), 60, 440, FLAC_TAGGED,
         extra=["-metadata", "title=Tagged Title One", "-metadata", "artist=Fixture Artist", "-metadata", "album=Album A", "-metadata", "track=1"])
    mp3(os.path.join(a, "02 track.mp3"), 30, 660,
        id3_tag("Tagged Title Two", "Fixture Artist", "Album A", "2",
                lyrics="[00:01.00]embedded line one\n[00:04.00]embedded line two",
                picture_png=os.path.join(imgs, "YELLOW.png")))
    to_jpg(os.path.join(imgs, "RED.png"), os.path.join(a, "cover.jpg"))
    shutil.copy(os.path.join(imgs, "BLUE.png"), os.path.join(a, "folder.png"))
    with open(os.path.join(a, "01 曲 #1+%.lrc"), "w", encoding="utf-8") as f:
        f.write("[ti:Tagged Title One]\n[00:00.50]LRC line one 曲\n[00:03.00]LRC line two #1+%\n[00:06.00]LRC line three\n")
    with open(os.path.join(a, "Info.nfo"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<album>\n  <title>Album A (NFO)</title>\n  <artist>Fixture Artist</artist>\n'
                '  <year>2026</year>\n  <genre>Test</genre>\n  <label>Fixture Label</label>\n  <review>NFO review text for Album A.</review>\n'
                '  <track><position>1</position><title>Tagged Title One</title><duration>1:00</duration></track>\n'
                '  <track><position>2</position><title>Tagged Title Two</title><duration>0:30</duration></track>\n</album>\n')

    # Album-B: untagged track, arbitrary image name.
    b = os.path.join(FX, "Album-B")
    tone(os.path.join(b, "track.flac"), 20, 550, FLAC_PLAIN)
    shutil.copy(os.path.join(imgs, "ORANGE.png"), os.path.join(b, "arbitrary-name.png"))

    # Album-C: only an embedded picture (MAGENTA).
    mp3(os.path.join(FX, "Album-C", "track-with-embedded-art.mp3"), 20, 770,
        id3_tag("Embedded Art Song", "Fixture Artist", "Album C", "1", picture_png=os.path.join(imgs, "MAGENTA.png")))

    base20 = os.path.join(TMP, "base20.flac")
    tone(base20, 20, 500, FLAC_PLAIN)

    def put_track(folder, name="track.flac"):
        os.makedirs(os.path.join(FX, folder), exist_ok=True)
        shutil.copy(base20, os.path.join(FX, folder, name))

    put_track("Parent/CD1")
    to_jpg(os.path.join(imgs, "CYAN.png"), os.path.join(FX, "Parent", "cover.jpg"))
    put_track("ChildOnly")
    os.makedirs(os.path.join(FX, "ChildOnly", "Artwork"))
    to_jpg(os.path.join(imgs, "WHITE.png"), os.path.join(FX, "ChildOnly", "Artwork", "cover.jpg"))
    put_track("LongChildName")
    put_track("CorruptCover")
    with open(os.path.join(FX, "CorruptCover", "cover.jpg"), "wb") as f:
        f.write(b"\xff\xd8\xff\xe0\x00\x10JFIF\x00" + bytes((i * 37) % 256 for i in range(4000)))
    shutil.copy(os.path.join(imgs, "GREEN.png"), os.path.join(FX, "CorruptCover", "folder.png"))
    put_track("LateCover")
    shutil.copy(os.path.join(imgs, "PINK.png"), os.path.join(EXTRAS, "late-cover.png"))

    u = os.path.join(FX, "Unindexed")
    put_track("Unindexed")
    open(os.path.join(u, ".nomedia"), "w").close()
    to_jpg(os.path.join(imgs, "PURPLE.png"), os.path.join(u, "cover.jpg"))
    with open(os.path.join(u, "track.lrc"), "w", encoding="utf-8") as f:
        f.write("[00:01.00]Unindexed LRC line\n[00:05.00]second line\n")
    with open(os.path.join(u, "Info.nfo"), "w", encoding="utf-8") as f:
        f.write("Artist.......: Hidden Artist\nAlbum........: Unindexed Album\nYear.........: 2025\n"
                "------------------------------\n01. Hidden Track [0:20]\n------------------------------\nText-format NFO for the .nomedia folder.\n")

    # Cue: one 60 s image with three 20 s tones.
    c = os.path.join(FX, "Cue")
    os.makedirs(c)
    ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=20",
           "-f", "lavfi", "-i", "sine=frequency=550:sample_rate=44100:duration=20",
           "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=44100:duration=20",
           "-filter_complex", "[0][1][2]concat=n=3:v=0:a=1,pan=stereo|c0=c0|c1=c0", "-c:a", "flac", rel(os.path.join(c, "image.flac")))
    with open(os.path.join(c, "image.cue"), "w", encoding="utf-8") as f:
        f.write('PERFORMER "Cue Artist"\nTITLE "Cue Album"\nFILE "image.flac" WAVE\n'
                '  TRACK 01 AUDIO\n    TITLE "Cue One"\n    INDEX 01 00:00:00\n'
                '  TRACK 02 AUDIO\n    TITLE "Cue Two"\n    INDEX 01 00:20:00\n'
                '  TRACK 03 AUDIO\n    TITLE "Cue Three"\n    PERFORMER "Guest"\n    INDEX 01 00:40:00\n')
    to_jpg(os.path.join(imgs, "OLIVE.png"), os.path.join(c, "cover.jpg"))

    # Formats for the native decoders.
    fm = os.path.join(FX, "Formats")
    tone(os.path.join(fm, "sample-alac.m4a"), 20, 440, ["-c:a", "alac"], extra=["-metadata", "title=ALAC Sample"])
    tone(os.path.join(fm, "sample-aac.m4a"), 20, 660, ["-c:a", "aac", "-b:a", "128k"])
    tone(os.path.join(fm, "sample.wma"), 20, 880, ["-c:a", "wmav2", "-b:a", "128k"])
    wav = os.path.join(TMP, "ape-src.wav")
    tone(wav, 20, 330, ["-c:a", "pcm_s16le"])
    run(["docker", "run", "--rm", "-v", f"{OUT}:/w", "-w", "/w", "fp-fixture-mac", rel(wav), rel(os.path.join(fm, "sample.ape")), "-c2000"])
    gen = os.path.join(TMP, "dsdgen")
    run(["gcc", "-O2", "-o", gen, os.path.join(ROOT, "scripts", "fixtures", "tools", "dsdgen.c"), "-lm"])
    run([gen, "dsf", os.path.join(fm, "sample.dsf"), "4", "1000", "1500"])
    run([gen, "dff", os.path.join(fm, "sample.dff"), "4", "1200", "1800"])
    run([gen, "dst", os.path.join(fm, "unsupported-dst.dff")])
    # Corrupted variants for error handling.
    with open(os.path.join(fm, "sample.wma"), "rb") as f:
        w = bytearray(f.read())
    for i in range(len(w) // 2, len(w) // 2 + 20000):
        w[i] = (w[i] * 7 + 13) & 0xFF
    with open(os.path.join(fm, "corrupt.wma"), "wb") as f:
        f.write(w)

    # Long track for network interruption / retry tests (10 minutes).
    tone(os.path.join(FX, "Long", "long.flac"), 600, 300, FLAC_PLAIN)
    # High-entropy track (~1.4 Mbit/s) so socket buffers cannot hide a network cut for minutes.
    os.makedirs(os.path.join(FX, "Noise"), exist_ok=True)
    ffmpeg("-f", "lavfi", "-i", "anoisesrc=color=white:amplitude=0.3:sample_rate=44100:duration=240:seed=20261003", "-ac", "2",
           *FLAC_PLAIN, rel(os.path.join(FX, "Noise", "noise.flac")))

    # Mixed folder for per-track cover tests is formed by playlists over Album-A and Album-B.

    # Hundreds of folders for scroll / rapid skip tests.
    tiny = os.path.join(TMP, "tiny.flac")
    tone(tiny, 2, 600, FLAC_PLAIN)
    many = os.path.join(FX, "Many")
    for i in range(300):
        d = os.path.join(many, f"Folder {i:03d}")
        os.makedirs(d)
        shutil.copy(tiny, os.path.join(d, f"track {i:03d}.flac"))
        rgb = ((i * 53) % 256, (i * 97) % 256, (i * 151) % 256)
        png(os.path.join(d, "cover.png"), rgb, mark=(i % 10) + 1, size=200)

    shutil.rmtree(TMP)
    # Copy of the decoder fixtures for the instrumentation test APK (DSF/DFF are invisible in shared storage).
    shutil.copytree(fm, os.path.join(OUT, "android-test-assets", "Formats"))

    manifest = {}
    for dirpath, _, files in os.walk(OUT):
        for fn in sorted(files):
            p = os.path.join(dirpath, fn)
            with open(p, "rb") as f:
                manifest[rel(p)] = {"bytes": os.path.getsize(p), "sha256": hashlib.sha256(f.read()).hexdigest()}
    doc = {
        "generator": "scripts/fixtures/make_fixture.py",
        "source": "All media generated by this script: sine tones via FFmpeg lavfi (mwader/static-ffmpeg:7.1), PNG drawn in Python, "
                  "JPEG converted by FFmpeg, APE by Monkey's Audio 13.27 (BSD-3) built from MAC_1327_SDK.zip, DSD/DFF/DST by tools/dsdgen.c.",
        "files": dict(sorted(manifest.items())),
    }
    mdir = os.path.join(ROOT, "docs", "fork", "fixtures")
    os.makedirs(mdir, exist_ok=True)
    with open(os.path.join(mdir, "MANIFEST.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"fixture written to {OUT}: {len(manifest)} files")


if __name__ == "__main__":
    main()
