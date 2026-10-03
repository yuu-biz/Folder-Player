package com.wing.folderplayer.playback

import androidx.core.content.edit
import androidx.core.net.toUri

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wing.folderplayer.data.repo.PlayerRepository
import com.wing.folderplayer.data.source.SourceFileSystem
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.SourceUris
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

class ExportSettings(context: Context) {
    private val prefs = context.getSharedPreferences("export_prefs", Context.MODE_PRIVATE)
    /** Auto-save is OFF by default. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit { putBoolean("enabled", v) }
    var folderName: String
        get() = prefs.getString("folder", "FolderPlayer") ?: "FolderPlayer"
        set(v) = prefs.edit { putString("folder", v.ifBlank { "FolderPlayer" }) }
}

data class ExportStatus(val ref: String, val state: State, val detail: String = "") {
    enum class State { QUEUED, DOWNLOADING, SAVED, SKIPPED_DUPLICATE, SKIPPED_CUE, FAILED, CANCELLED }
}

/** Download-and-verify core, independent of Android (unit tested). */
object ExportCore {
    class IncompleteException(expected: Long, got: Long) : IOException("incomplete download: $got of $expected bytes")
    class NoSpaceException(need: Long, free: Long) : IOException("not enough space: need $need, free $free")

    /** Copies the whole file into [target]; deletes it and throws unless exactly [expectedSize] bytes arrived. */
    suspend fun download(fs: SourceFileSystem, path: String, expectedSize: Long, target: File, freeBytes: Long) {
        if (expectedSize <= 0) throw IOException("unknown size")
        val margin = 16L * 1024 * 1024
        if (freeBytes < expectedSize + margin) throw NoSpaceException(expectedSize + margin, freeBytes)
        target.parentFile?.mkdirs()
        var total = 0L
        try {
            runInterruptible(Dispatchers.IO) {
                fs.openRead(path, 0, -1).use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (Thread.interrupted()) throw InterruptedException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            if (total > expectedSize) throw IOException("file grew while downloading")
                        }
                        out.fd.sync()
                    }
                }
            }
            if (total != expectedSize) throw IncompleteException(expectedSize, total)
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    /** What makes an exported file "the same track": source + path, plus size and mtime (a replaced file is new). */
    fun identity(sourceUri: String, size: Long, lastModified: Long) = "$sourceUri|$size|$lastModified"

    /** [name], or "name (n).ext" with the smallest n >= 2 not in [taken] (compared case-insensitively). */
    fun uniqueName(name: String, taken: Set<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in lower) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while ("$stem ($n)$ext".lowercase() in lower) n++
        return "$stem ($n)$ext"
    }

    /** Only our own recorded output counts as a duplicate; a file that merely has the same name and size does not. */
    fun alreadyExported(index: Map<String, String>, identity: String, size: Long, store: ExportStore): Boolean =
        index[identity]?.let { store.exists(it, size) } == true

    /** Publishes [part] under [relDir] with a free name; never replaces an existing file. Returns the stored location. */
    fun publish(part: File, relDir: String, name: String, store: ExportStore): String {
        var last: IOException? = null
        repeat(3) {
            try {
                return store.write(part, relDir, uniqueName(name, store.names(relDir)))
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                last = e // created concurrently by someone else: pick the next free name
            }
        }
        throw last ?: IOException("no free name in $relDir")
    }
}

/** Where exports go: the shared Music collection. Locations are opaque strings (content URI or absolute path). */
interface ExportStore {
    /** True when [location] (as returned by [write]) still exists, is complete and has [size] bytes. */
    fun exists(location: String, size: Long): Boolean
    /** Display names currently present in [relDir]. */
    fun names(relDir: String): Set<String>
    /** Stores [part] as [relDir]/[name]; throws FileAlreadyExistsException instead of replacing a file. */
    fun write(part: File, relDir: String, name: String): String
}

/** Plain files below [root] (Android 9 and older; unit tests). */
class FileExportStore(private val root: File) : ExportStore {
    override fun exists(location: String, size: Long): Boolean {
        val f = File(location)
        return f.isAbsolute && f.isFile && f.length() == size
    }

    override fun names(relDir: String): Set<String> = File(root, relDir).list()?.toSet() ?: emptySet()

    override fun write(part: File, relDir: String, name: String): String {
        val dir = File(root, relDir).apply { mkdirs() }
        val out = File(dir, name)
        if (out.exists()) throw java.nio.file.FileAlreadyExistsException(out.path)
        val tmp = File(dir, ".$name.part")
        try {
            part.copyTo(tmp, overwrite = true)
            // Without REPLACE_EXISTING the move fails rather than replacing a file that appeared meanwhile.
            java.nio.file.Files.move(tmp.toPath(), out.toPath())
        } finally {
            tmp.delete()
        }
        return out.absolutePath
    }
}

/**
 * Auto-save: when a track from a network source has played to its natural end (auto transition / end of queue) it is
 * downloaded in full in the background, verified against the source size and only then published under
 * Music/<folder>/ (MediaStore IS_PENDING on Android 10+). Seeks or partial playback never produce a published file
 * because the export is a separate complete read. CUE virtual tracks are skipped. Failures never affect playback.
 */
