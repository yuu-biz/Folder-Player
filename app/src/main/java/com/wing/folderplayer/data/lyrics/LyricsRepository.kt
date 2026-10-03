package com.wing.folderplayer.data.lyrics

import android.content.Context
import androidx.media3.common.MediaItem
import com.wing.folderplayer.data.ai.AiClient
import com.wing.folderplayer.data.ai.AiLyricsService
import com.wing.folderplayer.data.metadata.EmbeddedTagReader
import com.wing.folderplayer.data.network.LyricApi
import com.wing.folderplayer.data.prefs.LyricPreferences
import com.wing.folderplayer.data.repo.PlayerRepository
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceUris
import com.wing.folderplayer.data.source.readText
import com.wing.folderplayer.utils.LrcParser
import com.wing.folderplayer.utils.LyricLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

data class LyricsResult(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: String,
    val translation: List<LyricLine> = emptyList(),
    val error: String? = null,
)

/**
 * Lyrics for the current track. Local first by default: .lrc next to the file → embedded lyrics → configured lyric
 * API. AI lyrics are only requested when "auto" is on (or explicitly via [fetchAi]) and endpoint/key/model are set;
 * with AI-first priority the AI answer is tried before the local chain.
 */
class LyricsRepository(context: Context, private val prefs: LyricPreferences) {
    private val ai = AiLyricsService(AiClient(), File(context.filesDir, "ai-cache"))

    fun clearMemory() = Unit

    private fun songRef(item: MediaItem): SourceRef? = SourceUris.parse(item.mediaId)

    suspend fun load(item: MediaItem, title: String, artist: String?, durationMs: Long): LyricsResult {
        val aiAllowed = prefs.aiLyricsAuto && prefs.aiConfig().isComplete
        val base = if (aiAllowed && prefs.lyricsPriority == "AI_FIRST") {
            aiLyrics(item, title, artist, durationMs) ?: local(item, title, artist)
        } else {
            local(item, title, artist) ?: if (aiAllowed) aiLyrics(item, title, artist, durationMs) else null
        } ?: return LyricsResult(emptyList(), true, "")
        return withTranslation(item, title, artist, durationMs, base, prefs.aiLyricsAuto)
    }

    /** Explicit user request ("Get AI lyrics"): runs even if auto lookup is off, but never without configuration. */
    suspend fun fetchAi(item: MediaItem, title: String, artist: String?, durationMs: Long, regenerate: Boolean): LyricsResult {
        if (!prefs.aiConfig().isComplete) return LyricsResult(emptyList(), true, "", error = com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.ai_not_configured))
        return try {
            val r = aiLyrics(item, title, artist, durationMs, useCache = !regenerate)
                ?: return LyricsResult(emptyList(), true, "", error = com.wing.folderplayer.utils.Strings.get(com.wing.folderplayer.R.string.ai_lyrics_not_found))
            withTranslation(item, title, artist, durationMs, r, true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LyricsResult(emptyList(), true, "", error = e.message ?: "AI request failed")
        }
    }

    private suspend fun withTranslation(item: MediaItem, title: String, artist: String?, durationMs: Long, r: LyricsResult, allowed: Boolean): LyricsResult {
        val target = prefs.translationTarget
        if (!allowed || target.isBlank() || r.lines.isEmpty() || !prefs.aiConfig().isComplete) return r
        val song = AiLyricsService.SongKey(item.mediaId, title, artist, null, durationMs)
        val translated = try {
            ai.translate(prefs.aiConfig(), song, r.lines, target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return if (translated != null) r.copy(translation = translated) else r
    }

    private suspend fun aiLyrics(item: MediaItem, title: String, artist: String?, durationMs: Long, useCache: Boolean = true): LyricsResult? {
        val song = AiLyricsService.SongKey(item.mediaId, title, artist, null, durationMs)
        val r = try {
            ai.fetchLyrics(prefs.aiConfig(), song, prefs.aiLyricsLanguage, useCache)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("LyricsRepository", "AI lyrics failed: ${e.message}")
            null
        } ?: return null
        return LyricsResult(r.lines, r.synced, "AI (${r.model})")
    }

    private suspend fun local(item: MediaItem, title: String, artist: String?): LyricsResult? {
        val ref = songRef(item)
        val extras = item.mediaMetadata.extras
        val isCue = extras?.getBoolean(PlayerRepository.EXTRA_IS_CUE_TRACK) == true
        if (ref != null && SourceRegistry.get(ref.sourceId) != null && !isCue) {
            // 1. .lrc with the same base name
            val lrc = runCatching {
                runInterruptible(Dispatchers.IO) {
                    val fs = SourceRegistry.fileSystem(ref)
                    val lrcPath = extras?.getString(PlayerRepository.EXTRA_LRC_PATH)
                        ?: (ref.parent?.path ?: SourcePath.ROOT).let { parent ->
                            fs.list(parent).firstOrNull { !it.isDirectory && MediaTypes.isLyric(it.name) && SourcePath.baseName(it.name) == SourcePath.baseName(ref.name) }?.path
                        }
                    lrcPath?.let { fs.readText(it, 1L * 1024 * 1024) }
                }
            }.getOrElse { if (it is CancellationException) throw it else null }
            parse(lrc)?.let { return it.copy(source = "LRC") }

            // 2. Embedded lyrics (ID3 USLT / Vorbis LYRICS / MP4 ©lyr)
            val embedded = runCatching {
                runInterruptible(Dispatchers.IO) { EmbeddedTagReader.read(SourceRegistry.fileSystem(ref), ref.path)?.lyrics }
            }.getOrElse { if (it is CancellationException) throw it else null }
            parse(embedded)?.let { return it.copy(source = "Embedded") }
        }
        // 3. Configured lyric API
        val apiUrl = prefs.getLyricApiUrl()
        if (apiUrl.isNotBlank() && title.isNotBlank() && title != "No Song Playing") {
            val apiLrc = LyricApi.fetchLyrics(apiUrl, title, artist)
            parse(apiLrc)?.let { return it.copy(source = "Lyric API") }
        }
        return null
    }

    private fun parse(text: String?): LyricsResult? {
        if (text.isNullOrBlank()) return null
        val synced = LrcParser.parse(text)
        if (synced.isNotEmpty()) return LyricsResult(synced, true, "")
        val plain = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (plain.isEmpty()) null else LyricsResult(plain.map { LyricLine(-1, it) }, false, "")
    }
}
