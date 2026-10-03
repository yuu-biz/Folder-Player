package com.wing.folderplayer.playback

import androidx.core.content.edit

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
            val dedupKey = "$key|${st.size}"
            val index = loadIndex()
            val folder = ref.parent?.let { SourcePath.name(it.path) }?.ifBlank { null } ?: cfg.name
            val relDir = "${Environment.DIRECTORY_MUSIC}/${sanitize(settings.folderName)}/${sanitize(folder)}"
            if (index[dedupKey] != null && outputExists(relDir, ref.name, st.size)) {
                publish(ExportStatus(key, ExportStatus.State.SKIPPED_DUPLICATE, ref.name))
                return
            }
            if (outputExists(relDir, ref.name, st.size)) {
                index[dedupKey] = "$relDir/${ref.name}"
                saveIndex(index)
                publish(ExportStatus(key, ExportStatus.State.SKIPPED_DUPLICATE, ref.name))
                return
            }
            publish(ExportStatus(key, ExportStatus.State.DOWNLOADING, ref.name))
            val free = minOf(StatFs(context.cacheDir.path).availableBytes, StatFs(Environment.getExternalStorageDirectory().path).availableBytes)
            ExportCore.download(fs, ref.path, st.size, tmp, free)
            coroutineContext.ensureActive()
            val saved = writeToMusic(tmp, relDir, ref.name)
            index[dedupKey] = saved
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

    private fun outputExists(relDir: String, name: String, size: Long): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val c = context.contentResolver.query(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}=?",
                arrayOf("$relDir/", name, size.toString()), null
            ) ?: return false
            return c.use { it.count > 0 }
        }
        val f = File(Environment.getExternalStorageDirectory(), "$relDir/$name")
        return f.exists() && f.length() == size
    }

    private fun writeToMusic(tmp: File, relDir: String, name: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$relDir/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri, "w")?.use { out -> tmp.inputStream().use { it.copyTo(out, 256 * 1024) } }
                    ?: throw IOException("cannot open MediaStore output")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return uri.toString()
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        val dir = File(Environment.getExternalStorageDirectory(), relDir).apply { mkdirs() }
        val out = File(dir, name)
        val part = File(dir, ".$name.part")
        tmp.copyTo(part, overwrite = true)
        if (!part.renameTo(out)) { part.delete(); throw IOException("cannot move into $dir") }
        MediaScannerConnection.scanFile(context, arrayOf(out.path), null, null)
        return out.path
    }

    companion object {
        private const val TAG = "PlaybackExport"
        private val _status = MutableStateFlow<ExportStatus?>(null)
        val status: StateFlow<ExportStatus?> = _status.asStateFlow()
    }
}
