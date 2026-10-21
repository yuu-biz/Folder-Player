package com.wing.folderplayer.data.viewer

import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceUris
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** What the browser's file viewer shows a file as. */
enum class ViewerKind { TEXT, IMAGE, PDF }

/** Files beside the songs that the browser opens in its viewer instead of playing them. */
object ViewerTypes {
    val TEXT = setOf("txt", "text", "log", "md", "nfo", "lrc", "cue", "m3u", "m3u8", "sfv", "md5", "ffp", "csv", "json", "xml", "ini")
    val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    val PDF = setOf("pdf")
    /** Text read with a fixed-width font by default: rip logs, NFO art and checksum lists are laid out in columns. */
    private val MONOSPACE = setOf("log", "nfo", "cue", "sfv", "md5", "ffp")

    fun kind(name: String): ViewerKind? = when (SourcePath.extension(name)) {
        in TEXT -> ViewerKind.TEXT
        in IMAGE -> ViewerKind.IMAGE
        in PDF -> ViewerKind.PDF
        else -> null
    }

    fun isViewable(name: String) = kind(name) != null
    fun monospaceByDefault(name: String) = SourcePath.extension(name) in MONOSPACE

    /** MIME type for another app; the generic one of the kind where Android's table has none (e.g. `.nfo`, `.cue`). */
    fun mimeType(name: String, fromTable: (String) -> String?): String {
        val ext = SourcePath.extension(name)
        return when (kind(name)) {
            ViewerKind.TEXT -> fromTable(ext)?.takeIf { it.startsWith("text/") } ?: "text/plain"
            ViewerKind.IMAGE -> fromTable(ext) ?: "image/*"
            ViewerKind.PDF -> "application/pdf"
            null -> fromTable(ext) ?: "application/octet-stream"
        }
    }
}

/** Character encodings the text viewer offers; [AUTO] guesses (see [ViewerText.decode]). */
enum class TextEncoding(val charsetName: String?) {
    AUTO(null),
    UTF_8("UTF-8"),
    SHIFT_JIS("windows-31j"),
    EUC_JP("EUC-JP"),
    GB18030("GB18030"),
    BIG5("Big5"),
    UTF_16LE("UTF-16LE"),
    UTF_16BE("UTF-16BE"),
    WINDOWS_1252("windows-1252");

    val label: String get() = when (this) {
        AUTO -> ""
        SHIFT_JIS -> "Shift_JIS"
        else -> charsetName!!
    }
}

data class TextPreview(
    val text: String,
    /** The file is longer than what was read: only its beginning is shown. */
    val truncated: Boolean,
    /** The encoding the text was decoded with (never [TextEncoding.AUTO]). */
    val encoding: TextEncoding,
)

object ViewerText {
    /** The text viewer reads at most this much of a file. */
    const val LIMIT = 1L shl 20

    /** The first [limit] bytes of [path], and whether the file goes on after them. */
    fun read(fs: SourceFileSystem, path: String, limit: Long = LIMIT): Pair<ByteArray, Boolean> {
        fs.openRead(path, 0, limit + 1).use { input ->
            val out = java.io.ByteArrayOutputStream(64 * 1024)
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (total <= limit) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), limit + 1 - total).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            val bytes = out.toByteArray()
            return if (bytes.size > limit) bytes.copyOf(limit.toInt()) to true else bytes to false
        }
    }

    /**
     * Decodes [bytes] with [encoding], or guesses for [TextEncoding.AUTO]: a byte order mark, then strict UTF-8, then the
     * legacy encodings of the device language first (Shift_JIS for Japanese, Big5 for Traditional Chinese, GB18030 for
     * Chinese and the rest, as the lyric and CUE reader does), each accepted only when every byte decodes. [truncated]
     * text may end inside a character; that end is dropped instead of failing the strict check.
     */
    fun decode(bytes: ByteArray, truncated: Boolean, encoding: TextEncoding, locale: Locale = Locale.getDefault()): TextPreview {
        if (encoding != TextEncoding.AUTO) {
            val text = lenient(bytes, Charset.forName(encoding.charsetName!!), truncated)
            return TextPreview(text, truncated, encoding)
        }
        bom(bytes)?.let { (enc, skip) ->
            return TextPreview(lenient(bytes.copyOfRange(skip, bytes.size), Charset.forName(enc.charsetName!!), truncated), truncated, enc)
        }
        for (enc in autoOrder(locale)) {
            val cs = runCatching { Charset.forName(enc.charsetName!!) }.getOrNull() ?: continue
            strict(bytes, cs, truncated)?.let { return TextPreview(it, truncated, enc) }
        }
        return TextPreview(lenient(bytes, Charsets.ISO_8859_1, truncated), truncated, TextEncoding.WINDOWS_1252)
    }

    fun autoOrder(locale: Locale): List<TextEncoding> = when {
        locale.language == "ja" -> listOf(TextEncoding.UTF_8, TextEncoding.SHIFT_JIS, TextEncoding.EUC_JP, TextEncoding.GB18030)
        locale.language == "zh" && (locale.country in setOf("TW", "HK", "MO") || locale.script == "Hant") ->
            listOf(TextEncoding.UTF_8, TextEncoding.BIG5, TextEncoding.GB18030, TextEncoding.SHIFT_JIS)
        else -> listOf(TextEncoding.UTF_8, TextEncoding.GB18030, TextEncoding.SHIFT_JIS)
    }

    private fun bom(b: ByteArray): Pair<TextEncoding, Int>? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> TextEncoding.UTF_8 to 3
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> TextEncoding.UTF_16LE to 2
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> TextEncoding.UTF_16BE to 2
        else -> null
    }

    private fun strict(bytes: ByteArray, cs: Charset, partialEnd: Boolean): String? = run(bytes, cs, partialEnd, CodingErrorAction.REPORT)

    private fun lenient(bytes: ByteArray, cs: Charset, partialEnd: Boolean): String = run(bytes, cs, partialEnd, CodingErrorAction.REPLACE)!!

    private fun run(bytes: ByteArray, cs: Charset, partialEnd: Boolean, action: CodingErrorAction): String? {
        val dec = cs.newDecoder().onMalformedInput(action).onUnmappableCharacter(action)
        val out = CharBuffer.allocate((bytes.size * dec.maxCharsPerByte().toDouble()).toInt() + 16)
        // Not at the end of input for a truncated file: an incomplete last character is left over, not an error.
        val r = dec.decode(ByteBuffer.wrap(bytes), out, !partialEnd)
        if (r.isError) return null
        if (!partialEnd && dec.flush(out).isError) return null
        out.flip()
        return out.toString()
    }
}

