package com.timebank.app.util

import com.timebank.app.data.ActivityState
import java.util.Locale
import kotlin.math.abs

fun formatMoney(v: Double): String =
    "$" + String.format(Locale.US, "%,.1f", v)

/** Signed rate, e.g. "+$1.0" or "-$11.0". */
fun formatRate(v: Double): String {
    val sign = if (v >= 0) "+" else "-"
    return sign + "$" + String.format(Locale.US, "%,.1f", abs(v))
}

/**
 * The balance squeezed down for the status-bar icon, which is a 24dp square: no decimals,
 * and abbreviated past a thousand so a full day's earnings still fit. The "$" is kept —
 * a bare "83" in the status bar reads as a notification count or a stray number, and the
 * icon renderer shrinks to fit, so the extra glyph costs legibility rather than meaning.
 */
fun formatCompactMoney(v: Double): String {
    // Thresholds are where each bucket *rounds up* into the next, not the round numbers,
    // so nothing ever renders as "1000" or "10.0k" on its way over a boundary.
    val n = abs(v)
    return "$" + when {
        n < 999.5 -> String.format(Locale.US, "%.0f", n)
        n < 9_950 -> String.format(Locale.US, "%.1fk", n / 1_000)
        n < 999_500 -> String.format(Locale.US, "%.0fk", n / 1_000)
        else -> String.format(Locale.US, "%.1fM", n / 1_000_000)
    }
}

fun stateLabel(state: ActivityState): String = when (state) {
    ActivityState.STOPPED -> "Stopped"
    ActivityState.SCREEN_OFF -> "Screen off — earning"
    ActivityState.MEDIA -> "Media playing"
    ActivityState.APP -> "App open — spending"
    ActivityState.NEUTRAL -> "Home / TimeBank — idle"
    ActivityState.COVER -> "Cover charge — waiting"
}
