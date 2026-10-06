package com.wing.folderplayer.utils

import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.edit

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language: System default, Simplified Chinese, English, Japanese, Traditional Chinese, French, Italian.
 *
 * Android 13+: the system's per-app language (Settings > Apps > Folder Player Fork > Language, [LocaleManager]) is the
 * only source of truth. It is read, never copied: a change made in the system settings and one made in the app are the
 * same thing, the resources of the activity and of the application context follow it by themselves, and the context
 * is not wrapped. A language stored by an older version in the app's own preference is handed to the system once
 * ([migrate]) and not looked at again.
 *
 * Android 12 and older (no per-app language): the choice is kept in the app's preference and applied by wrapping the
 * activity context ([wrap], and again for texts outside the activity, see [Strings]).
 */
object AppLocale {
    /**
     * Languages offered in Settings, in the order they are listed: tag to its name in that language ("" = system default,
     * labelled by the UI). The Chinese of the original author comes first; English is one language among the others.
     */
    val LANGUAGES = listOf(
        "" to "", "zh-CN" to "简体中文", "en" to "English", "ja" to "日本語", "zh-TW" to "繁體中文", "fr" to "Français", "it" to "Italiano",
    )
    val TAGS = LANGUAGES.map { it.first }
    private const val PREFS = "ui_prefs"
    private const val KEY = "app_language"
    /** Set once the preference of an older version was handed to the system (Android 13+). */
    private const val KEY_MIGRATED = "app_language_os_migrated"

    @get:ChecksSdkIntAtLeast(api = 33)
    private val perAppLanguage get() = Build.VERSION.SDK_INT >= 33

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The chosen language, one of [TAGS] ("" = system default). */
    fun get(context: Context): String =
        if (perAppLanguage) matchTag(osLocales(context).takeIf { !it.isEmpty }?.get(0)?.toLanguageTag().orEmpty())
        else prefs(context).getString(KEY, "") ?: ""

    fun set(context: Context, tag: String) {
        if (perAppLanguage) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            prefs(context).edit { putString(KEY, tag) }
        }
    }

    /**
     * Android 13+, once: a language stored by an older version is handed to the system, unless the system already has a
     * per-app language. Afterwards the preference is ignored for good, so a later change in the system settings
     * (including back to "System default") is never overwritten by it. Call early at start-up.
     */
    fun migrate(context: Context) {
        if (!perAppLanguage) return
        val p = prefs(context)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        val legacy = p.getString(KEY, "") ?: ""
        if (shouldHandOver(legacy, osLocales(context).isEmpty)) set(context, legacy)
        p.edit { putBoolean(KEY_MIGRATED, true) }
    }

    /** Whether a stored language is handed to the system: only when there is one and the system has no choice of its own. */
    internal fun shouldHandOver(legacyTag: String, systemLanguageEmpty: Boolean): Boolean =
        legacyTag.isNotEmpty() && legacyTag in TAGS && systemLanguageEmpty

    @RequiresApi(33)
    private fun osLocales(context: Context): LocaleList =
        context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: LocaleList.getEmptyLocaleList()

    /**
     * Maps a language tag as the system reports it (zh-Hans-CN, fr-FR, ja-JP, …) to the matching entry of [TAGS];
     * "" for a language the app has no translation of.
     */
    fun matchTag(languageTag: String): String {
        if (languageTag.isEmpty()) return ""
        val loc = Locale.forLanguageTag(languageTag)
        return when (loc.language) {
            "zh" -> if (loc.script == "Hant" || loc.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            "en", "fr", "it", "ja" -> loc.language
            else -> ""
        }
    }

    fun locale(tag: String): Locale? = if (tag.isEmpty()) null else Locale.forLanguageTag(tag)

    /** Context with the chosen locale applied: Android 12 and older only; 13+ follows the system's per-app language by itself. */
    fun wrap(base: Context): Context {
        if (perAppLanguage) return base
        val loc = locale(get(base)) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(loc))
        return base.createConfigurationContext(config)
    }
}
