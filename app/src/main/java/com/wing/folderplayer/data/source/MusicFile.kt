package com.wing.folderplayer.data.source

/**
 * A directory entry. [path] is source-relative ([SourcePath]); [sourceId] identifies the source it belongs to.
 * (In the upstream code [path] was an absolute file path or full WebDAV URL; see SourceMigration.)
 */
data class MusicFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val sourceId: String = "",
    /** Creation time in ms; 0 = the source does not tell (only local files and SMB do). */
    val createdAt: Long = 0,
    /** Track length in ms when known (from the length cache, filled for sorting by length); null: not known or not a song. */
    val durationMs: Long? = null,
) {
    /** What "sort by creation date" uses: the creation time, or the modification time where there is none. */
    val createdOrModified: Long get() = if (createdAt > 0) createdAt else lastModified
    val ref: SourceRef get() = SourceRef(sourceId, path)
    val extension: String get() = SourcePath.extension(name)
}

/**
 * The one sort order of the browser list and of the playback queue built from a folder. Equal keys (the rule for
 * "Created": an album copied in one go has the same second for every file) fall back to the name, in the same
 * direction for both, so the queue always follows the list. Folders first is the caller's business. By [LENGTH][SORT_LENGTH]
 * the entries whose length is not known (folders, files not read yet) come last in either direction, by name.
 */
fun sortMusicFiles(files: List<MusicFile>, field: String?, ascending: Boolean): List<MusicFile> {
    val byName = compareBy<MusicFile>({ it.name.lowercase() }, { it.name })
    if (field == SORT_LENGTH) {
        val (known, unknown) = files.partition { it.durationMs != null }
        val byLength = compareBy<MusicFile> { it.durationMs }
        return known.sortedWith((if (ascending) byLength else byLength.reversed()).then(byName)) + unknown.sortedWith(byName)
    }
    val primary: Comparator<MusicFile> = when (field) {
        "DATE" -> compareBy { it.lastModified }
        "CREATED" -> compareBy { it.createdOrModified }
        "SIZE" -> compareBy { it.size }
        else -> byName
    }
    return files.sortedWith((if (ascending) primary else primary.reversed()).then(byName))
}

/** The sort field of the track length (songs only; see [sortMusicFiles]). */
const val SORT_LENGTH = "LENGTH"
