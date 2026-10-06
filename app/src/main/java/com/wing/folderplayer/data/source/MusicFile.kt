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
) {
    /** What "sort by creation date" uses: the creation time, or the modification time where there is none. */
    val createdOrModified: Long get() = if (createdAt > 0) createdAt else lastModified
    val ref: SourceRef get() = SourceRef(sourceId, path)
    val extension: String get() = SourcePath.extension(name)
}

/**
 * The one sort order of the browser list and of the playback queue built from a folder. Equal keys (the rule for
 * "Created": an album copied in one go has the same second for every file) fall back to the name, in the same
 * direction for both, so the queue always follows the list. Folders first is the caller's business.
 */
fun sortMusicFiles(files: List<MusicFile>, field: String?, ascending: Boolean): List<MusicFile> {
    val byName = compareBy<MusicFile>({ it.name.lowercase() }, { it.name })
    val primary: Comparator<MusicFile> = when (field) {
        "DATE" -> compareBy { it.lastModified }
        "CREATED" -> compareBy { it.createdOrModified }
        "SIZE" -> compareBy { it.size }
        else -> byName
    }
    return files.sortedWith((if (ascending) primary else primary.reversed()).then(byName))
}
