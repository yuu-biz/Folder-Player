package com.wing.folderplayer.data.metadata

import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourcePath
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

data class EmbeddedTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: String? = null,
    val lyrics: String? = null,
    val picture: ByteArray? = null,
) {
    val isEmpty get() = title == null && artist == null && album == null && lyrics == null && picture == null
}

/**
 * Reads tags that Media3 does not surface to the UI (lyrics frames) from the start of a file: ID3v2 (MP3),
 * FLAC metadata blocks and MP4 `moov/udta/meta/ilst`. Only the tag region is read and every read is capped at
 * [maxBytes], so large audio files are never loaded into memory.
 */
object EmbeddedTagReader {
    const val DEFAULT_MAX = 8L * 1024 * 1024

    fun read(fs: SourceFileSystem, path: String, maxBytes: Long = DEFAULT_MAX): EmbeddedTags? =
        when (SourcePath.extension(path)) {
            "mp3" -> fs.openRead(path, 0, -1).use { readId3(it, maxBytes) }
            "flac" -> fs.openRead(path, 0, -1).use { readFlac(it, maxBytes) }
            "m4a", "mp4", "aac" -> readMp4(fs, path, maxBytes)
            else -> null
        }

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    private fun skipFully(input: InputStream, n: Long): Boolean {
        var left = n
        val tmp = ByteArray(8192)
        while (left > 0) {
            val r = input.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
            if (r < 0) return false
            left -= r
        }
        return true
    }

    // ---------------- ID3v2 ----------------

