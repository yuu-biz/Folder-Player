package com.wing.folderplayer.utils

import androidx.core.content.edit

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language: System default, English, Simplified/Traditional Chinese, French, Italian.
 * Android 13+ stores it as the per-app language; older versions wrap the activity context.
 */
object AppLocale {
    val TAGS = listOf("", "en", "zh-CN", "zh-TW", "fr", "it")
    private const val PREFS = "ui_prefs"
    private const val KEY = "app_language"

    fun get(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun set(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, tag) }
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
    }

    fun locale(tag: String): Locale? = if (tag.isEmpty()) null else Locale.forLanguageTag(tag)

    /** Context with the chosen locale applied (pre-33 path; harmless on 33+). */
    fun wrap(base: Context): Context {
        val loc = locale(get(base)) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(loc))
        return base.createConfigurationContext(config)
    }
}
