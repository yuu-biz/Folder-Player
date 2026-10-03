# Releases

`.github/workflows/release.yml` runs when a tag `v*` is pushed. In order, and stopping at the first failure:

1. checks the tag format and that all four signing secrets exist (no secrets → the run fails; there is no
   debug-signed fallback);
2. stops if a **published** release for the tag already exists — a re-run never replaces the APK, checksums or notes
   of a published release; push a new tag instead (a draft left by an unfinished run is replaced);
3. builds FFmpeg, then runs `testDebugUnitTest lintDebug` — a failing test or lint error stops the release
   (reports are attached to the run) — and checks that every resolved runtime library has a notice in the app
   (`gen_notices.py --check`) and that the notices asset is current (`--verify`);
4. builds and signs the release APK and verifies it: exactly one signer, not the debug certificate, not debuggable,
   versionCode/versionName as derived from the tag, 16 KB zip alignment;
5. creates a draft release with the notes, `FolderPlayerFork-<version>.apk`, `SHA256SUMS.txt` and
   `ffmpeg-7.1.5.tar.xz` (the FFmpeg source the APK was built from, LGPL requirement), and publishes it only after
   everything is attached. The notes carry the SHA-256 of the signing certificate read from the APK itself.

Tags are `vMAJOR.MINOR.PATCH` or `vMAJOR.MINOR.PATCH-<label>N` (N = 1..98). versionName is the tag without `v`;
versionCode depends only on the tag: `((MAJOR*100 + MINOR)*100 + PATCH)*100 + N`, with N = 99 for a final release
(`v0.5.1` → 50199, `v0.5.1-rc1` → 50101), so pre-releases sort below their final release.

The real-server protocol tests and the emulator suites need the Docker fixtures and are run locally before tagging
(see [VERIFICATION.md](VERIFICATION.md)).

The source of the release is the tag itself (GitHub's source archive). "Run workflow" on the Actions page runs the
same steps and keeps the APK as a workflow artifact without creating a release.

```
git tag v0.5.1
git push origin v0.5.1
```

## Signing

Android only installs an update over an existing install when both are signed with the same key. Create the key once
and keep a backup — a lost key means users must uninstall before installing a newer build:

```
keytool -genkeypair -v -keystore fork-release.jks -alias fork -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 fork-release.jks > fork-release.jks.b64
```

Then add these repository secrets (Settings → Secrets and variables → Actions, or `gh secret set NAME`):

| Secret | Value |
|---|---|
| `FP_KEYSTORE_BASE64` | content of `fork-release.jks.b64` |
| `FP_KEYSTORE_PASSWORD` | keystore password |
| `FP_KEY_ALIAS` | `fork` (the alias used above) |
| `FP_KEY_PASSWORD` | key password |

Local signed builds use the same mechanism through an untracked `keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); never commit the keystore or that file.

## License notices

Distributing the APK requires the notices of the bundled code. They are generated into
`app/src/main/assets/licenses/third_party_notices.txt` by `python3 scripts/licenses/gen_notices.py` (re-run after
changing dependencies) and shown in the app under Settings → Open source licenses. The component list in the script
is written by hand; `--check` compares it with `:app:dependencies --configuration releaseRuntimeClasspath` (plus
`coreLibraryDesugaring`) and fails on any library without a notice. See also
[DEPENDENCIES.md](DEPENDENCIES.md).
