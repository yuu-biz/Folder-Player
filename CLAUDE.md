# Folder Player Fork

Unofficial personal fork of Folder Player (Android, Kotlin, Jetpack Compose, Media3). App id `com.wing.folderplayer.fork`,
Kotlin package `com.wing.folderplayer`. The owner writes in Japanese; answer in Japanese.

## Where things are

- What the fork adds: `docs/fork/FEATURES.md`. Limitations, open investigations and proposed next steps:
  `docs/fork/KNOWN_ISSUES.md` (newest sections first). How it is built and tested, and a record per change:
  `docs/fork/VERIFICATION.md` (§1 also covers a cloud session; §5.x newest first). Releases and version codes:
  `docs/fork/RELEASE.md`.
- Release notes for the next tag: `.github/release-notes.md` (placeholders `{VERSION}` etc. are filled by `release.yml`).
- Strings: edit `app/src/main/res/values/strings.xml`, add the translations to `scripts/i18n/gen_translations.py`, run
  `python3 scripts/i18n/gen_translations.py` (never edit `values-*/strings.xml` by hand; `StringResourcesParityTest` checks).

## Working rules (owner's wishes)

- GitHub Actions minutes are limited: `ci.yml` runs only by hand, once just before a release. Check changes locally
  (`bash scripts/wsl-gradle.sh lintDebug testDebugUnitTest …`, see VERIFICATION §1) instead of relying on CI.
- A signed APK for the owner to try before merging: "Run workflow" of `release.yml` on the branch with the `version`
  input `MAJOR.MINOR.PATCH-devN` (installs over the last release). It never creates a release.
- A release: update `.github/release-notes.md`, the docs and a VERIFICATION §5 record, merge to `main`, push tag
  `vX.Y.Z`; `release.yml` builds, tests, signs and creates the GitHub release (a draft unless `FP_PUBLISH_RELEASES` is
  `true`).
- Comments and docs follow the existing style: plain sentences that say what the code does and why.
