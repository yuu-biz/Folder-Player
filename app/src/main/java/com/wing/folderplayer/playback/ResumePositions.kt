package com.wing.folderplayer.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wing.folderplayer.data.source.SourceUris

/**
 * Where each long file was left, for the option "remember where each long file was left": files of at least
 * [MIN_DURATION_MS], by media id, the [MAX_ENTRIES] used last. A file left in its first or its last seconds is forgotten.
 */
class ResumePositions(read: () -> String?, private val write: (String) -> Unit) {
    private val gson = Gson()
    // Access order: the least recently played or left file is the first one dropped.
    private val positions = LinkedHashMap<String, Long>(16, 0.75f, true).apply {
        val type = object : TypeToken<LinkedHashMap<String, Long>>() {}.type
        runCatching { read()?.let { gson.fromJson<LinkedHashMap<String, Long>>(it, type) } }.getOrNull()?.let { putAll(it) }
    }

    fun positionOf(id: String): Long? = positions[id]

    fun leave(id: String, positionMs: Long, durationMs: Long) {
        if (durationMs < MIN_DURATION_MS) return
        if (positionMs < EDGE_MS || positionMs > durationMs - EDGE_MS) {
            if (positions.remove(id) == null) return
        } else {
            positions[id] = positionMs
            while (positions.size > MAX_ENTRIES) positions.remove(positions.keys.first())
        }
        write(gson.toJson(positions))
    }

    companion object {
        const val MIN_DURATION_MS = 10 * 60_000L
        const val EDGE_MS = 10_000L
        const val MAX_ENTRIES = 200

        fun of(context: Context): ResumePositions {
            val prefs = context.getSharedPreferences("resume_positions", Context.MODE_PRIVATE)
            return ResumePositions({ prefs.getString("positions", null) }, { prefs.edit { putString("positions", it) } })
        }
    }
}

/**
 * Keeps [ResumePositions] for the service's player while [enabled]: a file is left on a track change, a pause, the end
 * of the queue or when the service stops, and starts again where it was left when it comes back at its beginning (a
 * play request that seeks elsewhere, like the restore of the last session, keeps its position). CUE tracks are slices
 * of one file and are not kept.
 */
class ResumeTracker(private val player: Player, private val enabled: () -> Boolean, private val store: ResumePositions) : Player.Listener {
    // The current file's length once known: when a file is left, the timeline may already be the next queue's.
    private var lengthOf: Pair<String, Long>? = null

    fun attach() = player.addListener(this)

    fun detach() {
        leave(player.currentMediaItem, player.currentPosition)
        player.removeListener(this)
    }

    private fun idOf(item: MediaItem?): String? = item?.mediaId?.takeUnless { it.isEmpty() || SourceUris.isCueTrackId(it) }

    private fun leave(item: MediaItem?, positionMs: Long) {
        if (!enabled()) return
        val id = idOf(item) ?: return
        val length = lengthOf?.takeIf { it.first == id }?.second ?: return
        store.leave(id, positionMs, length)
    }

    // After the single callbacks: a file being left is still the one noted here. A gapless change to the next file
    // changes no playback state, so the length is taken whenever it is known.
    override fun onEvents(player: Player, events: Player.Events) {
        val id = idOf(player.currentMediaItem) ?: return
        player.duration.takeIf { it > 0 }?.let { lengthOf = id to it }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) leave(player.currentMediaItem, player.currentPosition)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) leave(player.currentMediaItem, player.currentPosition)
    }

    @OptIn(UnstableApi::class) // PositionInfo.mediaItem
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
            reason == Player.DISCONTINUITY_REASON_REMOVE) {
            leave(oldPosition.mediaItem, oldPosition.positionMs)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (!enabled() || player.currentPosition > START_TOLERANCE_MS) return
        val at = store.positionOf(idOf(mediaItem) ?: return) ?: return
        player.seekTo(at)
    }

    private companion object {
        const val START_TOLERANCE_MS = 1_000L
    }
}
