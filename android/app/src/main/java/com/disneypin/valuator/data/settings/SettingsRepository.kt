package com.disneypin.valuator.data.settings

import android.content.Context
import com.disneypin.valuator.BuildConfig
import com.disneypin.valuator.data.model.AppSettings

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): AppSettings {
        val storedKey = prefs.getString(KEY_GEMINI_API_KEY, "")?.trim().orEmpty()
        val effectiveKey = storedKey.ifEmpty { BuildConfig.DEFAULT_GEMINI_API_KEY }
        val model = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL)?.trim().orEmpty()
            .ifEmpty { DEFAULT_MODEL }
        val staleDays = prefs.getInt(KEY_STALE_PRICE_DAYS, DEFAULT_STALE_DAYS)
            .coerceIn(7, 365)
        return AppSettings(
            geminiApiKey = effectiveKey,
            geminiModel = model,
            stalePriceDays = staleDays
        )
    }

    fun saveSettings(apiKey: String, model: String, stalePriceDays: Int) {
        prefs.edit()
            .putString(KEY_GEMINI_API_KEY, apiKey.trim())
            .putString(KEY_GEMINI_MODEL, model.trim().ifEmpty { DEFAULT_MODEL })
            .putInt(KEY_STALE_PRICE_DAYS, stalePriceDays.coerceIn(7, 365))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "disney_pin_valuator_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_STALE_PRICE_DAYS = "stale_price_days"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        const val DEFAULT_STALE_DAYS = 60
    }
}
