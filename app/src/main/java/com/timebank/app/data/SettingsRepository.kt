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
        val IDLE = doublePreferencesKey("idle_rate")
        val MEDIA = doublePreferencesKey("media_rate")
        val APP = doublePreferencesKey("app_cost")
        val MULT = doublePreferencesKey("earn_multiplier")
        val LOCK = booleanPreferencesKey("lock_when_broke")
        val BALANCE = doublePreferencesKey("balance")
        val OVERRIDES = stringPreferencesKey("app_overrides")
        val PRIVATE = doublePreferencesKey("private_surcharge")
        // Was a single double under "cover_charge" before the charge went per-app; the
        // old key is left behind rather than migrated, so upgrades start un-gated.
        val COVER = stringPreferencesKey("cover_charges")
        val HAPPY_HOURS = stringPreferencesKey("happy_hours")
        val HAPPY_APP = doublePreferencesKey("happy_app_cost")
        val HAPPY_COVER = doublePreferencesKey("happy_cover_charge")
        val SURGE_HOURS = stringPreferencesKey("surge_hours")
        val SURGE_APP = doublePreferencesKey("surge_app_cost")
        val SURGE_COVER = doublePreferencesKey("surge_cover_charge")
        val SLEEP_HOURS = stringPreferencesKey("sleep_hours")
        val SLEEP_RATE = doublePreferencesKey("sleep_off_rate")
    }

    val configFlow: Flow<EconomyConfig> = context.dataStore.data.map { p ->
        val d = EconomyConfig()
        EconomyConfig(
            offRatePerMin = p[Keys.OFF] ?: d.offRatePerMin,
            idleRatePerMin = p[Keys.IDLE] ?: d.idleRatePerMin,
            mediaRatePerMin = p[Keys.MEDIA] ?: d.mediaRatePerMin,
            appCostPerMin = p[Keys.APP] ?: d.appCostPerMin,
            earnMultiplier = p[Keys.MULT] ?: d.earnMultiplier,
            lockWhenBroke = p[Keys.LOCK] ?: d.lockWhenBroke,
            appOverrides = decodeOverrides(p[Keys.OVERRIDES]),
            privateSurchargePerMin = p[Keys.PRIVATE] ?: d.privateSurchargePerMin,
            coverCharges = decodeOverrides(p[Keys.COVER]),
            // A stored blank means "no windows left", which must survive as an empty list
            // rather than falling back to the defaults the user just deleted.
            happyHours = p[Keys.HAPPY_HOURS]?.let { decodeWindows(it) } ?: d.happyHours,
            happyAppCostPerMin = p[Keys.HAPPY_APP] ?: d.happyAppCostPerMin,
            happyCoverChargePerApp = p[Keys.HAPPY_COVER] ?: d.happyCoverChargePerApp,
            surgeHours = p[Keys.SURGE_HOURS]?.let { decodeWindows(it) } ?: d.surgeHours,
            surgeAppCostPerMin = p[Keys.SURGE_APP] ?: d.surgeAppCostPerMin,
            surgeCoverChargePerApp = p[Keys.SURGE_COVER] ?: d.surgeCoverChargePerApp,
            sleepHours = p[Keys.SLEEP_HOURS]?.let { decodeWindows(it) } ?: d.sleepHours,
            sleepOffRatePerMin = p[Keys.SLEEP_RATE] ?: d.sleepOffRatePerMin
        )
    }

    /** Null when nothing has ever been saved — a real stored $0 comes back as 0.0. */
    suspend fun readBalanceOnce(): Double? =
        context.dataStore.data.first()[Keys.BALANCE]

    suspend fun saveBalance(value: Double) {
        context.dataStore.edit { it[Keys.BALANCE] = value }
    }

    suspend fun saveConfig(c: EconomyConfig) {
        context.dataStore.edit {
            it[Keys.OFF] = c.offRatePerMin
            it[Keys.IDLE] = c.idleRatePerMin
            it[Keys.MEDIA] = c.mediaRatePerMin
            it[Keys.APP] = c.appCostPerMin
            it[Keys.MULT] = c.earnMultiplier
            it[Keys.LOCK] = c.lockWhenBroke
            it[Keys.OVERRIDES] = encodeOverrides(c.appOverrides)
            it[Keys.PRIVATE] = c.privateSurchargePerMin
            it[Keys.COVER] = encodeOverrides(c.coverCharges)
            it[Keys.HAPPY_HOURS] = encodeWindows(c.happyHours)
            it[Keys.HAPPY_APP] = c.happyAppCostPerMin
            it[Keys.HAPPY_COVER] = c.happyCoverChargePerApp
            it[Keys.SURGE_HOURS] = encodeWindows(c.surgeHours)
            it[Keys.SURGE_APP] = c.surgeAppCostPerMin
            it[Keys.SURGE_COVER] = c.surgeCoverChargePerApp
            it[Keys.SLEEP_HOURS] = encodeWindows(c.sleepHours)
            it[Keys.SLEEP_RATE] = c.sleepOffRatePerMin
        }
    }
}

/*
 * Preferences DataStore has no map type, so overrides ride in one string as
 * "pkg=cost;pkg=cost". Package names can contain neither '=' nor ';', so a plain
 * split is unambiguous and needs no JSON dependency.
 */

/** Same one-string trick as the overrides, as "12-13;19-20". */
internal fun encodeWindows(l: List<HourWindow>): String =
    l.joinToString(";") { "${it.startHour}-${it.endHour}" }

internal fun decodeWindows(s: String): List<HourWindow> {
    if (s.isBlank()) return emptyList()
    return s.split(";").mapNotNull { entry ->
        val parts = entry.split("-")
        if (parts.size != 2) return@mapNotNull null
        val start = parts[0].toIntOrNull() ?: return@mapNotNull null
        val end = parts[1].toIntOrNull() ?: return@mapNotNull null
        HourWindow(start.coerceIn(0, 23), end.coerceIn(0, 24))
    }
}

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
