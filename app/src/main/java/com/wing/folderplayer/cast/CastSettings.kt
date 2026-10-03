package com.wing.folderplayer.cast

import androidx.core.content.edit

import android.content.Context

/** DLNA casting is OFF by default; the HTTP relay only runs while a cast session is active. */
class CastSettings(context: Context) {
    private val prefs = context.getSharedPreferences("cast_prefs", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit { putBoolean("enabled", v) }
}
