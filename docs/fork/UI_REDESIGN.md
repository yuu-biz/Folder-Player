# UI redesign: browser-based navigation and mini player

Replaces the three pages Player / Browser / Settings that were switched by swiping sideways (`HorizontalPager`).
Stage 1 is implemented on `feature/player-navigation`; stage 2 waits for feedback from a real device.

## Structure

```
MainActivity                     ViewModels (activity scope), controller connected once in onCreate
└─ AppRoot (ui/AppRoot.kt)
   ├─ Column  "app_pages"
   │  ├─ NavHost: browser (start) | settings
   │  └─ MiniPlayer               only on the browser page and only when there is a track
   └─ Full player (MainPlayerScreen) over everything, AnimatedVisibility
```

- The player is **not** a navigation destination. Its open / closed state is one saveable value
  (`PlayerSheetState.expanded`); mini / full / nothing follow from it, from whether there is a track
  (`PlayerUiState.hasTrack`) and from the current page. No second set of flags.
- One `PlayerViewModel`, one `MediaController` per activity. `initializeController` is called in
  `MainActivity.onCreate` (before any UI) and nowhere else; opening, closing or recreating the screen does not connect
  again, re-queue, seek or pause. A song tapped right after a cold start waits for the controller (`awaitPlayer`),
  and a play request cancels the pending restore of the last session.
- The browser stays composed under the full player, so its scroll position, search and list survive without reloading.
  While the player is open the pages are hidden from accessibility (`clearAndSetSemantics`), and once the opening
  animation has finished they are not drawn (alpha 0).
- Settings is a page of its own (`NavHost`); the browser page keeps its saved state (scroll position) while Settings
  is open. Playback continues; Settings shows no mini player (stage 1).
- Only narrow parts of the player state are collected by the shell and by Settings (`hasTrack`, playlists, current
  media id, cover size, background style; `StateFlow`s in `PlayerViewModel`). The per-second position updates reach
  only the player and the mini player's progress line, never the browser or Settings.
- Since the browser now loads folder images right at start, the folder-image index is written off the main thread
  and coalesced (`ThumbnailRepository.scheduleSave`); before, every image found rewrote the whole JSON file on the
  main thread. Neither change is proven to be the cause of the stutter reported with the old page swipe.

## Navigation

| From | Action | Result |
|---|---|---|
| Start (new or restored) | — | browser (last browsed folder or source list); restoring a session never starts playback |
| Browser | tap a song (folder, search result, favourite, CUE) or Shuffle | play request, full player opens at once (loading / error shown there) |
| Browser | long-press → add to playlist / favourite | no player |
| Browser, any level (sources, source root, folder, search, favourites) | ⋮ → Settings | Settings |
| Mini player | tap | full player |
| Mini player | play / pause (next on wide screens) | only that; the player does not open |
| Full player | ⌄ button or Back | back to the browser exactly as it was; music keeps playing |
| Settings | ← or Back | back to the browser as it was |
| Mini player (paused / stopped, no cast) | swipe sideways, either way | ends the session: queue emptied, notification gone, nothing restored on the next start; playlists kept (TalkBack action "Close player"). While playing, loading or casting the bar only gives a little and springs back; a sideways swipe never opens the player |
| Notification / lock screen | tap | the running activity (singleTop) comes forward, Settings is closed, full player opens; Back → browser. On a new activity without a cached track the request waits until the controller connection is handled; with no track at all it ends on the browser and nothing plays |

### Back order

1. Dialogs, menus and bottom sheets (their own windows) close first; the keyboard is closed by the system.
2. Landscape playlist overlay in the full player (not a window): closed by Back.
3. Full player → folded into the mini player (browser underneath).
4. Settings → browser.
5. Browser: search → closed (back to the list it was opened from); favourites → source list;
   folder → parent folder; source root → source list.
6. Source list: Back is not handled by the app (Android default: leave the app); playback continues.

