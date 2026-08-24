package com.timebank.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
        val OVERRIDES = stringPreferencesKey("app_overrides")
        val PRIVATE = doublePreferencesKey("private_surcharge")
    }

    val configFlow: Flow<EconomyConfig> = context.dataStore.data.map { p ->
        val d = EconomyConfig()
        EconomyConfig(
            offRatePerMin = p[Keys.OFF] ?: d.offRatePerMin,
            mediaRatePerMin = p[Keys.MEDIA] ?: d.mediaRatePerMin,
            appCostPerMin = p[Keys.APP] ?: d.appCostPerMin,
            earnMultiplier = p[Keys.MULT] ?: d.earnMultiplier,
            lockWhenBroke = p[Keys.LOCK] ?: d.lockWhenBroke,
            appOverrides = decodeOverrides(p[Keys.OVERRIDES]),
            privateSurchargePerMin = p[Keys.PRIVATE] ?: d.privateSurchargePerMin
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
            it[Keys.OVERRIDES] = encodeOverrides(c.appOverrides)
            it[Keys.PRIVATE] = c.privateSurchargePerMin
        }
    }
}

/*
 * Preferences DataStore has no map type, so overrides ride in one string as
 * "pkg=cost;pkg=cost". Package names can contain neither '=' nor ';', so a plain
 * split is unambiguous and needs no JSON dependency.
 */

internal fun encodeOverrides(m: Map<String, Double>): String =
    m.entries.joinToString(";") { "${it.key}=${it.value}" }

internal fun decodeOverrides(s: String?): Map<String, Double> {
    if (s.isNullOrBlank()) return emptyMap()
    return s.split(";").mapNotNull { entry ->
        val i = entry.lastIndexOf('=')
        if (i <= 0) return@mapNotNull null
        val cost = entry.substring(i + 1).toDoubleOrNull() ?: return@mapNotNull null
        entry.substring(0, i) to cost
    }.toMap()
}
