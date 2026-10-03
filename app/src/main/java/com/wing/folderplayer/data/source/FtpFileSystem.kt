package com.wing.folderplayer.data.source

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds logged-in Commons Net clients for one source. FTPS is explicit TLS (AUTH TLS, PBSZ 0, PROT P) and is never
 * downgraded to plain FTP. The server certificate is validated against the system trust store plus host name, unless
 * the user explicitly pinned the SHA-256 fingerprint of a self-signed certificate.
 */
class FtpConnectionFactory(private val config: SourceConfig, private val password: String?) {

    fun connect(): FTPClient {
        val client: FTPClient = if (config.useTls) {
            FTPSClient("TLS", false).apply {
                trustManager = trustManager()
                if (config.tlsPinnedSha256.isBlank()) {
                    hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                    isEndpointCheckingEnabled = true
                }
            }
        } else {
            FTPClient()
        }
        client.controlEncoding = "UTF-8"
        client.connectTimeout = 15_000
        client.defaultTimeout = 30_000
        client.setDataTimeout(Duration.ofSeconds(30))
        try {
            client.connect(config.host, config.effectivePort)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw SourceException.Unreachable("server refused connection: ${client.replyString?.trim()}")
            }
            client.soTimeout = 30_000
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            val user = if (config.anonymous && config.username.isBlank()) "anonymous" else config.username
            val pass = if (config.anonymous && password.isNullOrEmpty()) "folderplayer@" else (password ?: "")
            if (!client.login(user, pass)) {
                throw SourceException.AuthFailed("login rejected: ${client.replyString?.trim()}")
            }
            // Ignore failure: servers without the feature already speak UTF-8 or a fixed charset.
            runCatching { client.sendCommand("OPTS", "UTF8 ON") }
            client.enterLocalPassiveMode()
            if (!client.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw SourceException.Protocol("TYPE I rejected: ${client.replyString?.trim()}")
            }
            return client
        } catch (e: Exception) {
            runCatching { if (client.isConnected) client.disconnect() }
            throw FtpErrors.map(e, "connect")
        }
    }

    private fun trustManager(): X509TrustManager {
        val pin = config.tlsPinnedSha256.replace(":", "").replace(" ", "").lowercase()
        if (pin.isNotEmpty()) {
            // Explicit user trust for one self-signed certificate: the leaf must match the pinned
            // SHA-256 exactly; anything else is rejected. This is not a trust-all manager.
            @android.annotation.SuppressLint("CustomX509TrustManager")
            val pinned = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                    throw CertificateException("client auth not supported")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
                    val actual = sha256Hex(leaf.encoded)
                    if (actual != pin) throw CertificateException("certificate fingerprint $actual does not match the pinned value")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return pinned
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

internal object FtpErrors {
    private val PERMISSION_WORDS = listOf("permission", "privilege", "denied", "not allowed", "forbidden")
    private val NOT_FOUND_WORDS = listOf("no such", "not found", "does not exist", "doesn't exist", "cannot find")

    fun map(e: Throwable, what: String): IOException {
        if (e is SourceException) return e
        if (e is InterruptedException) throw e
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is CertificateException, is javax.net.ssl.SSLHandshakeException, is javax.net.ssl.SSLPeerUnverifiedException ->
                    return SourceException.TlsFailure("$what: ${t.message}", t)
                is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException ->
                    return SourceException.Unreachable("$what: ${t.javaClass.simpleName}", t)
            }
            t = t.cause
        }
        if (e is SSLException) return SourceException.TlsFailure("$what: ${e.message}", e)
        return if (e is IOException) e else SourceException.Protocol("$what: ${e.message}", e)
    }

    /** Maps a negative reply after a command. */
    fun reply(client: FTPClient, what: String): SourceException {
        val code = client.replyCode
        val text = client.replyString?.trim().orEmpty()
        return when {
            code == 530 -> SourceException.AuthFailed("$what: $text")
            code == 550 && PERMISSION_WORDS.any { text.contains(it, true) } -> SourceException.PermissionDenied("$what: $text")
            code == 550 || code == 450 || NOT_FOUND_WORDS.any { text.contains(it, true) } -> SourceException.NotFound("$what: $text")
            code == 553 || code == 532 -> SourceException.PermissionDenied("$what: $text")
            code == 421 || code == 425 || code == 426 -> SourceException.Unreachable("$what: $text")
            else -> SourceException.Protocol("$what: $text")
        }
    }
}

class FtpFileSystem(override val config: SourceConfig, credentials: CredentialStore) : SourceFileSystem {
    private val factory = FtpConnectionFactory(config, credentials.get(config.effectiveCredentialRef))
    private val idle = ArrayDeque<FTPClient>()
    @Volatile private var mlsd: Boolean? = null

    override val capabilities = setOf(SourceCapability.WRITE)

    private fun serverPath(path: String) = SourcePath.join(config.rootPath, path)

    /** A client is used by exactly one operation/transfer at a time. */
    private fun borrow(): FTPClient {
        while (true) {
            val c = synchronized(idle) { idle.removeFirstOrNull() } ?: break
            val alive = try { c.isConnected && c.sendNoOp() } catch (e: IOException) { false }
            if (alive) return c
            runCatching { c.disconnect() }
        }
        return factory.connect().also { c ->
            if (mlsd == null) mlsd = runCatching { c.hasFeature("MLST") }.getOrDefault(false)
        }
    }

    private fun giveBack(c: FTPClient) {
        synchronized(idle) {
            if (idle.size < 2 && c.isConnected) { idle.addLast(c); return }
        }
        discard(c)
    }

