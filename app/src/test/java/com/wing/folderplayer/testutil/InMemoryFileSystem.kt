package com.wing.folderplayer.testutil

import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceCapability
import com.wing.folderplayer.data.source.SourceConfig
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourceInput
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceType

/** Test double with real path semantics; can simulate read-only, unreadable folders and truncated reads. */
class InMemoryFileSystem(
    id: String = "mem",
    var writable: Boolean = true,
) : SourceFileSystem {
    override val config = SourceConfig(id = id, name = id, type = SourceType.LOCAL, url = "/mem")
    override val capabilities: Set<SourceCapability> get() = if (writable) setOf(SourceCapability.WRITE) else emptySet()

    val files = LinkedHashMap<String, ByteArray>()
    val dirs = LinkedHashSet<String>().apply { add("/") }
    val unreadable = HashSet<String>()
    val mtimes = HashMap<String, Long>()
    /** Path → number of bytes after which reads fail with a premature EOF. */
    val truncateAt = HashMap<String, Long>()
    /** Delay per read() call, to simulate a slow network transfer. */
    var readDelayMs = 0L
    var writes = 0
    var listCalls = 0

    fun mkdirs(path: String) {
        var p: String? = SourcePath.normalize(path)
        while (p != null) { dirs.add(p); p = SourcePath.parent(p) }
    }

    fun put(path: String, data: ByteArray, mtime: Long = 1000) {
        val p = SourcePath.normalize(path)
        mkdirs(SourcePath.parent(p)!!)
        files[p] = data
        mtimes[p] = mtime
    }

    fun put(path: String, text: String) = put(path, text.toByteArray())

    override fun list(path: String): List<MusicFile> {
        listCalls++
        val p = SourcePath.normalize(path)
        if (p in unreadable) throw SourceException.PermissionDenied(p)
        if (p !in dirs) throw SourceException.NotFound(p)
        val children = (dirs.filter { it != p && SourcePath.parent(it) == p }.map { MusicFile(SourcePath.name(it), it, true, 0, 0, config.id) } +
            files.filterKeys { SourcePath.parent(it) == p }.map { (k, v) -> MusicFile(SourcePath.name(k), k, false, v.size.toLong(), mtimes[k] ?: 0, config.id) })
        return children
    }

    override fun stat(path: String): MusicFile? {
        val p = SourcePath.normalize(path)
        files[p]?.let { return MusicFile(SourcePath.name(p), p, false, it.size.toLong(), mtimes[p] ?: 0, config.id) }
        if (p in dirs) return MusicFile(SourcePath.name(p), p, true, 0, 0, config.id)
        return null
    }

    override fun openRead(path: String, offset: Long, length: Long): SourceInput {
        val p = SourcePath.normalize(path)
        if (SourcePath.parent(p) in unreadable) throw SourceException.PermissionDenied(p)
        val data = files[p] ?: throw SourceException.NotFound(p)
        val end = if (length < 0) data.size.toLong() else minOf(data.size.toLong(), offset + length)
        val cut = truncateAt[p]
        return object : SourceInput() {
            var pos = offset
            override val length: Long = end - offset
            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= end) return -1
                if (readDelayMs > 0) Thread.sleep(readDelayMs)
                if (cut != null && pos >= cut) throw SourceException.PrematureEof(end - offset, pos - offset)
                var n = minOf(len.toLong(), end - pos).toInt()
                if (cut != null) n = minOf(n.toLong(), cut - pos).toInt()
                System.arraycopy(data, pos.toInt(), b, off, n)
                pos += n
                return n
            }
        }
    }

    override fun write(path: String, data: ByteArray, overwrite: Boolean) {
        if (!writable) throw SourceException.ReadOnly()
        val p = SourcePath.normalize(path)
        if (!overwrite && p in files) throw java.nio.file.FileAlreadyExistsException(p)
        writes++
        put(p, data)
    }

    override fun rename(from: String, to: String): Boolean {
        if (!writable) throw SourceException.ReadOnly()
        val d = files.remove(SourcePath.normalize(from)) ?: return false
        put(to, d)
        return true
    }

    override fun delete(path: String): Boolean = files.remove(SourcePath.normalize(path)) != null
}