/**
 * Local copies of files the viewer shows from a source: images and PDFs are decoded from a file (PDF needs one), and
 * "open in another app" hands a file to the other app. Each copy is `<dir>/<key>/<file name>` (the other app sees the
 * real name); the key comes from the file's URI, size and modification time, so a copy is used again until the file
 * changes. The copies together stay under [maxBytes] (oldest first out; the one being opened is kept).
 */
class ViewerCache(private val dir: File, private val maxBytes: Long = MAX_CACHE) {
    /** One copy at a time per file: the viewer and "open in another app" may ask for the same file together. */
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun fileFor(entry: MusicFile): File {
        val key = SourceUris.toUri(entry.ref) + "|" + entry.size + "|" + entry.lastModified
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        return File(File(dir, hash), safeName(entry.name))
    }

    /**
     * The local copy of [entry], made from [fs] when there is none yet. [onProgress] gets the bytes copied so far.
     * Throws [SourceException.TooLarge] above [limit].
     */
    fun fetch(fs: SourceFileSystem, entry: MusicFile, limit: Long = MAX_FILE, onProgress: (Long) -> Unit = {}): File {
        val target = fileFor(entry)
        val lock = locks.getOrPut(target.path) { ReentrantLock() }
        lock.lockInterruptibly()
        try {
            return copy(fs, entry, target, limit, onProgress)
        } finally {
            lock.unlock()
        }
    }

    private fun copy(fs: SourceFileSystem, entry: MusicFile, target: File, limit: Long, onProgress: (Long) -> Unit): File {
        if (target.isFile && (entry.size <= 0 || target.length() == entry.size)) {
            target.parentFile?.setLastModified(System.currentTimeMillis())
            return target
        }
        if (entry.size > limit) throw SourceException.TooLarge(limit, entry.size)
        val folder = target.parentFile!!
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("cannot create ${folder.path}")
        val part = File(folder, target.name + ".part")
        try {
            fs.openRead(entry.path).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        if (Thread.interrupted()) throw InterruptedException()
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > limit) throw SourceException.TooLarge(limit, total)
                        out.write(buf, 0, n)
                        onProgress(total)
                    }
                }
            }
            if (entry.size > 0 && part.length() != entry.size) throw SourceException.PrematureEof(entry.size, part.length())
            if (!part.renameTo(target)) throw IOException("cannot rename ${part.path}")
        } finally {
            part.delete()
        }
        folder.setLastModified(System.currentTimeMillis())
        trim(keep = folder)
        return target
    }

    /** Deletes the oldest copies until the rest fits in [maxBytes]; [keep] stays. */
    fun trim(keep: File? = null) {
        val folders = dir.listFiles()?.filter { it.isDirectory } ?: return
        var total = folders.sumOf { size(it) }
        for (f in folders.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (f == keep) continue
            total -= size(f)
            f.deleteRecursively()
        }
    }

    fun usedBytes(): Long = dir.listFiles()?.sumOf { size(it) } ?: 0

    private fun size(f: File): Long = if (f.isDirectory) f.listFiles()?.sumOf { size(it) } ?: 0 else f.length()

    companion object {
        /** Largest file copied for the viewer or another app. */
        const val MAX_FILE = 200L shl 20
        const val MAX_CACHE = 300L shl 20

        /** A name a local file can have (names on a server may hold characters a local file system rejects). */
        fun safeName(name: String): String {
            val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.')
            return cleaned.ifEmpty { "file" }.let { if (it.length > 120) it.take(60) + "…" + it.takeLast(59) else it }
        }
    }
}
