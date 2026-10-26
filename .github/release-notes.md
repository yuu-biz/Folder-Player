Folder Player Fork {VERSION} — an unofficial personal fork of
[Folder Player](https://github.com/wyvern3000/Folder-Player) by wyvern3000. It is not affiliated with the original
project. This release follows 1.7.2.

## What's new in 1.8.0

- **Play next / Add to queue**: the long-press menu of a song or a folder (also in the songs list, favourites and
  search results) has "Play next" (right after the song playing) and "Add to queue" (at the end; a folder adds its
  songs). The song playing goes on; with nothing playing they start.
- **Speed and left / right**: a new button beside the sleep timer in the player's playlist opens "Speed and channels":
  playback speed from 0.5× to 2× (the pitch stays the same) and "Swap left and right". Both are kept; the button is lit
  while either is changed.
- **Swipe the cover**: in the full player, swipe the cover to the left for the next song and to the right for the
  previous one.
- **Songs list of several folders**: the folder button of the songs list now ticks several bookmarks and sources at
  once (the menu stays open); "All folders" shows every song again. "Songs list of this folder" still shows that one
  folder.
- **A song on a NAS that went silent**: when the NAS (or Wi-Fi) stops answering in the middle of a song, the app no
  longer waits on the dead connection before connecting again, so it notices the dead connection sooner and retries
  sooner (each retry waited 60 s, now 30 s, and connects at once when the NAS answers again).
- **Playback log**: it now also records when playback waits for data, the read errors the player retries by itself, a
  line with the position every 5 minutes while playing, memory warnings, and more about why the app was ended. If
  playback stops by itself again, share the log (Settings → Playback → Playback log).

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

