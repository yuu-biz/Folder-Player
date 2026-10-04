# Known issues

Open issues that do not block the first (private) release. Each is to be fixed in a later version.

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

## Navigation redesign: open points (stage 1)

- Opening a folder from search results or the favourites list leaves that list; Back then goes to the folder's parent
  instead of back to the results (as before the redesign).
- Settings shows no mini player.
- No drag gestures between mini and full player yet (stage 2, see [UI_REDESIGN.md](UI_REDESIGN.md)).
- How the new navigation feels on a real phone (smoothness, reachability) has not been checked yet; the earlier
  report of stutter when swiping between pages is not explained, so it is not claimed to be fixed.
