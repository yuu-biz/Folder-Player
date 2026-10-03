package com.wing.folderplayer

import androidx.media3.common.PlaybackException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.artwork.ArtworkResult
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.readText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Network playback on the device: real SMB3, FTP (REST / no REST / truncated), FTPS and WebDAV through the
 * production DataSource, including seeks near the end, tricky names, covers and text files per source.
 */
@RunWith(AndroidJUnit4::class)
class NetworkPlaybackTest {
    private val ids = mutableMapOf<String, String>()

    @Before fun setUp() {
        Fx.require("smb_host", "ftp_host", "webdav_url")
        SourceRegistry.init(Fx.ctx)
        SourceRegistry.savedSources().forEach { SourceRegistry.remove(it.id) }
        fun add(key: String, c: SourceConfig, pw: String) { SourceRegistry.upsert(c, pw); ids[key] = c.id }
        add("smb", SourceConfig(name = "smb", type = SourceType.SMB, host = Fx.arg("smb_host")!!, share = "music", username = "alice"), "alicepass")
        add("ftp", SourceConfig(name = "ftp", type = SourceType.FTP, host = Fx.arg("ftp_host")!!, port = 2121, username = "alice"), "alicepass")
        add("ftp_norest", SourceConfig(name = "ftp-norest", type = SourceType.FTP, host = Fx.arg("ftp_host")!!, port = 2122, username = "alice"), "alicepass")
        add("ftp_trunc", SourceConfig(name = "ftp-trunc", type = SourceType.FTP, host = Fx.arg("ftp_host")!!, port = 2123, username = "alice"), "alicepass")
        Fx.arg("ftps_pin")?.let { pin ->
            add("ftps", SourceConfig(name = "ftps", type = SourceType.FTP, host = Fx.arg("ftp_host")!!, port = 2124, username = "alice", useTls = true, tlsPinnedSha256 = pin), "alicepass")
        }
        add("dav", SourceConfig(name = "dav", type = SourceType.WEBDAV, url = Fx.arg("webdav_url")!!, username = "alice"), "pa:ss/1")
        ids["local"] = SourceRegistry.LOCAL_INTERNAL_ID
    }

    private fun path(key: String, rel: String) = if (key == "local") "/Music$rel" else rel

    @Test fun trickyNamesPlayAndSeekOnEverySource() {
        for (key in listOf("local", "smb", "ftp", "dav") + listOfNotNull(ids["ftps"]?.let { "ftps" })) {
            val ref = SourceRef(ids[key]!!, path(key, "/fixture/Album-A/01 曲 #1+%.flac"))
            val fs = SourceRegistry.fileSystem(ref)
            assertTrue("$key listing", fs.list(ref.parent!!.path).any { it.name == "01 曲 #1+%.flac" })
            assertTrue("$key lrc", fs.readText(path(key, "/fixture/Album-A/01 曲 #1+%.lrc")).contains("#1+%"))
            val start = Fx.play(ref, playMs = 1500)
            Fx.log("$key play: $start")
            assertTrue("$key play $start", start.reachedReady && start.positionMs > 500 && start.durationMs in 59_000..61_000)
            val nearEnd = Fx.play(ref, seekToMs = 57_000, playMs = 1500)
            Fx.log("$key seek near end: $nearEnd")
            assertTrue("$key seek $nearEnd", nearEnd.reachedReady && nearEnd.positionMs >= 57_000)
            val mid = Fx.play(ref, seekToMs = 30_000, playMs = 1000)
            assertTrue("$key mid $mid", mid.positionMs >= 30_000)
            val toEnd = Fx.play(SourceRef(ids[key]!!, path(key, "/fixture/Many/Folder 001/track 001.flac")), untilEnd = true)
            assertTrue("$key to end $toEnd", toEnd.ended)
            val cover = runBlocking { ThumbnailRepository.get(Fx.ctx).playbackCover(ref.parent!!, null) }
            assertTrue("$key cover $cover", cover is ArtworkResult.Found && (cover as ArtworkResult.Found).image.name == "cover.jpg")
        }
    }

    @Test fun ftpWithoutRestAndTruncatedTransfersAreReportedNotHidden() {
        val norest = SourceRef(ids["ftp_norest"]!!, "/fixture/Album-A/01 曲 #1+%.flac")
        val ok = Fx.play(norest, playMs = 1000)
        assertTrue("plays from start without REST: $ok", ok.reachedReady)
        // Seeking beyond the buffered range needs a reopen at a byte offset (REST); the 10-minute track guarantees it.
        val seek = Fx.play(SourceRef(ids["ftp_norest"]!!, "/fixture/Long/long.flac"), seekToMs = 500_000, playMs = 8000)
        Fx.log("no-REST seek: $seek")
        assertTrue("seek without REST must be an error: $seek", seek.error != null &&
            generateSequence<Throwable>(seek.error) { it.cause }.any { it.message?.contains("REST") == true })

        // An early end of the data connection surfaces as a network error from the DataSource (the player may then
        // retry and resume with REST), never as a normal end of file.
        val ds = com.wing.folderplayer.playback.SourceDataSource()
        val uri = android.net.Uri.parse(SourceRef(ids["ftp_trunc"]!!, "/fixture/Album-A/01 曲 #1+%.flac").toUriString())
        val length = ds.open(androidx.media3.datasource.DataSpec(uri))
        var total = 0L
        val err = runCatching {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ds.read(buf, 0, buf.size)
                if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                total += n
            }
        }.exceptionOrNull()
        ds.close()
        Fx.log("truncated: length=$length read=$total error=$err")
        assertTrue("early end reported as error, got EOF after $total of $length", err is androidx.media3.datasource.DataSourceException)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, (err as androidx.media3.datasource.DataSourceException).reason)
    }
}
