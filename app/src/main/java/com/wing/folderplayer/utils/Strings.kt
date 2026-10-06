package com.wing.folderplayer.utils

import androidx.annotation.StringRes
import com.wing.folderplayer.FolderPlayerApp

/**
 * Resource strings for code outside Compose (ViewModels, repositories), resolved in the language chosen in Settings
 * (before Android 13 the application context alone keeps the system language; from 13 on it follows the per-app language).
 */
object Strings {
    fun get(@StringRes id: Int, vararg args: Any): String = AppLocale.wrap(FolderPlayerApp.context).getString(id, *args)
}
