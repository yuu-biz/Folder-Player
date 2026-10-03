# Releases

`.github/workflows/release.yml` builds the APK when a tag `v*` is pushed and publishes a GitHub release with:

- `FolderPlayerFork-<version>.apk` (versionName = tag without `v`, versionCode = 1000 + workflow run number)
- `SHA256SUMS.txt`
- `ffmpeg-7.1.5.tar.xz` — the FFmpeg source the APK was built from (LGPL requirement)

The source of the release is the tag itself (GitHub's source archive). "Run workflow" on the Actions page builds the
same APK as a workflow artifact without creating a release.

```
git tag v0.5.1
git push origin v0.5.1
```

## Signing

Android only installs an update over an existing install when both are signed with the same key. Without signing
secrets the workflow publishes a debug-signed APK (a new temporary key on every run, so such builds cannot update each
other). To sign releases with your own key, create it once and keep a backup — a lost key means users must uninstall
before installing a newer build:

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
changing dependencies) and shown in the app under Settings → Open source licenses. See also
[DEPENDENCIES.md](DEPENDENCIES.md).
