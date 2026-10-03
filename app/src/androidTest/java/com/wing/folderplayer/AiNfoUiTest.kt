package com.wing.folderplayer

import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wing.folderplayer.data.nfo.NfoParser
import com.wing.folderplayer.data.prefs.LyricPreferences
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceType
import com.wing.folderplayer.data.source.readBytes
import com.wing.folderplayer.ui.player.PlayerViewModel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * NFO display, AI album info (cache, regenerate, explicit Info.nfo save, replace confirmation, read-only) on every
 * storage type, and AI lyrics (manual request, auto off = no traffic, translation, errors, no stale answer)
 * against an OpenAI-compatible mock server running inside the test. Network writes go only to the fixture `rw/` roots.
 */
@RunWith(AndroidJUnit4::class)
class AiNfoUiTest : UiTestBase() {
    private val server = MockWebServer()
    private val albumCalls = AtomicInteger()
    private val lyricCalls = AtomicInteger()
    private val translateCalls = AtomicInteger()
    @Volatile private var lyricDelayMs = 0L
    @Volatile private var failNext = false
    private lateinit var prefs: LyricPreferences

    private val player: PlayerViewModel get() = onUi { ViewModelProvider(compose.activity)[PlayerViewModel::class.java] }
    private fun <T> onUi(block: () -> T): T { var r: Any? = null; compose.runOnUiThread { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }
    private val ps get() = player.uiState.value

    private fun completion(content: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(
        JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString())

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                if (failNext) { failNext = false; return MockResponse().setResponseCode(500) }
                return when {
                    body.contains("专辑") -> completion("MOCK ALBUM ${albumCalls.incrementAndGet()}\n[ARTIST_START]\nMOCK ARTIST")
                    body.contains("complete lyrics") -> {
                        val n = lyricCalls.incrementAndGet()
                        if (lyricDelayMs > 0) Thread.sleep(lyricDelayMs)
                        completion("[00:00.50]ai line one $n\n[00:01.00]ai line two $n\n[00:01.50]ai line three $n")
                    }
                    else -> { translateCalls.incrementAndGet(); completion("[\"译一\",\"译二\",\"译三\"]") }
                }
            }
        }
        server.start()
        prefs = LyricPreferences(Fx.ctx)
        prefs.setAiBaseUrl(server.url("/v1").toString()); prefs.setAiApiKey("test-key"); prefs.setAiModel("mock-model")
        prefs.aiLyricsAuto = false; prefs.translationTarget = ""; prefs.lyricsPriority = "LOCAL_FIRST"
        File(Fx.ctx.filesDir, "ai-cache").deleteRecursively()
        SourceRegistry.savedSources().filter { it.type != SourceType.SAF }.forEach { SourceRegistry.remove(it.id) }
    }

    @After fun tearDown() {
        onUi { if (ps.isPlaying) player.playPause() }
        server.shutdown()
        prefs.setAiApiKey(""); prefs.aiLyricsAuto = false; prefs.translationTarget = ""
    }

    private fun play(folder: SourceRef) {
        onUi { player.playFolder(folder, null) }
        until(20_000, "playing ${folder.path}") { ps.isPlaying && ps.currentMediaId?.startsWith("fpsrc://${folder.sourceId}/") == true }
        toPlayer()
    }

    private fun openInfo() {
        until(10_000, "folder name") { ps.currentFolderName.isNotEmpty() && textExists(ps.currentFolderName) }
        clickText(ps.currentFolderName)
        until(20_000, "album info loaded") { !ps.isFetchingAlbumInfo && exists("album_info_text") }
    }

    private fun closeInfo() { clickText(str(R.string.common_close)); compose.waitForIdle() }

    /** Copies a fixture track into a dedicated folder below the source's `rw/` root. */
    private fun prepare(src: SourceConfig, dir: String) {
        val fs = SourceRegistry.fileSystem(src.id)
        runCatching { fs.list(dir).filter { !it.isDirectory }.forEach { fs.delete(it.path) } }
        val audio = SourceRegistry.fileSystem(SourceRegistry.LOCAL_INTERNAL_ID).readBytes("/Music/fixture/Album-B/track.flac", 1 shl 24)
        fs.write("$dir/track.flac", audio, overwrite = true)
    }

    /** Full flow for one writable source plus its read-only twin. */
    private fun nfoFlow(rw: SourceConfig, ro: SourceConfig?, dir: String) {
        prepare(rw, dir)
        play(SourceRef(rw.id, dir))
        openInfo()
        assertEquals(1, albumCalls.get())
        assertTrue(text("album_info_text").contains("MOCK ALBUM 1"))
        assertEquals(str(R.string.player_info_from_ai, "mock-model"), text("album_info_source"))
        closeInfo()
        // Cached: no second request.
        openInfo()
        assertEquals(1, albumCalls.get())
        assertTrue(text("album_info_source").contains(str(R.string.player_info_cached)))
        // Regenerate → new request; then explicit save.
        click("btn_regenerate")
        until(20_000, "regenerated") { !ps.isFetchingAlbumInfo && text("album_info_text").contains("MOCK ALBUM 2") }
        click("btn_save_nfo")
        until(15_000, "saved") { exists("nfo_result") && text("nfo_result") == str(R.string.player_nfo_saved) }
        val fs = SourceRegistry.fileSystem(rw.id)
        val saved = NfoParser.parse(fs.readBytes("$dir/Info.nfo", 1 shl 20), "Info.nfo")
        assertTrue("saved NFO holds the AI text: ${saved.description}", saved.description?.contains("MOCK ALBUM 2") == true)
        closeInfo()
        // Next open shows the NFO; regenerate + save asks before replacing.
        openInfo()
        assertEquals(str(R.string.player_info_from_nfo), text("album_info_source"))
        click("btn_regenerate")
        until(20_000, "regenerated again") { !ps.isFetchingAlbumInfo && text("album_info_text").contains("MOCK ALBUM 3") }
        click("btn_save_nfo")
        until(10_000, "replace confirmation") { exists("btn_nfo_replace") }
        click("btn_nfo_replace")
        until(15_000, "replaced") { exists("nfo_result") && text("nfo_result") == str(R.string.player_nfo_saved) }
        assertTrue(NfoParser.parse(fs.readBytes("$dir/Info.nfo", 1 shl 20), "Info.nfo").description?.contains("MOCK ALBUM 3") == true)
        closeInfo()

        if (ro != null) {
            play(SourceRef(ro.id, dir))
            File(Fx.ctx.filesDir, "ai-cache").deleteRecursively()
            openInfo() // shows the NFO saved above
            click("btn_regenerate")
            until(20_000, "AI info for read-only source") { !ps.isFetchingAlbumInfo && text("album_info_text").contains("MOCK ALBUM") }
            if (exists("btn_save_nfo")) {
                click("btn_save_nfo")
                // Info.nfo exists (saved above), so the app first asks before replacing; the server then refuses.
                until(10_000, "replace confirmation or result") { exists("btn_nfo_replace") || exists("nfo_result") }
                if (exists("btn_nfo_replace")) click("btn_nfo_replace")
                try {
                    until(15_000, "read-only reported") { exists("nfo_result") && text("nfo_result") == str(R.string.player_nfo_read_only) }
                } catch (e: AssertionError) {
                    throw AssertionError("read-only save shows '${runCatching { text("nfo_result") }.getOrNull()}'", e)
                }
            } else {
                assertTrue(textExists(str(R.string.player_nfo_cannot_save)))
            }
            Fx.log("read-only ${ro.type}: save offered=${exists("btn_save_nfo")} result=${runCatching { text("nfo_result") }.getOrNull()}")
            closeInfo()
        }
        runCatching { SourceRegistry.fileSystem(rw.id).let { f -> f.list(dir).filter { !it.isDirectory }.forEach { f.delete(it.path) } } }
    }

    @Test fun a22_existingNfoIsShownFirst() {
        Fx.require("webdav_url")
        val dav = SourceConfig(name = "dav", type = SourceType.WEBDAV, url = Fx.arg("webdav_url")!!, username = "alice").also { SourceRegistry.upsert(it, "pa:ss/1") }
        play(SourceRef(dav.id, "/fixture/Album-A"))
        openInfo()
        assertEquals(str(R.string.player_info_from_nfo), text("album_info_source"))
        val t = text("album_info_text")
        Fx.log("NFO dialog text: $t")
        assertTrue(t.contains("Album A (NFO)") && t.contains("NFO review text for Album A.") && t.contains("Tagged Title Two"))
        assertEquals("no AI call when an NFO exists", 0, albumCalls.get())
    }

    @Test fun a22_webDav() {
        Fx.require("webdav_url")
        val u = Fx.arg("webdav_url")!!
        nfoFlow(SourceConfig(name = "dav-rw", type = SourceType.WEBDAV, url = u, username = "alice").also { SourceRegistry.upsert(it, "pa:ss/1") },
            SourceConfig(name = "dav-ro", type = SourceType.WEBDAV, url = u, username = "bob").also { SourceRegistry.upsert(it, "bobpass") }, "/rw")
    }

    @Test fun a22_smb() {
        Fx.require("smb_host")
        val h = Fx.arg("smb_host")!!
        nfoFlow(SourceConfig(name = "smb-rw", type = SourceType.SMB, host = h, share = "music", username = "alice").also { SourceRegistry.upsert(it, "alicepass") },
            SourceConfig(name = "smb-ro", type = SourceType.SMB, host = h, share = "music", username = "bob").also { SourceRegistry.upsert(it, "bobpass") }, "/rw")
    }

    @Test fun a22_ftp() {
        Fx.require("ftp_host")
        val h = Fx.arg("ftp_host")!!
        nfoFlow(SourceConfig(name = "ftp-rw", type = SourceType.FTP, host = h, port = 2121, username = "alice").also { SourceRegistry.upsert(it, "alicepass") },
            SourceConfig(name = "ftp-ro", type = SourceType.FTP, host = h, port = 2121, username = "bob").also { SourceRegistry.upsert(it, "bobpass") }, "/rw")
    }

    @Test fun a22_saf() {
        val saf = SourceRegistry.savedSources().firstOrNull { it.type == SourceType.SAF && Uri.decode(it.url).endsWith("Music/fixture/Unindexed") }
            ?: error("SAF grant missing: run SafTest first")
        // Dedicated sub-folder inside the granted (fixture) tree.
        val fs = SourceRegistry.fileSystem(saf.id)
        if (fs.stat("/fp-nfo-test") == null) {
            val tree = Uri.parse(saf.url)
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(Fx.ctx.contentResolver, root, DocumentsContract.Document.MIME_TYPE_DIR, "fp-nfo-test")
        }
        nfoFlow(saf, null, "/fp-nfo-test")
    }

    @Test fun a22_localSharedStorageIsReportedNotWritable() {
        play(SourceRef(SourceRegistry.LOCAL_INTERNAL_ID, "/Music/fixture/Album-B"))
        openInfo()
        until(10_000, "AI text") { text("album_info_text").contains("MOCK ALBUM") }
        if (exists("btn_save_nfo")) {
            click("btn_save_nfo")
            until(15_000, "not writable reported") { exists("nfo_result") && text("nfo_result") != str(R.string.player_nfo_saved) }
        } else {
            assertTrue(textExists(str(R.string.player_nfo_cannot_save)))
        }
        assertTrue("nothing written to shared storage", !Fx.localFile("/Music/fixture/Album-B/Info.nfo").exists())
    }

    // ---------------- AI lyrics ----------------

    @Test fun a23_aiLyricsManualOnlyTranslationErrorsNoStale() {
        val local = SourceRegistry.LOCAL_INTERNAL_ID
        // Long tracks so the queue does not run out while the steps below take their time.
        val tracks = listOf("/Music/fixture/Long/long.flac", "/Music/fixture/Noise/noise.flac", "/Music/fixture/Cue/image.flac")
            .map { MusicFile(it.substringAfterLast('/'), it, false, 0, 0, local) }
        // Auto off: playing a track without lyrics sends nothing.
        onUi { player.playCustomList(tracks, 0) }
        until(15_000, "playing") { ps.isPlaying }
        toPlayer()
        Thread.sleep(3_000)
        assertEquals(0, lyricCalls.get() + translateCalls.get())

        // Manual request from the player.
        until(10_000, "AI button") { exists("btn_ai_lyrics") }
        click("btn_ai_lyrics")
        try {
            until(20_000, "AI lyrics shown") { ps.lyrics.any { it.text == "ai line one 1" } }
        } catch (e: AssertionError) {
            throw AssertionError("calls=${lyricCalls.get()} lyrics=${ps.lyrics.map { it.text }} source=${ps.lyricsSource} error=${ps.lyricsError} running=${ps.lyricsRequestRunning}", e)
        }
        assertTrue(ps.lyricsSource.startsWith("AI"))
        assertTrue(ps.lyricsSynced)
        assertTrue(text("lyrics_source").isNotBlank())
        // Second request is served from the cache; regenerate makes a new call.
        Fx.log("before regenerate: calls=${lyricCalls.get()} source=${ps.lyricsSource} running=${ps.lyricsRequestRunning} button=${runCatching { text("btn_ai_lyrics") }.getOrNull()}")
        click("btn_ai_lyrics")
        try {
            until(20_000, "regenerated") { ps.lyrics.any { it.text == "ai line one 2" } }
        } catch (e: AssertionError) {
            throw AssertionError("calls=${lyricCalls.get()} lyrics=${ps.lyrics.map { it.text }} source=${ps.lyricsSource} error=${ps.lyricsError}", e)
        }

        // Translation with the same timestamps.
        prefs.translationTarget = "zh-CN"
        onUi { player.requestAiLyrics(true) }
        try {
            until(20_000, "translation") { ps.translatedLyrics.map { it.text } == listOf("译一", "译二", "译三") }
        } catch (e: AssertionError) {
            throw AssertionError("translateCalls=${translateCalls.get()} lyricCalls=${lyricCalls.get()} target=${prefs.translationTarget} " +
                "translated=${ps.translatedLyrics.map { it.text }} lyrics=${ps.lyrics.map { it.text }} error=${ps.lyricsError} running=${ps.lyricsRequestRunning}", e)
        }
        val withTranslation = ps
        assertEquals(withTranslation.lyrics.map { it.timeMs }, withTranslation.translatedLyrics.map { it.timeMs })
        Thread.sleep(3_000)
        Fx.log("3 s later: lyrics=${ps.lyrics.map { it.text }} translation=${ps.translatedLyrics.map { it.text }} source=${ps.lyricsSource}")
        assertEquals("translation stays visible", listOf("译一", "译二", "译三"), ps.translatedLyrics.map { it.text })
        prefs.translationTarget = ""

        // Server error is shown, not hidden.
        failNext = true
        onUi { player.requestAiLyrics(true) }
        until(20_000, "error shown") { exists("lyrics_error") }

        // A slow answer for the previous track never lands on the next one.
        lyricDelayMs = 4_000
        onUi { player.requestAiLyrics(true) }
        Thread.sleep(300)
        onUi { player.next() }
        until(10_000, "next track") { ps.currentMediaId?.contains("/Noise/") == true }
        Thread.sleep(6_000)
        Fx.log("after skip: lyrics=${ps.lyrics.map { it.text }} source=${ps.lyricsSource}")
        assertTrue("no stale AI lyrics on the new track", ps.lyrics.none { it.text.startsWith("ai line") })
        lyricDelayMs = 0

        // Auto + AI_FIRST: requested automatically on track change.
        prefs.aiLyricsAuto = true; prefs.lyricsPriority = "AI_FIRST"
        onUi { player.invalidateLyrics(); player.next() }
        until(20_000, "automatic AI lyrics") { ps.currentMediaId?.contains("/Cue/") == true && ps.lyricsSource.startsWith("AI") }
    }
}
