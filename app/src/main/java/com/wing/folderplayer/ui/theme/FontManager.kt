package com.wing.folderplayer.ui.theme

import androidx.core.content.edit

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.wing.folderplayer.data.ai.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloadable fonts. Files are the unmodified OFL fonts hosted in the upstream repository's fonts/ folder at
 * a fixed commit (see fonts/README.md for the original sources and licenses); each download is checked against the
 * expected size and SHA-256 before use, so an unavailable or changed URL fails safely back to the system font.
 */
enum class AppFont(val id: String, val fileName: String?, val sizeBytes: Long, val sha256: String?) {
    SYSTEM("system", null, 0, null),
    NOTO_SANS_SC("noto_sans_sc", "NotoSansSC-Variable.ttf", 17_772_300, "a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da"),
    LXGW_WENKAI("lxgw_wenkai", "LXGWWenKaiLite-Regular.ttf", 13_872_424, "140c99ba4e28e817cec49bf82a0c5fcdc4fe633fb9dfda16d0ee8d59a8545f15"),
    SARASA_UI_SC("sarasa_ui_sc", "SarasaUiSC-Regular.ttf", 24_049_996, "c27311eb179c8b3f43644e82c8876b2dc2bca9c11cf6c70eebf3e61a27e5bf6b"),
    IMPORTED("imported", null, 0, null);

    val url: String? get() = fileName?.let { "$BASE_URL/$it" }

    companion object {
        /** Upstream commit 98906d8 ("Add fonts/ directory"), pinned so the bytes cannot change under us. */
        const val BASE_URL = "https://raw.githubusercontent.com/wyvern3000/Folder-Player/98906d8426f0cda1792d77104e5c0ecc84f18e76/fonts"
        fun byId(id: String?) = values().firstOrNull { it.id == id } ?: SYSTEM
    }
}

sealed class FontState {
    object NotDownloaded : FontState()
    data class Downloading(val progress: Float) : FontState()
    object Ready : FontState()
    data class Failed(val reason: String) : FontState()
}

class FontImportException(msg: String) : Exception(msg)

object FontValidation {
    const val MAX_IMPORT_BYTES = 50L * 1024 * 1024

    /** TrueType (0x00010000 / 'true'), OpenType CFF ('OTTO') or collection ('ttcf'). */
    fun hasFontHeader(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val tag = String(head, 0, 4, Charsets.ISO_8859_1)
        return (head[0] == 0.toByte() && head[1] == 1.toByte() && head[2] == 0.toByte() && head[3] == 0.toByte()) ||
            tag == "OTTO" || tag == "true" || tag == "ttcf"
    }

    fun sha256(file: File): String = file.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}

