#!/usr/bin/env python3
"""Writes app/src/main/assets/licenses/third_party_notices.txt (shown in Settings → Open source licenses).

The license texts in scripts/licenses/texts/ come from the official sources: apache.org, the SPDX license list,
the FFmpeg 7.1.5 source tarball (COPYING.LGPLv2.1) and the LICENSE/NOTICE files inside the dependency jars.
Re-run after changing dependencies: python3 scripts/licenses/gen_notices.py
"""
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TEXTS = os.path.join(ROOT, "scripts", "licenses", "texts")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "licenses", "third_party_notices.txt")
REPO = "https://github.com/yuu-biz/Folder-Player"


def text(name):
    with open(os.path.join(TEXTS, name), encoding="utf-8") as f:
        return f.read().strip()


COMPONENTS = {
    "Apache License 2.0": [
        "AndroidX / Jetpack (Compose, Media3, Lifecycle, Navigation, DataStore, Activity, Core, AppCompat, DocumentFile and their dependencies) — https://developer.android.com/jetpack",
        "Kotlin standard library, kotlinx.coroutines — https://kotlinlang.org",
        "Accompanist — https://github.com/google/accompanist",
        "OkHttp, Okio, Retrofit — Square, Inc. — https://square.github.io",
        "Gson, Guava, JSR-305 annotations — https://github.com/google",
        "Coil — https://coil-kt.github.io/coil",
        "SMBJ, ASN-One — Jeroen van Erp — https://github.com/hierynomus/smbj",
        "Apache Commons Net, Apache Commons IO — The Apache Software Foundation (NOTICE files below)",
        "Ktor — JetBrains s.r.o. — https://ktor.io",
        "Typesafe Config — https://github.com/lightbend/config",
        "Jansi — https://github.com/fusesource/jansi",
        "JetBrains Java Annotations — https://github.com/JetBrains/java-annotations",
    ],
    "Common Development and Distribution License 1.0 (CDDL-1.0)": [
        "jUPnP 3.0.5 (org.jupnp, org.jupnp.support), used unmodified. Source: https://github.com/jupnp/jupnp",
    ],
    "MIT License": [
        "SLF4J (slf4j-api, slf4j-nop) — QOS.ch",
        "MBassador — Benjamin Diedrichsen",
        "Checker Framework qualifiers — the Checker Framework developers",
        "Bouncy Castle (bcprov) — The Legion of the Bouncy Castle Inc.",
    ],
    "GNU General Public License 2.0 with the Classpath Exception": [
        "desugar_jdk_libs (Java library desugaring, derived from OpenJDK) — https://github.com/google/desugar_jdk_libs",
    ],
}

FFMPEG = f"""This app uses code of FFmpeg (https://ffmpeg.org), licensed under the GNU Lesser General Public License
version 2.1 or later. FFmpeg 7.1.5 (libavformat, libavcodec, libavutil, libswresample) is built unmodified from
https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz
(sha256 de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f) with native/build-ffmpeg.sh (no GPL or
non-free parts) and statically linked into libfpnative.so. The FFmpeg source tarball and the complete source of this
app, with the scripts needed to rebuild FFmpeg and relink the app, are available at {REPO} and are attached to every
release of this app. FFmpeg is a trademark of Fabrice Bellard, originator of the FFmpeg project."""

SEP = "\n\n" + "=" * 78 + "\n\n"


def main():
    parts = [f"""Folder Player Fork — open source notices

This app is an unofficial personal fork of Folder Player (https://github.com/wyvern3000/Folder-Player), which is
licensed under the MIT License. Source code of this fork: {REPO}

Downloadable fonts (Noto Sans SC, LXGW WenKai, Sarasa UI SC) are not part of the app; they are fetched on request
from the upstream repository, where their SIL Open Font License texts are published (fonts/licenses)."""]
    parts.append("Folder Player (MIT License)\n\n" + text("MIT-folder-player.txt"))
    parts.append("FFmpeg (LGPL-2.1-or-later)\n\n" + FFMPEG)
    lines = ["Third-party libraries"]
    for lic, items in COMPONENTS.items():
        lines.append("")
        lines.append(lic + ":")
        lines += ["  - " + i for i in items]
    parts.append("\n".join(lines))
    parts.append("NOTICE — Apache Commons Net\n\n" + text("NOTICE-commons-net-3.13.0.txt")
                 + "\n\nNOTICE — Apache Commons IO\n\n" + text("NOTICE-commons-io-2.21.0.txt"))
    parts.append("SLF4J\n\n" + text("MIT-slf4j.txt"))
    parts.append("MBassador\n\n" + text("MIT-mbassador.txt"))
    parts.append(text("MIT-checker-qual.txt"))
    parts.append("Bouncy Castle\n\n" + text("MIT-bouncycastle.txt"))
    parts.append("Apache License, Version 2.0\n\n" + text("Apache-2.0.txt"))
    parts.append("GNU Lesser General Public License, version 2.1\n\n" + text("LGPL-2.1.txt"))
    parts.append("Common Development and Distribution License, version 1.0\n\n" + text("CDDL-1.0.txt"))
    parts.append("GNU General Public License, version 2, with the Classpath Exception\n\n" + text("GPL-2.0-with-classpath-exception.txt"))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(SEP.join(parts) + "\n")
    print("wrote", os.path.relpath(OUT, ROOT), os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
