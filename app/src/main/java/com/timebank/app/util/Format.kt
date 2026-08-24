package com.timebank.app.util

import com.timebank.app.data.ActivityState
import java.util.Locale
import kotlin.math.abs

fun formatMoney(v: Double): String =
    "$" + String.format(Locale.US, "%,.1f", v)

/** Signed rate, e.g. "+$10.0" or "-$30.0". */
fun formatRate(v: Double): String {
    val sign = if (v >= 0) "+" else "-"
    return sign + "$" + String.format(Locale.US, "%,.1f", abs(v))
}

fun stateLabel(state: ActivityState): String = when (state) {
    ActivityState.STOPPED -> "Stopped"
    ActivityState.SCREEN_OFF -> "Screen off — earning"
    ActivityState.MEDIA -> "Media playing"
    ActivityState.APP -> "App open — spending"
    ActivityState.NEUTRAL -> "Home / TimeBank — idle"
}
