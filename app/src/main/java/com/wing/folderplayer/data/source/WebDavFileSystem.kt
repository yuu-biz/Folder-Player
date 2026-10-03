package com.wing.folderplayer.data.source

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV over OkHttp. Credentials come from this source only and are sent only to the configured host
 * (redirects to other hosts, e.g. cloud direct links, get no Authorization header).
 */
class WebDavFileSystem(
    override val config: SourceConfig,
    credentials: CredentialStore,
    baseClient: OkHttpClient = sharedClient,
) : SourceFileSystem {
    private val password = credentials.get(config.effectiveCredentialRef)
    private val base: HttpUrl = normalizeBaseUrl(config.url).toHttpUrlOrNull()
        ?: throw IllegalArgumentException("invalid WebDAV URL")

    /**
     * Cookies belong to this source only (the registry keeps one instance per source and rebuilds it when the
     * connection or credentials change): two accounts on the same server never share a session cookie.
     */
    val client: OkHttpClient = baseClient.newBuilder()
        .cookieJar(okhttp3.JavaNetCookieJar(java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER)))
        .authenticator { _, response ->
            val u = response.request.url
            if (u.host != base.host || u.port != base.port || u.scheme != base.scheme) return@authenticator null
            if (config.username.isEmpty() && password.isNullOrEmpty()) return@authenticator null
            if (response.priorCount() >= 2) return@authenticator null
            response.request.newBuilder().header("Authorization", Credentials.basic(config.username, password ?: "", Charsets.UTF_8)).build()
        }
        .build()

    override val capabilities = setOf(SourceCapability.WRITE)

    /** Decoded path segments of base URL + configured root. */
    private val rootSegments: List<String> =
        base.pathSegments.filter { it.isNotEmpty() } + SourcePath.segments(config.rootPath)

    fun url(path: String, directory: Boolean = false): HttpUrl {
        val b = base.newBuilder().encodedPath("/")
        (rootSegments + SourcePath.segments(path)).forEach { b.addPathSegment(it) }
        if (directory) b.addPathSegment("")
        return b.build()
    }

    /** Authorization header for this source when the URL is on the configured host (used by the DLNA relay check only). */
    fun authHeaderFor(url: HttpUrl): String? =
        if (url.host == base.host && url.port == base.port && (config.username.isNotEmpty() || !password.isNullOrEmpty()))
            Credentials.basic(config.username, password ?: "", Charsets.UTF_8) else null

    private fun Response.priorCount(): Int {
        var n = 0
        var r = priorResponse
        while (r != null) { n++; r = r.priorResponse }
        return n
    }

    private fun execute(request: Request, what: String): Response {
        val resp = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw mapIo(e, what)
        }
        if (resp.isSuccessful || resp.code == 207) return resp
        resp.close()
        throw httpError(resp.code, what)
    }

    private fun httpError(code: Int, what: String): SourceException = when (code) {
        401 -> SourceException.AuthFailed("$what: HTTP 401")
        403 -> SourceException.PermissionDenied("$what: HTTP 403")
        404, 410 -> SourceException.NotFound("$what: HTTP $code")
        405 -> SourceException.ReadOnly("$what: HTTP 405")
        else -> SourceException.Protocol("$what: HTTP $code")
    }

    private fun mapIo(e: IOException, what: String): IOException = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException ->
            SourceException.Unreachable("$what: ${e.javaClass.simpleName}", e)
        is javax.net.ssl.SSLException -> SourceException.TlsFailure("$what: ${e.message}", e)
        else -> e
    }

    private fun propfind(path: String, depth: Int, directory: Boolean): List<MusicFile> {
        val req = Request.Builder()
            .url(url(path, directory))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth.toString())
            .build()
        execute(req, "PROPFIND $path").use { resp ->
            val body = resp.body?.bytes() ?: return emptyList()
            return parseMultistatus(body, SourcePath.normalize(path))
        }
    }

    override fun list(path: String): List<MusicFile> {
        val dir = SourcePath.normalize(path)
        return propfind(path, 1, true).filter { it.path != dir }
    }

    override fun stat(path: String): MusicFile? = try {
        val p = SourcePath.normalize(path)
        val entries = propfind(path, 0, false)
        entries.firstOrNull { it.path == p } ?: entries.firstOrNull()?.copy(path = p, name = SourcePath.name(p))
    } catch (e: SourceException.NotFound) {
        null
    }

    internal fun parseMultistatus(xml: ByteArray, requested: String): List<MusicFile> {
        if (SafeXml.hasDoctype(xml)) throw SourceException.Protocol("PROPFIND response with DOCTYPE rejected")
        val doc = SafeXml.parse(xml)
        val out = ArrayList<MusicFile>()
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: continue
            val rel = hrefToPath(href) ?: continue
            val isDir = r.getElementsByTagNameNS("DAV:", "collection").length > 0
            val size = r.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0
            val modified = r.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)?.textContent?.trim()?.let(::parseDate) ?: 0
            out.add(MusicFile(SourcePath.name(rel), rel, isDir || rel == SourcePath.ROOT, if (isDir) 0 else size, modified, config.id))
        }
        return out
    }

    /** href (absolute URL or absolute path, percent-encoded once) → source-relative path, or null if outside root. */
    internal fun hrefToPath(href: String): String? {
        val encodedPath = if (href.startsWith("http://") || href.startsWith("https://")) {
            val u = href.toHttpUrlOrNull() ?: return null
            if (u.host != base.host) return null
            u.encodedPath
        } else href.substringBefore('?')
        val segs = encodedPath.split('/').filter { it.isNotEmpty() }.map { SourceUris.decode(it) ?: return null }
        if (segs.size < rootSegments.size) return null
        // Some NAS servers echo the root with different letter case.
        for (i in rootSegments.indices) if (!segs[i].equals(rootSegments[i], ignoreCase = true)) return null
        val rest = segs.drop(rootSegments.size)
        if (rest.any { it == "." || it == ".." }) return null
        return if (rest.isEmpty()) SourcePath.ROOT else rest.joinToString("/", prefix = "/")
    }

    override fun openRead(path: String, offset: Long, length: Long): SourceInput {
        val b = Request.Builder().url(url(path)).header("Accept-Encoding", "identity")
        if (offset > 0 || length >= 0) {
            b.header("Range", if (length >= 0) "bytes=$offset-${offset + length - 1}" else "bytes=$offset-")
        }
        val resp = try { client.newCall(b.build()).execute() } catch (e: IOException) { throw mapIo(e, "GET $path") }
        if (resp.code == 416) {
            resp.close()
            throw java.io.EOFException("range not satisfiable")
        }
        if (!resp.isSuccessful) {
            resp.close()
            throw httpError(resp.code, "GET $path")
        }
        val body = resp.body ?: run { resp.close(); throw SourceException.Protocol("empty body") }
        val stream = body.byteStream()
        val bodyLen = body.contentLength()
        // Server ignored Range: skip in-stream (slow but correct).
        if (resp.code == 200 && offset > 0) {
            var toSkip = offset
            val tmp = ByteArray(64 * 1024)
            while (toSkip > 0) {
                val n = stream.read(tmp, 0, minOf(tmp.size.toLong(), toSkip).toInt())
                if (n < 0) { resp.close(); throw java.io.EOFException("offset beyond end") }
                toSkip -= n
            }
        }
        val available = when {
            resp.code == 206 -> bodyLen
            bodyLen >= 0 -> bodyLen - offset
            else -> -1
        }
        val len = if (length >= 0 && available >= 0) minOf(length, available) else if (length >= 0) length else available
        return object : SourceInput() {
            var count = 0L
            override val length: Long = len
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, l: Int): Int {
                if (len >= 0 && count >= len) return -1
                val want = if (len >= 0) minOf(l.toLong(), len - count).toInt() else l
                val n = try { stream.read(b, off, want) } catch (e: IOException) { throw mapIo(e, "read") }
                if (n < 0) {
                    if (len >= 0 && count < len) throw SourceException.PrematureEof(len, count)
                    return -1
                }
                count += n
                return n
            }
            override fun close() = resp.close()
        }
    }

    override fun write(path: String, data: ByteArray, overwrite: Boolean) {
        val b = Request.Builder().url(url(path)).put(data.toRequestBody("application/octet-stream".toMediaType()))
        if (!overwrite) b.header("If-None-Match", "*")
        try {
            execute(b.build(), "PUT $path").close()
        } catch (e: SourceException.AuthFailed) {
            // Servers answer 401 to an authenticated user who lacks write rights (e.g. Apache "Require user").
            throw SourceException.PermissionDenied("write not allowed for this account: ${e.message}", e)
        }
    }

    override fun rename(from: String, to: String): Boolean {
        val req = Request.Builder().url(url(from)).method("MOVE", null)
            .header("Destination", url(to).toString()).header("Overwrite", "T").build()
        execute(req, "MOVE $from").close()
        return true
    }

    override fun delete(path: String): Boolean {
        execute(Request.Builder().url(url(path)).delete().build(), "DELETE $path").close()
        return true
    }

    companion object {
        /**
         * Same transport behaviour as public main's MusicService client: redirects (Alist → cloud direct links, with
         * the per-source cookie jar added in [client]) and the mobile-cloud User-Agent some providers require for
         * direct links. Holds no cookies itself; only connection pool and dispatcher are shared.
         */
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request()
                    val b = req.newBuilder()
                    if (req.header("User-Agent") == null) b.header("User-Agent", USER_AGENT)
                    if (req.method == "GET") b.header("Accept", "audio/*, */*").header("Cache-Control", "no-cache")
                    chain.proceed(b.build())
                }
                .build()
        }

        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; MCloudApp/10.7.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun normalizeBaseUrl(raw: String): String {
            val t = raw.trim()
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
        }

        private val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><prop><resourcetype/><getcontentlength/><getlastmodified/></prop></propfind>"""

        private fun parseDate(s: String): Long = try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }
}

/** DOM parsing with DTDs refused up front (no external entities, no entity expansion attacks). */
object SafeXml {
    fun hasDoctype(xml: ByteArray): Boolean {
        val head = String(xml, 0, minOf(xml.size, 64 * 1024), Charsets.ISO_8859_1)
        return head.contains("<!DOCTYPE", ignoreCase = true) || head.contains("<!ENTITY", ignoreCase = true)
    }

    fun hasDoctype(text: String): Boolean = text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)

    fun parse(xml: ByteArray): org.w3c.dom.Document = builder().parse(xml.inputStream())

    /** Parses already-decoded text; the encoding in an XML declaration is ignored. */
    fun parseText(text: String): org.w3c.dom.Document {
        if (hasDoctype(text)) throw IllegalArgumentException("DOCTYPE not allowed")
        return builder().parse(org.xml.sax.InputSource(java.io.StringReader(text.removePrefix("\uFEFF"))))
    }

    private fun builder(): javax.xml.parsers.DocumentBuilder {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.isExpandEntityReferences = false
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return f.newDocumentBuilder()
    }
}
