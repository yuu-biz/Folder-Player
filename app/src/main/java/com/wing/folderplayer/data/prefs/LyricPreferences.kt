package com.wing.folderplayer.data.prefs

import androidx.core.content.edit

import android.content.Context
import android.content.SharedPreferences
import com.wing.folderplayer.data.ai.AiConfig
import com.wing.folderplayer.data.source.SourceRegistry

class LyricPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("lyric_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LYRIC_API_URL = "lyric_api_url"
        private const val DEFAULT_LYRIC_API_URL = "https://api.lrc.cx/lyrics"
        private const val AI_KEY_REF = "ai_api_key"
    }

    fun getLyricApiUrl(): String {
        return prefs.getString(KEY_LYRIC_API_URL, DEFAULT_LYRIC_API_URL) ?: DEFAULT_LYRIC_API_URL
    }

    fun setLyricApiUrl(url: String) {
        prefs.edit { putString(KEY_LYRIC_API_URL, url) }
    }

    fun getGeminiApiKey(): String {
        return prefs.getString("gemini_api_key", "") ?: ""
    }

    fun setGeminiApiKey(key: String) {
        prefs.edit { putString("gemini_api_key", key) }
    }

    fun getAiBaseUrl(): String {
        return prefs.getString("ai_base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
    }

    fun setAiBaseUrl(url: String) {
        prefs.edit { putString("ai_base_url", url) }
    }

    /** The API key is kept in the encrypted credential store; a public-main plaintext value is moved there once. */
    fun getAiApiKey(): String {
        val legacy = prefs.getString("ai_api_key", null)
        if (SourceRegistry.isInitialized) {
            if (!legacy.isNullOrEmpty()) {
                SourceRegistry.credentials.put(AI_KEY_REF, legacy)
                prefs.edit { remove("ai_api_key") }
                return legacy
            }
            return SourceRegistry.credentials.get(AI_KEY_REF) ?: ""
        }
        return legacy ?: ""
    }

    fun setAiApiKey(key: String) {
        if (SourceRegistry.isInitialized) {
            if (key.isEmpty()) SourceRegistry.credentials.remove(AI_KEY_REF) else SourceRegistry.credentials.put(AI_KEY_REF, key)
            prefs.edit { remove("ai_api_key") }
        } else {
            prefs.edit { putString("ai_api_key", key) }
        }
    }

    fun getAiModel(): String {
        return prefs.getString("ai_model", "gpt-3.5-turbo") ?: "gpt-3.5-turbo"
    }

    fun setAiModel(model: String) {
        prefs.edit { putString("ai_model", model) }
    }

    fun aiConfig(): AiConfig = AiConfig(getAiBaseUrl(), getAiApiKey(), getAiModel())

    /** Automatic AI lyrics lookup. OFF by default — no AI request is made unless enabled or asked for. */
    var aiLyricsAuto: Boolean
        get() = prefs.getBoolean("ai_lyrics_auto", false)
        set(v) = prefs.edit { putBoolean("ai_lyrics_auto", v) }

    /** "LOCAL_FIRST" (LRC / embedded / lyric API before AI, default) or "AI_FIRST". */
    var lyricsPriority: String
        get() = prefs.getString("lyrics_priority", "LOCAL_FIRST") ?: "LOCAL_FIRST"
        set(v) = prefs.edit { putString("lyrics_priority", v) }

    /** Language requested for AI lyrics ("" = original). */
    var aiLyricsLanguage: String
        get() = prefs.getString("ai_lyrics_language", "") ?: ""
        set(v) = prefs.edit { putString("ai_lyrics_language", v) }

    /** Target language for AI translation ("" = no translation). */
    var translationTarget: String
        get() = prefs.getString("translation_target", "") ?: ""
        set(v) = prefs.edit { putString("translation_target", v) }

    fun clear() {
        prefs.edit { clear() }
    }
}
