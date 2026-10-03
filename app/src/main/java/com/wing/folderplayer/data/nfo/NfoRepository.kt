package com.wing.folderplayer.data.nfo

import com.wing.folderplayer.data.source.SourceCapability
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef
import com.wing.folderplayer.data.source.SourceRegistry
import com.wing.folderplayer.data.source.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

sealed class NfoSaveResult {
    object Saved : NfoSaveResult()
    /** Info.nfo already exists; the caller must confirm replacing it and call again with overwrite = true. */
    object NeedsConfirmation : NfoSaveResult()
    data class NotWritable(val reason: String) : NfoSaveResult()
    data class Failed(val reason: String) : NfoSaveResult()
}

/** Finds, parses and (only on explicit request) writes album NFO files in a folder of any source. */
object NfoRepository {
    const val INFO_NFO = "Info.nfo"
    private const val MAX_NFO_BYTES = 1L * 1024 * 1024

    suspend fun find(folder: SourceRef): NfoInfo? = runInterruptible(Dispatchers.IO) {
        val fs = SourceRegistry.fileSystem(folder)
        val entries = try { fs.list(folder.path) } catch (e: SourceException) { return@runInterruptible null }
        for (c in NfoParser.candidates(entries)) {
            try {
                return@runInterruptible NfoParser.parse(fs.readBytes(c.path, MAX_NFO_BYTES), c.name)
            } catch (e: NfoRejectedException) {
                android.util.Log.w("NfoRepository", "${c.name}: ${e.message}")
            } catch (e: SourceException) {
                android.util.Log.w("NfoRepository", "${c.name}: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.w("NfoRepository", "${c.name} unreadable: ${e.message}")
            }
        }
        null
    }

    fun canWrite(folder: SourceRef): Boolean =
        SourceRegistry.get(folder.sourceId) != null && SourceCapability.WRITE in SourceRegistry.fileSystem(folder).capabilities

    suspend fun save(folder: SourceRef, xml: String, overwrite: Boolean): NfoSaveResult = runInterruptible(Dispatchers.IO) {
        if (!canWrite(folder)) return@runInterruptible NfoSaveResult.NotWritable("source is read-only")
        val fs = SourceRegistry.fileSystem(folder)
        val target = SourcePath.child(folder.path, INFO_NFO)
        try {
            val existing = fs.list(folder.path).firstOrNull { !it.isDirectory && it.name.equals(INFO_NFO, true) }
            if (existing != null && !overwrite) return@runInterruptible NfoSaveResult.NeedsConfirmation
            fs.write(existing?.path ?: target, xml.toByteArray(Charsets.UTF_8), overwrite = true)
            NfoSaveResult.Saved
        } catch (e: SourceException.PermissionDenied) {
            NfoSaveResult.NotWritable(e.message ?: "permission denied")
        } catch (e: SourceException.ReadOnly) {
            NfoSaveResult.NotWritable(e.message ?: "read-only")
        } catch (e: Exception) {
            NfoSaveResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
