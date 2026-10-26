package com.wing.folderplayer.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime

class PlaybackLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val at = ZonedDateTime.of(2026, 10, 24, 8, 15, 30, 123_000_000, tokyo).toInstant().toEpochMilli()

    @After fun closeLog() = PlaybackLog.open(null)

    @Test fun lineRoundTripsInTheGivenZone() {
        val event = PlaybackEvent(at, PlaybackEventKind.PAUSE, "AUDIO_FOCUS_LOSS song.flac at 12s")
        val line = PlaybackLogFormat.encode(event, tokyo)
        assertEquals("2026-10-24 08:15:30.123 PAUSE AUDIO_FOCUS_LOSS song.flac at 12s", line)
        assertEquals(event, PlaybackLogFormat.parse(line, tokyo))
        assertEquals("2026-10-23 23:15:30.123 PAUSE AUDIO_FOCUS_LOSS song.flac at 12s", PlaybackLogFormat.encode(event, ZoneId.of("UTC")))
    }

    @Test fun emptyDetailRoundTrips() {
        val event = PlaybackEvent(at, PlaybackEventKind.SERVICE_STOP, "")
        val line = PlaybackLogFormat.encode(event, tokyo)
        assertEquals("2026-10-24 08:15:30.123 SERVICE_STOP", line)
        assertEquals(event, PlaybackLogFormat.parse(line, tokyo))
    }

    @Test fun newlinesInDetailStayOnOneLine() {
        val line = PlaybackLogFormat.encode(PlaybackEvent(at, PlaybackEventKind.ERROR, "first\nsecond\r\nthird"), tokyo)
        assertFalse(line.contains('\n') || line.contains('\r'))
        assertEquals("first second  third", PlaybackLogFormat.parse(line, tokyo)?.detail)
    }

    @Test fun unparseableLinesAreSkipped() {
        for (bad in listOf("", "garbage", "2026-10-24 08:1", "2026-10-24 08:15:30.123", "2026-10-24 08:15:30.123 NOT_A_KIND x", "2026-13-40 08:15:30.123 PLAY x")) {
            assertNull(bad, PlaybackLogFormat.parse(bad, tokyo))
        }
        val file = tmp.root.resolve("playback.log")
        file.writeText("2026-10-24 08:15:30.123 PLAY USER_REQUEST a.flac at 0s\ncut sho\n2026-10-24 08:16:00.000 PAUSE USER_REQUEST a.flac at 29s\n")
        PlaybackLog.open(file)
        assertEquals(listOf(PlaybackEventKind.PLAY, PlaybackEventKind.PAUSE), PlaybackLog.read().map { it.kind })
    }

    @Test fun boundKeepsTheLast500OnceOver1000() {
        val full = (1..1000).map { "line $it" }
        assertEquals(full, PlaybackLogFormat.bound(full))
        val over = (1..1001).map { "line $it" }
        assertEquals((502..1001).map { "line $it" }, PlaybackLogFormat.bound(over))
    }

    @Test fun logFileIsBoundedAndReadNewestLast() {
        val file = tmp.root.resolve("diagnostics/playback.log")
        PlaybackLog.open(file)
        repeat(1001) { PlaybackLog.record(PlaybackEventKind.TRACK, "#$it", at + it) }
        val events = PlaybackLog.read()
        assertEquals(500, events.size)
        assertEquals("#501", events.first().detail)
        assertEquals("#1000", events.last().detail)
        assertEquals(500, file.readLines().size)
        assertFalse(tmp.root.resolve("diagnostics/playback.log.tmp").exists())
    }

    @Test fun clearEmptiesTheLog() {
        PlaybackLog.open(tmp.root.resolve("playback.log"))
        PlaybackLog.record(PlaybackEventKind.PLAY, "USER_REQUEST a.flac at 0s")
        assertEquals(1, PlaybackLog.read().size)
        PlaybackLog.clear()
        assertEquals(emptyList<PlaybackEvent>(), PlaybackLog.read())
        assertEquals("", PlaybackLog.text())
    }

    @Test fun nothingIsRecordedBeforeInit() {
        PlaybackLog.open(null)
        PlaybackLog.record(PlaybackEventKind.PLAY, "x")
        assertEquals(emptyList<PlaybackEvent>(), PlaybackLog.read())
    }

    @Test fun onlyExitsAfterTheLastLoggedOneOldestFirst() {
        val newestFirst = listOf(
            ProcessExit(300, "LOW_MEMORY", null, 125),
            ProcessExit(200, "SIGNALED", "kill", 400, status = 9, pssKb = 81_920, rssKb = 163_840),
            ProcessExit(100, "USER_REQUESTED", null, 100),
        )
        assertEquals(listOf(200L, 300L), PlaybackLogFormat.newExits(newestFirst, lastLogged = 150).map { it.timestamp })
        assertEquals(emptyList<ProcessExit>(), PlaybackLogFormat.newExits(newestFirst, lastLogged = 300))
        assertEquals("reason=SIGNALED status=9 importance=400 pss=80MB rss=160MB desc=kill", PlaybackLogFormat.exitDetail(newestFirst[1]))
    }

    @Test fun credentialsInUrlsAreDropped() {
        assertEquals(
            "Unable to connect to http://nas:5005/music/a.flac",
            PlaybackLogFormat.withoutUserInfo("Unable to connect to http://bob:s3cret@nas:5005/music/a.flac"),
        )
        assertEquals("smb://nas/share/a.flac", PlaybackLogFormat.withoutUserInfo("smb://nas/share/a.flac"))
    }
}
