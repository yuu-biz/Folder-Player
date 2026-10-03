#!/usr/bin/env python3
"""Writes app/src/main/assets/licenses/third_party_notices.txt (shown in Settings → Open source licenses).

The license texts in scripts/licenses/texts/ come from the official sources: apache.org, the SPDX license list,
the FFmpeg 7.1.5 source tarball (COPYING.LGPLv2.1) and the LICENSE/NOTICE files inside the dependency jars.
Re-run after changing dependencies: python3 scripts/licenses/gen_notices.py

The component list is written by hand. --check verifies it against the dependencies Gradle actually resolves, so a new
library cannot ship without a notice:
  ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath > deps.txt
  ./gradlew -q :app:dependencies --configuration coreLibraryDesugaring >> deps.txt
  python3 scripts/licenses/gen_notices.py --check deps.txt
--verify fails when the generated asset differs from what this script writes.
"""
import os
import re
import sys

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
        "Kotlin standard library, kotlin-reflect, kotlinx.coroutines — https://kotlinlang.org",
        "Accompanist — https://github.com/google/accompanist",
        "OkHttp, Okio, Retrofit — Square, Inc. — https://square.github.io",
        "Gson, Guava (with failureaccess, listenablefuture), JSR-305 annotations, Error Prone annotations — https://github.com/google",
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

# Every resolved runtime artifact (group:artifact) must match one of these; the value names the COMPONENTS entry that
# covers it. Checked by --check.
COVERAGE = [
    (r"androidx\..*", "AndroidX / Jetpack"),
    (r"org\.jetbrains\.kotlin:kotlin-(stdlib|stdlib-common|stdlib-jdk7|stdlib-jdk8|reflect)", "Kotlin standard library"),
    (r"org\.jetbrains\.kotlinx:kotlinx-coroutines-.*", "kotlinx.coroutines"),
    (r"com\.google\.accompanist:.*", "Accompanist"),
    (r"com\.squareup\.(okhttp3|okio|retrofit2):.*", "OkHttp, Okio, Retrofit"),
    (r"com\.google\.code\.gson:gson", "Gson"),
    (r"com\.google\.guava:(guava|failureaccess|listenablefuture)", "Guava"),
    (r"com\.google\.code\.findbugs:jsr305", "JSR-305 annotations"),
    (r"com\.google\.errorprone:error_prone_annotations", "Error Prone annotations"),
    (r"io\.coil-kt:.*", "Coil"),
    (r"com\.hierynomus:(smbj|asn-one)", "SMBJ, ASN-One"),
    (r"commons-net:commons-net", "Apache Commons Net"),
    (r"commons-io:commons-io", "Apache Commons IO"),
    (r"io\.ktor:.*", "Ktor"),
    (r"com\.typesafe:config", "Typesafe Config"),
    (r"org\.fusesource\.jansi:jansi", "Jansi"),
    (r"org\.jetbrains:annotations", "JetBrains Java Annotations"),
    (r"org\.jupnp:org\.jupnp(\.support)?", "jUPnP"),
    (r"org\.slf4j:slf4j-(api|nop)", "SLF4J"),
    (r"net\.engio:mbassador", "MBassador"),
    (r"org\.checkerframework:checker-qual", "Checker Framework qualifiers"),
    (r"org\.bouncycastle:bcprov-jdk18on", "Bouncy Castle"),
    (r"com\.android\.tools:desugar_jdk_libs(_configuration)?", "desugar_jdk_libs"),
]


def check(deps_file):
    """Lists resolved artifacts without a notice (and coverage entries naming no COMPONENTS item). Exit 1 on gaps."""
    with open(deps_file, encoding="utf-8") as f:
        found = sorted(set(m.group(1) for m in re.finditer(r"[-\\+] ([\w.-]+:[\w.-]+):", f.read())))
    listed = "\n".join(i for items in COMPONENTS.values() for i in items)
    missing = [ga for ga in found if not any(re.fullmatch(rx, ga) for rx, _ in COVERAGE)]
    unnamed = sorted(set(name for _, name in COVERAGE if name.split(",")[0] not in listed))
    for ga in found:
        name = next((n for rx, n in COVERAGE if re.fullmatch(rx, ga)), None)
        print(f"{ga:70} {name or 'MISSING'}")
    if not found:
        print("no dependencies found in", deps_file)
        return 1
    if missing or unnamed:
        print("artifacts without a notice:", missing, "coverage names not in COMPONENTS:", unnamed)
        return 1
    print(f"OK: {len(found)} artifacts, all covered")
    return 0


FFMPEG = f"""This app uses code of FFmpeg (https://ffmpeg.org), licensed under the GNU Lesser General Public License
version 2.1 or later. FFmpeg 7.1.5 (libavformat, libavcodec, libavutil, libswresample) is built unmodified from
https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz
(sha256 de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f) with native/build-ffmpeg.sh (no GPL or
non-free parts) and statically linked into libfpnative.so. The FFmpeg source tarball and the complete source of this
app, with the scripts needed to rebuild FFmpeg and relink the app, are available at {REPO} and are attached to every
release of this app. FFmpeg is a trademark of Fabrice Bellard, originator of the FFmpeg project."""

SEP = "\n\n" + "=" * 78 + "\n\n"


def render():
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
    return SEP.join(parts) + "\n"


def main(argv):
    if len(argv) == 2 and argv[0] == "--check":
        return check(argv[1])
    content = render()
    if argv == ["--verify"]:
        with open(OUT, encoding="utf-8") as f:
            same = f.read() == content
        print("notices asset is", "up to date" if same else "OUT OF DATE (run gen_notices.py)")
        return 0 if same else 1
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
    print("wrote", os.path.relpath(OUT, ROOT), os.path.getsize(OUT), "bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
