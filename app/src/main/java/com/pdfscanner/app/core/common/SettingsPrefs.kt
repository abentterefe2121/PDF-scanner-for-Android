package com.pdfscanner.app.core.common

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pdfscanner.app.core.vision.OpenCvHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "scanner_prefs")

@Singleton
class SettingsPrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_FILTER = stringPreferencesKey("default_filter")
        private val KEY_AUTO_CAPTURE = booleanPreferencesKey("auto_capture")
        private val KEY_BATCH_MODE = booleanPreferencesKey("batch_mode")
        private val KEY_LANGUAGE = stringPreferencesKey("app_language")
    }

    val defaultFilter: Flow<OpenCvHelper.Filter> = context.dataStore.data.map { prefs ->
        val name = prefs[KEY_FILTER] ?: OpenCvHelper.Filter.MAGIC.name
        runCatching { OpenCvHelper.Filter.valueOf(name) }.getOrDefault(OpenCvHelper.Filter.MAGIC)
    }

    val autoCapture: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_CAPTURE] ?: true
    }

    val batchMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BATCH_MODE] ?: false
    }

    /** "system", "en", or "am" */
    val appLanguage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LANGUAGE] ?: "system"
    }

    suspend fun setDefaultFilter(filter: OpenCvHelper.Filter) {
        context.dataStore.edit { it[KEY_FILTER] = filter.name }
    }

    suspend fun setAutoCapture(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_CAPTURE] = enabled }
    }

    suspend fun setBatchMode(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BATCH_MODE] = enabled }
    }

    suspend fun setAppLanguage(tag: String) {
        context.dataStore.edit { it[KEY_LANGUAGE] = tag }
    }
}
