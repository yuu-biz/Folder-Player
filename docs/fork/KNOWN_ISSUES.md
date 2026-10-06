# Known issues

Open issues that did not block the first public release (0.6.0). Each is to be fixed in a later version.

## Cast: Play / Pause / Seek outside the session ordering

`cast`, `stop` and `shutdown` run one at a time and only the latest one owns the session (see `CastController`).
Play, Pause and Seek do not take part in that: they read the active renderer when pressed and send the command
outside the session lock, without a generation check. A command pressed just before Stop or before casting to
another renderer can therefore still reach the old renderer after the session ended or changed (for example Play
after Stop restarts a renderer whose relay URL is already revoked), and its result or error is written into the
state of the new session.

Fix: give the commands the session's generation and drop them (or their result) when it is no longer current.

## Android 13+: app language changed in the system settings

On Android 13 and newer the language chosen in Settings › Language is stored by the app (`AppLocale`, preference
`app_language`) and also handed to the system as the per-app language. When the language is changed outside the app
(system Settings › Apps › Folder Player Fork › Language), the app's own preference is not updated:

- the activity context is still wrapped with the stored language (`AppLocale.wrap`), so the app can keep showing the
  old language instead of the one chosen in the system settings;
- texts built outside the activity (`Strings.get`, notifications, error messages) use the stored language as well;
- Settings › Language in the app still highlights the old choice.

Choosing the language again inside the app brings both back in line. Android 12 and older are not affected (there is
no system per-app language).

Fix: on Android 13+ read the language from `LocaleManager.applicationLocales` (and stop wrapping the context there),
keeping the preference only for older versions.

Both issues above are still open; the navigation redesign (0.6.0-dev1, [UI_REDESIGN.md](UI_REDESIGN.md)) does not
touch them.

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
  kept (`FavoritesSyncConcurrencyTest`).
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
