# Folder Player Fork — added features

This is an unofficial personal fork of [Folder Player](https://github.com/wyvern3000/Folder-Player), based on the
public `main` (`069fd1b`, versionName 0.4). It installs next to the original app (`com.wing.folderplayer.fork`,
label "Folder Player Fork") and adds the features below. Paths are relative to
`app/src/main/java/com/wing/folderplayer/`. How everything was tested: [VERIFICATION.md](VERIFICATION.md).
Releases (APK downloads): [RELEASE.md](RELEASE.md).

| Area | Upstream (public main) | This fork | Tests |
|---|---|---|---|
| SMB | — | `data/source/SmbFileSystem.kt`: SMBJ 0.15.0, SMB2/3, per-source connection reuse with idle timeout, guest access, typed errors; random-access reads for seeking | `protocol/SmbProtocolTest` (Samba), `NetworkPlaybackTest`, `PlaybackServiceTest` |
| SMB search | — | "Search network" in the SMB editor, only when pressed: servers that announce themselves (mDNS `_smb._tcp`) and TCP 445 on the Wi-Fi / Ethernet subnet (private addresses only, the /24 around the device), names from mDNS or NetBIOS; the address is stored (names rarely resolve on Android); share, user and password stay manual | `SmbDiscoveryTest`, `SmbDiscoveryUiTest` |
| FTP / FTPS | — | `data/source/FtpFileSystem.kt`: commons-net 3.13.0, passive, binary, REST seeking (servers without REST are reported, not hidden), early EOF reported, explicit FTPS with hostname check or a pinned certificate | `protocol/FtpProtocolTest` (normal / no REST / truncating / FTPS), `RetryExportTest` |
| Sources & settings | Local + WebDAV, one global WebDAV login, passwords in plain prefs | `SourceRegistry` with stable source ids, `SourceUris` (`fpsrc://<sourceId>/<path>`), passwords per source in the Android Keystore, migration of existing settings/playlists/playback state, source editor (add, edit, duplicate, reorder, delete) with a connection test that names the failure | `SourceMigrationTest`, `SourceRefTest`, `MigrationTest`, `SourceRegistryTest`, `SourceUiTest` |
| Local images (Android 13+) | only READ_MEDIA_AUDIO: images beside songs are invisible | READ_MEDIA_IMAGES and partial photo access with a diagnostics section; SAF folders (`SafFileSystem`) for `.nomedia` folders and `.lrc/.cue/.nfo`; findings in [ANDROID_STORAGE.md](ANDROID_STORAGE.md) | `LocalAccessTest`, `SafTest` |
| Covers & thumbnails | folder cover for local files | fixed order cover → folder → album → front → disk, next image if one is broken, parent folder for CD1-style names; folder image → embedded picture in the player, notification and lock screen for every source; grid/list with per-protocol thumbnails (network off by default, Wi-Fi only), errors shown differently from "no image" | `ArtworkResolverTest`, `PlaybackServiceTest`, `NotificationSwitchTest`, `BrowserUiTest` |
| Search | — | recursive search below the current folder, cancellable, skips unreadable folders | `SearchRepositoryTest`, `LibraryUiTest` |
| Favourites | — | song/folder favourites across sources, optional sync of a `fav.json` that several devices can share (additive merge; entries on the file's own source are stored by their path on the server, so each device maps them to its own source id and root; a broken remote file is never overwritten without an explicit "replace") | `FavoritesTest`, `FavoritesCrossDeviceTest`, `WebDavProtocolTest`, `LibraryUiTest`, `SyncTagsUiTest` |
| Bookmarks | — | folder shortcuts kept on this device only (not synced, own file `files/favorites/bookmarks.json`): added from a folder's long-press sheet or from the browser's ⋮ menu, listed above the sources, a tap opens the folder; never plays anything | `BookmarksTest`, `BookmarksUiTest` |
| NFO | — | `Info.nfo` / `folder.nfo` / `album.nfo` (XML and text format) shown as album info; DOCTYPE/XXE rejected | `NfoParserTest`, `AiNfoUiTest` |
| AI (optional) | AI album/artist description | cached AI info with "regenerate" and explicit save to `Info.nfo`; AI lyrics and translation (off by default, OpenAI-compatible endpoint) | `AiLyricsServiceTest`, `AiNfoUiTest` |
| Titles & tags | file names | file name or tag titles, embedded pictures/lyrics, audio info | `EmbeddedTagReaderTest`, `SyncTagsUiTest` |
| DLNA casting | — | jUPnP 3.0.5 control point + Ktor 2.3.13 relay (token per session, Range 200/206/416), off by default | `RelayServerTest`, `DlnaRendererIntegrationTest` (gmrender-resurrect) |
| Auto-save | — | optional: a network track played to its end is downloaded completely, verified and saved to `Music/` | `PlaybackLogicTest`, `RetryExportTest` |
| More formats | extension list only | ALAC, WMA, APE, DSF, DFF through FFmpeg 7.1.5 built from source (JNI, arm64-v8a + x86_64, 16 KB pages); DST is reported as unsupported | `NativeDecodeTest`, `scripts/check-16k.sh` |
| Fonts | built-in | Noto Sans SC / LXGW WenKai / Sarasa UI SC download (pinned, checksum-verified), TTF/OTF import, safe fallback | `FontUiTest` |
| Languages & display | hard-coded strings | English, Simplified/Traditional Chinese, French, Italian, Japanese; grid density, background style, cover size restored | `LibraryUiTest` |
| Navigation (0.6.0) | Player / Browser / Settings pages switched by swiping | browser as start page, mini player, full player over the browser, Settings from ⋮, Back order and kept browsing state, mini player swipe to end the session, playlist reordering by drag, track length in the list; one player that grows from the mini into the full player (tap, drag up / down following the finger, fling; the cover moves between both), small track-change motion ([UI_REDESIGN.md](UI_REDESIGN.md)) | `NavigationUiTest`, `PlayerSheetUiTest`, `PlayerSkipTest`, `OpenPlayerColdStartTest` |
| Network retry | — | retries transient network errors with growing delays for up to 5 minutes; not for login/not-found errors | `PlaybackLogicTest`, `RetryExportTest` |
| Large screens & Settings (1.0.0) | phone layout only | wide windows (≥ 640 × 480 dp of the window itself): browser and player side by side, no mini player; the layout follows fold / unfold, rotation and split screen without recreating the activity; Settings as categories, two panes in a wide window; larger touch targets, TalkBack semantics, brighter secondary text ([UI_REDESIGN.md](UI_REDESIGN.md)) | `AdaptiveLayoutTest`, `SettingsLayoutTest`, `NavigationUiTest` |
| Sorting (1.0.0) | name / date / size, remembered per folder | name / **Modified** / **Created** / size; Created = media-library add time (local), share creation time (SMB), modification time elsewhere | `CreatedSortTest`, `CreationTimeTest`, `LikeEscapeTest` |
| Robustness (1.0.0) | — | latest play request wins, Cast commands ordered per session, sleep timer in `MusicService`, Android 13+ per-app language as the single source of truth, bounded artwork / thumbnail caches | `PlayRequestRaceTest`, `CastSessionOrderTest`, `SleepTimerLifecycleTest`, `AppLocaleOsTest`, `ArtworkIoTest` |

Existing behaviour (Local/WebDAV playback, LRC, CUE, playlists, sleep timer, sort memory, next-folder playback,
notification) is kept and covered by `PlaybackServiceTest` and `MigrationTest`.

## Notes

- The notification artwork is decoded in the app so SMB/FTP/SAF covers reach the system UI; notification updates are
  coalesced because Android drops bursts of updates.
- Android 8.0 has no FLAC decoder; FLAC files show a "cannot be decoded on this Android version" message there.
- Not included: DSF/DFF casting, DST-compressed DSD.
