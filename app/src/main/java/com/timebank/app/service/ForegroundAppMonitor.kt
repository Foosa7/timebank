package com.timebank.app.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Reports the package name currently in the foreground using UsageStats.
 * Requires the "Usage access" special permission. There is no push callback,
 * so we scan the recent event window each tick and remember the last result.
 */
class ForegroundAppMonitor(context: Context) {

    private val usm =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var lastPackage: String? = null

    fun currentForegroundPackage(): String? {
        val end = System.currentTimeMillis()
        val begin = end - WINDOW_MS
        try {
            val events = usm.queryEvents(begin, end)
            val e = UsageEvents.Event()
            var pkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    pkg = e.packageName
                }
            }
            if (pkg != null) lastPackage = pkg
        } catch (_: Exception) {
            // No permission yet, or transient failure -> keep last known value.
        }
        return lastPackage
    }

    private companion object {
        const val WINDOW_MS = 12_000L
    }
}