class PlaybackExportManager(private val context: Context, private val player: Player) : Player.Listener {
    private val settings = ExportSettings(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<MediaItem>(Channel.UNLIMITED)
    private var worker: Job? = null
    private var lastItem: MediaItem? = null
    private val indexFile = File(context.filesDir, "export-index.json")
    private val gson = Gson()

    fun attach() {
        player.addListener(this)
        lastItem = player.currentMediaItem
        worker = scope.launch { for (item in queue) process(item) }
    }

    fun detach() {
        player.removeListener(this)
        worker?.cancel()
        queue.close()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val finished = lastItem
        lastItem = mediaItem
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && finished != null) enqueue(finished)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) player.currentMediaItem?.let { enqueue(it) }
    }

    private fun enqueue(item: MediaItem) {
        if (!settings.enabled) return
        queue.trySend(item)
    }

    private fun publish(s: ExportStatus) {
        _status.value = s
        android.util.Log.i(TAG, "${s.state} ${s.detail}")
    }

    private fun loadIndex(): MutableMap<String, String> = try {
        if (indexFile.exists()) gson.fromJson<MutableMap<String, String>>(indexFile.readText(), object : TypeToken<MutableMap<String, String>>() {}.type) ?: mutableMapOf()
        else mutableMapOf()
    } catch (e: Exception) { mutableMapOf() }

    private suspend fun process(item: MediaItem) {
        val ref = SourceUris.parse(item.localConfiguration?.uri?.toString() ?: item.mediaId) ?: return
        val cfg = SourceRegistry.get(ref.sourceId) ?: return
        if (!cfg.isNetwork) return
        val key = ref.toUriString()
        if (item.mediaMetadata.extras?.getBoolean(PlayerRepository.EXTRA_IS_CUE_TRACK) == true) {
            publish(ExportStatus(key, ExportStatus.State.SKIPPED_CUE, "CUE virtual tracks are not exported"))
            return
        }
        val tmp = File(context.cacheDir, "export/${System.nanoTime()}.part")
        try {
            val fs = SourceRegistry.fileSystem(ref)
            val st = runInterruptible { fs.stat(ref.path) } ?: throw IOException("source file disappeared")
            val identity = ExportCore.identity(key, st.size, st.lastModified)
            val index = loadIndex()
            val folder = ref.parent?.let { SourcePath.name(it.path) }?.ifBlank { null } ?: cfg.name
            val relDir = "${Environment.DIRECTORY_MUSIC}/${sanitize(settings.folderName)}/${sanitize(folder)}"
            migrateLegacyEntry(index, "$key|${st.size}", identity)
            if (ExportCore.alreadyExported(index, identity, st.size, store)) {
                publish(ExportStatus(key, ExportStatus.State.SKIPPED_DUPLICATE, ref.name))
                return
            }
            publish(ExportStatus(key, ExportStatus.State.DOWNLOADING, ref.name))
            val free = minOf(StatFs(context.cacheDir.path).availableBytes, StatFs(Environment.getExternalStorageDirectory().path).availableBytes)
            ExportCore.download(fs, ref.path, st.size, tmp, free)
            coroutineContext.ensureActive()
            val saved = ExportCore.publish(tmp, relDir, sanitize(ref.name), store)
            if (saved.startsWith("/")) MediaScannerConnection.scanFile(context, arrayOf(saved), null, null)
            index[identity] = saved
            saveIndex(index)
            publish(ExportStatus(key, ExportStatus.State.SAVED, saved))
        } catch (e: CancellationException) {
            publish(ExportStatus(key, ExportStatus.State.CANCELLED))
            throw e
        } catch (e: Exception) {
            publish(ExportStatus(key, ExportStatus.State.FAILED, e.message ?: e.javaClass.simpleName))
        } finally {
            tmp.delete()
        }
    }

    private fun saveIndex(index: Map<String, String>) = runCatching { indexFile.writeText(gson.toJson(index)) }

    private fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "_" }

    private fun mime(name: String) = when (SourcePath.extension(name)) {
        "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "m4a" -> "audio/mp4"; "ogg" -> "audio/ogg"; "opus" -> "audio/opus"
        "wav" -> "audio/wav"; "aac" -> "audio/aac"; "wma" -> "audio/x-ms-wma"; "ape" -> "audio/x-ape"
        "dsf" -> "audio/x-dsf"; "dff" -> "audio/x-dff"; else -> "audio/*"
    }

    private val store: ExportStore =
        if (Build.VERSION.SDK_INT >= 29) MediaStoreExportStore() else FileExportStore(Environment.getExternalStorageDirectory())

    /**
     * Index entries of earlier versions were keyed "uri|size". Keep only those that point at a file we wrote
     * ourselves (content URI / absolute path); the ones recorded for a same-named file found in Music/ are dropped.
     */
    private fun migrateLegacyEntry(index: MutableMap<String, String>, legacyKey: String, identity: String) {
        val old = index.remove(legacyKey) ?: return
        if (old.startsWith("content://") || old.startsWith("/")) index.putIfAbsent(identity, old)
        saveIndex(index)
    }

    @androidx.annotation.RequiresApi(29)
    private inner class MediaStoreExportStore : ExportStore {
        private val collection get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        override fun exists(location: String, size: Long): Boolean {
            if (!location.startsWith("content://")) return false
            val c = runCatching {
                context.contentResolver.query(location.toUri(), arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING), null, null, null)
            }.getOrNull() ?: return false
            return c.use { it.moveToFirst() && it.getLong(0) == size && it.getInt(1) == 0 }
        }

        override fun names(relDir: String): Set<String> {
            val out = HashSet<String>()
            context.contentResolver.query(
                collection, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf("$relDir/"), null
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let(out::add) }
            return out
        }

        /** MediaStore never replaces on insert (it renames a colliding name itself); the stored URI is the identity. */
        override fun write(part: File, relDir: String, name: String): String {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$relDir/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri, "w")?.use { out -> part.inputStream().use { it.copyTo(out, 256 * 1024) } }
                    ?: throw IOException("cannot open MediaStore output")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return uri.toString()
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "PlaybackExport"
        private val _status = MutableStateFlow<ExportStatus?>(null)
        val status: StateFlow<ExportStatus?> = _status.asStateFlow()
    }
}
