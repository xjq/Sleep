package com.self.sleepcollect.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "sleep_collect_prefs")

/**
 * 持久化用户填写的后端完整上报 URL。默认为空，不内置真实服务器地址。
 */
class UserPreferences(private val context: Context) {

    val backendUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_BACKEND_URL].orEmpty()
    }

    suspend fun setBackendUrl(url: String) {
        context.dataStore.edit { it[KEY_BACKEND_URL] = url.trim() }
    }

    private companion object {
        val KEY_BACKEND_URL = stringPreferencesKey("backend_url")
    }
}
