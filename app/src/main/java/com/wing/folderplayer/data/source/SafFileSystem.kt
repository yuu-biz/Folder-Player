package com.wing.folderplayer.data.source

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Storage Access Framework tree chosen with ACTION_OPEN_DOCUMENT_TREE (persisted permission). Works for files the
 * media provider does not index (cover.jpg in .nomedia folders, .lrc, .nfo) and for SD cards. Content URIs are
 * never turned into File paths; seeking uses the provider's file descriptor when it is seekable and otherwise
 * falls back to skipping forward in a fresh stream.
 */
class SafFileSystem(override val config: SourceConfig, context: Context) : SourceFileSystem {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val treeUri: Uri = config.url.toUri()
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    /** source-relative path → documentId (document ids are opaque; names are resolved by listing). */
    private val idCache = ConcurrentHashMap<String, String>().apply { put(SourcePath.ROOT, rootDocId) }

    override val capabilities = setOf(SourceCapability.WRITE, SourceCapability.RANDOM_ACCESS)

    private fun hasPersistedPermission(): Boolean =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private data class Row(val docId: String, val name: String, val mime: String, val size: Long, val modified: Long)

    private fun queryChildren(docId: String): List<Row> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cursor = try {
            resolver.query(uri, PROJECTION, null, null, null)
        } catch (e: SecurityException) {
            throw SourceException.PermissionDenied("SAF permission missing or revoked; pick the folder again", e)
        } catch (e: IllegalArgumentException) {
            throw SourceException.NotFound("document not found", e)
        } ?: throw SourceException.PermissionDenied("provider returned no cursor")
        return cursor.use { c ->
            val out = ArrayList<Row>(c.count)
            while (c.moveToNext()) {
                out.add(Row(c.getString(0), c.getString(1) ?: continue, c.getString(2) ?: "", if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4)))
            }
            out
        }
    }

    private fun resolve(path: String): String {
        val p = SourcePath.normalize(path)
        idCache[p]?.let { return it }
        val parent = SourcePath.parent(p) ?: return rootDocId
        val parentId = resolve(parent)
        val name = SourcePath.name(p)
        val row = queryChildren(parentId).firstOrNull { it.name == name } ?: throw SourceException.NotFound(p)
        idCache[p] = row.docId
        return row.docId
    }

    override fun list(path: String): List<MusicFile> {
        if (!hasPersistedPermission()) throw SourceException.PermissionDenied("SAF permission missing or revoked; pick the folder again")
        val dir = SourcePath.normalize(path)
        val rows = queryChildren(resolve(dir))
        return rows.map { r ->
            val child = SourcePath.child(dir, r.name)
            idCache[child] = r.docId
            val isDir = r.mime == DocumentsContract.Document.MIME_TYPE_DIR
            MusicFile(r.name, child, isDir, if (isDir) 0 else r.size, r.modified, config.id)
        }
    }

    override fun stat(path: String): MusicFile? {
        val p = SourcePath.normalize(path)
        if (p == SourcePath.ROOT) return MusicFile("", p, true, 0, 0, config.id)
        val parent = SourcePath.parent(p) ?: SourcePath.ROOT
        return try {
            list(parent).firstOrNull { it.name == SourcePath.name(p) }
        } catch (e: SourceException.NotFound) {
            null
        }
    }

    private fun openFd(path: String): ParcelFileDescriptor {
        val uri = docUri(resolve(path))
        return try {
            resolver.openFileDescriptor(uri, "r") ?: throw SourceException.NotFound(path)
        } catch (e: SecurityException) {
            throw SourceException.PermissionDenied("SAF permission missing or revoked", e)
        } catch (e: FileNotFoundException) {
            idCache.remove(SourcePath.normalize(path))
            throw SourceException.NotFound(path, e)
        }
    }

    override fun openRead(path: String, offset: Long, length: Long): SourceInput {
        val pfd = openFd(path)
        val fis = FileInputStream(pfd.fileDescriptor)
        val size = pfd.statSize
        try {
            if (offset > 0) {
                val seeked = runCatching { fis.channel.position(offset); fis.channel.position() == offset }.getOrDefault(false)
                if (!seeked) {
                    var toSkip = offset
                    val tmp = ByteArray(64 * 1024)
                    while (toSkip > 0) {
                        val n = fis.read(tmp, 0, minOf(tmp.size.toLong(), toSkip).toInt())
                        if (n < 0) throw java.io.EOFException("offset beyond end")
                        toSkip -= n
                    }
                }
            }
        } catch (e: IOException) {
            fis.close(); pfd.close(); throw e
        }
        val available = if (size >= 0) (size - offset).coerceAtLeast(0) else -1
        val len = if (length >= 0 && available >= 0) minOf(length, available) else if (length >= 0) length else available
        return object : SourceInput() {
            var count = 0L
            override val length: Long = len
            override fun read(): Int {
                if (len >= 0 && count >= len) return -1
                val b = fis.read()
                if (b >= 0) count++
                return b
            }
            override fun read(b: ByteArray, off: Int, l: Int): Int {
                if (len >= 0 && count >= len) return -1
                val n = fis.read(b, off, if (len >= 0) minOf(l.toLong(), len - count).toInt() else l)
                if (n > 0) count += n
                return n
            }
            override fun close() { runCatching { fis.close() }; runCatching { pfd.close() } }
        }
    }

    override fun openRandomAccess(path: String): RandomAccessReader {
        val pfd = openFd(path)
        val fis = FileInputStream(pfd.fileDescriptor)
        val seekable = runCatching { fis.channel.position(0); true }.getOrDefault(false)
        if (!seekable) {
            fis.close(); pfd.close()
            return ReopeningRandomAccessReader(this, path)
        }
        return object : RandomAccessReader {
            override val size: Long = pfd.statSize
            override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
                if (size in 0..position) return -1
                return fis.channel.read(java.nio.ByteBuffer.wrap(buffer, offset, len), position)
            }
            override fun close() { runCatching { fis.close() }; runCatching { pfd.close() } }
        }
    }

    override fun write(path: String, data: ByteArray, overwrite: Boolean) {
        val p = SourcePath.normalize(path)
        val existing = try { resolve(p) } catch (e: SourceException.NotFound) { null }
        val target = if (existing != null) {
            if (!overwrite) throw java.nio.file.FileAlreadyExistsException(p)
            docUri(existing)
        } else {
            val parentId = resolve(SourcePath.parent(p) ?: SourcePath.ROOT)
            // A typed MIME makes the provider "fix" the extension (Info.nfo → Info.nfo.txt); octet-stream keeps the name.
            val mime = "application/octet-stream"
            try {
                DocumentsContract.createDocument(resolver, docUri(parentId), mime, SourcePath.name(p))
            } catch (e: SecurityException) {
                throw SourceException.PermissionDenied("no write permission for this SAF folder", e)
            } ?: throw SourceException.PermissionDenied("provider refused to create ${SourcePath.name(p)}")
        }
        try {
            resolver.openOutputStream(target, "wt")?.use { it.write(data) } ?: throw SourceException.PermissionDenied("cannot open for writing")
        } catch (e: SecurityException) {
            throw SourceException.PermissionDenied("no write permission for this SAF folder", e)
        }
        idCache.remove(p)
    }

    override fun delete(path: String): Boolean {
        val p = SourcePath.normalize(path)
        val ok = DocumentsContract.deleteDocument(resolver, docUri(resolve(p)))
        idCache.remove(p)
        return ok
    }

    override fun testConnection(): ConnectionTestResult {
        if (!hasPersistedPermission()) return ConnectionTestResult(ConnectionOutcome.PERMISSION_DENIED, "folder permission was revoked")
        return super.testConnection()
    }

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
