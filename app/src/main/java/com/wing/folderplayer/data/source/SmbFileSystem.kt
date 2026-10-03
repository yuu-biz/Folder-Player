package com.wing.folderplayer.data.source

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * One SMB2/3 connection + session + share per source, reused across requests and torn down after [idleMillis]
 * without use or when the source's connection settings change. SMB1 is not offered (SMBJ does not implement it).
 */
class SmbConnectionManager(
    private val config: SourceConfig,
    private val password: String?,
    private val idleMillis: Long = 2 * 60 * 1000L,
) : java.io.Closeable {
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    @Volatile private var lastUsed = 0L
    /** Open file handles; an idle connection is only recycled when none are open. */
    private val openHandles = java.util.concurrent.atomic.AtomicInteger()

    fun handleOpened() { openHandles.incrementAndGet() }
    fun handleClosed() { openHandles.decrementAndGet() }

    private fun smbConfig(): SmbConfig = SmbConfig.builder()
        .withDialects(
            // Guest/anonymous sessions have no session key, which SMB 3.x signing/encryption key derivation needs.
            *if (config.anonymous) arrayOf(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
            else arrayOf(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
        )
        .withSecurityProvider(BCSecurityProvider())
        .withTimeout(30, TimeUnit.SECONDS)
        .withSoTimeout(45, TimeUnit.SECONDS)
        .withReadBufferSize(1 shl 20)
        .withSigningEnabled(!config.anonymous)
        .build()

    @Synchronized
    fun share(): DiskShare {
        if (config.share.isBlank()) throw SourceException.ShareMissing()
        val now = System.currentTimeMillis()
        val current = share
        if (current != null && current.isConnected && connection?.isConnected == true &&
            (now - lastUsed < idleMillis || openHandles.get() > 0)) {
            lastUsed = now
            return current
        }
        closeQuietly()
        try {
            val c = SMBClient(smbConfig())
            client = c
            val conn = c.connect(config.host, config.effectivePort)
            connection = conn
            val auth = when {
                config.anonymous && config.username.isBlank() -> AuthenticationContext.anonymous()
                config.anonymous -> AuthenticationContext.guest()
                else -> AuthenticationContext(config.username, (password ?: "").toCharArray(), config.domain.ifBlank { null })
            }
            val s = conn.authenticate(auth)
            session = s
            val sh = s.connectShare(config.share) as? DiskShare ?: throw SourceException.ShareNotFound("${config.share} is not a disk share")
            share = sh
            lastUsed = now
            return sh
        } catch (e: Exception) {
            closeQuietly()
            throw SmbErrors.map(e, "connect")
        }
    }

    fun touch() { lastUsed = System.currentTimeMillis() }

    @Synchronized
    fun closeQuietly() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close(true) }
        runCatching { client?.close() }
        share = null; session = null; connection = null; client = null
    }

    override fun close() = closeQuietly()
}

internal object SmbErrors {
    fun map(e: Throwable, what: String): IOException {
        if (e is SourceException) return e
        if (e is InterruptedException) throw e
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is SMBApiException -> return when (t.status) {
                    NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
                    NtStatus.STATUS_PASSWORD_EXPIRED, NtStatus.STATUS_ACCOUNT_DISABLED -> SourceException.AuthFailed("$what: ${t.status}", t)
                    NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_BAD_NETWORK_PATH -> SourceException.ShareNotFound("$what: ${t.status}", t)
                    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
                    NtStatus.STATUS_OBJECT_NAME_INVALID -> SourceException.NotFound("$what: ${t.status}", t)
                    NtStatus.STATUS_ACCESS_DENIED -> SourceException.PermissionDenied("$what: ${t.status}", t)
                    else -> SourceException.Protocol("$what: ${t.status}", t)
                }
                is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException ->
                    return SourceException.Unreachable("$what: ${t.javaClass.simpleName}", t)
            }
            t = t.cause
        }
        if (e is TransportException) return SourceException.Unreachable("$what: transport ${e.message}", e)
        return if (e is IOException) e else SourceException.Protocol("$what: ${e.message}", e)
    }
}

class SmbFileSystem(override val config: SourceConfig, credentials: CredentialStore) : SourceFileSystem {
    private val manager = SmbConnectionManager(config, credentials.get(config.effectiveCredentialRef))

    override val capabilities = setOf(SourceCapability.WRITE, SourceCapability.RANDOM_ACCESS)

    /** Share-relative path with backslashes, no leading separator. */
    private fun smbPath(path: String): String = SourcePath.join(config.rootPath, path).removePrefix("/").replace('/', '\\')

    private inline fun <T> withShare(what: String, block: (DiskShare) -> T): T {
        var attempt = 0
        while (true) {
            val share = manager.share()
            try {
                return block(share).also { manager.touch() }
            } catch (e: Exception) {
                val mapped = SmbErrors.map(e, what)
                // A dropped TCP connection surfaces as transport error: reconnect once.
                if (mapped is SourceException.Unreachable && attempt == 0) {
                    attempt++
                    manager.closeQuietly()
                    continue
                }
                throw mapped
            }
        }
    }

