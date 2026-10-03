package com.wing.folderplayer.data.ai

import android.content.Context
import com.wing.folderplayer.data.nfo.NfoInfo
import com.wing.folderplayer.data.nfo.NfoParser
import com.wing.folderplayer.data.nfo.NfoRepository
import com.wing.folderplayer.data.nfo.NfoSaveResult
import com.wing.folderplayer.data.prefs.LyricPreferences
import com.wing.folderplayer.data.source.SourceRef
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class AlbumInfo(
    val album: String?,
    val artist: String?,
    val fromCache: Boolean = false,
    val nfo: NfoInfo? = null,
    val aiGenerated: Boolean = false,
    val model: String? = null,
    val error: String? = null,
)

/**
 * Album/artist description: a folder NFO is shown first; otherwise the AI description (public-main feature) is read
 * from the local cache or requested. "Regenerate" bypasses the cache; saving to Info.nfo only happens on request.
 */
class AlbumInfoRepository(context: Context, private val prefs: LyricPreferences) {
    private val client = AiClient()
    private val cacheDir = File(context.filesDir, "ai-cache/album")

    private fun cacheFile(folder: SourceRef?, folderName: String, artist: String, model: String): File {
        val key = listOf(folder?.toUriString().orEmpty(), folderName, artist, model).joinToString("\u0001")
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$digest.json")
    }

    suspend fun load(folder: SourceRef?, folderName: String, artist: String, forceRegenerate: Boolean): AlbumInfo {
        if (!forceRegenerate && folder != null) {
            val nfo = runCatching { NfoRepository.find(folder) }.getOrElse { if (it is CancellationException) throw it else null }
            if (nfo != null) {
                return AlbumInfo(formatNfo(nfo), nfo.fields["Artist Bio"], nfo = nfo)
            }
        }
        val cfg = prefs.aiConfig()
        val file = cacheFile(folder, folderName, artist, cfg.model)
        if (!forceRegenerate && file.exists()) {
            runCatching {
                val o = JSONObject(file.readText())
                return AlbumInfo(o.optString("album").ifEmpty { null }, o.optString("artist").ifEmpty { null }, fromCache = true, aiGenerated = true, model = o.optString("model"))
            }
        }
        if (cfg.apiKey.isBlank()) return AlbumInfo(com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.ai_key_missing), null, error = "no key")
        return try {
            val prompt = """
                你是一位资深的音乐唱片评论家。请简要介绍一下专辑《$folderName》，它的演唱者/艺术家是 $artist。
                请按照以下格式返回内容，确保中间包含分隔符 [ARTIST_START]：

                [专辑介绍部分：从专辑特点、音乐风格、制作背景等角度进行分段介绍，项目符号请用简单的减号，不要使用Markdown格式。此部分300字以内。]
                [ARTIST_START]
                [艺术家介绍部分：介绍这个专辑的艺术家或乐团的生平或成就，不要使用Markdown格式。此部分200字以内。]
            """.trimIndent()
            val text = client.chat(cfg, null, prompt, 0.7)
            val parts = text.split("[ARTIST_START]")
            val album = parts[0].trim()
            val artistInfo = parts.getOrNull(1)?.trim()
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(JSONObject().put("album", album).put("artist", artistInfo ?: "").put("model", cfg.model).toString())
            }
            AlbumInfo(album, artistInfo, aiGenerated = true, model = cfg.model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            val msg = when (e.httpCode) {
                429 -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.ai_http_429)
                401 -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.ai_http_401)
                404 -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.ai_http_404)
                0 -> e.message ?: "Error"
                else -> com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.ai_http_other, e.httpCode)
            }
            AlbumInfo(msg, null, error = msg)
        } catch (e: Exception) {
            AlbumInfo(com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.err_generic, e.message ?: ""), null, error = e.message)
        }
    }

    suspend fun saveToNfo(folder: SourceRef, title: String, info: AlbumInfo, overwrite: Boolean): NfoSaveResult {
        val existing = runCatching { NfoRepository.find(folder) }.getOrNull()?.takeIf { it.fileName.equals(NfoRepository.INFO_NFO, true) }
        val xml = NfoParser.toXml(title, existing?.artist, info.album, info.artist, keep = existing)
        return NfoRepository.save(folder, xml, overwrite)
    }

    fun canSave(folder: SourceRef?): Boolean = folder != null && runCatching { NfoRepository.canWrite(folder) }.getOrDefault(false)

    companion object {
        fun formatNfo(nfo: NfoInfo): String = buildString {
            nfo.title?.let { append(it).append("\n\n") }
            nfo.fields.forEach { (k, v) -> if (k != "Artist Bio") append(k).append(": ").append(v).append('\n') }
            nfo.description?.let { append('\n').append(it).append('\n') }
            if (nfo.tracks.isNotEmpty()) {
                append('\n')
                nfo.tracks.forEachIndexed { i, t ->
                    append(t.position ?: (i + 1)).append(". ").append(t.title)
                    t.duration?.let { append(" [").append(it).append(']') }
                    append('\n')
                }
            }
        }.trim()
    }
}
