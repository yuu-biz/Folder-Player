# Dependencies (pinned)

All versions are fixed in `gradle/libs.versions.toml` / `app/build.gradle.kts` (no `latest`/`+`). Verified = compiled into the debug APK and exercised by the tests named (see VERIFICATION.md).

## Toolchain (unchanged from baseline unless noted)

| Item | Version | Note |
|---|---|---|
| AGP / Gradle | 8.13.2 / 8.13 | baseline |
| Kotlin | 1.9.0 | baseline; constrains Ktor to 2.x (Ktor 3 needs Kotlin 2) |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 | baseline; not lowered to avoid permission rules |
| JDK for Gradle daemon | JetBrains JDK 21 (JBR SDK 21.0.11 b1163.116) | required by `gradle/gradle-daemon-jvm.properties` (baseline) |
| Android NDK | 28.2.13676358 (r28c) | **added** for the native decoders |
| CMake | 3.22.1 | **added** for the JNI library |
| coreLibraryDesugaring `com.android.tools:desugar_jdk_libs` | 2.1.5 | **added**: SMBJ/jUPnP use `java.time`/newer JDK APIs on API 26 — GPL-2.0 with Classpath Exception |

## New runtime libraries

| Artifact | Version | Why this version | License | Verified by |
|---|---|---|---|---|
| `com.hierynomus:smbj` | 0.15.0 | current release, SMB2/3, works on API 26 with desugaring | Apache-2.0 | SmbProtocolTest (SMB 3.1.1), NetworkPlaybackTest, PlaybackServiceTest |
| `org.bouncycastle:bcprov-jdk18on` (transitive of SMBJ) | 1.85.2 | resolved by SMBJ; used through SMBJ's `BCSecurityProvider` (MD4/AES-CMAC on Android) | Bouncy Castle (MIT-style) | SMB auth/signing in the tests above |
| `commons-net:commons-net` | 3.13.0 | current release; explicit FTPS (`FTPSClient`) | Apache-2.0 | FtpProtocolTest (normal/no-REST/truncating/FTPS) |
| `org.jupnp:org.jupnp` + `org.jupnp.support` | 3.0.5 | jUPnP 3.x core; the Android/Jetty transport module is **not** used — the Fork supplies an OkHttp stream client and a no-op stream server (control point only) | CDDL-1.0 | DlnaRendererIntegrationTest (gmrender-resurrect) |
| `io.ktor:ktor-server-core` / `ktor-server-cio` | 2.3.13 | last 2.x line (Kotlin 1.9); CIO engine runs on Android without Netty/Jetty | Apache-2.0 | RelayServerTest (200/206/416, HEAD, token), DLNA test |
| `org.slf4j:slf4j-nop` | 2.0.20 | silences SMBJ/jUPnP SLF4J logging (no credentials in logs) | MIT | build |
| `com.google.code.gson:gson` | 2.10.1 | explicit: retrofit converter 2.9.0 alone resolves Gson 2.8.5 (no `JsonParser.parseString`) | Apache-2.0 | FavoritesTest, migration tests |
| `androidx.documentfile:documentfile` | 1.0.1 | display names of SAF documents | Apache-2.0 | SafTest |

Unchanged from the baseline and kept: Media3 1.2.0, Coil 2.5.0, OkHttp 4.12.0, Retrofit 2.9.0, Compose BOM 2023.08.00,
Guava 33.0.0-android,
kotlinx-coroutines(-guava) 1.7.3. A wholesale upgrade of Compose/Material3/coroutines was not part of this work.

Removed: `com.github.thegrizzlylabs:sardine-android` v0.9 (baseline WebDAV client). WebDAV is now implemented directly on
OkHttp (`WebDavFileSystem`, PROPFIND parsed with a DOCTYPE-rejecting XML parser) so that authentication is bound to the
source (same scheme/host/port only) instead of the baseline's global `WebDavAuthManager`.

## Native decoders

| Component | Version / source | Build | License |
|---|---|---|---|
| FFmpeg | 7.1.5 "Péter", `https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz`, sha256 `de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f` | `native/build-ffmpeg.sh` (NDK r28c, API 26, static, `--disable-everything` + decoders alac, wmav1, wmav2, wmapro, wmalossless, ape, dsd_lsbf/msbf(_planar); demuxers mov, asf, ape, dsf, iff; **no DST decoder**; no `--enable-gpl/--enable-nonfree`; `-Wl,-z,max-page-size=16384`) | LGPL-2.1-or-later |
| `libfpnative.so` | `app/src/main/cpp/fpnative.c` + `CMakeLists.txt` (this repo) | Gradle `externalNativeBuild` (CMake 3.22.1), ABIs arm64-v8a, x86_64, 16 KB page aligned | same as the app |

