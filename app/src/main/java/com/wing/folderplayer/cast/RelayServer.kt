package com.wing.folderplayer.cast

import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourceRef
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device HTTP relay for DLNA renderers. It runs only while a cast session is active, serves exactly the
 * tracks registered for the session under unguessable tokens (`/audio/<token>/<name>`), reads them through the
 * source file system (so SMB/FTP/WebDAV credentials never reach the renderer) and answers Range requests with
 * 200 / 206 / 416 plus Content-Length. Arbitrary paths are never accepted.
 */
class RelayServer(
    private val resolve: (SourceRef) -> SourceFileSystem,
    private val preferredPort: Int = DEFAULT_PORT,
) {
    data class Entry(val ref: SourceRef, val size: Long, val mime: String)

    private val tokens = ConcurrentHashMap<String, Entry>()
    /** Requests answered for registered tokens (diagnostics / tests). */
    val servedRequests = java.util.concurrent.atomic.AtomicInteger()
    private val random = SecureRandom()
    @Volatile private var engine: ApplicationEngine? = null
    @Volatile var port: Int = 0
        private set

    val isRunning: Boolean get() = engine != null

    @Synchronized
    fun start(): Int {
        engine?.let { return port }
        for (candidate in listOf(preferredPort, 0)) {
            try {
                val e = embeddedServer(CIO, port = candidate, host = "0.0.0.0") {
                    routing {
                        get("/audio/{token}/{name}") { serve(call, head = false) }
                        head("/audio/{token}/{name}") { serve(call, head = true) }
                    }
                }
                e.start(wait = false)
                port = kotlinx.coroutines.runBlocking { e.resolvedConnectors().first().port }
                engine = e
                return port
            } catch (t: Exception) {
                if (candidate == 0) throw t
            }
        }
        throw IllegalStateException("relay could not start")
    }

    /** Registers a track and returns its URL path (append to http://<lan-ip>:<port>). */
    fun register(ref: SourceRef): String {
        val fs = resolve(ref)
        val st = fs.stat(ref.path) ?: throw SourceException.NotFound(ref.path)
        val bytes = ByteArray(18).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tokens[token] = Entry(ref, st.size, HttpRange.mimeFor(ref.name))
        val safeName = "track." + ref.name.substringAfterLast('.', "bin").lowercase().filter { it.isLetterOrDigit() }
        return "/audio/$token/$safeName"
    }

    fun revokeAll() = tokens.clear()

    @Synchronized
    fun stop() {
        tokens.clear()
        engine?.stop(200, 1000)
        engine = null
        port = 0
    }

    private suspend fun serve(call: io.ktor.server.application.ApplicationCall, head: Boolean) {
        val token = call.parameters["token"] ?: return call.respond(HttpStatusCode.NotFound)
        val entry = tokens[token] ?: return call.respond(HttpStatusCode.NotFound)
        servedRequests.incrementAndGet()
        val range = HttpRange.evaluate(call.request.header(HttpHeaders.Range), entry.size)
        val type = ContentType.parse(entry.mime)
        val common = listOf(HttpHeaders.AcceptRanges to "bytes", "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        when (range) {
            RangeResult.Unsatisfiable -> call.respond(object : OutgoingContent.NoContent() {
                override val status = HttpStatusCode.RequestedRangeNotSatisfiable
                override val headers: Headers = headersOf(HttpHeaders.ContentRange to listOf("bytes */${entry.size}"))
                override val contentLength = 0L
            })
            else -> {
                val (start, len, status, extra) = when (range) {
                    is RangeResult.Partial -> Quad(range.start, range.length, HttpStatusCode.PartialContent,
                        listOf(HttpHeaders.ContentRange to "bytes ${range.start}-${range.endInclusive}/${entry.size}"))
                    else -> Quad(0L, entry.size, HttpStatusCode.OK, emptyList())
                }
                val hdrs = Headers.build { (common + extra).forEach { (k, v) -> append(k, v) } }
                if (head) {
                    call.respond(object : OutgoingContent.NoContent() {
                        override val status = status
                        override val headers = hdrs
                        override val contentLength = len
                        override val contentType = type
                    })
                } else {
                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val status = status
                        override val headers = hdrs
                        override val contentLength = len
                        override val contentType = type
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            val fs = resolve(entry.ref)
                            val input = runInterruptible(Dispatchers.IO) { fs.openRead(entry.ref.path, start, len) }
                            try {
                                val buf = ByteArray(64 * 1024)
                                var left = len
                                while (left > 0) {
                                    val n = runInterruptible(Dispatchers.IO) { input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()) }
                                    if (n < 0) break
                                    channel.writeFully(buf, 0, n)
                                    left -= n
                                }
                            } finally {
                                runInterruptible(Dispatchers.IO) { input.close() }
                            }
                        }
                    })
                }
            }
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        /** Fixed default port; a random port is used if it is taken. */
        const val DEFAULT_PORT = 47651
    }
}
