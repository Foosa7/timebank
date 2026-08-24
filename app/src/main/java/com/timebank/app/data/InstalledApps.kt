package com.timebank.app.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

/** One launchable app the user can put a per-minute price on. */
data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Bitmap?
)

/**
 * Every app with a launcher entry, alphabetised, minus TimeBank itself — pricing
 * our own screen would be pointless since it accounts as NEUTRAL anyway.
 * Icons are decoded once here rather than per-recomposition; the list is small
 * enough (a few hundred at worst) to hold in memory while the picker is open.
 */
fun loadLaunchableApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .asSequence()
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { info ->
            AppInfo(
                packageName = info.packageName,
                label = pm.getApplicationLabel(info).toString(),
                icon = runCatching { info.loadIcon(pm).toBitmap(ICON_PX, ICON_PX) }.getOrNull()
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

/** Resolve a stored package name back to a display label, falling back to the raw name. */
fun labelFor(context: Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
}.getOrDefault(packageName)

private const val ICON_PX = 96
