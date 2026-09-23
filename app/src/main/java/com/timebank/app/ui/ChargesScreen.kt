package com.timebank.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timebank.app.data.AppBaseline
import com.timebank.app.data.AppGraph
import com.timebank.app.data.Economy
import com.timebank.app.data.EconomyConfig
import com.timebank.app.data.Observations
import com.timebank.app.data.coverEquivalentPerMin
import com.timebank.app.data.labelFor
import com.timebank.app.data.excludingWork
import com.timebank.app.data.readObservations
import com.timebank.app.util.formatMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Per-app pricing, ordered by how much of your time each app actually takes.
 *
 * The Settings tab prices apps you go looking for; this prices the ones that matter, because
 * the list is built from measurement rather than from the launcher. On a phone where one app
 * is 60% of all screen time, an alphabetical picker buries the only row worth editing.
 *
 * Every row shows both instruments together and what each is worth in the other's unit. That
 * conversion is the point: a cover is worth `cover / minutes-per-visit` per minute, so the
 * same `$5` is a mild toll on a feed you sit in for four minutes and a punitive one on a
 * messaging app you touch for forty seconds. Setting a cover without seeing that number is
 * how the gate ends up somewhere it does real damage.
 */
@Composable
fun ChargesScreen() {
    val context = LocalContext.current
    val cfg by Economy.config.collectAsState()
    val scope = rememberCoroutineScope()
    var obs by remember { mutableStateOf<Observations?>(null) }

    LaunchedEffect(Unit) {
        obs = withContext(Dispatchers.IO) { readObservations(context) }
    }

    fun apply(next: EconomyConfig) {
        Economy.config.value = next // instant effect for the running service
        scope.launch(Dispatchers.IO) { AppGraph.settings.saveConfig(next) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Charges", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Your apps, heaviest first. The per-minute price meters how long you stay; the " +
                "cover charge prices the decision to open at all.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))

        val ready = remember(obs, cfg) { obs?.excludingWork(cfg) }
        when {
            ready == null -> Text("Reading your usage…", style = MaterialTheme.typography.bodySmall)

            ready.baseline.isEmpty -> Text(
                "No usage history yet, so there is nothing to rank. Grant Usage access on " +
                    "the Settings tab, or price apps by hand there.",
                style = MaterialTheme.typography.bodySmall
            )

            else -> {
                val total = ready.baseline.appMinutesPerDay
                ready.baseline.perApp.take(MAX_ROWS).forEach { app ->
                    AppChargeRow(app, total, cfg, ::apply)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Apps below the top ${MAX_ROWS} are on the default rate and no cover. " +
                        "Price them by hand on the Settings tab if you need to.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AppChargeRow(
    app: AppBaseline,
    totalMinutes: Double,
    cfg: EconomyConfig,
    apply: (EconomyConfig) -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val price = cfg.costFor(app.packageName)
    val cover = cfg.coverCharges[app.packageName] ?: 0.0
    val overridden = cfg.appOverrides.containsKey(app.packageName)

    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        labelFor(context, app.packageName),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${minutes(app.minutesPerDay)}/day · " +
                            "${(100 * app.minutesPerDay / totalMinutes).roundToInt()}% of your " +
                            "time · ${oneDp(app.meanSessionMin)} min a visit",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${formatMoney(price)}/min",
                        fontWeight = if (overridden) FontWeight.Bold else FontWeight.Normal
                    )
                    if (cover > 0.0) {
                        Text("+ ${formatMoney(cover)} to open", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // The conversion that makes the two instruments comparable. Shown whenever a
            // cover exists, because it is the number that says whether the gate is mild or
            // punitive for *this* app, and it is not guessable from the charge alone.
            if (cover > 0.0 && app.meanSessionMin > 0.0) {
                Text(
                    "That cover is worth " +
                        "${formatMoney(coverEquivalentPerMin(cover, app.meanSessionMin))}/min " +
                        "at your visit length, and about " +
                        "${formatMoney(cover * app.visitsPerDay)} a day at " +
                        "${app.visitsPerDay.roundToInt()} visits.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Done" else "Price this app")
            }

            if (expanded) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Per minute", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (overridden) formatMoney(price) else "default (${formatMoney(price)})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = price.toFloat().coerceIn(0f, MAX_RATE),
                    onValueChange = {
                        apply(
                            cfg.copy(
                                appOverrides = cfg.appOverrides + (app.packageName to it.toDouble())
                            )
                        )
                    },
                    valueRange = 0f..MAX_RATE
                )
                if (overridden) {
                    TextButton(onClick = {
                        apply(cfg.copy(appOverrides = cfg.appOverrides - app.packageName))
                    }) { Text("Back to the default rate") }
                }

                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Cover to open", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (cover > 0.0) formatMoney(cover) else "no gate",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = cover.toFloat().coerceIn(0f, MAX_COVER),
                    onValueChange = {
                        val v = it.toDouble()
                        // Zero means "not gated", and the map is the list of gated apps, so
                        // a zero is a removal rather than a stored zero — otherwise coverFor
                        // carries a dead entry that the schedule's floor could revive.
                        apply(
                            cfg.copy(
                                coverCharges = if (v < 0.25) {
                                    cfg.coverCharges - app.packageName
                                } else {
                                    cfg.coverCharges + (app.packageName to v)
                                }
                            )
                        )
                    },
                    valueRange = 0f..MAX_COVER
                )
                if (app.meanSessionMin in 0.01..SHORT_VISIT_MIN) {
                    Text(
                        "Your visits here are short, so a cover bites hard — " +
                            "${formatMoney(1.0)} is already " +
                            "${formatMoney(coverEquivalentPerMin(1.0, app.meanSessionMin))}/min " +
                            "at this visit length. Gating something you open reflexively to " +
                            "read one message is usually a mistake.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun minutes(v: Double): String = when {
    v >= 60.0 -> String.format(Locale.US, "%.1f h", v / 60.0)
    v < 10.0 -> String.format(Locale.US, "%.1f min", v)
    else -> "${v.roundToInt()} min"
}

private fun oneDp(v: Double): String = String.format(Locale.US, "%.1f", v)

/** Matches the app-cost slider's range on the Settings tab. */
private const val MAX_RATE = 60f
private const val MAX_COVER = 30f

/** Below this, a per-visit charge is a toll on a reflex rather than a price on a decision. */
private const val SHORT_VISIT_MIN = 1.5

private const val MAX_ROWS = 12