    private fun discard(c: FTPClient) {
        runCatching { if (c.isConnected) { c.logout(); c.disconnect() } }
    }

    private inline fun <T> withClient(what: String, block: (FTPClient) -> T): T {
        val c = borrow()
        try {
            return block(c).also { giveBack(c) }
        } catch (e: Exception) {
            discard(c)
            throw FtpErrors.map(e, what)
        }
    }

    private fun toMusicFile(parent: String, f: FTPFile): MusicFile = MusicFile(
        name = f.name,
        path = SourcePath.child(parent, f.name),
        isDirectory = f.isDirectory,
        size = if (f.isDirectory) 0 else f.size.coerceAtLeast(0),
        lastModified = f.timestamp?.timeInMillis ?: 0,
        sourceId = config.id,
    )

    override fun list(path: String): List<MusicFile> = withClient("list") { c ->
        val parent = SourcePath.normalize(path)
        val files = if (mlsd == true) c.mlistDir(serverPath(path)) else c.listFiles(serverPath(path))
        if (!FTPReply.isPositiveCompletion(c.replyCode)) throw FtpErrors.reply(c, "list $path")
        files.filterNotNull()
            .filter { it.name != "." && it.name != ".." && it.name.isNotEmpty() && !it.name.contains('/') }
            .map { toMusicFile(parent, it) }
    }

    override fun stat(path: String): MusicFile? {
        val p = SourcePath.normalize(path)
        if (p == SourcePath.ROOT) return MusicFile("", p, true, 0, 0, config.id)
        if (mlsd == true) {
            val f = withClient("stat") { c -> c.mlistFile(serverPath(p)) } ?: return null
            return MusicFile(SourcePath.name(p), p, f.isDirectory, if (f.isDirectory) 0 else f.size, f.timestamp?.timeInMillis ?: 0, config.id)
        }
        val parent = SourcePath.parent(p) ?: SourcePath.ROOT
        return try {
            list(parent).firstOrNull { it.name == SourcePath.name(p) }
        } catch (e: SourceException.NotFound) {
            null
        }
    }

    override fun openRead(path: String, offset: Long, length: Long): SourceInput {
        val c = borrow()
        try {
            val sp = serverPath(path)
            val size = c.getSize(sp)?.trim()?.toLongOrNull() ?: -1L
            if (size < 0 && c.replyCode == 550) throw FtpErrors.reply(c, "size $path")
            if (offset > 0) {
                val code = c.rest(offset.toString())
                if (code != FTPReply.FILE_ACTION_PENDING) throw SourceException.SeekUnsupported("REST $offset rejected: ${c.replyString?.trim()}")
            }
            val stream = c.retrieveFileStream(sp) ?: throw FtpErrors.reply(c, "RETR $path")
            val available = if (size >= 0) (size - offset).coerceAtLeast(0) else -1L
            val len = when {
                length < 0 -> available
                available < 0 -> length
                else -> minOf(length, available)
            }
            return FtpInput(c, stream, len, expectedToEof = length < 0 || (available in 0..length))
        } catch (e: Exception) {
            discard(c)
            throw FtpErrors.map(e, "open $path")
        }
    }

    /**
     * RETR data stream. Reaching the end before the announced size is reported as [SourceException.PrematureEof],
     * never as a normal end of file. Closing early aborts the transfer and drops the control connection instead of
     * reusing it in an unknown state.
     */
    private inner class FtpInput(
        private val client: FTPClient,
        private val stream: InputStream,
        override val length: Long,
        private val expectedToEof: Boolean,
    ) : SourceInput() {
        private var count = 0L
        private var finished = false
        private var closed = false

        private fun limit(len: Int): Int = if (length >= 0) minOf(len.toLong(), length - count).toInt() else len

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (length >= 0 && count >= length) { finished = expectedToEof; return -1 }
            val n = try { stream.read(b, off, limit(len)) } catch (e: Exception) { throw FtpErrors.map(e, "read") }
            if (n < 0) {
                if (length >= 0 && count < length) throw SourceException.PrematureEof(length, count)
                finished = true
                return -1
            }
            count += n
            return n
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { stream.close() }
            if (finished || (length >= 0 && count >= length && expectedToEof)) {
                val ok = try { client.completePendingCommand() } catch (e: IOException) { false }
                if (ok) giveBack(client) else discard(client)
            } else {
                runCatching { client.abort() }
                discard(client)
            }
        }
    }

    override fun write(path: String, data: ByteArray, overwrite: Boolean) = withClient("write") { c ->
        val sp = serverPath(path)
        if (!overwrite) {
            val exists = c.getSize(sp) != null
            if (exists) throw java.nio.file.FileAlreadyExistsException(path)
        }
        if (!c.storeFile(sp, ByteArrayInputStream(data))) throw FtpErrors.reply(c, "STOR $path")
    }

    override fun rename(from: String, to: String): Boolean = withClient("rename") { c -> c.rename(serverPath(from), serverPath(to)) }

    override fun delete(path: String): Boolean = withClient("delete") { c -> c.deleteFile(serverPath(path)) }

    override fun testConnection(): ConnectionTestResult {
        if (config.host.isBlank()) return ConnectionTestResult(ConnectionOutcome.INVALID_CONFIG, "host is empty")
        return super.testConnection()
    }

    override fun close() {
        val all = synchronized(idle) { idle.toList().also { idle.clear() } }
        all.forEach { discard(it) }
    }
}
