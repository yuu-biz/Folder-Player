Folder Player Fork {VERSION} — an unofficial personal fork of
[Folder Player](https://github.com/wyvern3000/Folder-Player) by wyvern3000. It is not affiliated with the original
project. This release follows 1.9.0.

## What's new in 1.10.0

- **Sleep timer**: presets (15 to 90 min), two new options (let the song finish at the time; fade out over the last
  30 seconds), the time left shown in the player (tap it to change the timer), and the timer is kept when Android
  closes the app. A "songs" timer now counts a song repeated by "repeat one" and carries on into the next folder.
- **Queue**: "Save queue as playlist" and "Clear queue" in the playlist menu. Clearing keeps the song playing, and
  your own playlists are not changed.
- **Undo**: removing a song from a playlist with a swipe, or removing a bookmark, shows "Undo" for a few seconds.
- **Repeat is kept** for the next start, like shuffle.
- **Equalizer**: the playback options (beside the sleep timer) open the phone's own equalizer, when the phone has
  one. Equalizer apps can now attach to the player.
- **Open from other apps**: an audio file opened from a file manager plays in the player. It is not kept in the queue
  for the next start.
- **Long titles scroll** sideways instead of being squeezed.
- **Fewer accidents**: a tap beside the source editor no longer closes it and loses what was typed; "Replace remote
  favorites with local" asks first; at the limit of 10 playlists the menu says why no new one can be made.

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

