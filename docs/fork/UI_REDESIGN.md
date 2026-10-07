# UI redesign: browser-based navigation and mini player

Replaces the three pages Player / Browser / Settings that were switched by swiping sideways (`HorizontalPager`).
Stage 1 (browser + mini / full player) and stage 2 (one player that grows from the mini into the full player) are
implemented on `feature/player-navigation`.

## Structure

```
MainActivity                     ViewModels (activity scope), controller connected once in onCreate
└─ AppRoot (ui/AppRoot.kt)
   ├─ Column  "app_pages"
   │  ├─ NavHost: browser (start) | settings
   │  └─ place kept for the mini player (only on the browser page and only when there is a track)
   └─ PlayerSheetHost (ui/player/PlayerSheet.kt), over the pages
      ├─ panel background        only while open: scrim above it, mini colour → BLACK / BLUR / GRADIENT
      ├─ MiniPlayer              static at the bottom, fades out while the player opens
      └─ full player (MainPlayerScreen), only while open; clipped to the panel
```

- The player is **not** a navigation destination. Its state is one saveable value, `PlayerSheetState.expanded`
  (where the player is or is going); the expansion `fraction` (0 = mini, 1 = full) only follows it and is never saved.
  Mini / full / nothing follow from it, from whether there is a track (`PlayerUiState.hasTrack`) and from the current
  page. No second set of flags.
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
| Mini player | tap, or drag up | full player (a drag follows the finger, see "Mini ⇄ full") |
| Full player | drag down (outside the seek bar, the lyrics list and the playlist) | mini player, following the finger |
| Full player | swipe up (portrait: anywhere outside seek bar / lyrics list; landscape: left panel) | playlist, as before |
| Mini player | play / pause (next on wide screens) | only that; the player does not open |
| Full player | ⌄ button or Back | back to the browser exactly as it was; music keeps playing |
| Settings | ← or Back | back to the browser as it was |
| Mini player (paused / stopped, no cast) | swipe sideways, either way | ends the session: queue emptied, notification gone, nothing restored on the next start; playlists kept (TalkBack action "Close player"). While playing, loading or casting the bar only gives a little and springs back; a sideways swipe never opens the player |
| Notification / lock screen | tap | the running activity (singleTop) comes forward, Settings is closed, full player opens; Back → browser. On a new activity without a cached track the request waits until the controller connection is handled; with no track at all it ends on the browser and nothing plays |

### Back order

1. Dialogs, menus and bottom sheets (their own windows) close first; the keyboard is closed by the system.
2. Landscape playlist overlay in the full player (not a window): closed by Back.
3. Full player (also while a finger holds it part-way) → folded into the mini player (browser underneath). Once it
   is folding, the next Back goes to the browser.
4. Settings → browser.
5. Browser: search → closed (back to the list it was opened from). Otherwise Back is the previous place, as in a browser:
   the folder, the favourites list (as it was left) or the source list the user came from, step by step (favourites →
   folder A → folder B: Back → A → favourites list → source list; bookmark likewise). Browsing from the source list goes back
   level by level, as before, and the source root leads to the source list. The arrow-up button next to the back arrow goes up
   one folder; it is a step of its own, so Back after it returns to the folder the user was in. A search result opened leads
   back to the folder the search was run in. Without history (the app opened on the folder it was last in): the folder above.
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

## Mini ⇄ full (stage 2, 0.6.0-dev4)

One panel grows from the mini player into the full player and back (own implementation in Compose; general UX ideas
only, no code, assets or layout values taken from other players).

**State.** `PlayerSheetState`: `fraction` 0..1, `expanded` (logical end), `isDragging`, `isAnimating`. The fraction
is read only in draw / layout lambdas (`graphicsLayer`, `drawWithContent`) or through `derivedStateOf` thresholds, so
moving the player recomposes nothing per frame; it is not part of `PlayerUiState` and the browser never sees it.
A recreation saves only `expanded`: a move interrupted by it always lands at an end.

**Motion.**
- Tap on the mini player (or a song tap, the notification): animates to full. ⌄ / Back: animates to mini.
- Drag: the panel's top edge follows the finger 1:1 (finger travel = distance from the mini player's top to the screen
  top). On release a fling (≥ 600 dp/s) goes in its direction, otherwise the nearer end wins; a finger that stood
  still for more than 80 ms before lifting is no fling (the speed of the earlier move is dropped). The spring has no
  bounce and the fraction is clamped, so the player never overshoots. Reversing mid-way is followed.
- A touch while the player moves on its own takes it over at once (it can be dragged from there); a touch that does
  not move lets it go on to where it was going. Buttons of the fading full player get nothing while it moves.
