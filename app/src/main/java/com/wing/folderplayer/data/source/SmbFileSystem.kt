package com.wing.folderplayer.data.source

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2Packet
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.messages.SMB2Echo
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.wing.folderplayer.playback.PlaybackEventKind
import com.wing.folderplayer.playback.PlaybackLog
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** SMB client settings; guest / anonymous sessions have no session key, which SMB 3.x signing and encryption need, so they use SMB 2.x. */
internal fun smbClientConfig(anonymous: Boolean, timeoutSeconds: Long = 30, soTimeoutSeconds: Long = 45): SmbConfig = SmbConfig.builder()
    .withDialects(
        *if (anonymous) arrayOf(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
        else arrayOf(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
    )
    .withSecurityProvider(BCSecurityProvider())
    .withTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .withSoTimeout(soTimeoutSeconds, TimeUnit.SECONDS)
    .withReadBufferSize(1 shl 20)
    .withSigningEnabled(!anonymous)
    .build()

internal fun smbAuthentication(username: String, password: String?, domain: String, anonymous: Boolean): AuthenticationContext = when {
    anonymous && username.isBlank() -> AuthenticationContext.anonymous()
    anonymous -> AuthenticationContext.guest()
    else -> AuthenticationContext(username, (password ?: "").toCharArray(), domain.ifBlank { null })
}

/**
 * One SMB2/3 connection + session + share per source, shared by everything that reads the source at once (browser,
 * player, scans, covers) and kept until it fails or the source's connection settings change. SMB1 is not offered
 * (SMBJ does not implement it).
 *
 * A connection is always closed at once, without the tree disconnect / logoff, which wait for answers a dead connection
 * never gives (a failed read took 75 s instead of 30 s). A connection unused for [probeAfterMillis] may have died
 * silently (Wi-Fi power save, a phone that slept): an echo with a short timeout tells before it is used again, instead
 * of the first request waiting 30 s on it.
 */
class SmbConnectionManager(
    private val config: SourceConfig,
    private val password: String?,
    private val probeAfterMillis: Long = 10_000L,
) : java.io.Closeable {
    private class Link(val client: SMBClient, val connection: Connection, val share: DiskShare) {
        @Volatile var lastAnswer = System.currentTimeMillis()
    }

    @Volatile private var link: Link? = null

    @Synchronized
    fun share(): DiskShare {
        if (config.share.isBlank()) throw SourceException.ShareMissing()
        link?.let { l -> deadReason(l)?.let { drop(l.share, it) } ?: return l.share }
        val start = System.nanoTime()
        val client = SMBClient(smbClientConfig(config.anonymous))
        var connection: Connection? = null
        try {
            connection = client.connect(config.host, config.effectivePort)
            val session = connection.authenticate(smbAuthentication(config.username, password, config.domain, config.anonymous))
            val share = session.connectShare(config.share) as? DiskShare ?: throw SourceException.ShareNotFound("${config.share} is not a disk share")
            link = Link(client, connection, share)
            log("connected to ${config.host}/${config.share} in ${(System.nanoTime() - start) / 1_000_000} ms")
            return share
        } catch (e: Exception) {
            runCatching { connection?.close(true) }
            runCatching { client.close() }
            throw SmbErrors.map(e, "connect")
        }
    }

    /** Why [l] cannot be used any more, or null when it can. */
    private fun deadReason(l: Link): String? {
        if (!l.connection.isConnected || !l.share.isConnected) return "closed by the server or the network"
        val idle = System.currentTimeMillis() - l.lastAnswer
        if (idle < probeAfterMillis) return null
        return try {
            l.connection.send<SMB2Packet>(SMB2Echo(l.connection.negotiatedProtocol.dialect)).get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            l.lastAnswer = System.currentTimeMillis()
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            "no answer to an echo after ${idle / 1000} s unused"
        }
    }

    /** The server answered a request on the current connection. */
    fun touch() { link?.lastAnswer = System.currentTimeMillis() }

    /**
     * Closes the connection [failed] belongs to, if it is still the current one: a request that failed on a connection
     * already replaced must not close the new one, which the others are using.
     */
    @Synchronized
    fun drop(failed: DiskShare, reason: String) {
        val l = link?.takeIf { it.share === failed } ?: return
        link = null
        runCatching { l.connection.close(true) }
        runCatching { l.client.close() }
        log("dropped: $reason")
    }

    private fun log(what: String) = PlaybackLog.record(PlaybackEventKind.CONNECTION, "SMB ${config.name}: $what")

    @Synchronized
    override fun close() {
        link?.let { drop(it.share, "closed") }
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 3L
    }
}

internal object SmbErrors {
    /**
     * Maps an SMBJ failure to a [SourceException]. A request whose thread was interrupted (a cancelled coroutine, the
     * player's loader stopping a read) throws [InterruptedException]: SMBJ wraps it in a [TransportException], which
     * would otherwise read as a lost connection and close the connection the others are using.
     */
    fun map(e: Throwable, what: String): IOException {
        if (e is SourceException) return e
        if (interrupted(e)) throw InterruptedException("$what interrupted")
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
                // A request without an answer within the timeout (a server or Wi-Fi that stopped answering), wrapped by SMBJ.
                is TransportException -> return SourceException.Unreachable("$what: transport ${t.message}", t)
                is java.util.concurrent.TimeoutException -> return SourceException.Unreachable("$what: timeout", t)
            }
            t = t.cause
        }
        if (e is TransportException) return SourceException.Unreachable("$what: transport ${e.message}", e)
        return if (e is IOException) e else SourceException.Protocol("$what: ${e.message}", e)
    }

    private fun interrupted(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(10).any {
        it is InterruptedException || it is java.nio.channels.ClosedByInterruptException ||
            (it is java.io.InterruptedIOException && it !is SocketTimeoutException)
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
                    manager.drop(share, "$what: ${mapped.message}")
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
                    createdAt = it.creationTime.toEpochMillis(),
                )
            }
    }

    override fun stat(path: String): MusicFile? = try {
        withShare("stat") { share ->
            val info = share.getFileInformation(smbPath(path))
            val dir = info.standardInformation.isDirectory
            val p = SourcePath.normalize(path)
            MusicFile(SourcePath.name(p), p, dir, if (dir) 0 else info.standardInformation.endOfFile,
                info.basicInformation.lastWriteTime.toEpochMillis(), config.id,
                info.basicInformation.creationTime.toEpochMillis())
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

    /**
     * A read that got no answer leaves a connection that still looks open: the next open would wait on it for the whole
     * timeout before connecting again (60 s instead of 30 s per retry of the player, `SmbStallTest`). It is dropped.
     * An interrupted read (the player stopping its loader) is an [InterruptedIOException] for the player.
     */
    private fun readFailed(e: Exception, file: File): IOException = try {
        SmbErrors.map(e, "read").also { if (it is SourceException.Unreachable) manager.drop(file.diskShare, "read: ${it.message}") }
    } catch (_: InterruptedException) {
        InterruptedIOException("read interrupted")
    }

    override fun openRead(path: String, offset: Long, length: Long): SourceInput {
        val file = withShare("open") { openFile(it, path) }
        val size = try { file.fileInformation.standardInformation.endOfFile } catch (e: Exception) { file.close(); throw SmbErrors.map(e, "stat") }
        val available = (size - offset).coerceAtLeast(0)
        val len = if (length < 0) available else minOf(length, available)
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
            val n = try { file.read(buf, position, 0, want) } catch (e: Exception) { throw readFailed(e, file) }
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
        }
    }

    override fun openRandomAccess(path: String): RandomAccessReader {
        val file = withShare("open") { openFile(it, path) }
        val size = try { file.fileInformation.standardInformation.endOfFile } catch (e: Exception) { file.close(); throw SmbErrors.map(e, "stat") }
        return object : RandomAccessReader {
            override val size: Long = size
            override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
                if (position >= size) return -1
                val n = try { file.read(buffer, position, offset, len) } catch (e: Exception) { throw readFailed(e, file) }
                manager.touch()
                return if (n <= 0) -1 else n
            }
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                runCatching { file.close() }
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
