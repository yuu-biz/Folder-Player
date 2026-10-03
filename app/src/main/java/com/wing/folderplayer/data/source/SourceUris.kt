package com.wing.folderplayer.data.source

import java.io.ByteArrayOutputStream

/** A file inside a configured source. This, not a display name or URL, identifies media everywhere. */
data class SourceRef(val sourceId: String, val path: String) {
    init {
        require(sourceId.isNotEmpty()) { "empty sourceId" }
    }

    val name: String get() = SourcePath.name(path)
    val parent: SourceRef? get() = SourcePath.parent(path)?.let { SourceRef(sourceId, it) }
    fun child(name: String) = SourceRef(sourceId, SourcePath.child(path, name))

    /** Stable string form used as Media3 URI / mediaId and in persisted state. */
    fun toUriString(): String = SourceUris.toUri(this)

    override fun toString(): String = toUriString()
}

/**
 * The only place that converts between [SourceRef] and URI strings.
 *
 * Format: `fpsrc://<sourceId>/<seg>/<seg>` where sourceId and every segment are percent-encoded once
 * (RFC 3986 unreserved characters stay literal). A CUE virtual track appends `#track_<startMs>`; because a literal
 * `#` inside a name is always encoded as `%23`, the fragment delimiter is unambiguous.
 */
object SourceUris {
    const val SCHEME = "fpsrc"
    private const val PREFIX = "$SCHEME://"
    private const val CUE_FRAGMENT = "#track_"

    fun isSourceUri(s: String?): Boolean = s != null && s.startsWith(PREFIX)

    fun toUri(ref: SourceRef): String {
        val sb = StringBuilder(PREFIX).append(encode(ref.sourceId))
        val segs = SourcePath.segments(ref.path)
        if (segs.isEmpty()) sb.append('/') else segs.forEach { sb.append('/').append(encode(it)) }
        return sb.toString()
    }

    fun toUri(sourceId: String, path: String) = toUri(SourceRef(sourceId, path))

    /** Parses a source URI (an optional CUE fragment is ignored). Returns null for any other string. */
    fun parse(uri: String?): SourceRef? {
        if (uri == null || !uri.startsWith(PREFIX)) return null
        val body = uri.substring(PREFIX.length).substringBefore('#').substringBefore('?')
        val slash = body.indexOf('/')
        val idPart = if (slash < 0) body else body.substring(0, slash)
        val pathPart = if (slash < 0) "" else body.substring(slash + 1)
        val id = decode(idPart) ?: return null
        if (id.isEmpty()) return null
        val segs = pathPart.split('/').filter { it.isNotEmpty() }.map { decode(it) ?: return null }
        if (segs.any { it == "." || it == ".." || it.contains('/') }) return null
        return SourceRef(id, if (segs.isEmpty()) SourcePath.ROOT else segs.joinToString("/", prefix = "/"))
    }

    fun cueTrackId(audio: SourceRef, startMs: Long): String = toUri(audio) + CUE_FRAGMENT + startMs

    fun isCueTrackId(mediaId: String?): Boolean = mediaId != null && mediaId.contains(CUE_FRAGMENT)

    fun cueStartMs(mediaId: String): Long? = mediaId.substringAfter(CUE_FRAGMENT, "").toLongOrNull()

    private fun isUnreserved(b: Int): Boolean =
        (b in 'A'.code..'Z'.code) || (b in 'a'.code..'z'.code) || (b in '0'.code..'9'.code) ||
            b == '-'.code || b == '.'.code || b == '_'.code || b == '~'.code

    fun encode(s: String): String {
        val sb = StringBuilder()
        for (byte in s.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xff
            if (isUnreserved(b)) sb.append(b.toChar())
            else sb.append('%').append(HEX[b shr 4]).append(HEX[b and 0xf])
        }
        return sb.toString()
    }

    /** Strict single decode; returns null for malformed escapes. `+` is literal (not a space). */
    fun decode(s: String): String? {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                val cp = s.codePointAt(i)
                val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                i += Character.charCount(cp)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
