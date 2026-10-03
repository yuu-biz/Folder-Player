# Local storage, permissions and SAF

## Problem

Upstream asks only for READ_MEDIA_AUDIO (no READ_MEDIA_IMAGES) and reads local folders through the File API. On a
HyperOS 3.0.4 phone the images next to local songs never showed in the list, the player or the notification.

## What the emulators show (measured with `LocalAccessTest`)

Fixture pushed to `/sdcard/Music/fixture` (`scripts/emulator/instrument.sh push-fixture`), API 34 google_apis x86_64:

| Runtime grants (API 34) | `File.listFiles()` of `Album-A` | Folder image | Local playback |
|---|---|---|---|
| READ_MEDIA_AUDIO + READ_MEDIA_IMAGES | audio, `.lrc`, `cover.jpg`, `folder.png` (no `Info.nfo`) | found | OK |
| READ_MEDIA_AUDIO only (= upstream's manifest) | audio and `.lrc` only — **images are invisible** | not visible | OK |
| AUDIO + partial photo access (READ_MEDIA_VISUAL_USER_SELECTED) | audio only (images not picked) | not visible | OK |
| none | empty | — | — |

Further measured facts:

1. Root cause on API 33+: without READ_MEDIA_IMAGES the media provider hides image files from the File API, so a
   `cover.jpg` beside the songs is simply not listed. This matches upstream's missing permission.
2. Even with every media permission, a folder containing `.nomedia` (`Unindexed/`) lists as **empty** through the File API.
3. On Android 11+ `.cue`, `.lrc`, `.nfo` written by another app/adb cannot be *read* through the File API
   (`EACCES`) even when listed; DSF/DFF are not classified as audio by MediaProvider and are invisible.
4. So READ_MEDIA_IMAGES alone fixes indexed images only; SAF is required for `.nomedia` folders, `.cue/.lrc/.nfo` and
   any non-media file.

## Fix in the Fork

- Manifest: READ_MEDIA_AUDIO, READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED (API 34+), READ_EXTERNAL_STORAGE
  (maxSdk 32). `MainActivity` requests the set returned by `PermissionDiagnostics.mediaPermissions()` for the OS version.
  Denying images never blocks audio (table above: playback OK in every audio-granted state).
- `PermissionDiagnostics` reports audio/images as GRANTED / PARTIAL / DENIED plus persisted SAF grants; shown in
  Settings → "Storage & permissions" with a request button. Grants changed in system settings are picked up on resume
  (`permissionEpoch`), and thumbnail negatives are invalidated.
- `ThumbnailRepository`: a local folder with no visible image while images are not GRANTED is reported as
  `Failed(PERMISSION)` (lock icon + text), not "no image", and is not cached as absent.
- **SAF source** (`SafFileSystem`): "Folder on this device (SAF)…" in the add menu launches ACTION_OPEN_DOCUMENT_TREE;
  the persistable read(/write) grant is taken and stored; listing/reading uses `DocumentsContract` and the provider's file
  descriptor (seekable → random access; otherwise a re-opening reader that skips forward). Content URIs are never
  converted to `File`. A revoked grant is reported as PermissionDenied; "Pick folder again" keeps the same source id
  (favourites/playlists stay valid). No MANAGE_EXTERNAL_STORAGE.
- Playback of a local `.cue`/`.lrc` that shared storage refuses shows a hint to add the folder as a SAF folder.
- Notification/lock screen artwork is decoded in-process (`SourceBitmapLoader`) for every source type (SAF included).

## Verification

| Check | Where | Result |
|---|---|---|
| Permission states ALL / AUDIO_ONLY / PARTIAL / NONE (API 34) | `LocalAccessTest` | PASS (API 34); API 33 / 36 to run |
| SAF pick of `Music/fixture/Unindexed` (`.nomedia`) through the real DocumentsUI, listing incl. `.nomedia`, purple `cover.jpg` decoded, LRC, text NFO; File API sees nothing | `SafTest.a_pickNomediaFolderThroughSystemPicker` | PASS (API 34) |
| Grant after a full emulator reboot, playback from SAF, SAF cover in player and notification, LRC lyrics | `SafTest.b_grantSurvivesRestartAndPlays` via `scripts/emulator/saf-sequence.sh` | PASS (API 34) |
| Revoke → PermissionDenied; "Pick folder again" → same source id, single entry, contents readable | `SafTest.c_revokedGrantIsReportedAndRepickRestoresSameSource` | PASS (API 34) |
| SD card | emulators have no SD card | not tested |

Observation: Android writes persisted URI grants to `/data/system/urigrants.xml` with a delay of about 10 s. A reboot
right after granting lost the grant once; with a 20 s wait the grant was present in `urigrants.xml` and survived the
reboot. This is OS behaviour; `saf-sequence.sh` waits and records the file check.

## HyperOS 3.0.4 (target device) — pending

Not yet measured: needs the user's phone over wireless debugging (`adb pair` / `adb connect`). Planned procedure:

1. Record `getprop ro.build.version.release/sdk`, `ro.mi.os.version.name`, page size (`getconf PAGE_SIZE`), ABI.
2. Original app (`com.wing.folderplayer`) next to the Fork (`com.wing.folderplayer.fork`): open a local album with
   `cover.jpg` → screenshot list/player/notification ("before": expected no image).
3. Fork with READ_MEDIA_IMAGES granted → same album (expected image); then the same folder added via SAF (expected image,
   plus `.lrc`/`.nfo`); portrait + landscape + notification screenshots; SMB album cover still shown.
4. Store screenshots and `adb logcat` excerpts and update this section.
