Folder Player Fork {VERSION} — an unofficial personal fork of
[Folder Player](https://github.com/wyvern3000/Folder-Player) by wyvern3000. It is not affiliated with the original
project. This release follows 1.6.0.

## What's new in 1.7.0

- **Favourite songs and favourite folders, apart**: the source list now has two entries, "Favorite songs" and
  "Favorite folders", each with its own order. The heart at the top right of the full player adds or removes the song
  that is playing. `fav.json` keeps its format, so a device with an older version still syncs it (and shows one list).
- **To the song's folder**: a tap on the folder name in the full player opens that folder in the browser with the song
  in view; Back returns to where you were. The album info (NFO / AI) moved to a long press on the name.
- **Fine seeking**: hold the seek bar still until it vibrates, then drag: the whole bar width is 30 seconds (a quarter of
  a short track), and the time shows tenths of a second. Letting go without moving does not seek.
- **Sort by length**: songs can be sorted by their length (folders stay by name). Lengths the app does not know yet are
  read for the folder on screen, one file at a time, and the list re-sorts once when they are in. For a whole tree,
  "Read song lengths" in a folder's ⋮ menu, in a bookmark's long-press menu or for all bookmarks in the source list's ⋮
  menu reads them in the background with progress and a Cancel button; a stopped scan continues where it left off. The
  lengths are kept on the device: a file moved elsewhere is found again by name, size and date without reading it, and
  files that are gone are dropped at the next scan.
- **The music, drawn**: where a song has no lyrics, the full player shows the music that is playing as frequency bars and
  a waveform, in time with what you hear (it holds still when paused). Nothing is recorded; no microphone permission.

## New in 1.6.0

- **Sort the favourites**: the favourites list now has sort buttons like a folder: Added (when you added each one, as
  before), Name, Modified, Created and Size; tap again to reverse. The choice is kept. For favourites added with an
  earlier version the app reads the file dates once from their source the first time you sort by date.

## New in 1.5.0

- **Playback log**: Settings → Playback → Playback log records what started, paused or stopped playback and why: the
  reason Android gave (another app took the audio, headphones or Bluetooth disconnected, the end of the queue…), which app
  sent a play or pause (system UI, Bluetooth, a car, this app), errors and network retries, the sleep timer, and, on
  Android 11+, why the app was closed before (low memory, killed by the system…). If playback stops by itself, open it
  right after and share it. It stays on the device (the last 500 events) and holds file names, no passwords.
- **Network retries with the screen off**: while the app waits to retry a dropped network connection, it keeps the phone
  and Wi-Fi awake (for up to 5 minutes), so the retry happens on time instead of when the screen is turned on.
- **Bookmarks by drag**: drag the handle at the end of a bookmark to put it elsewhere in the list.
- **Waveform kept when the player is closed**: a waveform that is being measured now finishes even if you close the
  player, and is there when you open it again (before, closing the player for a few seconds threw it away).

## New in 1.4.0

- **Rename, move and delete files**: the long-press menu of a file and the ⋮ menu of the file viewer can rename it, move it
  to another folder of the same source (picked in a folder list) or delete it, each after a confirmation. Handy for
  tidying up scans, logs and covers beside the songs, or a misnamed track. Works on SMB, FTP, WebDAV and SAF folders. A
  name that is already taken in the target folder is refused, so nothing is overwritten; folders and the song that is
  playing are left alone. In the phone's own storage ("Internal Storage", "SD Card") Android lets the app change only
  files it created itself: there the app says so and suggests adding the folder as a SAF folder (+ on the source list).
- **Bookmarks in your order and under your names**: the long-press menu of a bookmark renames it (only the name in the
  list changes) and moves it up or down.
- While the file viewer is open, messages of the browser show as a short pop-up instead of behind the viewer.

## New in 1.3.2

- **Waveform, faster again and drawn at once**: the waveform is now measured with the bundled FFmpeg decoder for every
  format (as Namida does), instead of Android's media codecs, which some phones make slow (a 30-minute MP3 took about
  30 s on a phone with 1.3.1). A local file is decoded whole again, so every part of it counts; from a NAS a long file
  is sampled where each seek lands, so only a little of it is read. The waveform appears once, whole, growing out of
  the seek bar, instead of filling in with gaps and changing height while it is measured. On the test emulator a
  30-minute MP3 now takes about 2 s (4.6 s in 1.3.1, drawn 15 times), and 1.5 s with a simulated NAS that answers each
  read after 5 ms (1.3.1 read about 11 times per point there, 75 ms a point: over 20 s for the file). The APK grows by
  about 1 MB (the added decoders).

