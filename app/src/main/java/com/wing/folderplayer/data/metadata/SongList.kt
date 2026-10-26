package com.wing.folderplayer.data.metadata

import com.wing.folderplayer.data.search.NameFilter
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SORT_LENGTH
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef

/**
 * The songs list: every song the length index knows ([DurationRepository.songs]), as a music player lists its library,
 * or those below some folders ([inFolders]).
 * Sorted by title, artist, album, length or modification time; a song without tags goes by its file name, and blank
 * artists / albums come last in either direction.
 */
object SongList {
    const val TITLE = "TITLE"
    const val ARTIST = "ARTIST"
    const val ALBUM = "ALBUM"
    const val DATE = "DATE"
    val FIELDS = listOf(TITLE, ARTIST, ALBUM, SORT_LENGTH, DATE)

    /** What the row shows as the song's name. */
    fun title(f: MusicFile): String = f.tags?.title?.takeIf { it.isNotBlank() } ?: SourcePath.baseName(f.name)

    fun sort(songs: List<MusicFile>, field: String, ascending: Boolean): List<MusicFile> {
        val byTitle = compareBy<MusicFile>({ title(it).lowercase() }, { it.path })
        val albumOrder = compareBy<MusicFile>({ it.tags?.album.orEmpty().lowercase() }, { trackOrMax(it) }, { SourcePath.parent(it.path) }, { it.name.lowercase() })
        fun direction(c: Comparator<MusicFile>) = if (ascending) c else c.reversed()
        return when (field) {
            ARTIST -> blankLast(songs, { it.tags?.artist }, direction(compareBy { it.tags!!.artist.lowercase() }), albumOrder.then(byTitle))
            ALBUM -> blankLast(songs, { it.tags?.album }, direction(compareBy { it.tags!!.album.lowercase() }), compareBy<MusicFile> { trackOrMax(it) }.then(byTitle))
            SORT_LENGTH -> songs.sortedWith(direction(compareBy<MusicFile> { it.durationMs ?: 0L }).then(byTitle))
            DATE -> songs.sortedWith(direction(compareBy<MusicFile> { it.lastModified }).then(byTitle))
            else -> songs.sortedWith(direction(byTitle))
        }
    }

    private fun trackOrMax(f: MusicFile): Int = f.tags?.track?.takeIf { it > 0 } ?: Int.MAX_VALUE

    /** Songs with [key] by [primary] then [then]; those without it after them, by title. */
    private fun blankLast(songs: List<MusicFile>, key: (MusicFile) -> String?, primary: Comparator<MusicFile>, then: Comparator<MusicFile>): List<MusicFile> {
        val (with, without) = songs.partition { !key(it).isNullOrBlank() }
        return with.sortedWith(primary.then(then)) + without.sortedWith(compareBy<MusicFile>({ title(it).lowercase() }, { it.path }))
    }

    /** The songs in any of [folders] and the folders below them, each once, in their order; no folder: all of them. */
    fun inFolders(songs: List<MusicFile>, folders: List<SourceRef>): List<MusicFile> =
        if (folders.isEmpty()) songs
        else songs.filter { song -> folders.any { it.sourceId == song.sourceId && SourcePath.isAncestorOrSelf(it.path, song.path) } }

    /** Every word of [filter] in the title, artist, album or file name (as the folder filter: case, width, kana ignored). */
    fun filter(songs: List<MusicFile>, filter: String): List<MusicFile> {
        val terms = NameFilter.terms(filter)
        if (terms.isEmpty()) return songs
        return songs.filter { f ->
            val text = NameFilter.fold(listOfNotNull(f.name, f.tags?.title, f.tags?.artist, f.tags?.album).joinToString(" "))
            terms.all(text::contains)
        }
    }
}
