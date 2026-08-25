package com.timebank.app

import android.app.Application
import com.timebank.app.data.AppGraph
import com.timebank.app.data.Economy
import com.timebank.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TimeBankApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val repo = SettingsRepository(applicationContext)
        AppGraph.settings = repo

        // Seed the balance from disk once at startup. A fresh install has nothing stored,
        // and debug builds start it at DEBUG_START_BALANCE so the cover charge and the
        // per-minute drain can be exercised without first earning a float. Release builds
        // still start at $0.
        scope.launch {
            Economy.balance.value = repo.readBalanceOnce()
                ?: if (BuildConfig.DEBUG) DEBUG_START_BALANCE else 0.0
        }

        // Keep the live config in sync with whatever is stored.
        scope.launch { repo.configFlow.collect { Economy.config.value = it } }
    }

    companion object {
        /** Debug-only opening balance, so a fresh install has something to spend. */
        const val DEBUG_START_BALANCE = 100.0
    }
}