- "Remove animations" (animator scale 0): jumps instead of animating (tap, drag release, Back); the cover is still
  drawn moving with the finger.

**What moves.**
- Cover: the full player's cover itself is moved and scaled (`graphicsLayer`) from the mini player's cover place to
  its own place, corners going from the mini's to the full player's; at fraction 1 the transform is the identity, so
  the full player's own animations (large lyrics mode, cover size setting) are untouched. The mini player's cover
  steps aside as soon as the full player's cover shows the picture — both show the same thumbnail request, so the
  hand-over at fraction 0 is not visible. Pictures are never requested again while moving: the moving view keeps its
  size, the thumbnail is one request (disk-cached), the large picture is requested once per cover when the player
  opens, and the blurred background waits for it and then takes it from the memory cache.
- Panel: the background rises with the top edge, from the mini player's colour to the chosen background
  (BLACK / BLUR / GRADIENT unchanged; no colour taken from the artwork), with rounded top corners while moving and a
  scrim over the pages above it.
- Mini player: does not move; its texts and buttons fade out in the first 30 %.
- Full player's texts, lyrics, seek bar, buttons: fade in and rise slightly in the second half.

**Gestures kept apart.**
- Mini player: up = open; sideways = end the session (paused only, unchanged); tap = open. Each gesture claims only
  its own axis after the touch slop.
- Full player: down = fold; up = playlist (unchanged entry point; landscape: left panel only, the lyrics panel has no
  swipe as before). The lyrics list scrolls (never folds); the seek bar takes sideways drags as seeks and swallows
  vertical ones (neither seek nor fold); the playlist sheet (its own window) and the landscape overlay keep their own
  drag / reorder.

**Track change.** The cover (when the picture changes) and the title slide in briefly from the side of the change
(next: right, previous: left, from the queue position); play / pause swaps with a short scale and fade.

**Performance rules.** No per-frame writes to the ViewModel; the shell collects only narrow flows (`hasTrack`,
`cover`, `backgroundStyle`, …); the full player is composed only while open and leaves composition at fraction 0;
no new MediaController or listener for opening or closing; pages under the settled full player are not drawn.

## Status and limits

Implemented: everything above. Settings slides in from the side.

Known limits:
- Settings has no mini player.
- Opening a folder from search results leaves the results: Back goes to the folder the search was run in (the results
  are not restored).
- The pages under the open full player stay composed; they are only skipped when drawing.
- While the player moves, a touch anywhere on screen (also above the panel) is taken by the player; the move takes
  about 0.3 s.
- Smoothness on a real phone not checked yet.

# 1.0.0 UI polish: Settings, browser rows, accessibility, wide windows

(`feature/ui-adaptive-polish`.) The mini ⇄ full player and the browser-centred navigation above are unchanged on a
phone; this section lists what was added or moved. Nothing in playback, the network protocols, the artwork cache or
the stored preferences changed (every SharedPreferences key and value is as before).

## Settings

Categories → settings → (for long lists and text fields) a page of its own. Where the user is (category, page) is two
saved values in `SettingsScreen`, so a recreation (language change) or a window change shows the same place.

| Category | Holds |
|---|---|
| Playback | DLNA casting, saving network songs, decoder status |
| Display | Language (page), Font (page), cover size, player background, track title, screen orientation, notch |
| Library & network | default sort + direction, list / grid, grid columns, Folder thumbnails (page: per source type, Wi-Fi only) |
| Lyrics & AI | lyric API URL, lyrics priority, AI lyrics (auto, language, translation), AI connection (page: URL, key, model) |
| Storage & permissions | permission state and the buttons, clear image cache |
| About | version, fork note, links, licenses, the debug-log toggle (tap the icon twice, as before) |

Each row shows its name and its current value or a short description. Choices of 2–5 options stay chips on their
category page; the language list (radio rows, whole row selects) and the font list are pages. Language order: system
default, 简体中文, English, 日本語, 繁體中文, Français, Italiano (`AppLocale.LANGUAGES`; the OS per-app language handling is
untouched). No row has a fixed width or height: rows grow with the text, every control is at least 48 dp high, and the
page scrolls with the keyboard open (`imePadding`).

- Phone / narrow window (< 600 dp wide): one pane. Back goes up one level: page → category → category list → browser.
- Wide window (≥ 600 dp wide and ≥ 480 dp high, measured on the Settings window itself: a phone in landscape, about 720 × 360 dp, stays one pane): the category list stays on the left (300 dp), the
  category's settings (or page) on the right. The first category is shown until another is chosen. Back: page →
  category → browser (the list is always there, so there is no "up" from a category).

