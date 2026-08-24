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

        // Seed the balance from disk once at startup.
        scope.launch { Economy.balance.value = repo.readBalanceOnce() }

        // Keep the live config in sync with whatever is stored.
        scope.launch { repo.configFlow.collect { Economy.config.value = it } }
    }
}
