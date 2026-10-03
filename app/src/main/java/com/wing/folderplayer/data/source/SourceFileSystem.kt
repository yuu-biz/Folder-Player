package com.wing.folderplayer.data.source

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/** Typed I/O failures so callers can tell "no image" from "no permission" or "network down". */
sealed class SourceException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class AuthFailed(msg: String = "authentication failed", cause: Throwable? = null) : SourceException(msg, cause)
    class NotFound(msg: String = "not found", cause: Throwable? = null) : SourceException(msg, cause)
    class PermissionDenied(msg: String = "permission denied", cause: Throwable? = null) : SourceException(msg, cause)
    class Unreachable(msg: String = "host unreachable", cause: Throwable? = null) : SourceException(msg, cause)
    class ShareMissing(msg: String = "share name is empty") : SourceException(msg)
    class ShareNotFound(msg: String = "share not found", cause: Throwable? = null) : SourceException(msg, cause)
    class TlsFailure(msg: String = "TLS / certificate validation failed", cause: Throwable? = null) : SourceException(msg, cause)
    class SeekUnsupported(msg: String = "server does not support resuming at an offset (REST)") : SourceException(msg)
    class PrematureEof(expected: Long, got: Long) : SourceException("connection ended early: got $got of $expected bytes")
    class ReadOnly(msg: String = "source is read-only") : SourceException(msg)
    class TooLarge(limit: Long, actual: Long) : SourceException("file too large: $actual > $limit bytes")
    class Protocol(msg: String, cause: Throwable? = null) : SourceException(msg, cause)
}

enum class SourceCapability { WRITE, RANDOM_ACCESS }

/** A sequential read handle starting at the requested offset. [length] is the number of bytes this handle yields, or -1. */
abstract class SourceInput : InputStream() {
    abstract val length: Long
}

/** Positional reads for decoders that seek (FFmpeg AVIO). Implementations must be used from one thread at a time. */
interface RandomAccessReader : Closeable {
    val size: Long
    /** Reads up to [len] bytes at [position]; returns -1 at end of file. */
    fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int
}

enum class ConnectionOutcome {
    OK, AUTH_FAILED, SHARE_MISSING, SHARE_NOT_FOUND, ROOT_NOT_FOUND, PERMISSION_DENIED, UNREACHABLE, TLS_ERROR, INVALID_CONFIG, ERROR
}

data class ConnectionTestResult(val outcome: ConnectionOutcome, val detail: String = "", val entries: Int = 0) {
    val ok get() = outcome == ConnectionOutcome.OK

    companion object {
        fun fromException(e: Throwable): ConnectionTestResult = when (e) {
            is SourceException.AuthFailed -> ConnectionTestResult(ConnectionOutcome.AUTH_FAILED, e.message.orEmpty())
            is SourceException.ShareMissing -> ConnectionTestResult(ConnectionOutcome.SHARE_MISSING, e.message.orEmpty())
            is SourceException.ShareNotFound -> ConnectionTestResult(ConnectionOutcome.SHARE_NOT_FOUND, e.message.orEmpty())
            is SourceException.NotFound -> ConnectionTestResult(ConnectionOutcome.ROOT_NOT_FOUND, e.message.orEmpty())
            is SourceException.PermissionDenied -> ConnectionTestResult(ConnectionOutcome.PERMISSION_DENIED, e.message.orEmpty())
            is SourceException.Unreachable -> ConnectionTestResult(ConnectionOutcome.UNREACHABLE, e.message.orEmpty())
            is SourceException.TlsFailure -> ConnectionTestResult(ConnectionOutcome.TLS_ERROR, e.message.orEmpty())
            is IllegalArgumentException -> ConnectionTestResult(ConnectionOutcome.INVALID_CONFIG, e.message.orEmpty())
            else -> ConnectionTestResult(ConnectionOutcome.ERROR, e.message ?: e.javaClass.simpleName)
        }
    }
}

/**
 * Common I/O over one configured source. Paths are source-relative ([SourcePath]). All methods block and must run on
 * an I/O thread; they are interruptible where the underlying library allows it and never swallow cancellation.
 */
interface SourceFileSystem : Closeable {
    val config: SourceConfig
    val capabilities: Set<SourceCapability>

    fun list(path: String): List<MusicFile>

    /** Returns null when the entry does not exist. */
    fun stat(path: String): MusicFile?

    fun openRead(path: String, offset: Long = 0, length: Long = -1): SourceInput

    fun openRandomAccess(path: String): RandomAccessReader = ReopeningRandomAccessReader(this, path)

    fun write(path: String, data: ByteArray, overwrite: Boolean): Unit = throw SourceException.ReadOnly()

    /** Rename within the same directory, used for atomic replace of small files where supported. */
    fun rename(from: String, to: String): Boolean = false

    fun delete(path: String): Boolean = false

    /** Verifies login and that the configured root can actually be listed. */
    fun testConnection(): ConnectionTestResult = try {
        val entries = list(SourcePath.ROOT)
        ConnectionTestResult(ConnectionOutcome.OK, entries = entries.size)
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        ConnectionTestResult.fromException(e)
    }

    fun ref(path: String) = SourceRef(config.id, path)

    override fun close() {}
}

fun SourceFileSystem.readBytes(path: String, maxBytes: Long): ByteArray {
    val st = stat(path) ?: throw SourceException.NotFound(path)
    if (st.size > maxBytes) throw SourceException.TooLarge(maxBytes, st.size)
    openRead(path).use { input ->
        val out = java.io.ByteArrayOutputStream(if (st.size > 0) st.size.toInt() else 8192)
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw SourceException.TooLarge(maxBytes, total)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

fun SourceFileSystem.readText(path: String, maxBytes: Long = 2L * 1024 * 1024): String =
    TextDecoding.decode(readBytes(path, maxBytes))

/** Fallback random access for sources whose streams cannot seek in place: reopens at the requested offset. */
class ReopeningRandomAccessReader(private val fs: SourceFileSystem, private val path: String) : RandomAccessReader {
    override val size: Long = fs.stat(path)?.size ?: throw SourceException.NotFound(path)
    private var stream: SourceInput? = null
    private var streamPos = -1L

    override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        if (position >= size) return -1
        var s = stream
        if (s == null || streamPos != position) {
            // Small forward gaps are cheaper to skip than to reconnect.
            if (s != null && position > streamPos && position - streamPos <= 256 * 1024) {
                var toSkip = position - streamPos
                val tmp = ByteArray(16 * 1024)
                while (toSkip > 0) {
                    val n = s.read(tmp, 0, minOf(tmp.size.toLong(), toSkip).toInt())
                    if (n < 0) break
                    toSkip -= n
                }
                streamPos = position - toSkip
            }
            if (streamPos != position) {
                s?.close()
                s = fs.openRead(path, position)
                stream = s
                streamPos = position
            }
        }
        val n = s!!.read(buffer, offset, len)
        if (n > 0) streamPos += n
        return n
    }

    override fun close() {
        stream?.close()
        stream = null
    }
}