## New in 1.3.1

- **Faster waveform**: the waveform on the seek bar now shows within about a second. A track longer than two minutes is
  measured from short samples spread over it (only those parts are read, so a long file on a NAS is not read again
  whole), and decoding no longer waits between buffers. On the test emulator a 10-minute FLAC took 22 s in 1.3.0.

## New in 1.3.0

- **Waveform on the seek bar**: the seek bar of the full player shows how loud each part of the track is, so loud
  scenes (in a drama CD, for example) can be found at a glance and reached with one tap. Each file is measured once in
  the background and the result is kept; a CUE track shows its own
  part of the disc image. Settings → Playback turns it off and sets the longest file to measure (2 hours by default);
  files on network sources are measured only on Wi-Fi.
- **Home button**: next to the back arrow and the arrow-up button, one tap goes from any folder straight back to the source
  list (it replaces "Back to sources" in the ⋮ menu).
- **Filter for the folder on screen**: the filter button left of the sort buttons narrows the folders and songs of the
  folder you are in by name as you type, without searching the folders below it. Every word you type must appear in
  the name; upper / lower case, full / half width and katakana / hiragana do not matter. Back to the folder brings the
  filter back; another folder starts without it. Playing a song or shuffling uses what the filter shows.
- **Top bar**: "Grid / list" is always on the bar and "Shuffle" is now in the ⋮ menu. On a phone held upright and in the
  browser pane beside the player, "Refresh" is in the ⋮ menu, so that the folder name keeps room.
- **File viewer**: text files, images and PDFs beside the songs open with a tap (text with its encoding guessed or chosen,
  images with swipe and zoom, PDF pages); "Open in another app" hands the file over. Works for every source.
- **Image thumbnails**: image files in the grid and list show their own picture, whole and framed with an image badge, so
  they are not mistaken for folders (thumbnails of network sources stay opt-in and Wi-Fi only).
- **Browser only on large screens**: on an unfolded foldable or a tablet the player pane can be hidden, so that the
  browser has the whole screen; a mini player shows what plays, and a tap on it brings the player back.

Earlier in 1.x: 1.2.0 kept NAS images in a two-tier cache with one size limit, added a rough account of network use and
made Back lead to the previous place (with an arrow-up button); 1.1.0 added "Search network" and "Get shares" in the SMB
editor, favourites shared through one `fav.json` on a NAS, and bookmarks for folders.

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
- **Search, filter, favourites and playlists**: search below the current folder, a name filter for the folder on screen, favourite songs and folders (optional `fav.json` that several devices can share), bookmarks for folders (renamed and ordered as you like), playlists with drag reordering, track lengths in the list.
- **Cover artwork**: folder image first, then the embedded picture, in the player, notification and lock screen for
  every source; grid or list with thumbnails (thumbnails of network sources are opt-in and Wi-Fi only).
- **Sorting**: by name, modified time, created time, size or (songs) length, remembered per folder. "Created" is the time a local file
  was added to the media library, the share's creation time on SMB, and the modified time elsewhere.
- **Lyrics and album info**: LRC files, embedded lyrics, CUE sheets, `Info.nfo` album info. Optional AI album info,
  lyrics and translation (off by default; you provide your own OpenAI-compatible endpoint and key).
- **DLNA casting** to renderers on your network (off by default).
- **Formats**: ALAC, WMA, APE, DSF and DFF through FFmpeg 7.1.5, in addition to what Android plays (DST-compressed DSD
  is not supported).
- **Waveform on the seek bar**: loudness over the whole track, measured once in the background and kept.
- **File viewer and simple file management**: text, images and PDFs beside the songs open in the app; files can be
  renamed, moved within their source and deleted.
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
- Folders are not sorted by the total length of their songs. A scan of a large library on a NAS reads one file at a time and can take long. The visualiser stays a flat line during a DLNA cast (the music plays elsewhere).
- Rename, move and delete work on files, not folders, and a move stays within the source. In the phone's own storage most
  files cannot be changed by the app; use a SAF folder there. Playlists and favourites keep the old path of a
  renamed or moved song.
- The waveform of a long file is a rough trend from samples (from a NAS about one point per 1 % of the file for an MP3),
  and it was timed on an emulator with a simulated NAS, not on a real NAS. Files longer than the limit in Settings, and
  network files on mobile data, keep the plain seek bar.
- The filter matches the names of the folder on screen only (not tags, not the folders below); it is not offered in the
  favourites list or in search results.
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

