# Known issues

## Limitations (current: 1.8.0)

What a user can run into, in one place. The sections below give the history of each point and what was fixed when.

- **Foldables / hinge**: no fold or hinge information is used; the layout follows the window size. In a wide window the
  player pane can be hidden (browser only, see "Image thumbnails, browser-only wide layout"). Not checked on a real
  foldable or tablet (window resizing on emulators only).
- **Navigation**: Back is the previous place (the folder, favourites list or source list the user came from, step by step); the
  arrow-up button goes up one folder and Back after it returns to where the user was; the home button goes to the source
  list with no way back from it. A folder opened from search results
  leads back to the folder the search was run in, not to the results;
  Settings shows no mini player.
- **Sorting "Created"**: Android gives apps no file birth time. Local files: the time added to the media library;
  SMB: the share's creation time; WebDAV, FTP, SAF folders: the modification time. Files with equal times are ordered by
  name. Not compared with real creation times on other phones.
- **Formats**: Android 8.0 cannot decode FLAC (no platform decoder); DSF / DFF cannot be cast; DST-compressed DSD is
  not supported.
- **Cast (DLNA)**: tested against a software renderer in Docker and with a fake renderer, not with a real TV / speaker.
  Only the current track is sent: when it ends on the renderer the session stays open (Stop button, replay) but
  nothing advances to the next track. The discovery service (and its Wi-Fi multicast lock) runs from the first time the
  cast list is opened until DLNA is switched off in Settings or the process ends. Discovery was not checked after a
  Wi-Fi change (home → office) or when first opened without Wi-Fi: if the list stays empty, switch DLNA off and on.
  The relay listens on all interfaces; every URL carries a random token and exists only during a session.
- **Playback control from other apps**: `MusicService` is exported so that the system UI, Bluetooth and car
  controls can use it, and accepts any controller. Another installed app can start / stop playback and replace the queue.
  An allow-list of known controller packages is a hardening candidate after 1.0.0.
- **Sleep timer**: a "songs" timer ends with the queue (with "next folder" on it does not carry on into the next
  folder; use the minutes timer) and does not count a track repeated by "repeat one". The timer is lost when the app
  process ends.
- **Restore**: after playing a list built from search results, favourites or a shuffle across folders, the next start
  restores the folder of the first song of that list, at the position of the song that played last.
- **Queue** (1.8.0): songs added with "Play next" / "Add to queue" are in the queue and in "Default" (when the queue came
  from it), but a restart restores the folder of the song that played, as above, not the queue. With shuffle on, the
  player places added songs among the shuffled ones, so "Play next" is not always next. A CUE sheet cannot be added
  (play it). A folder adds its own songs, not those of the folders below it.
- **Speed and left / right** (1.8.0): kept for the next start; the pitch stays the same (Media3's time stretching, which
  can sound rough at 0.5× and 2×). A DLNA cast plays at normal speed and unswapped (the renderer plays the file).
- **Cover swipe** (1.8.0): only on the cover of the full player (portrait and landscape), not on the mini player, whose
  sideways swipe still ends the session.
- **Android 8–9**: auto-save of network tracks and saving `Info.nfo` into shared storage need the storage write
  permission, which the app does not ask for (off by default; Android 10+ is not affected).
- **Large folders**: playing a folder of several thousand songs writes the playlist on the main thread (a short stall);
  every local folder listing queries the media library once (slow on very large libraries, not measured).
- **Backup**: on Android 11 and older Auto Backup covers the settings (shared preferences) only, not the playlists and
  `fav.json` under `files/`; Android 12+ backs them up (without downloaded fonts and the Keystore secrets).
- **Credentials**: a WebDAV address typed as `http://user:password@host` is stored as typed (use the user / password
  fields). Passwords entered in the fields are kept in the Android Keystore, per source. API keys of the AI feature
  read from an old (pre-0.6) settings file stay once in `files/migration-backup/` (not backed up).
- **Filter**: names only (not tags or the folders below); not available in the favourites list or search results. `OriginBackUiTest#a62`
  passed on an API 34 emulator (VERIFICATION 5.27).
- **File viewer**: text shows the first 1 MB, images up to 2560 px (enough to zoom, not the full size of a large scan), GIFs
  without animation, PDFs as pages (no text search, no links, no password-protected files); files over 200 MB are not
  opened. Copies of images and PDFs from a source are kept in the app's cache (up to 300 MB, outside the image cache of
  Settings). Checked on an API 34 emulator, not on a phone (see below).
- **Rename, move, delete** (1.4.0): files only, not folders; a move stays inside the file's source. Local folders
  of the phone (the built-in "Internal Storage" and "SD Card" sources) refuse it for files the app did not create itself
  (nearly all of them): add the folder as a SAF folder to change files there. Playlists, favourites and
  the queue keep the old path of a renamed or moved file (see "Rename, move and delete of files" below).
