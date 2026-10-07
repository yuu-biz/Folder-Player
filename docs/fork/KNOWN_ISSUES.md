# Known issues

## Limitations of 1.0.0 (current)

What a user can run into, in one place. The sections below give the history of each point and what was fixed when.

- **Foldables / hinge**: no fold or hinge information is used; the layout follows the window size. Not checked on a real
  foldable or tablet (window resizing on emulators only).
- **Navigation**: opening a folder from search results leaves the results (Back goes to the folder's parent; the arrow-up button
  goes up one folder as well). A folder opened from favourites or a bookmark leads back to that list;
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
- **Android 8–9**: auto-save of network tracks and saving `Info.nfo` into shared storage need the storage write
  permission, which the app does not ask for (off by default; Android 10+ is not affected).
- **Large folders**: playing a folder of several thousand songs writes the playlist on the main thread (a short stall);
  every local folder listing queries the media library once (slow on very large libraries, not measured).
- **Backup**: on Android 11 and older Auto Backup covers the settings (shared preferences) only, not the playlists and
  `fav.json` under `files/`; Android 12+ backs them up (without downloaded fonts and the Keystore secrets).
- **Credentials**: a WebDAV address typed as `http://user:password@host` is stored as typed (use the user / password
  fields). Passwords entered in the fields are kept in the Android Keystore, per source. API keys of the AI feature
  read from an old (pre-0.6) settings file stay once in `files/migration-backup/` (not backed up).
- **Not checked**: TalkBack sessions, HyperOS devices, a NAS, a background network retry on a real phone with the
  screen off (a refused foreground start is caught and logged, see below).

## SMB search (after 1.0.0)

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

## Bookmarks (after 1.0.0)

- Bookmarks are folders (a source root or a place inside it) kept on this device only: they are not part of `fav.json` and
  are not synced, because which places are handy depends on the device. They are not removed when the source is removed;
  they are hidden while their source does not exist.
- A bookmark is a path, not a check: a folder that was renamed or deleted on the server opens as an unreadable folder (the
  folder error with "Retry"); remove the bookmark from its long-press menu on the source list.
- There is no reordering or renaming: bookmarks are listed in the order they were added, under the name the folder had
  (the source's name for a source root).

## Favourites shared between devices (after 1.0.0)

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
