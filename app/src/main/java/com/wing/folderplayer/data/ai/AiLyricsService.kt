package com.wing.folderplayer.data.ai

import com.wing.folderplayer.utils.LrcParser
import com.wing.folderplayer.utils.LyricLine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Lyrics text with provenance. Unsynced lyrics carry no timestamps (timeMs = -1); none are invented. */
data class AiLyrics(val lines: List<LyricLine>, val synced: Boolean, val model: String)

/**
 * AI lyrics lookup and translation on an OpenAI-compatible endpoint, with a disk cache whose key covers the
 * song, the endpoint, the model and the requested languages. Validation rejects timestamped answers that are out of
 * order or beyond the track length and downgrades them to plain text rather than fabricating sync.
 */
class AiLyricsService(private val client: AiClient, private val cacheDir: File) {

    data class SongKey(val songRef: String, val title: String, val artist: String?, val album: String?, val durationMs: Long)

    private fun cacheFile(kind: String, parts: List<String>): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0001").toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(cacheDir, kind), "$digest.json")
    }

    private fun readCache(f: File): JSONObject? = try { if (f.exists()) JSONObject(f.readText()) else null } catch (e: Exception) { null }

    private fun writeCache(f: File, o: JSONObject) {
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }

    fun cachedLyrics(config: AiConfig, song: SongKey, language: String): AiLyrics? {
        val f = cacheFile("lyrics", listOf(song.songRef, song.title, song.artist.orEmpty(), config.endpoint, config.model, language))
        val o = readCache(f) ?: return null
        return decode(o, config.model)
    }

    suspend fun fetchLyrics(config: AiConfig, song: SongKey, language: String, useCache: Boolean = true): AiLyrics? {
        val f = cacheFile("lyrics", listOf(song.songRef, song.title, song.artist.orEmpty(), config.endpoint, config.model, language))
        if (useCache) readCache(f)?.let { return decode(it, config.model) }
        val prompt = buildString {
            append("Provide the complete lyrics of the song below in LRC format with [mm:ss.xx] timestamps if, and only if, you know the real timing. ")
            append("If you do not know the timing, return the plain lyrics without any timestamps. ")
            append("If you do not know the song, reply exactly NOT_FOUND. Output only the lyrics, no commentary.\n")
            append("Title: ").append(song.title).append('\n')
            song.artist?.takeIf { it.isNotBlank() }?.let { append("Artist: ").append(it).append('\n') }
            song.album?.takeIf { it.isNotBlank() }?.let { append("Album: ").append(it).append('\n') }
            if (song.durationMs > 0) append("Duration: ").append(song.durationMs / 1000).append(" seconds\n")
            if (language.isNotBlank()) append("Preferred lyric language: ").append(language).append('\n')
        }
        val answer = client.chat(config, "You return song lyrics. Never invent lyrics or timestamps.", prompt, 0.0)
        val result = validate(answer, song.durationMs, config.model) ?: return null
        writeCache(f, encode(result))
        return result
    }

    /** Translates line by line; the answer must be a JSON array with exactly one string per input line. */
    suspend fun translate(config: AiConfig, song: SongKey, lines: List<LyricLine>, target: String, useCache: Boolean = true): List<LyricLine>? {
        if (lines.isEmpty() || target.isBlank()) return null
        val texts = lines.map { it.text }
        val f = cacheFile("translation", listOf(song.songRef, config.endpoint, config.model, target, texts.joinToString("\n")))
        if (useCache) readCache(f)?.optJSONArray("lines")?.let { arr ->
            if (arr.length() == lines.size) return lines.mapIndexed { i, l -> LyricLine(l.timeMs, arr.optString(i)) }
        }
        val input = JSONArray(texts)
        val answer = client.chat(
            config,
            "You translate song lyrics. Reply with a JSON array of strings only.",
            "Translate each element of this JSON array of lyric lines into $target. Keep the array length (${texts.size}) and order; " +
                "keep empty strings empty.\n$input",
            0.2,
        )
        val arr = runCatching { JSONArray(answer.substring(answer.indexOf('['), answer.lastIndexOf(']') + 1)) }.getOrNull() ?: return null
        if (arr.length() != lines.size) return null
        writeCache(f, JSONObject().put("lines", arr))
        return lines.mapIndexed { i, l -> LyricLine(l.timeMs, arr.optString(i)) }
    }

    companion object {
        private val TIMESTAMP = Regex("\\[\\d{1,3}:\\d{2}[.:]\\d{2,3}]")
        private val ANY_TAG = Regex("\\[[^\\]]*]")

        /** Returns null for NOT_FOUND / empty answers. */
        fun validate(answer: String, durationMs: Long, model: String): AiLyrics? {
            val body = answer.trim().removePrefix("```lrc").removePrefix("```").removeSuffix("```").trim()
            if (body.isEmpty() || body.equals("NOT_FOUND", true)) return null
            val timed = LrcParser.parse(body).filter { it.text.isNotBlank() }
            val rawTimedOrder = body.lines().mapNotNull { l -> TIMESTAMP.find(l)?.let { LrcParser.parse(l).firstOrNull()?.timeMs } }
            val monotonic = rawTimedOrder.zipWithNext().all { (a, b) -> b >= a }
            val withinLength = durationMs <= 0 || (timed.lastOrNull()?.timeMs ?: 0) <= durationMs + 5_000
            val distinct = timed.map { it.timeMs }.distinct().size >= minOf(3, timed.size)
            if (timed.size >= 3 && monotonic && withinLength && distinct) return AiLyrics(timed, true, model)
            // Not trustworthy as synced lyrics: keep only the words.
            val plain = body.lines().map { it.replace(ANY_TAG, "").trim() }.filter { it.isNotEmpty() }
            if (plain.isEmpty()) return null
            return AiLyrics(plain.map { LyricLine(-1, it) }, false, model)
        }

        fun encode(l: AiLyrics): JSONObject = JSONObject()
            .put("synced", l.synced)
            .put("model", l.model)
            .put("lines", JSONArray().also { a -> l.lines.forEach { a.put(JSONObject().put("t", it.timeMs).put("x", it.text)) } })

        fun decode(o: JSONObject, model: String): AiLyrics? {
            val arr = o.optJSONArray("lines") ?: return null
            val lines = (0 until arr.length()).map { arr.getJSONObject(it).let { j -> LyricLine(j.getLong("t"), j.getString("x")) } }
            return AiLyrics(lines, o.optBoolean("synced"), o.optString("model", model))
        }
    }
}