class FontManager private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("font_prefs", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "fonts").apply { mkdirs() }
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private val _states = MutableStateFlow(AppFont.values().associateWith { initialState(it) })
    val states: StateFlow<Map<AppFont, FontState>> = _states.asStateFlow()

    private val _selected = MutableStateFlow(AppFont.byId(prefs.getString("selected", AppFont.SYSTEM.id)))
    val selected: StateFlow<AppFont> = _selected.asStateFlow()

    /** Message explaining why the System font is used instead of the chosen one (null when all is fine). */
    private val _fallbackReason = MutableStateFlow<String?>(null)
    val fallbackReason: StateFlow<String?> = _fallbackReason.asStateFlow()

    var importedName: String
        get() = prefs.getString("imported_name", "") ?: ""
        private set(v) = prefs.edit { putString("imported_name", v) }

    fun fileFor(font: AppFont): File? = when (font) {
        AppFont.SYSTEM -> null
        AppFont.IMPORTED -> File(dir, "imported.font")
        else -> File(dir, font.fileName!!)
    }

    private fun initialState(font: AppFont): FontState = when {
        font == AppFont.SYSTEM -> FontState.Ready
        fileFor(font)?.exists() == true -> FontState.Ready
        else -> FontState.NotDownloaded
    }

    private fun setState(font: AppFont, s: FontState) {
        _states.value = _states.value.toMutableMap().apply { put(font, s) }
    }

    fun select(font: AppFont) {
        prefs.edit { putString("selected", font.id) }
        _selected.value = font
        _fallbackReason.value = null
    }

    /** Font family for the theme; falls back to the system font (and records why) if the file is missing/broken. */
    fun fontFamily(font: AppFont = _selected.value): FontFamily {
        if (font == AppFont.SYSTEM) return FontFamily.Default
        val f = fileFor(font)
        if (f == null || !f.exists()) {
            _fallbackReason.value = "font file missing"
            return FontFamily.Default
        }
        return try {
            val head = f.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
            if (!FontValidation.hasFontHeader(head)) throw IOException("not a TrueType/OpenType font")
            // createFromFile returns the default typeface (instead of throwing) for unusable files on newer Android;
            // handing Compose the loaded Typeface also avoids a lazy load that would crash during layout.
            val tf = Typeface.createFromFile(f)
            if (tf == null || tf == Typeface.DEFAULT) throw IOException("font file cannot be loaded")
            FontFamily(androidx.compose.ui.text.font.Typeface(tf))
        } catch (e: Exception) {
            _fallbackReason.value = "font could not be loaded: ${e.message}"
            FontFamily.Default
        }
    }

    suspend fun download(font: AppFont): Boolean {
        val url = font.url ?: return false
        val target = fileFor(font)!!
        val part = File(dir, "${font.fileName}.part")
        setState(font, FontState.Downloading(0f))
        return try {
            val resp = http.newCall(Request.Builder().url(url).build()).await()
            resp.use {
                if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                val body = it.body ?: throw IOException("empty response")
                runInterruptible(Dispatchers.IO) {
                    body.byteStream().use { input -> copyWithProgress(input, part, font.sizeBytes) { p -> setState(font, FontState.Downloading(p)) } }
                }
            }
            if (part.length() != font.sizeBytes) throw IOException("size mismatch (${part.length()} bytes)")
            val digest = withContext(Dispatchers.IO) { FontValidation.sha256(part) }
            if (digest != font.sha256) throw IOException("checksum mismatch")
            if (!part.renameTo(target)) throw IOException("cannot store font")
            setState(font, FontState.Ready)
            true
        } catch (e: CancellationException) {
            part.delete()
            setState(font, FontState.NotDownloaded)
            throw e
        } catch (e: Exception) {
            part.delete()
            setState(font, FontState.Failed(e.message ?: e.javaClass.simpleName))
            false
        }
    }

    private fun copyWithProgress(input: InputStream, out: File, expected: Long, onProgress: (Float) -> Unit) {
        out.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var lastReport = 0L
            while (true) {
                if (Thread.interrupted()) throw InterruptedException()
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (expected > 0 && total > expected) throw IOException("download larger than expected")
                o.write(buf, 0, n)
                if (total - lastReport > 512 * 1024) { lastReport = total; onProgress(if (expected > 0) total.toFloat() / expected else 0f) }
            }
        }
    }

    /** Imports a user TTF/OTF chosen with the document picker (≤ 50 MiB, valid header, loadable by Android). */
    suspend fun import(uri: Uri, displayName: String): Unit = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size > FontValidation.MAX_IMPORT_BYTES) throw FontImportException("file larger than 50 MiB")
        val tmp = File(dir, "import.tmp")
        try {
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > FontValidation.MAX_IMPORT_BYTES) throw FontImportException("file larger than 50 MiB")
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw FontImportException("cannot open file")
            val head = tmp.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
            if (!FontValidation.hasFontHeader(head)) throw FontImportException("not a TrueType/OpenType font")
            val tf = try { Typeface.createFromFile(tmp) } catch (e: Exception) { null }
            if (tf == null || tf == Typeface.DEFAULT) throw FontImportException("font cannot be loaded")
            val target = fileFor(AppFont.IMPORTED)!!
            if (!tmp.renameTo(target)) throw FontImportException("cannot store font")
            importedName = displayName
            setState(AppFont.IMPORTED, FontState.Ready)
        } finally {
            tmp.delete()
        }
    }

    fun delete(font: AppFont) {
        fileFor(font)?.delete()
        setState(font, initialState(font))
        if (_selected.value == font) select(AppFont.SYSTEM)
    }

    companion object {
        // Holds only the application context, which lives as long as the process.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: FontManager? = null
        fun get(context: Context): FontManager =
            instance ?: synchronized(this) { instance ?: FontManager(context.applicationContext).also { instance = it } }
    }
}
