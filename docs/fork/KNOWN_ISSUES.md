# Known issues

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
