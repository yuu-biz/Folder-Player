Folder Player Fork {VERSION} — an unofficial personal fork of
[Folder Player](https://github.com/wyvern3000/Folder-Player) by wyvern3000. It is not affiliated with the original
project. This release follows 1.7.1.

## What's new in 1.7.2

- **Songs list of one folder**: the songs list can now show only the songs of one folder and the folders below it.
  Pick it with the folder button under the sort buttons (all folders, a bookmark or a source), or open "Songs list of
  this folder" from a folder's ⋮ menu or a bookmark's long-press menu. The choice is kept, and the source list shows
  its name next to the number of songs. The songs read elsewhere stay in the app (nothing is deleted); "All folders"
  shows them again. A folder whose songs were not read yet offers "Read song lengths" right there.

  Why other songs were listed: a scan of a bookmark reads only that folder and the folders below it, but the list also
  holds every song whose length was read while its folder was on screen (and those read before 1.7.1).

## Install

- Download `FolderPlayerFork-{VERSION}.apk` below and install it (Android 8.0 or newer; arm64-v8a and x86_64). Your
  browser or file manager may ask for permission to install apps.
- It installs as a separate app, **Folder Player Fork** (`com.wing.folderplayer.fork`), next to the original
  Folder Player. Settings, sources and playlists are its own; nothing is taken over from the original app.
- Signed with the fork's release key, certificate SHA-256 `{CERT_SHA256}`. Later versions install over this one
  only when they are signed with the same certificate. The APK's SHA-256 is at the end of these notes.

## More

- Every feature: [docs/fork/FEATURES.md]({REPO}/blob/{VERSION_TAG}/docs/fork/FEATURES.md)
- Known limitations: [docs/fork/KNOWN_ISSUES.md]({REPO}/blob/{VERSION_TAG}/docs/fork/KNOWN_ISSUES.md)
- Earlier versions: each release has its own notes ([all releases]({REPO}/releases))

## Licenses

- Folder Player is licensed under the MIT License; this fork keeps that license.
- The app contains FFmpeg 7.1.5 (LGPL-2.1-or-later), built unmodified from the official source tarball, which is
  attached to this release (`ffmpeg-7.1.5.tar.xz`). The complete source of this version, including the scripts to
  rebuild FFmpeg and relink the app, is the source archive of this release.
- Other libraries are under the Apache 2.0, BSD 3-Clause, MIT, CDDL 1.0 and GPL-2.0-with-Classpath-Exception licenses. The full
  notices are in the app under Settings → Open source licenses and in
  [docs/fork/DEPENDENCIES.md]({REPO}/blob/{VERSION_TAG}/docs/fork/DEPENDENCIES.md).

## SHA-256

