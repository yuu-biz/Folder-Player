package com.wing.folderplayer.data.playlist

/**
 * Where the queue was left, for the next start. The queue is always the playlist [playlistId] ("Default" unless it was
 * played from one of the user's playlists), so only this pointer is kept: the item at [index] ([mediaId]), the position
 * in it and the shuffle switch.
 */
data class SavedQueue(val playlistId: String, val index: Int, val mediaId: String, val positionMs: Long, val shuffle: Boolean) {
    /**
     * Where the rebuilt queue [ids] starts: at [index] when it still holds [mediaId] (a queue can hold a song twice),
     * else at the occurrence of [mediaId] nearest to it; null when the queue no longer holds it.
     */
    fun startIn(ids: List<String>): Int? {
        if (ids.getOrNull(index) == mediaId) return index
        return ids.indices.filter { ids[it] == mediaId }.minByOrNull { kotlin.math.abs(it - index) }
    }
}
