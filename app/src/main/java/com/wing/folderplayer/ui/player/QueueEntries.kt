package com.wing.folderplayer.ui.player

import com.wing.folderplayer.data.playlist.PlaylistItem
import com.wing.folderplayer.data.source.SourceUris

/**
 * The player queue as the entries of the playlist it was built from: queue item `q` plays entry [entryOf]`[q]`. The queue
 * holds the entries in their order, but not always all of them: entries that cannot play are left out when it is built
 * (a removed source, a CUE sheet that cannot be read), and songs added to the list are not queued. Edits of the list map
 * to the queue through it, and edits of the queue to the list, so that no entry is lost.
 */
class QueueEntries(private val entryOf: IntArray, private val entryCount: Int) {
    /** The queue item of entry [entry], or null when the entry is not queued. */
    fun queueIndexOf(entry: Int): Int? = entryOf.indexOf(entry).takeIf { it >= 0 }

    /** Where the queue starts for a tap on entry [entry]: at its item, else at the next entry that is queued (null: none). */
    fun startFor(entry: Int): Int? = entryOf.indexOfFirst { it >= entry }.takeIf { it >= 0 }

    /** The list position of songs inserted at queue position [at]: right after the entry before them; the list end for the queue end. */
    fun entryIndexForInsert(at: Int): Int = when {
        at >= entryOf.size -> entryCount
        at <= 0 -> 0
        else -> entryOf[at - 1] + 1
    }

    /** The queue position of an entry put into the list at [entry]: after the queued entries before it. */
    fun queueIndexForInsert(entry: Int): Int = entryOf.count { it < entry }

    /** The queue move (from, to) for the entry moving from [from] to [to] in the list; null when that entry is not queued. */
    fun queueMove(from: Int, to: Int): Pair<Int, Int>? {
        val queueFrom = queueIndexOf(from) ?: return null
        // Queued entries that end up before the moved one.
        val queueTo = entryOf.count { it != from && (if (from < to) it <= to else it < to) }
        return queueFrom to queueTo
    }

    companion object {
        /** The queue [queueIds] as [entries]; null when it holds an item that is not the next of the entries. */
        fun align(entries: List<PlaylistItem>, queueIds: List<String>): QueueEntries? {
            val entryOf = IntArray(queueIds.size)
            var e = 0
            for (q in queueIds.indices) {
                while (e < entries.size && !entries[e].matchesMediaId(queueIds[q])) e++
                if (e == entries.size) return null
                entryOf[q] = e++
            }
            return QueueEntries(entryOf, entries.size)
        }
    }
}

fun PlaylistItem.matchesMediaId(mediaId: String?): Boolean {
    if (mediaId == null) return false
    val r = ref ?: return false
    if (cueStartMs != null) {
        return SourceUris.isCueTrackId(mediaId) && SourceUris.cueStartMs(mediaId) == cueStartMs &&
            SourceUris.parse(mediaId)?.sourceId == r.sourceId
    }
    return mediaId == r.toUriString()
}
