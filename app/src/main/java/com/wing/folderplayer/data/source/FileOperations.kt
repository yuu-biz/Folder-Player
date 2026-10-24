package com.wing.folderplayer.data.source

/**
 * Rename, move (to another folder of the same source) and delete of one file, from the browser's long-press sheet and the
 * viewer. Folders are left alone: deleting one means different things per protocol (SMB and FTP refuse a folder that is
 * not empty, WebDAV deletes everything below it), and playlists, favourites and bookmarks point into them.
 *
 * [SourceFileSystem.rename] replaces an existing target (the favourites sync relies on that), so the folder the file goes
 * to is looked at first and a name that is taken there is refused. The comparison ignores case: SMB shares, SAF folders on
 * an SD card and FAT file systems treat "a.mp3" and "A.mp3" as one file, and a rename would overwrite the other one.
 */
object FileOperations {

    sealed class Failure(message: String) : Exception(message) {
        class InvalidName(val name: String) : Failure("invalid file name: $name")
        class AlreadyExists(val name: String) : Failure("already exists: $name")
        class NotAFile(path: String) : Failure("not a file: $path")
        /** The source answered "no" without saying why (a local file the app does not own, a provider without move support). */
        class Refused(val operation: String, path: String) : Failure("$operation refused: $path")
    }

    /** Characters that are refused because some source cannot hold them (Windows / SMB, FAT, SAF) or they mean a path. */
    private const val FORBIDDEN = "/\\:*?\"<>|"

    /** [name] trimmed if it can be a file name on every source, else null. */
    fun validName(name: String): String? {
        val n = name.trim()
        if (n.isEmpty() || n == "." || n == "..") return null
        if (n.any { it in FORBIDDEN || it < ' ' }) return null
        if (n.toByteArray(Charsets.UTF_8).size > 255) return null
        return n
    }

    /** Renames [file] in its folder. Returns the file under its new name (the same file when the name did not change). */
    fun rename(fs: SourceFileSystem, file: MusicFile, newName: String): MusicFile {
        requireFile(file)
        val name = validName(newName) ?: throw Failure.InvalidName(newName)
        if (name == file.name) return file
        val folder = SourcePath.parent(file.path) ?: SourcePath.ROOT
        return relocate(fs, file, folder, name)
    }

    /** Moves [file] into [folder] of the same source, under its name. */
    fun move(fs: SourceFileSystem, file: MusicFile, folder: String): MusicFile {
        requireFile(file)
        val target = SourcePath.normalize(folder)
        if (target == (SourcePath.parent(file.path) ?: SourcePath.ROOT)) return file
        return relocate(fs, file, target, file.name)
    }

    fun delete(fs: SourceFileSystem, file: MusicFile) {
        requireFile(file)
        if (!fs.delete(file.path)) throw Failure.Refused("delete", file.path)
    }

    private fun requireFile(file: MusicFile) {
        if (file.isDirectory) throw Failure.NotAFile(file.path)
    }

    private fun relocate(fs: SourceFileSystem, file: MusicFile, folder: String, name: String): MusicFile {
        val taken = fs.list(folder).any { it.path != file.path && it.name.equals(name, ignoreCase = true) }
        if (taken) throw Failure.AlreadyExists(name)
        val to = SourcePath.child(folder, name)
        if (!fs.rename(file.path, to)) throw Failure.Refused("rename", file.path)
        return file.copy(name = name, path = to)
    }
}
