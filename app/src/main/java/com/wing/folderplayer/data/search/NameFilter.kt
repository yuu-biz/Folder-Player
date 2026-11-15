package com.wing.folderplayer.data.search

import com.wing.folderplayer.data.source.MediaTypes
import com.wing.folderplayer.data.source.MusicFile

/** A file the browser's music-only view keeps (folders stay too): a song, or a CUE sheet, which plays as songs. */
fun isMusicName(name: String): Boolean = MediaTypes.isAudio(name) || MediaTypes.isCue(name)

/** The entries of the folder on screen: with [musicOnly] only folders and [isMusicName] files, then those [NameFilter] keeps. */
fun folderEntries(files: List<MusicFile>, filter: String, musicOnly: Boolean): List<MusicFile> =
    NameFilter.apply(if (musicOnly) files.filter { it.isDirectory || isMusicName(it.name) } else files, filter)

/**
 * The filter of the browser: narrows the entries of the folder on screen by name (nothing below the folder is listed, unlike
 * [SearchRepository]). Every word of the filter must occur in the name; case, full / half width and katakana / hiragana do
 * not matter.
 */
object NameFilter {
    fun apply(files: List<MusicFile>, filter: String): List<MusicFile> {
        val terms = terms(filter)
        if (terms.isEmpty()) return files
        return files.filter { f -> fold(f.name).let { name -> terms.all(name::contains) } }
    }

    fun terms(filter: String): List<String> = fold(filter).split(' ', '\t').filter { it.isNotEmpty() }

    /** [SearchRepository.norm] (NFKC turns an ideographic space into a plain one), katakana as hiragana. */
    fun fold(s: String): String {
        val n = SearchRepository.norm(s)
        val out = StringBuilder(n.length)
        for (c in n) out.append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
        return out.toString()
    }
}
