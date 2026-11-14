package com.example.data.firebase

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tilevault_prefs")

enum class AppLanguage { EN, HI }

class PreferencesRepository(private val context: Context) {
    private val langKey = stringPreferencesKey("app_language")
    private val activeShopIdKey = stringPreferencesKey("active_shop_id")

    val languageFlow: Flow<AppLanguage> = context.dataStore.data.map { prefs ->
        when (prefs[langKey]) {
            "HI" -> AppLanguage.HI
            else -> AppLanguage.EN
        }
    }

    val activeShopIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[activeShopIdKey] ?: ""
    }

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { it[langKey] = language.name }
    }

    suspend fun setActiveShopId(shopId: String) {
        context.dataStore.edit { it[activeShopIdKey] = shopId }
    }
}