Test tags: `settings_column` (the whole Settings window), `settings_categories`, `settings_cat_<id>`, `settings_sub_<id>`,
`settings_up`; the controls keep their tags (`lang_*`, `font_*`, `cover_*`, `thumbs_*`, …). `UiTestBase.settingsReveal(tag)`
opens the page that holds a tag.

## Browser rows

Reading order: name (up to two lines) → format (extension) → length at the end of the row. Size and modification time
are no longer in every row: they are in the actions sheet (long press; TalkBack says "Details and actions") and, while
the list is sorted by size or date, next to the format. Search results and favourites show the folder as the second line.
A length that is not known is not shown (no placeholder). The row says "Playing" for the current track. A folder
that cannot be read shows why, with Retry; an empty one says so.

## Accessibility

- Touch targets: sort buttons, the seek bar (48 dp high now), the playlist rename / delete icons, chips and switches.
- TalkBack: the seek bar is one adjustable control "Playback position — 1:23 of 4:00" (slider semantics); sort buttons say
  selected + direction; rows say "Playing"; Settings headings are headings; links and rows are buttons.
- Large fonts: nothing has a fixed width; the mini player's height grows with the font size (half of the extra size,
  at most 1.5×), rows wrap.
- Contrast: lyrics (inactive lines, "no lyrics"), times and the audio line of the player are brighter.

## Wide windows

The layout follows the size of the window the app has now (rotation, fold / unfold, split screen), never the device:
**wide = at least 640 dp wide and 480 dp high** (`WindowLayout`). A phone in landscape (about 400 dp high) stays narrow.

| | Narrow (phone UI, as before) | Wide |
|---|---|---|
| Browser | page | left pane, 40 % of the width between 320 and 480 dp |
| Player | mini player + sheet (mini ⇄ full) | right pane, docked: the same player content, always open |
| Mini player | at the bottom | none |
| Settings | one pane | two panes (also in a smaller window that is ≥ 600 × 480 dp) |
| System bars in landscape | hidden (immersive) | kept |

One `PlayerViewModel`, one `MediaController`, one queue: `DockedPlayerPane` shows `MainPlayerScreen` with
`PlayerSheetState.docked()` (fraction fixed at 1, no drag, no fold button, a swipe up still opens the playlist). The
layout inside the pane (portrait or landscape player) follows the pane's own size. The browser is the first child of
the same `Row` in both layouts, so a change of the window only changes its size: folder, search, favourites and list
position stay. The large cover is requested at the same size in both layouts (rounded up to 256 px), so the picture
is taken from the memory cache.

**Fold / unfold, rotation, resize.** `density` and `fontScale` were added to the activity's `configChanges` (as
`screenSize`, `screenLayout`, `orientation` already were), so the activity is not recreated; playback, queue, position,
browser place and controller are untouched. The sheet's logical state (`expanded`) is kept while the window is wide and
shows again when it is narrow again. If the window changes while a finger holds the sheet part-way,
`PlayerSheetState.settle()` lands it on the nearer end; a move that goes on by itself already heads for an end and
continues.

**Back (wide).** Dialogs and sheets (own windows) → the landscape playlist overlay of the pane → search → folder
hierarchy (parent folder, source root, source list) → the system. Settings: page → category → browser. The player pane is
never "closed" by Back.

**Hinge.** No fold / hinge information is used: both panes are plain rectangles. If a hinge separates the window, the
browser pane's right edge is at 40 % of the width, not at the hinge. (Not verified on a real foldable; see KNOWN_ISSUES.)

## Sort by creation date (1.0.0-dev5)

The sort buttons are Name, **Modified** (what "Date" was), **Created**, Size; "Created" is also a choice for the default sort in
Settings › Library & network. Where a source tells a creation time, files are sorted by it; where it does not (or a single file
has none), that file is placed by its modification time (`MusicFile.createdOrModified`), so the list never has holes.

| Source | Creation time |
|---|---|
| Local (this device) | the time the file was **added to the media library** (`MediaStore` `DATE_ADDED`: when it appeared on the device). Android gives apps no birth time of a file: `BasicFileAttributes.creationTime` returns the modification time (checked on API 34), so it is used only where it differs from it. Files the media library does not list (not audio / images the app may read, folders) → modification time |
| SMB | the share's creation time (`smbj` `creationTime`) |
| WebDAV, FTP, SAF folders | none → modification time |

The buttons are always there (the fallback makes them harmless). The row shows the creation date beside the format while the list
is sorted by it. The sort row is as wide as its buttons: with four equal buttons while the names fit, and scrolling sideways
(never breaking a name) with a large font or in the narrow browser pane.
