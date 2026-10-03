package com.wing.folderplayer.data.artwork

import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.source.SourcePath
import com.wing.folderplayer.data.source.SourceRef

/**
 * Folder image selection:
 *  1. jpg/jpeg/png directly in the folder;
 *  2. base names cover → folder → album → front → disk (case-insensitive), fixed order;
 *  3. same priority name: jpg → jpeg → png; then every other image sorted by name;
 *  4. if nothing usable directly and the folder name has ≤ 6 characters (CD1, Disc 2…), the parent folder once,
 *     never above the source root; sub folders (e.g. Artwork/) are never searched.
 */
object ArtworkRules {
    val PRIORITY = listOf("cover", "folder", "album", "front", "disk")
    val EXTENSIONS = listOf("jpg", "jpeg", "png")
    const val SHORT_FOLDER_NAME = 6

    fun candidates(entries: List<MusicFile>): List<MusicFile> {
        val images = entries.filter { !it.isDirectory && SourcePath.extension(it.name) in EXTENSIONS }
        val prioritized = images
            .filter { SourcePath.baseName(it.name).lowercase() in PRIORITY }
            .sortedWith(
                compareBy<MusicFile>(
                    { PRIORITY.indexOf(SourcePath.baseName(it.name).lowercase()) },
                    { EXTENSIONS.indexOf(SourcePath.extension(it.name)) },
                    { it.name },
                )
            )
        val others = (images - prioritized.toSet()).sortedWith(compareBy<MusicFile>({ it.name.lowercase() }, { it.name }))
        return prioritized + others
    }

    /** Parent fallback applies to short folder names and never leaves the source root. */
    fun parentForFallback(folderPath: String): String? {
        val p = SourcePath.normalize(folderPath)
        if (p == SourcePath.ROOT) return null
        if (SourcePath.name(p).length > SHORT_FOLDER_NAME) return null
        return SourcePath.parent(p)
    }
}

sealed class ArtworkResult {
    data class Found(val image: SourceRef, val entry: MusicFile) : ArtworkResult()
    object None : ArtworkResult()
    object Disabled : ArtworkResult()
    object WifiRequired : ArtworkResult()
    data class Failed(val kind: Kind, val message: String) : ArtworkResult()

    enum class Kind { PERMISSION, NETWORK, DECODE, OTHER }

    companion object {
        fun fromException(e: Throwable): Failed = when (e) {
            is SourceException.PermissionDenied, is SourceException.AuthFailed -> Failed(Kind.PERMISSION, e.message.orEmpty())
            is SourceException.Unreachable, is SourceException.PrematureEof, is java.net.SocketException,
            is java.net.SocketTimeoutException -> Failed(Kind.NETWORK, e.message.orEmpty())
            else -> Failed(Kind.OTHER, e.message ?: e.javaClass.simpleName)
        }
    }
}

/**
 * Resolves a folder's image, trying candidates in order and skipping ones that fail to read/decode. [lister] lists a
 * folder; [validate] reads + decodes an image and returns false for a broken image. It throws for I/O errors: the
 * next candidate is still tried, and if none works the I/O error is reported (network/permission), never "no image"
 * and never as an exception to the caller (a cover must not abort playback).
 */
class ArtworkResolver(
    private val lister: (SourceRef) -> List<MusicFile>,
    private val validate: (MusicFile) -> Boolean,
) {
    fun resolve(folder: SourceRef, known: List<MusicFile>? = null): ArtworkResult {
        val entries = known ?: try { lister(folder) } catch (e: java.io.IOException) { return ArtworkResult.fromException(e) }
        var decodeFailures = 0
        var ioError: java.io.IOException? = null
        fun tryCandidate(c: MusicFile): Boolean = try {
            validate(c)
        } catch (e: java.io.IOException) {
            ioError = ioError ?: e
            false
        }
        val direct = ArtworkRules.candidates(entries)
        for (c in direct) {
            if (tryCandidate(c)) return ArtworkResult.Found(SourceRef(folder.sourceId, c.path), c) else if (ioError == null) decodeFailures++
        }
        val parent = ArtworkRules.parentForFallback(folder.path)
        if (parent != null) {
            val parentEntries = try { lister(SourceRef(folder.sourceId, parent)) } catch (e: java.io.IOException) {
                return if (direct.isEmpty()) ArtworkResult.fromException(e) else ArtworkResult.Failed(ArtworkResult.Kind.DECODE, "all images unreadable")
            }
            for (c in ArtworkRules.candidates(parentEntries)) {
                if (tryCandidate(c)) return ArtworkResult.Found(SourceRef(folder.sourceId, c.path), c) else if (ioError == null) decodeFailures++
            }
        }
        ioError?.let { return ArtworkResult.fromException(it) }
        return if (decodeFailures > 0) ArtworkResult.Failed(ArtworkResult.Kind.DECODE, "$decodeFailures image(s) could not be decoded")
        else ArtworkResult.None
    }
}
