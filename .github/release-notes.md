Folder Player Fork {VERSION} — an unofficial personal fork of
[Folder Player](https://github.com/wyvern3000/Folder-Player) by wyvern3000. It is not affiliated with the original
project. This release follows 1.1.0.

## What's new in 1.2.0

- **Images that a NAS is not asked for again**: folder thumbnails are kept at 128 / 256 px and the picture of what is playing
  as one file of up to 2048 px, shared by the mini, full and wide player and the notification. A file is kept until the
  image itself changes. One size limit (128 MB, 256 MB, 512 MB by default, 1 GB or 2 GB) is shared by both, under
  Settings → Library & network → Image cache and network use, where the usage and "Clear" are too.
- **Network use at a glance**: the same page shows a rough account of what the app read from network sources since it was
  started (images, audio and metadata, other; per source; folder lists; image cache hits and misses), with a reset.
- **Back is the previous place**: from a folder opened from the favourites list or a bookmark (and the folders you enter after
  it), Back goes back step by step: the folder, then the list. A new arrow-up button next to the back arrow goes up one
  folder; Back after it returns to where you were.

Since 1.0.0 (1.1.0): "Search network" and "Get shares" in the SMB editor, the same favourites on several devices through one
`fav.json` on a NAS, and bookmarks for the folders you open often.

## Install

- Download `FolderPlayerFork-{VERSION}.apk` below and install it (Android 8.0 or newer; arm64-v8a and x86_64). Your
  browser or file manager may ask for permission to install apps.
- It installs as a separate app, **Folder Player Fork** (`com.wing.folderplayer.fork`), next to the original
  Folder Player. Settings, sources and playlists are its own; nothing is taken over from the original app.
- Signed with the fork's release key, certificate SHA-256 `{CERT_SHA256}`. Later versions install over this one
  only when they are signed with the same certificate. The APK's SHA-256 is at the end of these notes.

## Features

- **Folder-based browser**: play music straight from folders, no library scan. Sources: local storage, SAF folders,
  WebDAV, SMB 2/3, FTP / FTPS. Passwords are kept per source in the Android Keystore; a connection test names the
  failure.
- **Mini ⇄ full player**: the browser is the start page; the mini player grows into the full player and back (tap, or
  drag following the finger). Back keeps your place in the browser.
- **Large screens and foldables**: on a tablet, an unfolded foldable or a wide window the browser and the player sit
  side by side, and Settings has two panes. The layout follows the window, so folding, rotating or resizing keeps
  playback and your place in the browser.
- **Search, favourites and playlists**: search below the current folder, favourites (optional `fav.json` that several devices can share), bookmarks for folders, playlists with drag reordering, track lengths in the list.
- **Cover artwork**: folder image first, then the embedded picture, in the player, notification and lock screen for
  every source; grid or list with thumbnails (thumbnails of network sources are opt-in and Wi-Fi only).
- **Sorting**: by name, modified time, created time or size, remembered per folder. "Created" is the time a local file
  was added to the media library, the share's creation time on SMB, and the modified time elsewhere.
- **Lyrics and album info**: LRC files, embedded lyrics, CUE sheets, `Info.nfo` album info. Optional AI album info,
  lyrics and translation (off by default; you provide your own OpenAI-compatible endpoint and key).
- **DLNA casting** to renderers on your network (off by default).
- **Formats**: ALAC, WMA, APE, DSF and DFF through FFmpeg 7.1.5, in addition to what Android plays (DST-compressed DSD
  is not supported).
- **More**: sleep timer, next-folder playback, optional saving of network tracks played to the end, retries of short
  network outages, custom fonts.
- **Languages**: English, 日本語, 简体中文, 繁體中文, Français, Italiano. On Android 13+ the language can also be
  changed in the system's per-app language setting.

Details: [docs/fork/FEATURES.md]({REPO}/blob/{VERSION_TAG}/docs/fork/FEATURES.md).

## Known limitations

- Folds and hinges are not taken into account: on a foldable the browser pane is a fixed share of the width, wherever
  the hinge is. Wide layouts were tested by resizing windows on emulators, not on a real foldable or tablet.
- Opening a folder from search results leaves the results: Back goes to the folder the search was run in (the results are
  not restored).
- Images are made from the NAS once; the first view of a folder still reads each cover once, and again once per folder after a
  source's address or other connection setting is edited. Android may clear the cache when storage runs low (the images are
  then made again). The network account is an estimate, not the bytes on the wire. The cache and the account were tested with
  a Samba server in Docker, not with a real NAS, and the saving on a large real library was not measured.
- Settings shows no mini player.
- DLNA casting sends the current track only (nothing advances to the next one) and was tested with a software
  renderer, not a real TV or speaker.
- The "songs" sleep timer ends with the queue; use the minutes timer together with "next folder".
- "Created" is not a true file birth time (Android does not give apps one); see Sorting above.
- Android 8.0 cannot decode FLAC (no platform decoder); DSF / DFF cannot be cast.
- SMB search and the share list were tested with logic tests and a Samba server in Docker, not on a real LAN or NAS. Many NAS
  boxes and Windows PCs do not list their shares to a guest: log in first, or type the share name. The address that was found
  is what is saved: if the router gives the NAS a new address, search again.
- Favourites are shared through the source that holds `fav.json`; favourites on other sources stay on their device.
  Entries that version 1.0.0 wrote on another device stay hidden until that device has synced once with this version.
- Tested on emulators (Android 8.0 to 16) and on one phone; not with a real NAS, a screen reader or HyperOS devices.

Full list: [docs/fork/KNOWN_ISSUES.md]({REPO}/blob/{VERSION_TAG}/docs/fork/KNOWN_ISSUES.md).

## Licenses

- Folder Player is licensed under the MIT License; this fork keeps that license.
- The app contains FFmpeg 7.1.5 (LGPL-2.1-or-later), built unmodified from the official source tarball, which is
  attached to this release (`ffmpeg-7.1.5.tar.xz`). The complete source of this version, including the scripts to
  rebuild FFmpeg and relink the app, is the source archive of this release.
- Other libraries are under the Apache 2.0, BSD 3-Clause, MIT, CDDL 1.0 and GPL-2.0-with-Classpath-Exception licenses. The full
  notices are in the app under Settings → Open source licenses and in
  [docs/fork/DEPENDENCIES.md]({REPO}/blob/{VERSION_TAG}/docs/fork/DEPENDENCIES.md).

## SHA-256

