package com.wing.folderplayer.playback

/**
 * What "previous" does: the player's button and swipe, and the notification, lock screen and Bluetooth (MusicService),
 * so both follow the same option.
 */
enum class PreviousAction {
    PREVIOUS_TRACK, RESTART, NONE;

    companion object {
        const val RESTART_AFTER_MS = 3_000L

        /** [restartsSong]: the option "restart the song when more than 3 s in" (off: always the previous song). */
        fun of(restartsSong: Boolean, positionMs: Long, hasPrevious: Boolean): PreviousAction = when {
            restartsSong && (positionMs > RESTART_AFTER_MS || !hasPrevious) -> RESTART
            hasPrevious -> PREVIOUS_TRACK
            else -> NONE
        }
    }
}
