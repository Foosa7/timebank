package com.timebank.app.data

/** Tiny service locator so the foreground service and UI share one repository. */
object AppGraph {
    lateinit var settings: SettingsRepository
}