    override fun list(path: String): List<MusicFile> = withShare("list") { share ->
        val parent = SourcePath.normalize(path)
        share.list(smbPath(path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val dir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                MusicFile(
                    name = it.fileName,
                    path = SourcePath.child(parent, it.fileName),
                    isDirectory = dir,
                    size = if (dir) 0 else it.endOfFile,
                    lastModified = it.lastWriteTime.toEpochMillis(),
                    sourceId = config.id,
                )
            }
    }

    override fun stat(path: String): MusicFile? = try {
        withShare("stat") { share ->
            val info = share.getFileInformation(smbPath(path))
            val dir = info.standardInformation.isDirectory
            val p = SourcePath.normalize(path)
            MusicFile(SourcePath.name(p), p, dir, if (dir) 0 else info.standardInformation.endOfFile,
                info.basicInformation.lastWriteTime.toEpochMillis(), config.id)
        }
    } catch (e: SourceException.NotFound) {
        null
    }

    private fun openFile(share: DiskShare, path: String): File = share.openFile(
        smbPath(path),
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
    )

    override fun openRead(path: String, offset: Long, length: Long): SourceInput {
        val file = withShare("open") { openFile(it, path) }
        val size = try { file.fileInformation.standardInformation.endOfFile } catch (e: Exception) { file.close(); throw SmbErrors.map(e, "stat") }
        val available = (size - offset).coerceAtLeast(0)
        val len = if (length < 0) available else minOf(length, available)
        manager.handleOpened()
        return SmbInput(file, offset, len)
    }

    /** Bounded read-ahead: one chunk (max 256 KiB) is buffered; seeking means opening a new input. */
    private inner class SmbInput(private val file: File, private var position: Long, override val length: Long) : SourceInput() {
        private var remaining = length
        private val buf = ByteArray(256 * 1024)
        private var bufPos = 0
        private var bufLen = 0
        private var closed = false

        private fun fill(): Boolean {
            if (remaining <= 0) return false
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = try { file.read(buf, position, 0, want) } catch (e: Exception) { throw SmbErrors.map(e, "read") }
            manager.touch()
            if (n <= 0) throw SourceException.PrematureEof(length, length - remaining)
            position += n
            bufPos = 0
            bufLen = n
            return true
        }

        override fun read(): Int {
            if (bufPos >= bufLen && !fill()) return -1
            remaining--
            return buf[bufPos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (bufPos >= bufLen && !fill()) return -1
            val n = minOf(len, bufLen - bufPos)
            System.arraycopy(buf, bufPos, b, off, n)
            bufPos += n
            remaining -= n
            return n
        }

        override fun available(): Int = bufLen - bufPos

        override fun close() {
            if (closed) return
            closed = true
            runCatching { file.close() }
            manager.handleClosed()
        }
    }

    override fun openRandomAccess(path: String): RandomAccessReader {
        val file = withShare("open") { openFile(it, path) }
        val size = try { file.fileInformation.standardInformation.endOfFile } catch (e: Exception) { file.close(); throw SmbErrors.map(e, "stat") }
        manager.handleOpened()
        return object : RandomAccessReader {
            override val size: Long = size
            override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
                if (position >= size) return -1
                val n = try { file.read(buffer, position, offset, len) } catch (e: Exception) { throw SmbErrors.map(e, "read") }
                manager.touch()
                return if (n <= 0) -1 else n
            }
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                runCatching { file.close() }
                manager.handleClosed()
            }
        }
    }

    override fun write(path: String, data: ByteArray, overwrite: Boolean) {
        withShare("write") { share ->
            val target = smbPath(path)
            if (!overwrite && share.fileExists(target)) throw java.nio.file.FileAlreadyExistsException(path)
            share.openFile(
                target,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                if (overwrite) SMB2CreateDisposition.FILE_OVERWRITE_IF else SMB2CreateDisposition.FILE_CREATE,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { f -> f.write(data, 0) }
        }
    }

    override fun rename(from: String, to: String): Boolean = withShare("rename") { share ->
        share.openFile(smbPath(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null).use { it.rename(smbPath(to), true) }
        true
    }

    override fun delete(path: String): Boolean = withShare("delete") { share -> share.rm(smbPath(path)); true }

    override fun testConnection(): ConnectionTestResult {
        if (config.host.isBlank()) return ConnectionTestResult(ConnectionOutcome.INVALID_CONFIG, "host is empty")
        if (config.share.isBlank()) return ConnectionTestResult(ConnectionOutcome.SHARE_MISSING, "share is empty")
        return super.testConnection()
    }

    override fun close() = manager.close()
}
