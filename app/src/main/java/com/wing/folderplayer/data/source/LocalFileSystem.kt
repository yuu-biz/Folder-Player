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
class LocalFileSystem(override val config: SourceConfig) : SourceFileSystem {
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
        return children.map {
            MusicFile(
                name = it.name,
                path = SourcePath.child(parent, it.name),
                isDirectory = it.isDirectory,
                size = if (it.isDirectory) 0 else it.length(),
                lastModified = it.lastModified(),
                sourceId = config.id,
            )
        }
    }

    override fun stat(path: String): MusicFile? {
        val f = file(path)
        if (!f.exists()) return null
        val p = SourcePath.normalize(path)
        return MusicFile(SourcePath.name(p), p, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(), config.id)
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