    fun readId3(input: InputStream, maxBytes: Long): EmbeddedTags? {
        val header = readFully(input, 10) ?: return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        val major = header[3].toInt()
        val flags = header[5].toInt()
        val size = synchsafe(header, 6)
        if (size <= 0 || size > maxBytes) return null
        var tag = readFully(input, size) ?: return null
        if (flags and 0x80 != 0 && major < 4) tag = unsynchronize(tag)
        var pos = 0
        if (flags and 0x40 != 0) { // extended header
            val extSize = if (major >= 4) synchsafe(tag, 0) else be32(tag, 0) + 4
            pos += extSize
        }
        var title: String? = null; var artist: String? = null; var album: String? = null; var track: String? = null
        var lyrics: String? = null; var picture: ByteArray? = null
        val idLen = if (major == 2) 3 else 4
        val hdrLen = if (major == 2) 6 else 10
        while (pos + hdrLen <= tag.size) {
            val id = String(tag, pos, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val frameSize = when (major) {
                2 -> ((tag[pos + 3].toInt() and 0xff) shl 16) or ((tag[pos + 4].toInt() and 0xff) shl 8) or (tag[pos + 5].toInt() and 0xff)
                4 -> synchsafe(tag, pos + 4)
                else -> be32(tag, pos + 4)
            }
            val start = pos + hdrLen
            if (frameSize <= 0 || start + frameSize > tag.size) break
            val data = tag.copyOfRange(start, start + frameSize)
            when (id) {
                "TIT2", "TT2" -> title = textFrame(data)
                "TPE1", "TP1" -> artist = textFrame(data)
                "TALB", "TAL" -> album = textFrame(data)
                "TRCK", "TRK" -> track = textFrame(data)
                "USLT", "ULT" -> if (lyrics == null) lyrics = usltFrame(data)
                "APIC" -> if (picture == null) picture = apicFrame(data)
                "PIC" -> if (picture == null && data.size > 5) picture = picFrameV22(data)
            }
            pos = start + frameSize
        }
        return EmbeddedTags(title, artist, album, track, lyrics, picture)
    }

    private fun synchsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7f) shl 21) or ((b[o + 1].toInt() and 0x7f) shl 14) or ((b[o + 2].toInt() and 0x7f) shl 7) or (b[o + 3].toInt() and 0x7f)

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun unsynchronize(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun charset(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun terminatorLen(enc: Int) = if (enc == 1 || enc == 2) 2 else 1

    private fun indexOfTerminator(b: ByteArray, from: Int, enc: Int): Int {
        val step = terminatorLen(enc)
        var i = from
        while (i + step - 1 < b.size) {
            if (step == 1 && b[i] == 0.toByte()) return i
            if (step == 2 && b[i] == 0.toByte() && b[i + 1] == 0.toByte()) return i
            i += step
        }
        return b.size
    }

    private fun decode(b: ByteArray, from: Int, to: Int, enc: Int): String =
        if (to <= from) "" else String(b, from, to - from, charset(enc)).trimEnd('\u0000').trim()

    private fun textFrame(d: ByteArray): String? {
        if (d.isEmpty()) return null
        val enc = d[0].toInt()
        return decode(d, 1, indexOfTerminator(d, 1, enc), enc).takeIf { it.isNotEmpty() }
    }

    private fun usltFrame(d: ByteArray): String? {
        if (d.size < 5) return null
        val enc = d[0].toInt()
        val descEnd = indexOfTerminator(d, 4, enc)
        val textStart = descEnd + terminatorLen(enc)
        return decode(d, textStart, d.size, enc).takeIf { it.isNotEmpty() }
    }

    private fun apicFrame(d: ByteArray): ByteArray? {
        if (d.size < 4) return null
        val enc = d[0].toInt()
        var i = 1
        while (i < d.size && d[i] != 0.toByte()) i++ // mime
        i += 2 // terminator + picture type
        if (i >= d.size) return null
        val descEnd = indexOfTerminator(d, i, enc)
        val start = descEnd + terminatorLen(enc)
        return if (start < d.size) d.copyOfRange(start, d.size) else null
    }

    private fun picFrameV22(d: ByteArray): ByteArray? {
        val enc = d[0].toInt()
        val descEnd = indexOfTerminator(d, 5, enc)
        val start = descEnd + terminatorLen(enc)
        return if (start < d.size) d.copyOfRange(start, d.size) else null
    }

    // ---------------- FLAC ----------------

    fun readFlac(input: InputStream, maxBytes: Long): EmbeddedTags? {
        var head = readFully(input, 4) ?: return null
        if (String(head, Charsets.ISO_8859_1) == "ID3\u0003" || head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte()) {
            // Skip an ID3v2 tag in front of the FLAC stream.
            val rest = readFully(input, 6) ?: return null
            val size = synchsafe(rest, 2)
            if (!skipFully(input, size.toLong())) return null
            head = readFully(input, 4) ?: return null
        }
        if (String(head, Charsets.ISO_8859_1) != "fLaC") return null
        var read = 4L
        var tags = EmbeddedTags()
        while (true) {
            val bh = readFully(input, 4) ?: break
            val last = bh[0].toInt() and 0x80 != 0
            val type = bh[0].toInt() and 0x7f
            val len = ((bh[1].toInt() and 0xff) shl 16) or ((bh[2].toInt() and 0xff) shl 8) or (bh[3].toInt() and 0xff)
            read += 4 + len
            if (read > maxBytes) break
            when (type) {
                4 -> readFully(input, len)?.let { tags = mergeVorbis(tags, it) } ?: break
                6 -> readFully(input, len)?.let { if (tags.picture == null) tags = tags.copy(picture = flacPicture(it)) } ?: break
                else -> if (!skipFully(input, len.toLong())) break
            }
            if (last) break
        }
        return tags
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun mergeVorbis(t: EmbeddedTags, b: ByteArray): EmbeddedTags {
        var p = 0
        if (b.size < 8) return t
        val vendorLen = le32(b, 0)
        p = 4 + vendorLen
        if (p + 4 > b.size) return t
        val n = le32(b, p)
        p += 4
        var out = t
        repeat(n) {
            if (p + 4 > b.size) return out
            val l = le32(b, p)
            p += 4
            if (l < 0 || p + l > b.size) return out
            val kv = String(b, p, l, Charsets.UTF_8)
            p += l
            val key = kv.substringBefore('=').uppercase()
            val value = kv.substringAfter('=', "").trim()
            if (value.isEmpty()) return@repeat
            out = when (key) {
                "TITLE" -> out.copy(title = out.title ?: value)
                "ARTIST" -> out.copy(artist = out.artist ?: value)
                "ALBUM" -> out.copy(album = out.album ?: value)
                "TRACKNUMBER" -> out.copy(trackNumber = out.trackNumber ?: value)
                "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS" -> out.copy(lyrics = out.lyrics ?: value)
                else -> out
            }
        }
        return out
    }

    private fun flacPicture(b: ByteArray): ByteArray? {
        var p = 4
        if (p + 4 > b.size) return null
        val mimeLen = be32(b, p); p += 4 + mimeLen
        if (p + 4 > b.size) return null
        val descLen = be32(b, p); p += 4 + descLen
        p += 16
        if (p + 4 > b.size) return null
        val dataLen = be32(b, p); p += 4
        return if (dataLen > 0 && p + dataLen <= b.size) b.copyOfRange(p, p + dataLen) else null
    }

    // ---------------- MP4 ----------------

    private fun readMp4(fs: SourceFileSystem, path: String, maxBytes: Long): EmbeddedTags? {
        val size = fs.stat(path)?.size ?: return null
        fs.openRandomAccess(path).use { ra ->
            fun readAt(pos: Long, n: Int): ByteArray? {
                val buf = ByteArray(n)
                var off = 0
                while (off < n) {
                    val r = ra.read(pos + off, buf, off, n - off)
                    if (r <= 0) return null
                    off += r
                }
                return buf
            }
            // Find moov at top level (it may sit after mdat).
            var pos = 0L
            while (pos + 8 <= size) {
                val h = readAt(pos, 8) ?: return null
                var boxSize = be32(h, 0).toLong() and 0xffffffffL
                val type = String(h, 4, 4, Charsets.ISO_8859_1)
                var headerLen = 8
                if (boxSize == 1L) {
                    val ext = readAt(pos + 8, 8) ?: return null
                    boxSize = java.nio.ByteBuffer.wrap(ext).long
                    headerLen = 16
                } else if (boxSize == 0L) boxSize = size - pos
                if (boxSize < headerLen) return null
                if (type == "moov") {
                    if (boxSize > maxBytes) return null
                    val moov = readAt(pos + headerLen, (boxSize - headerLen).toInt()) ?: return null
                    return parseIlst(moov)
                }
                pos += boxSize
            }
            return null
        }
    }

    private fun findBox(b: ByteArray, from: Int, to: Int, type: String, skipHeader: Int = 0): IntRange? {
        var p = from
        while (p + 8 <= to) {
            val sz = be32(b, p)
            if (sz < 8 || p + sz > to) return null
            if (String(b, p + 4, 4, Charsets.ISO_8859_1) == type) return (p + 8 + skipHeader) until (p + sz)
            p += sz
        }
        return null
    }

    private fun parseIlst(moov: ByteArray): EmbeddedTags? {
        val udta = findBox(moov, 0, moov.size, "udta") ?: return null
        val meta = findBox(moov, udta.first, udta.last + 1, "meta", skipHeader = 4) ?: return null
        val ilst = findBox(moov, meta.first, meta.last + 1, "ilst") ?: return null
        var t = EmbeddedTags()
        var p = ilst.first
        val end = ilst.last + 1
        while (p + 8 <= end) {
            val sz = be32(moov, p)
            if (sz < 8 || p + sz > end) break
            val key = String(moov, p + 4, 4, Charsets.ISO_8859_1)
            val data = findBox(moov, p + 8, p + sz, "data")
            if (data != null && data.last + 1 - data.first >= 8) {
                val payloadStart = data.first + 8
                val payload = moov.copyOfRange(payloadStart, data.last + 1)
                val text = { String(payload, Charsets.UTF_8).trim() }
                t = when (key) {
                    "©nam" -> t.copy(title = text())
                    "©ART" -> t.copy(artist = text())
                    "©alb" -> t.copy(album = text())
                    "©lyr" -> t.copy(lyrics = text())
                    "trkn" -> if (payload.size >= 4) t.copy(trackNumber = (((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)).toString()) else t
                    "covr" -> t.copy(picture = payload)
                    else -> t
                }
            }
            p += sz
        }
        return t
    }
}
