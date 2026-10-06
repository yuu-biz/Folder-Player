package com.wing.folderplayer.data.source

import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

/**
 * Direct file access under a root directory (internal storage / SD card path).
 *
 * On Android 13+ the media provider only exposes files whose media type the app may read: audio needs
 * READ_MEDIA_AUDIO, images READ_MEDIA_IMAGES (or a partial selection on 14+), and non-media files such as .lrc/.nfo
 * of other apps are not visible at all. Folders that need those files must be added as a SAF source.
 */
class LocalFileSystem(override val config: SourceConfig, private val context: android.content.Context? = null) : SourceFileSystem {
    private val root: File = File(config.url).absoluteFile

    override val capabilities = setOf(SourceCapability.WRITE, SourceCapability.RANDOM_ACCESS)

    fun file(path: String): File {
        val f = File(root, SourcePath.normalize(path).removePrefix("/"))
        // normalize() already rejects "..", this guards symlinks that point outside the root.
        val canonRoot = root.canonicalPath
        val canon = f.canonicalPath
        if (canon != canonRoot && !canon.startsWith(canonRoot + File.separator)) {
            throw SourceException.PermissionDenied("outside source root")
        }
        return f
    }

    override fun list(path: String): List<MusicFile> {
        val dir = file(path)
        if (!dir.exists()) throw SourceException.NotFound(path)
        if (!dir.isDirectory) throw SourceException.NotFound("not a directory: $path")
        val children = dir.listFiles() ?: throw SourceException.PermissionDenied("cannot list $path")
        val parent = SourcePath.normalize(path)
        val added = addedTimes(dir)
        return children.map {
            MusicFile(
                name = it.name,
                path = SourcePath.child(parent, it.name),
                isDirectory = it.isDirectory,
                size = if (it.isDirectory) 0 else it.length(),
                lastModified = it.lastModified(),
                sourceId = config.id,
                createdAt = added[it.name] ?: creationTime(it),
            )
        }
    }

    /**
     * Creation time of a file. Android does not give apps the birth time of a file: `BasicFileAttributes.creationTime` returns the
     * modification time (checked on API 34), so it is only used when it differs from it (a file system that does keep one).
     * 0 = not known.
     */
    private fun creationTime(f: File): Long = runCatching {
        val a = java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        a.creationTime().toMillis().takeIf { it != a.lastModifiedTime().toMillis() } ?: 0L
    }.getOrDefault(0L)

    /**
     * When the files of [dir] (direct children) were added to the device's media library, by name, in ms: the time they
     * appeared on the device, which is what is left of a creation time on Android. Only files the media library knows (audio,
     * images the app may read); anything else, or a failing query, gives nothing and the sort falls back to the
     * modification time. Needs a context (without one, e.g. in JVM tests, the map is empty).
     */
    private fun addedTimes(dir: File?): Map<String, Long> = if (dir == null) emptyMap() else addedTimesFor(dir).byName

    /** The media library's answer for one folder: the times by name, and how many rows the query returned (test hook). */
    internal class AddedTimes(val byName: Map<String, Long>, val rowsReturned: Int)

    internal fun addedTimesFor(dir: File): AddedTimes {
        val ctx = context ?: return AddedTimes(emptyMap(), 0)
        val prefix = dir.absolutePath.trimEnd('/') + "/"
        var rows = 0
        val map = runCatching {
            val out = HashMap<String, Long>()
            val data = android.provider.MediaStore.MediaColumns.DATA
            ctx.contentResolver.query(
                android.provider.MediaStore.Files.getContentUri("external"),
                arrayOf(data, android.provider.MediaStore.MediaColumns.DATE_ADDED),
                "$data LIKE ? ESCAPE '\\' AND $data NOT LIKE ? ESCAPE '\\'", arrayOf(likeEscape(prefix) + "%", likeEscape(prefix) + "%/%"), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    rows++
                    val p = c.getString(0) ?: continue
                    val sec = c.getLong(1)
                    // (Belt and braces: LIKE ignores case, so a sibling folder that differs only in case is returned too.)
                    if (sec > 0 && p.startsWith(prefix) && !p.substring(prefix.length).contains('/')) out[p.substring(prefix.length)] = sec * 1000
                }
            }
            out
        }.getOrDefault(emptyMap())
        return AddedTimes(map, rows)
    }

    override fun stat(path: String): MusicFile? {
        val f = file(path)
        if (!f.exists()) return null
        val p = SourcePath.normalize(path)
        return MusicFile(SourcePath.name(p), p, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(), config.id, creationTime(f))
    }

    override fun openRead(path: String, offset: Long, length: Long): SourceInput {
        val f = file(path)
        val raf = try {
            RandomAccessFile(f, "r")
        } catch (e: FileNotFoundException) {
            throw if (f.exists()) SourceException.PermissionDenied(path, e) else SourceException.NotFound(path, e)
        }
        val size = raf.length()
        if (offset > size) {
            raf.close()
            throw java.io.EOFException("offset beyond end of file")
        }
        raf.seek(offset)
        val available = size - offset
        val len = if (length < 0) available else minOf(length, available)
        return object : SourceInput() {
            var remaining = len
            override val length: Long = len
            override fun read(): Int {
                if (remaining <= 0) return -1
                val b = raf.read()
                if (b >= 0) remaining--
                return b
            }
            override fun read(b: ByteArray, off: Int, l: Int): Int {
                if (remaining <= 0) return -1
                val n = raf.read(b, off, minOf(l.toLong(), remaining).toInt())
                if (n > 0) remaining -= n
                return n
            }
            override fun close() = raf.close()
        }
    }

    override fun openRandomAccess(path: String): RandomAccessReader {
        val f = file(path)
        val raf = try { RandomAccessFile(f, "r") } catch (e: FileNotFoundException) { throw SourceException.NotFound(path, e) }
        return object : RandomAccessReader {
            override val size: Long = raf.length()
            override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
                if (position >= size) return -1
                raf.seek(position)
                return raf.read(buffer, offset, len)
            }
            override fun close() = raf.close()
        }
    }

    override fun write(path: String, data: ByteArray, overwrite: Boolean) {
        val f = file(path)
        if (f.exists() && !overwrite) throw java.nio.file.FileAlreadyExistsException(path)
        try {
            val tmp = File(f.parentFile, ".${f.name}.fp-tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(f)) {
                f.writeBytes(data)
                tmp.delete()
            }
        } catch (e: java.io.IOException) {
            throw SourceException.PermissionDenied("cannot write $path (use a SAF folder for write access)", e)
        }
    }

    override fun rename(from: String, to: String): Boolean = file(from).renameTo(file(to))

    override fun delete(path: String): Boolean = file(path).delete()

    override fun testConnection(): ConnectionTestResult {
        if (!root.exists()) return ConnectionTestResult(ConnectionOutcome.ROOT_NOT_FOUND, root.path)
        return super.testConnection()
    }
}

/**
 * [s] as a literal in a `LIKE ? ESCAPE '\'` pattern: "%" and "_" of a folder name (and the escape character itself) must not
 * act as wildcards, or the query for "p%c" also returns the files of "pXc" and one for "%" everything below its parent.
 */
internal fun likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
