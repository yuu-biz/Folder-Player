package com.wing.folderplayer.utils

import java.io.File

/**
 * Development-only "safe mode" inherited from public main: a folder named `Init` / `init` in the shared Music folder
 * makes a **debug** build forget its saved state on every start — playback state and cached metadata, browser state
 * (sorting, view modes *and the configured sources*, which live in the same preferences file), lyrics / AI settings —
 * and start without connecting the player, so a state that crashes on start can be escaped while developing.
 *
 * Release builds never look for the folder: an ordinary folder name must not erase anything a user set up.
 */
object DevSafeMode {
    fun requested(musicDir: File?, debugBuild: Boolean): Boolean =
        debugBuild && musicDir != null && (File(musicDir, "Init").exists() || File(musicDir, "init").exists())
}
