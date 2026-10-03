package com.wing.folderplayer.data.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

/**
 * AI client: real OkHttp requests against a local OpenAI-compatible mock endpoint — request shape,
 * auth header, validation (no fabricated timestamps), translation, cache keys, error codes and cancellation.
 */
class AiLyricsServiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var config: AiConfig
    private lateinit var service: AiLyricsService
    private val song = AiLyricsService.SongKey("fpsrc://s/a.flac", "Song", "Artist", null, 200_000)

    private fun completion(content: String) = MockResponse().setBody(
        JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString()
    )

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        config = AiConfig(server.url("/v1").toString(), "test-key", "mock-model")
        service = AiLyricsService(AiClient(), tmp.root)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun syncedLyricsAreAcceptedAndRequestIsWellFormed() = runBlocking {
        server.enqueue(completion("[00:01.00]one\n[00:05.50]two\n[00:09.00]three"))
        val r = service.fetchLyrics(config, song, "")!!
        assertTrue(r.synced)
        assertEquals(listOf(1000L, 5500L, 9000L), r.lines.map { it.timeMs })
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer test-key", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("mock-model", body.getString("model"))
        assertTrue(body.getJSONArray("messages").toString().contains("Song"))
    }

    @Test fun untrustworthyTimestampsBecomePlainText() = runBlocking {
        server.enqueue(completion("[00:09.00]three\n[00:01.00]one\n[00:05.00]two")) // out of order
        val r = service.fetchLyrics(config, song, "")!!
        assertFalse(r.synced)
        assertEquals(listOf("three", "one", "two"), r.lines.map { it.text })
        assertTrue(r.lines.all { it.timeMs == -1L })
        // Beyond track length (200 s) is rejected as well.
        val tooLong = AiLyricsService.validate("[00:01.00]a\n[00:02.00]b\n[09:00.00]c", 200_000, "m")!!
        assertFalse(tooLong.synced)
        // Plain answer stays plain; nothing is invented.
        val plain = AiLyricsService.validate("line a\nline b", 200_000, "m")!!
        assertFalse(plain.synced)
        assertNull(AiLyricsService.validate("NOT_FOUND", 1, "m"))
    }

    @Test fun cacheAvoidsSecondRequestAndKeyIncludesModelAndLanguage() = runBlocking {
        server.enqueue(completion("[00:01.00]a\n[00:02.00]b\n[00:03.00]c"))
        service.fetchLyrics(config, song, "")
        service.fetchLyrics(config, song, "")
        assertEquals(1, server.requestCount)
        assertTrue(service.cachedLyrics(config, song, "") != null)
        assertNull(service.cachedLyrics(config.copy(model = "other"), song, ""))
        assertNull(service.cachedLyrics(config, song, "ja"))
        assertNull(service.cachedLyrics(config, song.copy(songRef = "fpsrc://s/b.flac"), ""))
        server.enqueue(completion("[00:01.00]x\n[00:02.00]y\n[00:03.00]z"))
        val regenerated = service.fetchLyrics(config, song, "", useCache = false)!!
        assertEquals("x", regenerated.lines.first().text)
        assertEquals(2, server.requestCount)
    }

    @Test fun translationKeepsTimestampsAndRequiresSameLength() = runBlocking {
        val lines = listOf(com.wing.folderplayer.utils.LyricLine(1000, "hello"), com.wing.folderplayer.utils.LyricLine(2000, "world"))
        server.enqueue(completion("[\"你好\",\"世界\"]"))
        val t = service.translate(config, song, lines, "zh-CN")!!
        assertEquals(listOf(1000L, 2000L), t.map { it.timeMs })
        assertEquals(listOf("你好", "世界"), t.map { it.text })
        server.enqueue(completion("[\"只有一行\"]"))
        assertNull(service.translate(config, song, lines, "ja"))
    }

    @Test fun httpErrorsAreReported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            service.fetchLyrics(config, song, "")
            fail("no error")
        } catch (e: AiException) {
            assertEquals(401, e.httpCode)
        }
        server.enqueue(MockResponse().setBody("{not json"))
        try {
            service.fetchLyrics(config, song.copy(title = "Other"), "")
            fail("no error")
        } catch (e: AiException) {
        }
    }

    @Test fun incompleteConfigSendsNothing() = runBlocking {
        try {
            service.fetchLyrics(config.copy(apiKey = ""), song, "")
            fail("request without key")
        } catch (e: AiException) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun cancellationAbortsTheHttpCall() = runBlocking {
        server.enqueue(completion("[00:01.00]late").setBodyDelay(5, TimeUnit.SECONDS))
        val job = async { service.fetchLyrics(config, song, "") }
        delay(300)
        job.cancel()
        val started = System.currentTimeMillis()
        try { job.await() } catch (e: kotlinx.coroutines.CancellationException) {}
        assertTrue(System.currentTimeMillis() - started < 2000)
        assertNull(service.cachedLyrics(config, song, ""))
    }
}