The on-screen arrow in the browser's top bar does the same as Back (the former "back to sources" jump moved into ⋮,
the separate "up" button is gone). Back handlers do not depend on registration order: the browser's handler is
disabled while the full player is open, and the player's handler exists only while it is open.

### Kept browsing state

Folder, list/grid mode, sort order, scroll position, search query and results, favourites view. The browser list is
not reloaded when coming back from the player or Settings; the list only scrolls on a new folder load
(`BrowserViewModel.handledScrollTrigger`).

### Activity lifecycle

- Rotation does not recreate the activity (`configChanges`); the layout switches between portrait and landscape.
- Language change / process restore: page (browser or Settings), player open/closed and the browser's scroll
  position are restored; the start intent is not applied again.
- Notification intent: action `ACTION_OPEN_PLAYER`, `FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP`, activity
  `launchMode="singleTop"` — no second activity (and no second controller).

## Mini player

Cover (same resolved cover as the full player; no extra lookup), title (same title mode as the player), artist when
there is one, play / pause, next when the bar is at least 360 dp wide (disabled when there is no next track), a thin
progress line. Loading is a ring around
the play button, an error replaces the artist line; neither hides the bar, and pause does not hide it either. It sits
below the list (the last row is never covered), runs under the navigation bar and respects side cutouts.
TalkBack labels: "Open player", "Play" / "Pause", "Next".

## Additions after the first device check (0.6.0-dev2)

- Mini player swipe to end the session (above).
- Playlist (full player, portrait sheet and landscape overlay share one list): drag the handle on the right, or
  long-press a row and drag, to move an entry. Stored on release; if the queue was built from this list (and still
  holds it), the queue moves the same way and the current track keeps playing. Another list with the same entries, or
  Default while the queue came from a playlist, is not the queue. TalkBack: "Move up" / "Move down".
- Browser list: track length after size and type (`DurationRepository`): read from the file headers for rows on screen,
  kept by source / path / size / mtime (only parsed results; read failures are looked at again, unparsable files after
  7 days or on refresh); local and SAF always, network sources follow the thumbnail switches (per
  protocol, Wi-Fi only); refresh looks again. APE / DSF / DFF in shared storage are not listed at all (Android shows
  apps only files it indexes as audio); through a SAF source they are listed, and their length would come from the
  FFmpeg decoder (the FFmpeg path is tested with WMA; APE / DSF / DFF lengths are not tested).
- Review fixes: an activity recreation (language change) no longer counts as a permission change (it reloaded the list
  and lost the scroll position); next / previous without a target change nothing (they showed "Loading" and waited).

## Stage 1 status

Implemented: everything above. Simple transitions only: the full player slides up / fades (250 ms), Settings
slides in from the side.

Not in stage 1 / known limits:
- No continuous mini → full expansion, no drag to open or close (stage 2).
- Settings has no mini player.
- Opening a folder from search results or favourites leaves that list: Back then goes to the folder's parent, not back
  to the results (unchanged from before).
- The full player keeps its own gesture: swipe up opens the playlist (portrait sheet / landscape overlay).
- The pages under the open full player stay composed (needed for stage 2); they are only skipped when drawing.

## Stage 2 (after device feedback)

- Replace `PlayerSheetState.expanded` by an expansion fraction (anchored drag): mini → full with the cover moving and
  growing continuously, title / background / controls following the fraction; drag up on the mini player, drag down
  on the full player; settle to the nearer end, reversible mid-way.
- Keep drag areas away from the seek bar, lyrics scrolling and the queue; move "swipe up for playlist" to an explicit
  button if it conflicts.
- Small transitions on track change (cover, text, background colour); background colour from the artwork must keep
  the BLACK / gradient / blur setting.
- Back, repeated taps, track changes and rotation during the animation must never leave a half-open player or an
  invisible layer taking touches; reduced-motion settings must still work.
- Check on the device where it still stutters before optimising anything.
