package com.wing.folderplayer.data.repo

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.wing.folderplayer.data.artwork.ArtworkResult
import com.wing.folderplayer.data.artwork.ImageUris
import com.wing.folderplayer.data.artwork.ThumbnailRepository
import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceUris
import com.wing.folderplayer.data.source.sortMusicFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Title shown for tracks: file name (default, as in public main) or embedded tags. */
enum class TitleMode { FILENAME, TAGS }

/**
 * Builds Media3 items from source entries. URI and mediaId are `fpsrc://` refs; artwork is the folder image
 * resolved per track's own parent folder (so mixed-folder playlists get the right cover per track).
 */
class PlayerRepository(private val context: Context) {
    private val thumbnails = ThumbnailRepository.get(context)

    var titleMode: TitleMode = TitleMode.FILENAME

    suspend fun list(folder: SourceRef): List<MusicFile> = runInterruptible(Dispatchers.IO) {
        SourceRegistry.fileSystem(folder).list(folder.path)
    }

    fun sortFiles(files: List<MusicFile>, field: String?, ascending: Boolean): List<MusicFile> =
        sortMusicFiles(files, field, ascending)

    /** Folder image URI for the player/notification (independent of thumbnail switches). */
    suspend fun coverFor(folder: SourceRef, known: List<MusicFile>? = null): String? =
        when (val r = thumbnails.playbackCover(folder, known)) {
            is ArtworkResult.Found -> ImageUris.of(r.image, r.entry)
            else -> null
        }

    suspend fun getMediaItemsInFolder(folder: SourceRef, sortField: String? = "NAME", sortAscending: Boolean = true): List<MediaItem> {
        val entries = list(folder)
        val music = sortFiles(entries.filter { !it.isDirectory && MediaTypes.isAudio(it.name) }, sortField, sortAscending)
        val cover = coverFor(folder, entries)
        return music.map { buildMediaItem(it, cover, findLyric(it, entries)) }
    }

    /** Items for files that may come from different folders; covers are resolved once per parent folder. */
    suspend fun mediaItemsFor(files: List<MusicFile>): List<MediaItem> {
        val covers = HashMap<String, String?>()
        val listings = HashMap<String, List<MusicFile>?>()
        return files.map { f ->
            val parent = f.ref.parent ?: SourceRef(f.sourceId, SourcePath.ROOT)
            val key = parent.toUriString()
            val listing = listings.getOrPut(key) { runCatching { list(parent) }.getOrNull() }
            val cover = if (covers.containsKey(key)) covers[key] else coverFor(parent, listing).also { covers[key] = it }
            buildMediaItem(f, cover, listing?.let { findLyric(f, it) })
        }
    }

    fun findLyric(audio: MusicFile, siblings: List<MusicFile>): MusicFile? {
        val base = SourcePath.baseName(audio.name)
        return siblings.firstOrNull { !it.isDirectory && MediaTypes.isLyric(it.name) && SourcePath.baseName(it.name) == base }
            ?: siblings.firstOrNull { !it.isDirectory && MediaTypes.isLyric(it.name) && SourcePath.baseName(it.name).equals(base, true) }
    }

    fun buildMediaItem(file: MusicFile, coverUri: String?, lrc: MusicFile?): MediaItem {
        val ref = file.ref
        val uri = ref.toUriString()
        val ext = file.extension
        val extras = android.os.Bundle().apply {
            putString(EXTRA_SOURCE_ID, ref.sourceId)
            putString(EXTRA_PATH, ref.path)
            putLong("file_size", file.size)
            putString("file_ext", ext)
            lrc?.let { putString(EXTRA_LRC_PATH, it.path) }
        }
        val display = SourcePath.baseName(file.name)
        val md = MediaMetadata.Builder()
            .setDisplayTitle(display)
            .setExtras(extras)
            .setIsBrowsable(false)
            .setIsPlayable(true)
        // A MediaItem title overrides tag titles, so it is only set in file-name mode.
        if (titleMode == TitleMode.FILENAME) md.setTitle(display)
        coverUri?.let { md.setArtworkUri(it.toUri()) }
        val builder = MediaItem.Builder().setUri(uri).setMediaId(uri).setMediaMetadata(md.build())
        mimeFor(ext)?.let { builder.setMimeType(it) }
        return builder.build()
    }

    fun createCueMediaItem(
        audio: MusicFile,
        trackTitle: String,
        performer: String?,
        startTimeMs: Long,
        endTimeMs: Long?,
        coverUri: String?,
        cue: SourceRef,
        trackNumber: Int,
    ): MediaItem {
        val extras = android.os.Bundle().apply {
            putString(EXTRA_SOURCE_ID, audio.sourceId)
            putString(EXTRA_PATH, audio.path)
            putString(EXTRA_CUE_PATH, cue.path)
            putLong("file_size", audio.size)
            putString("file_ext", audio.extension)
            putBoolean(EXTRA_IS_CUE_TRACK, true)
        }
        val md = MediaMetadata.Builder()
            .setTitle(trackTitle)
            .setDisplayTitle(trackTitle)
            .setArtist(performer)
            .setTrackNumber(trackNumber)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(extras)
        coverUri?.let { md.setArtworkUri(it.toUri()) }
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startTimeMs)
            .apply { if (endTimeMs != null) setEndPositionMs(endTimeMs) }
            .build()
        val builder = MediaItem.Builder()
            .setUri(audio.ref.toUriString())
            .setMediaId(SourceUris.cueTrackId(audio.ref, startTimeMs))
            .setMediaMetadata(md.build())
            .setClippingConfiguration(clipping)
        mimeFor(audio.extension)?.let { builder.setMimeType(it) }
        return builder.build()
    }

    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_PATH = "source_path"
        const val EXTRA_LRC_PATH = "lrc_path"
        const val EXTRA_CUE_PATH = "cue_path"
        const val EXTRA_IS_CUE_TRACK = "is_cue_track"

        /** Formats decoded natively are exposed to Media3 as WAV by NativePcmDataSource. */
        fun mimeFor(ext: String): String? = when (ext) {
            "mp3" -> androidx.media3.common.MimeTypes.AUDIO_MPEG
            "flac" -> androidx.media3.common.MimeTypes.AUDIO_FLAC
            "wav", "wma", "ape", "dsf", "dff" -> androidx.media3.common.MimeTypes.AUDIO_WAV
            "ogg" -> androidx.media3.common.MimeTypes.AUDIO_OGG
            else -> null // m4a may be AAC (MP4 path) or ALAC (WAV via native decoder): let Media3 sniff
        }
    }
}
