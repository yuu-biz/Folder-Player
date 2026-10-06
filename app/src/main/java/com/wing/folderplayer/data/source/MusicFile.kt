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
