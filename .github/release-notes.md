Folder Player Fork {VERSION} — the first stable release of an unofficial personal fork of
[Folder Player](https://github.com/wyvern3000/Folder-Player). Details of every addition:
[docs/fork/FEATURES.md]({REPO}/blob/{VERSION_TAG}/docs/fork/FEATURES.md).

- Installs as a separate app (`com.wing.folderplayer.fork`, "Folder Player Fork") next to the original app.
- Android 8.0 or newer; native decoders for arm64-v8a and x86_64.
- Signed with the fork's release key, certificate SHA-256 `{CERT_SHA256}`. Updates install only over builds signed
  with that certificate (0.5.2-rc1 and the 0.6.0-dev builds update in place; 0.5.0-fork.1 and 0.5.1 must be
  uninstalled once).

## Main features

- **Sources**: local storage, SAF folders, WebDAV, SMB 2/3, FTP / FTPS; passwords per source in the Android Keystore;
  a connection test that names the failure.
- **Browser and player**: the browser is the start page; a mini player grows into the full player and back (tap, or
  drag following the finger; the cover moves between both); Settings from ⋮; Back keeps your place in the browser.
- **Covers**: folder image, then the embedded picture, in the player, notification and lock screen for every source;
  grid / list with thumbnails (network thumbnails are opt-in and Wi-Fi only).
- **Library**: search below the current folder, favourites (optional `fav.json` sync), playlists with drag
  reordering, track lengths in the list.
- **Formats**: ALAC, WMA, APE, DSF and DFF through FFmpeg 7.1.5 (DST-compressed DSD is not supported).
- **Lyrics and info**: LRC, embedded lyrics, CUE sheets, `Info.nfo` album info; optional AI album info, lyrics and
  translation (off by default).
- **More**: DLNA casting (off by default), optional saving of network tracks played to the end, retries of short
  network outages, fonts, languages: English, 日本語, 简体中文, 繁體中文, Français, Italiano.

## Known limitations

- Cast: Play / Pause / Seek pressed just before Stop or a switch to another renderer can still reach the old renderer.
- Android 13+: a language changed in the system's per-app language setting is not picked up; choose it again in the
  app.
- Opening a folder from search results or favourites leaves that list (Back goes to the folder's parent).
- Settings shows no mini player.
- Android 8.0 cannot decode FLAC (no platform decoder); DSF / DFF cannot be cast.
- Tested mainly on emulators (Android 8.0 to 16) and on one phone; not tested with a NAS or on HyperOS devices.

Full list: [docs/fork/KNOWN_ISSUES.md]({REPO}/blob/{VERSION_TAG}/docs/fork/KNOWN_ISSUES.md).

## Licenses

- Folder Player is licensed under the MIT License; this fork keeps that license.
- The app contains FFmpeg 7.1.5 (LGPL-2.1-or-later), built unmodified from the official source tarball, which is
  attached to this release (`ffmpeg-7.1.5.tar.xz`). The complete source of this version, including the scripts to
  rebuild FFmpeg and relink the app, is the source archive of this release.
- Other libraries are under the Apache 2.0, MIT, CDDL 1.0 and GPL-2.0-with-Classpath-Exception licenses. The full
  notices are in the app under Settings → Open source licenses and in
  [docs/fork/DEPENDENCIES.md]({REPO}/blob/{VERSION_TAG}/docs/fork/DEPENDENCIES.md).

## SHA-256
