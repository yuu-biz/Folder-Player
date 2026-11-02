Folder Player Fork {VERSION} — an unofficial personal fork of
[Folder Player](https://github.com/wyvern3000/Folder-Player) by wyvern3000. It is not affiliated with the original
project. This release follows 1.8.1.

## What's new in 1.9.0

- **The queue comes back after a restart**: the songs added with "Play next" or "Add to queue", and lists played
  from search results, favourites or the songs list, come back as they were (same song and position, paused).
- **Skip back and forward**: buttons beside previous and next in the player, and a double tap on the left or right
  half of the cover. 10 s by default; 5, 10, 15 or 30 s in Settings → Playback.
- **Two new options in Settings → Playback**, both off by default: "Previous button" can restart the song when it
  is past 3 s, and long files (10 min or more) can resume where you left them. The notification and lock screen now
  follow the same "Previous" setting as the app.
- **Reconnecting shows in the player**: while the app retries a NAS or server that stopped answering, the player
  shows a countdown with "Retry now" and "Stop", and "Reconnected" when it works again.
- **Where you are in a folder**: a path row under the top bar shows the source and every folder down to this one;
  tap a folder in it to go back up there.
- **Delete asks first**: deleting a playlist or a source now asks before it happens.
- **Clearer wording** in every language, and "SAF" is gone: the add menu says "Pick a folder…"
  (「フォルダを選んで追加」).
- **Fixed**: leaving a folder that was still loading could show "Load failed" on the screen you went to.

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