DSD→PCM uses FFmpeg's DSD decoders (no third-party dsd2pcm code).
Rebuild: `bash native/build-ffmpeg.sh` then `bash scripts/wsl-gradle.sh assembleDebug`.

## Fonts (downloaded at runtime, not bundled)

| Font | File (pinned upstream commit 98906d8) | Size / SHA-256 | License |
|---|---|---|---|
| Noto Sans SC | `NotoSansSC-Variable.ttf` | 17,772,300 / `a3041811…af0da` | SIL OFL 1.1 (`fonts/licenses/NotoSansSC-OFL.txt`) |
| LXGW WenKai Lite | `LXGWWenKaiLite-Regular.ttf` | 13,872,424 / `140c99ba…45f15` | SIL OFL 1.1 |
| Sarasa UI SC | `SarasaUiSC-Regular.ttf` | 24,049,996 / `c27311eb…bf6b` | SIL OFL 1.1 |

URL base: `https://raw.githubusercontent.com/wyvern3000/Folder-Player/98906d8426f0cda1792d77104e5c0ecc84f18e76/fonts/`.
A download whose size or hash differs is rejected (System font stays active).

## Test-only

| Artifact | Version | License |
|---|---|---|
| JUnit | 4.13.2 | EPL-1.0 |
| kotlinx-coroutines-test | 1.7.3 | Apache-2.0 |
| okhttp mockwebserver | 4.12.0 | Apache-2.0 |
| org.json (JVM tests) | 20260814 | Public Domain |
| androidx.test core/rules 1.5.0, runner 1.5.2, ext-junit 1.1.5, espresso 3.5.1, uiautomator 2.2.0, compose ui-test-junit4 (BOM) | — | Apache-2.0 |

Fixture tooling (not shipped): Samba, pyftpdlib, Apache httpd mod_dav, gmrender-resurrect (Docker images built from
`scripts/fixtures/servers/`), Monkey's Audio 13.27 `mac` (BSD-3-Clause, only to encode `sample.ape`), static FFmpeg image
`mwader/static-ffmpeg:7.1` (fixture generation), `scripts/fixtures/tools/dsdgen.c` (own code, DSF/DFF fixtures).

## Lint exceptions vs. baseline

Baseline: 19 errors (`UnsafeOptInUsageError`), 75 warnings. Now: 0 errors, 61 warnings (`app/build/reports/lint-results-debug.*`,
. Remaining warnings that are deliberate:

- `GradleDependency` / `NewerVersionAvailable`: versions are pinned on purpose (Kotlin 1.9 → Ktor 2.x; no Compose/Media3
  bulk upgrade).
- `TrustAllX509TrustManager` reported inside the commons-net jar (`org.apache.commons.net.util.TrustManagerUtils`):
  library code the app never calls; FTPS uses the platform trust manager with hostname verification or an explicit
  SHA-256 pin (`FtpFileSystem`).
- `ApplySharedPref` is suppressed (with the reason in code) in `LegacyDataMigrator`: migrated data must be on disk
  before the app reads it during the same start-up.

## Notices in the APK

The license texts and copyright notices of everything shipped in the APK (Folder Player's MIT license, FFmpeg's
LGPL-2.1 with the source location, Apache 2.0 with the Commons NOTICE files, CDDL 1.0 for jUPnP, MIT for SLF4J,
MBassador, Checker Framework and Bouncy Castle, GPL-2.0 with Classpath Exception for desugar_jdk_libs) are in
`app/src/main/assets/licenses/third_party_notices.txt`, generated by `scripts/licenses/gen_notices.py` from the texts in
`scripts/licenses/texts/`, and shown under Settings → Open source licenses. Releases also attach the FFmpeg source
tarball (see [RELEASE.md](RELEASE.md)).

Release builds are not shrunk with R8 (`isMinifyEnabled = false`): SMBJ, jUPnP, Ktor and Gson rely on reflection, and
the release runs the same code as the tested debug build.