- **Waveform**: since 1.5.0 a measurement that has started finishes and is kept even when the player is closed. Not timed on a phone or a real NAS (see "Waveform on the seek bar").
- **Visualiser** (in place of missing lyrics): during a DLNA cast the music plays elsewhere and the picture stays a flat line; it follows the playback speed's media time, drawn against the wall clock between the sink's position reports (at most a quarter of a second). Not timed on a phone (CPU of the FFT at the frame rate). The colours come from the cover once it is loaded; a cover of a far song of a long queue (songs list) may come a moment after the song starts. In portrait the area is short (about 75 dp on a phone): a double tap makes it larger. Seen once in the emulator suites (1.7.1, while the host was short of memory): a rendering error ("Recording currently in progress") in a browser test; not reproduced in five runs since and not explained (no log kept).
- **Song lengths**: folders are not sorted by the total length of their songs (only songs are). A scan reads every song it does not know yet, several at once since 1.7.1 (4 per SMB / WebDAV source, 2 for FTP, 6 locally); a large library on a NAS still takes a while, and the speed on a real NAS was not measured. The first scan after 1.7.0 reads the songs it knew once more, for their tags. A file that cannot be read now is tried again by the next scan. Lengths and tags are kept for up to 30 000 files.
- **Songs list** (1.7.1): only songs whose length was read are listed (scan a folder or the bookmarks first); it is built from the app's own index, not Android's media library. WMA, APE, DSF and DFF show their file name (their tags are not read). A copy of a file (same name, size and time) in another folder is listed once. It also holds the songs whose length was read while their folder was on screen, not only those of a scan; since 1.7.2 it can be narrowed to one folder, since 1.8.0 to several (bookmarks and sources ticked together in the folder button; a folder's own "Songs list of this folder" replaces them with that one folder). Playing from a list of many folders: the next songs get their folder cover and lyric file at each change of track; a song reached by a jump (or shuffle) shows its cover on screen, not in the notification. Seen twice in the emulator (1.7.1 and 1.7.2, of about a dozen runs of `SongsUiTest#s03`): Next from a song of the songs list reached the next song, which then stayed buffering for 15 s on a local file (not playing, no error); not reproduced since and not explained. The emulator's log shows Android reclaiming audio decoders ("Released by resource manager") in passing runs too.
- **Favourites**: since 1.7.0 the source list shows favourite songs and favourite folders as two lists; `fav.json` is unchanged, so an older app on another device still syncs it (and shows one list).
- **Playback that stops by itself**: a log of one (1.7.2, a song on an SMB share) points to a NAS or Wi-Fi that stopped
  answering, with no line logged while the player retried by itself; since 1.8.0 a dead SMB connection is dropped after
  a read times out and the log shows buffering, load errors, a heartbeat and memory warnings (see "Playback that stops
  by itself"). Share the log right after a stop.
- **Not checked**: TalkBack sessions, HyperOS devices, a background network retry on a real phone with the screen off (a
  refused foreground start is caught and logged, see below). Low priority (owner's decision, 2026-10-24): a real NAS
  (Synology, QNAP, Windows), SMB search and mDNS on a real LAN, and DLNA with a real TV or speaker; the owner uses the
  app with a NAS on the phone and reports what goes wrong.

## Waveform on the seek bar (1.3.0, faster in 1.3.1 and 1.3.2)

- **What it shows.** The loudness of the whole track, so that loud scenes (a drama CD's climax, a song's chorus) can be
  seen before they play and reached with one tap. Each bar is the RMS of its part of the track relative to the loudest
  bar, raised to the power 0.7 (-6 dB is about 60 % of the height, -20 dB about 20 %), so quiet passages stay visible.
  It is loudness, not a frequency picture and not a live visualizer of what plays at the moment.
- **How it is measured (1.3.2).** Every format goes through the bundled FFmpeg decoder (MP3, AAC, FLAC, Vorbis, Opus
  and WAV as well as WMA / APE / DSD / ALAC; MediaCodec only when FFmpeg declines a file), as Namida does.
  MediaCodec cost a round trip through the media service per buffer and a flush per seek, which some phones make slow
  (a 30-minute MP3 took about 30 s on the owner's phone with 1.3.1), and MediaExtractor read a few KB per request,
  each one a round trip to a NAS. A local file is decoded from start to end (one RMS level per bin of all its audio:
  a 30-minute file has about 3,900 bins). If, after 0.7 s, that would take more than 5 s (a slow phone, a very long
  file), it is sampled instead, and a file over 2 minutes on a network source always is: one 250 ms window (after
  50 ms that are decoded and dropped) aimed at each of 100 to 600 bins, one every 3 s of audio, in a coarse-to-fine
  order, measured where the seek lands, at or before the bin (an MP3 seeks to one of the 100 points of its table of
  contents, a FLAC file to its seek points, usually 10 s apart). Decoding from there up to the bin would read seconds
  of audio per window, more than the whole file over all windows. Windows that land where an earlier one did are not
  read again, so a VBR MP3 on a NAS has about 100–150 measured points and a FLAC file one per seek point; the bins
  between measured points are interpolated (the bars next to a sudden change are lifted a little). It is a rough
  trend: a short shout between two points is missed.
- **How it appears.** Once, whole: no bars fill in one by one and nothing rescales while it is measured (1.3.1 redrew it
  every 0.3 s with gaps). The bars grow out of the plain track in 0.6 s. Only when sampling is slow (more than 1.5 s,
  with as long again to go) is a coarse waveform shown first; the full one then moves the bars to their new heights.
  Sampling stops after 8 s and keeps what it has (interpolated).
- **Formats that seek slowly.** When a container has no index (some WMA, DFF) FFmpeg can only seek by decoding from the
  start; such a file is decoded once from start to end instead.
- **Cost.** Local files and short network files are read once whole, besides playback; long network files only around
  the sampled windows (reads of at most 32 KB). The reads count in the network account (Settings → Library & network)
  like any other read of a source. Network files are measured only on Wi-Fi / Ethernet; on mobile data, and for files
  longer than the limit in Settings, the plain seek bar is shown. The result is kept (`cache/waveforms`, a few KB per file, 500 files), so a
  file is read for its waveform once until it changes; Android may clear the cache when storage runs low. The FFmpeg
  decoders added for it make the APK about 1 MB larger.
- **When it is measured.** It starts when a seek bar shows the track (the full player or the player pane), one file at
  a time. Since 1.5.0 it then runs to its end and is kept even when the player is closed (before, closing the player for
  more than 5 s threw it away and the next opening started again from the beginning); opening the player again joins it.
  A track that needs measuring stops the measurement of the previous one; a track whose waveform is kept, or one that is
  not measured (mobile data, too long, setting off), does not, so the previous one finishes. Turning the setting off does
  not stop a measurement that has started.
- **Limits.** A CUE track is a slice of its disc image's bins: on a NAS a 5-minute track of a 70-minute image gets
  about 40 of the 600 windows (fewer measured points), so its bars are coarse; a local image is decoded whole. A file
  whose duration is not known up front is decoded whole and stops at the limit. A VBR MP3 whose length the player estimates differently from the decoder can
  have its bars slightly off in time. Timings were measured on an x86_64 emulator (FFmpeg without assembly there) with
  a NAS simulated by 5 ms per read; on a phone the owner found it much faster, but did not time it.

## Image thumbnails, browser-only wide layout, shuffle in the menu (1.3.0)

- **Image files have their own thumbnail.** An image in the list (jpg, png, gif, webp, bmp, heic) is shown by its own
  picture: the whole picture, not cropped, on a framed tile with an image badge in its corner. A folder's image fills its
  tile edge to edge as before, so the two are told apart at a glance. The thumbnail goes through the same image cache as
  folder images (128 / 256 px files, Settings → Library & network) and the same switches (local on, network off by default,
  Wi-Fi only). The first view of a folder of photos on a NAS reads each photo once (up to 20 MB each; larger ones keep the
  icon); the 256 px file is kept from then on.
- **Wide windows: browser only.** The player pane beside the browser can be hidden: the button in its top corner or
  "Hide player" in the ⋮ menu. The browser then has the whole window; while something plays a mini player sits at the
  bottom, and a tap on it (or the bar button "Show player", the ⋮ menu, the notification) brings the pane back. Playing a
  song from the browser does not bring the pane back (the mini player shows it). The choice is kept across restarts
  (`layout_prefs`); the phone UI is unchanged. The pane appears and disappears without an animation.
- **Top bar**: grid / list is always on the bar, shuffle is always in the ⋮ menu (it was on the bar, and grid / list was in
  the menu on narrow screens).

`BrowserUiTest#a19` and `AdaptiveLayoutTest#a11` passed on an API 34 emulator before the 1.3.0 tag (VERIFICATION 5.27).
Not checked on a real foldable.

## File viewer (1.3.0)

Text, images and PDF beside the songs open in a viewer of the browser (a tap, or "Open" in the long-press sheet); "Open in
another app" hands the file to an app chosen by Android. How it works and why:

- **One way for every source.** Files of SMB, FTP, WebDAV and SAF sources cannot be opened by other apps, so the viewer
  reads the file itself through the source. Text is read directly (the first 1 MB, `ViewerText.LIMIT`). Images and PDFs
  are copied to `cache/viewer/<key>/<name>` first (`ViewerCache`): PDF needs a local file for Android's `PdfRenderer`,
  and the same copy is what "Open in another app" shares (FileProvider `<applicationId>.viewer`, this folder only, read
  permission per intent). The key is the file's URI, size and modification time: a file is copied once and again only
  when it changes. The copies stay under 300 MB (oldest first out); files over 200 MB are refused with a message. Local
  files are copied too (simple and the same for all sources; a copy of a local file is quick).
- **Text encoding.** A byte order mark, then strict UTF-8, then the legacy encodings of the device language: Shift_JIS
  and EUC-JP on a Japanese device, Big5 on Traditional Chinese, GB18030 elsewhere (as the lyric and CUE reader). Each is
  taken only when every byte decodes; GB18030 accepts almost any bytes, so on an English device Shift_JIS text is shown as
  GB18030 garbage: the ⋮ menu of the viewer picks the encoding by hand. A file cut at 1 MB may end inside a character;
  that end is dropped instead of failing the UTF-8 check. Rip logs, NFO, CUE and checksum files use a fixed-width font
  by default (switchable in the same menu).
- **Images** swipe to the other images of the list on screen (in its order, the filter applied); pinch or double tap to
  zoom, a single finger at normal size swipes. Decoded by Coil from the copy at up to 2560 px.
- **PDF** pages are rendered one at a time on a thread of their own, at 1.5 × the screen width (at most 2048 px), so that
  zoomed text stays readable; a vertical drag of a zoomed page that reaches its edge scrolls the pages.
- **Long-press sheet**: a viewable file has "Open" and "Open in another app" instead of "Add to playlist" and
  "Add to favourites" (an image or PDF already in the favourites can still be removed there).

Open points:

- `ViewerUiTest` (text, images swiped, Back, the long-press sheet) passed on an API 34 emulator (VERIFICATION 5.27);
  not run on a phone. PDF has no device test (the fixture has no PDF); the JVM tests
  cover the file kinds, decoding and the copies (`FileViewerTest`).
- The viewer's copies are not part of Settings → image cache (usage and Clear); Android may clear them when storage
  runs low. Search results list folders, songs and CUE sheets only, not files the viewer opens.

## Rename, move and delete of files (1.4.0)

The long-press sheet of a file (any file: songs, images, text, PDF, CUE…) and the ⋮ menu of the viewer offer "Rename",
"Move to…" and "Delete". Each one asks first: rename shows the name with the part before the extension selected, "Move to…"
lists the folders of the same source starting at the file's own folder (up one level, into a folder, "Move here"),
delete asks for a confirmation that names the source. Afterwards the folder is read again and the viewer stays on the
file (rename) or goes on to the next image (move, delete; it closes when there is none).

- **Files only.** Folders cannot be renamed, moved or deleted: a folder delete means different things per protocol (SMB
  and FTP refuse one that is not empty, WebDAV deletes everything below it), and bookmarks, favourites and playlists
  point into folders.
- **Nothing is overwritten.** The sources' own rename replaces an existing file (the favourites sync relies on it), so
  the target folder is listed first and a name that is already there is refused, ignoring case (SMB shares, SD cards
  and SAF folders treat `a.mp3` and `A.mp3` as one file). Names with `/ \ : * ? " < > |`, control characters, `.` / `..`
  or more than 255 bytes are refused on every source, since some sources cannot hold them.
- **The file that is playing** is refused (play another one first).
- **Local folders** (the built-in "Internal Storage" and "SD Card" sources, read through the file system): Android 10
  and later lets an app rename, move or delete only files it created itself, and on Android 8–9 the app does not ask
  for the storage write permission; for the other files the app shows a message pointing to SAF. Adding the same folder as a SAF folder (the + on the source list) works, as do SMB, FTP and
  WebDAV with an account that may write. Asking Android for consent per file (`MediaStore.createWriteRequest` /
  `createDeleteRequest`) is not done.
- **SAF**: rename through `DocumentsContract.renameDocument`, move through `moveDocument`. A provider that cannot move
  documents (some cloud providers) refuses the move with a message; nothing is copied instead.
- **References keep the old path.** Playlists, favourites, the playing queue's saved state, the track-length and waveform
  caches and a `fav.json` on another device are not rewritten: a renamed or moved song shows as missing there until it
  is added again. The image and viewer caches are keyed by path and size, so a moved file is simply read again once.
- Delete is final on every source (no recycle bin; a NAS may keep its own recycle bin for SMB).
- Checked with JVM tests over an in-memory source and on an API 34 emulator (see VERIFICATION.md 5.30); the protocol
  servers in Docker (Samba, FTP, WebDAV) were used only through the tests listed there. Not checked on a phone or a
  real NAS.

## Playback that stops by itself (investigated after 1.2.0; playback log and retry locks in 1.5.0)

Report: playback sometimes stops in the middle of a track, also with battery optimisation off for the app. A code
reading (2026-10-21) found no bug that stops healthy playback of a local file; nothing records why playback stopped, so
the cause on the owner's phone is not known. Candidates, most likely first:

1. **Audio focus lost to another app** (a video, voice message, game, navigation): `MusicService` lets Media3 handle focus
   (`setAudioAttributes(…, true)`); a permanent loss pauses and is not resumed. Notification sounds only duck.
2. **Audio route dropped** (Bluetooth A2DP dropout, USB DAC re-enumerating, loose plug): the app's own
   `ACTION_AUDIO_BECOMING_NOISY` receiver calls `player.pause()`, which looks like a user pause (reason `USER_REQUEST`).
3. **Network source with the screen off** (the one weak spot in the code): an outage longer than the 30–60 s buffer ends in
   a player error → `STATE_IDLE`; the player then holds no wake / Wi-Fi lock and leaves the foreground, while
   `NetworkRetryController` schedules its retry with `Handler.postDelayed` (1 s … 30 s), which may only run when the device
   wakes; after 5 minutes it gives up and playback stays stopped. The retry status is not shown anywhere.
4. **A vendor process killer** after a pause (MIUI / HyperOS, ColorOS, Samsung "deep sleeping apps"; separate from the
   Android battery-optimisation switch).
5. By design: the sleep timer; decode errors (would repeat at the same place of the same file).

How to tell them apart right after a stop: a plain pause with the notification still there → 1, 2 (or 5); an error text
→ 3 (or a decode error); no notification and a cold start → 4. With adb: `adb shell dumpsys audio` (focus owner, device
disconnects), `adb shell dumpsys activity exit-info com.wing.folderplayer.fork` (why the process ended),
`adb logcat -s MusicService NetworkRetry`. The crash log of debug mode records uncaught exceptions only.

In 1.5.0:

- **Playback log** (Settings → Playback → Playback log; `playback/PlaybackLog.kt`): the last 500 events, kept in
  `files/diagnostics/playback.log` (rewritten to the last 500 when it reaches 1000 lines), each one line
  `date time KIND detail`, in English so it can be shared as it is: play / pause with Media3's reason (`USER_REQUEST`,
  `AUDIO_FOCUS_LOSS`, `AUDIO_BECOMING_NOISY`, `END_OF_MEDIA_ITEM`…), the file and position; suppression (transient focus
  loss); idle / ended; track changes; player errors (code, message, cause; a `user:password@` in a URL is removed);
  commands from controllers with the app that sent them (system UI, Bluetooth, a car, this app); headphones unplugged
  (the app's own receiver logs `NOISY` before its pause, which Media3 reports as `USER_REQUEST`); the sleep timer; network
  retries (scheduled, recovered, gave up, cancelled); service start (app version, device, Android version) and stop; and,
  on Android 11+, why the process ended before (`getHistoricalProcessExitReasons`: low memory, killed by the user or the
  system, crash, ANR…). Share sends it as text to an app of the user's choice; Clear empties it. It stays on the device
  and contains file names, no passwords.
- **Retry with the screen off**: while a network retry is pending (up to 5 minutes) the service holds a partial wake
  lock and a Wi-Fi lock (released when the retry has prepared the player again, which then holds its own, or when it is
  cancelled or gives up), so a retry runs on time with the screen off. Not checked on a phone with the screen off.

Still open: the retry status is not shown in the player (only in the log); retries inside ExoPlayer
(`LoadErrorHandlingPolicy`), which would keep the foreground notification, are not done; the cause of the reported stops
is not known until a log of one is read.

**A log of a stop (1.7.2, 2026-10-26, Xiaomi phone on Android 16, a 30-minute MP3 on an SMB share).** The song started at
05:46:18; the log then holds nothing until `PROCESS_EXIT reason=SIGNALED importance=125` at 06:12:25 (the process was
killed by a signal while it was still a foreground service, so the player still meant to play), and the app was opened
12 s later. The position restored at the next start was 1458 s; the app saves it every 5 s while the music moves, so
the music stopped moving at about 06:10, two minutes before the kill, with no pause, error or end logged. The owner had
only opened the app when it was killed. Reading of it:

- With the Samba fixture paused in the middle of a song (`SmbStallTest`, the TCP connection stays open, as with a NAS or
  Wi-Fi that stops answering): the read fails after 30 s (the SMB timeout), and every new open of the file then took
  60 s, because the dead connection was tried first (the timed-out read was mapped as a protocol error, so the
  connection was kept) and a new one only after that. ExoPlayer retries a failed load by itself (at least 3 times, 1–5 s
  apart) before it reports an error, and none of that was logged: about 4 minutes of silence before the first `ERROR`
  line (computed from the measured open times and ExoPlayer's retry count, not timed end to end), longer than the two
  minutes the music had stopped for. A NAS or Wi-Fi that stopped answering for a while fits
  the log; what the kill at 06:12:25 was (HyperOS, or the system when the app came to the front) is not known.
- 1.8.0: a read that times out (a transport error) drops the connection at once, without the goodbyes (close, tree
  disconnect, logoff) it would wait for in vain, so the read fails after 30 s and the next open connects again at once
  (30 s instead of 60 s when the server still does not answer, at once when it answers again; `SmbStallTest`). A first
  version that closed it politely made the failed read itself take 75 s; the test checks both times. The log now
  shows what was missing: `BUFFERING` when the player has waited for data for 3 s and when it goes on (with the
  position and how much is buffered), `LOAD_ERROR` for every load error the player retries by itself (the exception,
  the file and position, how long the load ran and what it read), a `HEARTBEAT` every 5 minutes while playback is
  wanted (state, position, buffered), `MEMORY` when the system asks the app to free memory, and with `PROCESS_EXIT`
  the signal or exit code and the memory the process used (`status`, `pss`, `rss`).
- If it happens again on a NAS: share the log; HyperOS settings that keep a player alive are "Autostart" on and Battery
  saver "No restrictions" for the app, and locking it in the recent apps.

## Image cache and network use (1.2.0)

- Images are kept on disk in two tiers under one size limit chosen in Settings → Library & network → Image cache and network
  use (128 MB, 256 MB, 512 MB default, 1 GB, 2 GB):
  - **Browser thumbnails**: a 128 px or a 256 px JPEG. A request above 128 px (also on a tablet's larger grid) gets the 256 px
    one, enlarged on screen. The 256 px one is written when a cover is validated, so a large cover is read from the
    NAS once; the 128 px one is cut from it without asking the source. A high-resolution image is never kept for the browser.
  - **Player artwork**: one file per image, longer side at most 2048 px (an image that already fits and is at most 2 MB is
    kept as it is, otherwise JPEG 90), made when something is played. Mini, full and wide player and the notification
    use that one file.
  - A file is used until the image itself changes (the size and modification time in its key); switching thumbnails on or
    off, Wi-Fi only, or editing a source (e.g. its address) does not discard it. Clear does. Broken or unreadable images,
    permission and network errors, and images whose URI has no version are never kept (unchanged rule).
  - Over the limit the least recently used files go until the total is at three quarters of the limit. Thumbnails are
    entitled to 40 % of that, player artwork to 60 %; a tier may use the other's unused room but gives way first when it holds
    more than its share. Lowering the limit cleans up at once. The cache of earlier versions (`thumbs`, other sizes) is deleted
    once at the first start of this version; its files would no longer be used.
- Still read from the NAS: each cover once on the first view of a folder (the app checks that it is an image), and again once
  per folder after a source's connection settings were edited (its folder-to-cover index is rebuilt). The cache is in the
  app's cache directory: Android may clear it when storage runs low; the images are then made again.
- The network account (Settings, same page) counts what the app asked network sources for through its file layer since
  the process started: bytes by kind (images, audio and metadata, other), by source, folder lists, thumbnails and player
  artwork fetched from a source, image cache hits and misses. It is an estimate: no protocol overhead, retransmissions or other
  apps; local storage is not counted; it is not kept across restarts; "Reset counters" zeroes it.
- Not checked: a real NAS (the Docker SMB share only); other API levels; how much traffic it saves on a large real library
  (not measured). The older entry "Thumbnail files were keyed by the exact requested pixels…" below describes the cache of
  1.0 and is superseded by this section.

## SMB search (1.1.0)

- "Search network" in the SMB editor looks only when the button is pressed (about 12 s at most). It finds servers that
  announce themselves through mDNS (NAS boxes, Macs, Samba with Avahi) and hosts that answer on TCP 445 in the /24 around
  the device's Wi-Fi / Ethernet address. Only private IPv4 addresses are probed; nothing is scanned over cellular or a VPN.
- What is stored is the **address**, not the name: if the router gives the NAS another address, edit the source or search
  again. User and password are typed by hand.
- "Get shares" (next to the Share field) asks the server which disk shares it offers for the login typed in the form (nothing
  is saved); administrative `$` shares, printers and pipes are left out. Servers answer a logged-in user; some Samba setups
  and routers also answer a guest, so right after a search the list is shown by itself when the server lists shares to a
  guest. Many NAS boxes and Windows PCs do not list shares to a guest: then type the login first, or the share name by hand.
  Samba lists shares the user cannot open (e.g. another user's share); picking one fails at "Test connection".
- Not found: a server on another subnet or VLAN, on a Wi-Fi that isolates its clients, one that listens on a port other than
  445 and does not announce itself (use the port field), IPv6-only hosts. mDNS needs the router to pass multicast.
- The name comes from mDNS, else from NetBIOS (UDP 137); a host that answers neither is listed by address.
- Not checked on a real LAN or NAS: the emulator's network has no SMB server, so the tests cover the logic (addresses,
  NetBIOS packets, merging) and that the search starts, ends and closes. The share list was checked against Samba only (not
  Synology, QNAP or Windows). A newer Android may ask for a local-network
  permission before apps reach LAN devices; it is not needed on API 34, the only level run for this change.

## Bookmarks (1.1.0; order and names in 1.4.0, drag in 1.5.0)

- Bookmarks are folders (a source root or a place inside it) kept on this device only: they are not part of `fav.json` and
  are not synced, because which places are handy depends on the device. They are not removed when the source is removed;
  they are hidden while their source does not exist.
- A bookmark is a path, not a check: a folder that was renamed or deleted on the server opens as an unreadable folder (the
  folder error with "Retry"); remove the bookmark from its long-press menu on the source list.
- A new bookmark is added at the end of the list, under the name the folder had (the source's name for a source root).
  Its long-press menu renames it (the name is only what the list shows; the folder is not touched) and moves it up or
  down one place. Bookmarks of a removed source are hidden and skipped when moving: a bookmark moves past the next one
  that is shown. Since 1.5.0 the handle at the end of a row drags it to another place.

## Order of the favourites list (1.6.0)

- The favourites list has the sort buttons of a folder plus "Added" in front: Added (when each entry was added; the
  default, oldest first, the order of earlier versions), Name, Modified, Created and Size. A second tap on the selected
  button reverses it. The choice is kept for the favourites list (folders keep their own).
- Added keeps folders and songs together in the order they were added; the other buttons put folders first, as in a
  folder.
- Modified and Created are the files' own times, kept in `fav.json` since 1.6.0 (`modified`, `created`; the format
  version stays 1, older versions ignore the two fields). Entries added before 1.6.0 lack them: when the list is sorted by
  Modified or Created, the app reads them from the sources once (in the background; the list re-sorts when they arrive)
  and stores them. A source that cannot be reached (a NAS that is off) is skipped after its first failure and asked again
  on the next start of the app; its entries sort as the oldest until then. Size comes from when the entry was added.
- A device that syncs `fav.json` with a version before 1.6.0 writes the entries back without the two times; they are read
  again from the sources when needed.
- Checked with JVM tests and on an API 34 emulator (VERIFICATION.md 5.32), with local files only.

## Favourites shared between devices (1.1.0)

- A source id is a random UUID per installation, so favourites synced through a NAS `fav.json` were not shown on a second
  device (the entries arrived but pointed at the other device's source). The file now stores an entry on the source it
  lives on as `"sourceId": "@sync"` with its path on the server (root path inside the share / FTP home; for WebDAV the base
  URL's path plus the root path). Each device maps it to its own source and root, so a NAS reached by name on one phone
  and by IP on another shows the same favourites. No file-format version change: an older app reads the entries, cannot
  place them, and writes them back unchanged.
- Entries outside a device's root: a device whose source root is deeper than the entry (root `/Library`, entry
  `/Elsewhere/x.flac`) does not list it, and the sync keeps it in the file. "Replace remote" overwrites the file with this
  device's list, so those entries go with the old file.
- Favourites on other sources (a local folder, a second NAS) are still per device: their entries keep the source id and are
  hidden on devices that do not have it. Only the source holding the `fav.json` is shared.
- Entries a device wrote before this change use that device's source id: this device's own are rewritten in the shared form
  by its next sync, those of other devices stay in the file, hidden, until the device that wrote them has
  synced once with this version.
- The match is on the path on the server and is case-sensitive. Not checked on a real NAS (in-memory file system).

## Final audit before 1.0.0: fixed

Found by reading the whole code base (four independent reviews of playback, sources / credentials, storage / caches
and UI / cast); each fix has a test that failed before it.

- **Backup**: the cloud backup included `files/fonts/` (a downloaded font is 14–24 MB); Auto Backup stops for good above
  25 MB of app data, so after one font download favourites, playlists and the source list were no longer backed up
  (`BackupRulesTest`).
- **Cast**: switching DLNA off in Settings during a session removed the cast button, and with it the only Stop control;
  the relay, the Wi-Fi / wake locks and the renderer went on (`NavigationUiTest#n17`). `CastController.shutdown()` had
  no caller; it now runs when DLNA is switched off. When the track ends on the renderer the Wi-Fi lock and the 6 h
  wake lock are released (and taken again if the renderer plays again) (`CastSessionOrderTest`).
- **Sleep timer by minutes + next folder**: the timer was cancelled when the queue ended, so with "next folder" on the
  next folder played on all night. It now runs to its deadline (`PlaybackServiceTest#timeTimerSurvivesTheEndOfTheQueue`).
- **Shuffle / next folder on a folder with no songs of its own** (the music root, an artist folder holding only albums):
  the running playback was stopped, the "Default" playlist replaced by an empty one, and the player stayed on "Loading…".
  Nothing is touched now (`PlaybackServiceTest#folderWithoutSongsOfItsOwnKeepsThePlayback`).
- **Playlists**: `metadata.json` (the list of playlists) was rewritten in place on every play; a kill or a full disk
  during the write left a cut-off file that the next start read as "no playlists". Written through a temporary file now
  (`AtomicWriteTest`).
- **Sorting**: songs with equal sort keys (normal for "Created": an album copied in one go has one second for every
  file) came in the order of the file system, and descending order reversed them differently in the browser list and
  in the queue of "play folder" / restore / next folder. One function with a name tie-break serves both
  (`SortOrderTest`).
- **Notification**: an Android 12+ refusal to start the foreground service from the background (a network retry with
  the screen off) is caught and logged instead of ending the app. Written from the code path, not reproduced on a
  device.

## Earlier history

The two issues known at the first public release (0.6.0) are fixed in 1.0.0-dev2; see the next section for what changed
and what is left. Older fixes follow below.

## UI polish and wide windows (1.0.0-dev3): changes and open points

See "1.0.0 UI polish" in [UI_REDESIGN.md](UI_REDESIGN.md) for what the screens do now. Limits and things not checked:

- **Hinge / fold posture is not used.** In a wide window the browser pane is 40 % of the width (320–480 dp), wherever a
  physical hinge is. A vertical hinge may run through the
  browser or the player pane on a real foldable; nothing in the app knows where it is. No Flex-mode / tabletop layout.
- **Real foldables and tablets were not available**: wide windows were tested by changing the window size of an API 34
  phone emulator (`wm size`, which gives the same configuration changes as a fold / unfold, a split-screen resize and a
  rotation). Not checked on a device: the animation of a real fold, a different density on the inner display (`density` is
  handled in place now, `configChanges`), freeform windows.
- **TalkBack was not run**: the semantics (names, states, the seek bar as a slider, headings, roles) were written
  for it and the seek bar slider is asserted in a test, but no screen reader session was done. Contrast and touch targets were checked by
  numbers and screenshots, not with an accessibility scanner.
- **Wide threshold**: 640 × 480 dp of the window's own (non-decor) size. A 7-inch tablet in portrait (600 dp) keeps the
  phone UI; a phone in landscape does too. The Settings window switches to two panes at 600 × 480 dp of its own size (a phone in landscape, about 720 × 360 dp, stays one pane).
- When the window turns wide while the full player is open on the phone, the sheet's state is kept: turning narrow again
  shows the full player again (not the mini player).
- The mini player's height grows with the font size, so with a very large font it takes more of the list than before.
- **"Created" is not the file's birth time on this device.** Android does not give apps a birth time. For local files "Created" is the
  time the file was added to the media library (when it appeared on the phone; a file copied with its old modification time
  is "created" when it was copied), for SMB the share's creation time; WebDAV, FTP, SAF folders and files the media library
  does not list sort by the modification time. Checked on an API 34 emulator only; how far the media library's time matches
  the real creation on other phones (and after a library rescan or a restore from backup) is not known.
- The "Date" sort is now named "Modified" (the same sort as before).
- Browser rows no longer show the size and the modification time; they are in the long-press sheet (and next to the
  format while sorted by size / date).
- A very long title in the player is still shrunk sideways to fit one line (`TextCompressed`, unchanged); with a large
  font the cover is made smaller so the title keeps its room.

## Quality hardening toward 1.0.0 (1.0.0-dev2): fixed

### Cast: Play / Pause / Seek outside the session ordering

Was: Play, Pause and Seek read the active renderer when pressed and sent the command outside the session lock, without
a generation check. Pressed just before Stop or before casting to another renderer they could still reach the old
renderer (Play after Stop restarted a renderer whose relay URL was already revoked) and write their result or error into
the new session's state.

Now: the commands take the session lock like cast / stop, remember the generation and the renderer they were pressed for,
and are dropped (not sent) when a stop or another cast was requested since or the renderer is no longer the active one.
A command already talking to the renderer cannot be recalled, but stop / cast wait for it (so the old renderer is never
operated while the new session starts), and its late success or error is not written into a newer session
(`CastSessionOrderTest`: command → Stop and command → cast to another renderer, each with a late success and a late
failure, queued commands, a command pressed while the Stop is running).

Behaviour changes: commands are sent one at a time in the order pressed (before: concurrently, unordered). A command
pressed while a cast is starting waits for that cast and is then dropped if it was meant for the previous renderer.
A hung renderer (control timeout 10 s) delays a following Stop / cast by up to that timeout, as a hung cast already did.

### Android 13+: app language changed in the system settings

Was: the app kept its own copy of the language (`app_language`), so a change in Settings › Apps › Folder Player Fork ›
Language was not reflected in the activity, in `Strings.get` / notifications / error texts, nor in Settings › Language.

Now: on Android 13+ the system's per-app language (`LocaleManager.applicationLocales`) is the only source of truth.
`AppLocale.get` reads it (mapped to the app's language list; `zh-Hans-CN`, `fr-FR`… are matched), `AppLocale.set`
writes only the system setting, and the activity context is no longer wrapped there. Android 12 and older keep the
preference + wrapped context. A language stored by an older version is handed to the system once at start
(`AppLocale.migrate`, flag `app_language_os_migrated`), only if the system has no per-app language of its own; the
preference is never read again afterwards, so "System default" chosen in the system settings stays.
Checked on API 34 in both directions (`AppLocaleOsTest`), the migration cases in `AppLocaleMigrationTest`, the mapping in
`AppLocaleTest` (JVM). The unchanged code failed the system-settings direction.

Open points: (a) a user already on Android 13+ with an older build of this app who had reset the language in the system
settings to "System default" while the app's own copy still held a language gets that old language once more at the first
start of the new version (the two cases cannot be told apart); (b) the application context follows a system-side change
a moment after the activity does (the test waits up to 5 s for `Strings.get`); a text built in that gap is in the old
language.

### Artwork / thumbnails

- A cover on a network source was read twice (validation, then display): now once. Measured with a counting source on
  API 34: 2 reads → 1; two sizes of the same cover 3 → 1 (`ArtworkIoTest`). The bytes are kept in a bounded LRU memory
  cache (12 MB total, 3 MB per image; larger images are read again for display). Only complete reads of decodable
  images are kept, whichever side (validation or display) read them: a failed, cancelled, permission-denied, broken or
  undecodable read, or bytes whose size is not the one in the URI's version, is never kept, so a glitch seen by the display
  cannot make a later validation pass (found in review before the merge). The key is the image URI with its
  size/mtime version and the source revision, no credentials.
- The first `positive` lookup read and parsed `artwork-index.json` on the calling (often main) thread: now read on an
  I/O thread when the repository is created; lookups wait for it, changes made meanwhile are merged, saves stay
  debounced (`ArtworkIndexTest`).
- Thumbnail files were keyed by the exact requested pixels: now rounded up to 128 / 192 / 288 / 512 px, never below the
  request (3 sizes of one image: 3 files → 1, `ArtworkIoTest`). Files live in `thumbs/r<revision>/`; a new artwork
  settings revision deletes older generations and the flat files of earlier versions. The cache is limited to 64 MB
  (trimmed to 48 MB, least recently used first, on an I/O thread). `clearCaches()` still removes everything.
  Reasons for the values: [VERIFICATION.md](VERIFICATION.md) 5.12.

Open points: the memory cache is not shared with Coil's own bitmap cache; images above 3 MB are still read twice;
the bucket and cache limits come from the current UI sizes and an estimate of file sizes, not from measurements on
a large real library.

## Fixed by the navigation redesign (0.6.0-dev1)

- Tapping the playback notification while the app was open started a second app screen on top of the first (with its
  own player connection); Back then led to the old screen (by code reading of the intent flags; not reproduced on a
  device). The tap now brings the running screen forward and opens the full player (`NavigationUiTest#n10`).
- Settings and the browser were recomposed with every player state update (about once a second during playback),
  because they read the whole player state. Found by reading the code, not measured; whether it contributed to the
  reported stutter is unknown.
- The folder-image index (`artwork-index.json`) was rewritten on the main thread for every folder image found while
  scrolling; it is now written on an I/O thread, at most once per half second. Also not measured as a cause of the
  stutter.

## Fixed after review (0.6.0-dev2)

- Language change (activity recreation) reloaded the browser list as if permissions had changed: the list jumped back
  to the top. Now only an actual change of the granted access reloads it (`NavigationUiTest#n12`).
- Notification tap on a new activity without a cached track: the "show the player" request was dropped while the
  controller was still connecting, so the player did not open. It now waits for the connection; with no track it
  ends on the browser without playing (`OpenPlayerColdStartTest`).
- Next / previous with nothing to move to (single track, last / first track without repeat) set the title to "Loading"
  and waited for a track change that never came. They now do nothing; the mini player's next button is disabled then
  (`PlayerSkipTest`).

## Fixed after the second review (0.6.0-dev3)

- Track lengths: a failed read (lost connection, authentication, permission, cancellation; in the FFmpeg decoder or
  inside MediaMetadataRetriever) was stored as "length unknown" for good. Only parsed results are kept now; files that
  are read but cannot be parsed are kept as unknown and looked at again after 7 days or on refresh; dev2's stored
  unknowns are dropped (`DurationRepositoryTest`).
- Next / previous onto another queue position holding the same track (the same song twice in the queue, one song with
  repeat all) left "Switching track…" / "Loading…" on screen: completion was judged by a changed track id. It is now
  judged by the queue position (`PlayerSkipTest`).
- Moving (and removing) entries of a playlist changed the playing queue whenever the shown list had the same entries,
  even if the queue came from another list. Only the list the queue was built from changes it now
  (`PlaylistQueueTest`).
- The mini player could be swiped away while casting (casting pauses the phone), leaving the renderer playing without
  a player on the phone. It stays while a cast is starting or running (`NavigationUiTest#n16`). Found on the way: a
  sideways swipe on the mini player while it may not be dismissed (playing) opened the full player, and a refused
  dismissal left the bar slid out of view; both fixed.

## Stability hardening toward 1.0.0 (1.0.0-dev1): fixed

Found by static review, reproduced by tests first (see VERIFICATION.md 5.11).

- Play requests raced: while one request was still reading a slow source (NAS listing, CUE file, cover lookup),
  a newer one could finish first; the older one then replaced the queue, the "Default" playlist, the saved last
  folder / track and the screen, or showed its late error over the new playback. Only the latest request (folder,
  list, playlist, CUE, restore, next folder) is applied now (`PlayRequestRaceTest`). The restore of the last session
  was already safe (its cancellation took effect).
- Favourites: a sync held the favourites lock across its network I/O, so adding / removing a favourite from the
  browser (main thread) waited for the server. The lock is now held only to merge; changes made during a sync are
  kept (`FavoritesSyncConcurrencyTest`). A local-only favourite removed while the sync (or "replace remote") is
  writing the remote is taken off the remote again, so the next sync does not bring it back.
- Sleep timer: it lived in the screen's ViewModel. After leaving the app (activity finished) playback went on in the
  service but the timer was gone, so it never stopped playback, and the next screen showed no timer. It now runs in
  `MusicService` (`SleepTimerLifecycleTest`). Changed on the way: a time deadline that passes while playback is paused
  now ends the timer without effect; before, the next resume was paused at once.
- A folder named `Init` or `init` in shared Music erased settings on every start — the configured sources included —
  and left the app unable to play (no player connection). Seen on API 34 with and without media permissions. Release
  builds now ignore the folder; debug builds keep it as a development escape hatch (`DevSafeModeTest`).

## Stability hardening: open points

- Debug safe mode (`Music/Init`) still clears the source list but leaves the sources' stored passwords in the
  credential store (unreachable, not readable without the source). There is no user-facing "reset app settings";
  Android's "Clear storage" is the full reset.
- The sleep timer is not kept when the app process ends (as before: it was never persisted).
- Next-folder playback is skipped while a play request is still loading (the request decides what plays).

## Fixed in 0.6.0-dev5

- Player drag (up / down) and the mini player's sideways swipe: a short fast move, then the finger held still, then
  released, still counted as a fling (the release speed came from the earlier moves), so the player opened / closed
  or the session ended unintentionally. A finger still for more than 80 ms at the lift now counts as stopped;
  immediate flicks are unchanged (`PlayerSheetUiTest#s10`, which failed on dev4 and passes now).

## Fixed in 0.6.0-dev4

- Landscape full player: the seek bar kept the length of the track it was first shown with, so after a track change a
  tap or drag sought to the wrong place (found while moving the seek gesture; `PlayerSheetUiTest#s09` covers the
  fixed code, the old build was not run against it).

## Navigation redesign: open points

- Opening a folder from search results or the favourites list leaves that list; Back then goes to the folder's parent
  instead of back to the results (as before the redesign).
- Settings shows no mini player.
- Mini ⇄ full (stage 2): a vertical drag that starts on the seek bar now does nothing (before, an upward one opened the
  playlist); while the player moves (about 0.3 s) a touch anywhere is taken by the player. See
  [UI_REDESIGN.md](UI_REDESIGN.md).
- On a real phone the user checked 0.6.0-dev5 (no major problems reported); other phones, and the earlier report of
  stutter when swiping between pages (never explained), are not checked further.
