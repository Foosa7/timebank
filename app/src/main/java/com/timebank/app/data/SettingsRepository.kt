package com.timebank.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "timebank")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val OFF = doublePreferencesKey("off_rate")
        val MEDIA = doublePreferencesKey("media_rate")
        val APP = doublePreferencesKey("app_cost")
        val MULT = doublePreferencesKey("earn_multiplier")
        val LOCK = booleanPreferencesKey("lock_when_broke")
        val BALANCE = doublePreferencesKey("balance")
    }

    val configFlow: Flow<EconomyConfig> = context.dataStore.data.map { p ->
        val d = EconomyConfig()
        EconomyConfig(
            offRatePerMin = p[Keys.OFF] ?: d.offRatePerMin,
            mediaRatePerMin = p[Keys.MEDIA] ?: d.mediaRatePerMin,
            appCostPerMin = p[Keys.APP] ?: d.appCostPerMin,
            earnMultiplier = p[Keys.MULT] ?: d.earnMultiplier,
            lockWhenBroke = p[Keys.LOCK] ?: d.lockWhenBroke
        )
    }

    suspend fun readBalanceOnce(): Double =
        context.dataStore.data.first()[Keys.BALANCE] ?: 0.0

    suspend fun saveBalance(value: Double) {
        context.dataStore.edit { it[Keys.BALANCE] = value }
    }

    suspend fun saveConfig(c: EconomyConfig) {
        context.dataStore.edit {
            it[Keys.OFF] = c.offRatePerMin
            it[Keys.MEDIA] = c.mediaRatePerMin
            it[Keys.APP] = c.appCostPerMin
            it[Keys.MULT] = c.earnMultiplier
            it[Keys.LOCK] = c.lockWhenBroke
        }
    }
}
