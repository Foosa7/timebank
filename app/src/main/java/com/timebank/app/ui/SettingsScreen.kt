package com.timebank.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.timebank.app.data.AppGraph
import com.timebank.app.data.labelFor
import com.timebank.app.data.Economy
import com.timebank.app.data.EconomyConfig
import com.timebank.app.data.HourWindow
import com.timebank.app.util.formatMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen() {
    val cfg by Economy.config.collectAsState()
    val balance by Economy.balance.collectAsState()
    val happyActive by Economy.happyHourActive.collectAsState()
    val surgeActive by Economy.surgeActive.collectAsState()
    val sleepActive by Economy.sleepActive.collectAsState()
    val scope = rememberCoroutineScope()

    fun apply(newCfg: EconomyConfig) {
        Economy.config.value = newCfg // instant effect for the running service
        scope.launch(Dispatchers.IO) { AppGraph.settings.saveConfig(newCfg) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            "Economy",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))

        RateSlider(
            label = "Screen-off earning",
            value = cfg.offRatePerMin,
            range = 0f..10f,
            suffix = "/min",
            decimals = 1
        ) { apply(cfg.copy(offRatePerMin = it)) }

        RateSlider(
            label = "Idle earning (screen on, no app)",
            value = cfg.idleRatePerMin,
            range = 0f..10f,
            suffix = "/min",
            decimals = 1
        ) { apply(cfg.copy(idleRatePerMin = it)) }

        RateSlider(
            label = "Media (YouTube / music) rate",
            value = cfg.mediaRatePerMin,
            range = -10f..10f,
            suffix = "/min",
            decimals = 1
        ) { apply(cfg.copy(mediaRatePerMin = it)) }

        RateSlider(
            label = "App-open cost",
            value = cfg.appCostPerMin,
            range = 0f..60f,
            suffix = "/min",
            decimals = 1
        ) { apply(cfg.copy(appCostPerMin = it)) }

        RateSlider(
            label = "Earn multiplier",
            value = cfg.earnMultiplier,
            range = 0.1f..10f,
            suffix = "x",
            money = false,
            decimals = 1
        ) { apply(cfg.copy(earnMultiplier = it)) }


        Spacer(Modifier.height(28.dp))
        ScheduleSection(
            title = "Happy hours",
            badge = "\uD83C\uDF7A",
            description = "Inside these windows apps are cheaper. Happy hour only ever " +
                "lowers a price, so an app already priced below these keeps its own rate.",
            active = happyActive,
            costLabel = "Happy hour app cost",
            cost = cfg.happyAppCostPerMin,
            coverLabel = "Happy hour cover charge",
            cover = cfg.happyCoverChargePerApp,
            windows = cfg.happyHours,
            newWindow = HourWindow(12, 13),
            onCost = { apply(cfg.copy(happyAppCostPerMin = it)) },
            onCover = { apply(cfg.copy(happyCoverChargePerApp = it)) },
            onWindows = { apply(cfg.copy(happyHours = it)) }
        )

        Spacer(Modifier.height(28.dp))
        ScheduleSection(
            title = "Surge hours",
            badge = "\u26A1",
            description = "The opposite of happy hour — apps cost more, for the times " +
                "you would rather not be on the phone at all. Surge only ever raises a " +
                "price, and it beats happy hour where the two overlap.",
            active = surgeActive,
            costLabel = "Surge app cost",
            cost = cfg.surgeAppCostPerMin,
            coverLabel = "Surge cover charge",
            cover = cfg.surgeCoverChargePerApp,
            windows = cfg.surgeHours,
            newWindow = HourWindow(0, 6),
            onCost = { apply(cfg.copy(surgeAppCostPerMin = it)) },
            onCover = { apply(cfg.copy(surgeCoverChargePerApp = it)) },
            onWindows = { apply(cfg.copy(surgeHours = it)) }
        )

        Spacer(Modifier.height(28.dp))
        CoverCharges(cfg) { apply(it) }

        Spacer(Modifier.height(28.dp))
        ScheduleSection(
            title = "Sleep hours",
            badge = "\uD83D\uDCA4",
            description = "Screen-off earning drops to this rate overnight. A full night " +
                "at the normal rate is a wage earned for doing nothing, which drowns out " +
                "everything the economy is trying to price.",
            active = sleepActive,
            costLabel = "Sleep screen-off earning",
            cost = cfg.sleepOffRatePerMin,
            coverLabel = null,
            cover = 0.0,
            windows = cfg.sleepHours,
            newWindow = HourWindow(23, 7),
            onCost = { apply(cfg.copy(sleepOffRatePerMin = it)) },
            onCover = {},
            onWindows = { apply(cfg.copy(sleepHours = it)) }
        )

        Spacer(Modifier.height(28.dp))
        PerAppCosts(cfg) { apply(it) }

        Spacer(Modifier.height(28.dp))
        Text(
            "Private browsing",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Charged on top of the app's own cost while it has incognito / InPrivate " +
                "tabs open. Needs notification access. Set to \$0 to disable.",
            style = MaterialTheme.typography.bodySmall
        )
        RateSlider(
            label = "Incognito surcharge",
            value = cfg.privateSurchargePerMin,
            range = 0f..60f,
            suffix = "/min",
            decimals = 1
        ) { apply(cfg.copy(privateSurchargePerMin = it)) }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Lock apps at \$0", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Show a blocking overlay when you run out of money.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = cfg.lockWhenBroke,
                onCheckedChange = { apply(cfg.copy(lockWhenBroke = it)) }
            )
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "Balance",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text("Current: ${formatMoney(balance)}")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                Economy.balance.value = 0.0
                scope.launch(Dispatchers.IO) { AppGraph.settings.saveBalance(0.0) }
            }
        ) { Text("Reset balance to $0") }
    }
}

@Composable
private fun RateSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    money: Boolean = true,
    decimals: Int = 0,
    onChange: (Double) -> Unit
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            val shown = if (money) {
                "$" + String.format(Locale.US, "%,.${decimals}f", value)
            } else {
                String.format(Locale.US, "%,.${decimals}f", value)
            }
            Text("$shown$suffix", fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range
        )
    }
}

/**
 * One time-of-day price schedule — happy hours or their surge mirror. Hours are whole
 * local hours and the end is exclusive, which is why the end slider runs to 24: "22 → 24"
 * is the last two hours of the day. A window whose ends are equal covers nothing, so it
 * can be parked without deleting it.
 *
 * Both schedules are the same control, so they get the same composable rather than two
 * that can drift apart.
 */
@Composable
private fun ScheduleSection(
    title: String,
    badge: String,
    description: String,
    active: Boolean,
    costLabel: String,
    cost: Double,
    /** Null for a schedule that only moves a rate, like sleep hours. */
    coverLabel: String?,
    cover: Double,
    windows: List<HourWindow>,
    newWindow: HourWindow,
    onCost: (Double) -> Unit,
    onCover: (Double) -> Unit,
    onWindows: (List<HourWindow>) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (active) {
            Spacer(Modifier.width(8.dp))
            Text("$badge on now", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(description, style = MaterialTheme.typography.bodySmall)

    RateSlider(label = costLabel, value = cost, range = 0f..60f, suffix = "/min", decimals = 1) {
        onCost(it)
    }
    if (coverLabel != null) {
        RateSlider(label = coverLabel, value = cover, range = 0f..50f, suffix = "", decimals = 1) {
            onCover(it)
        }
    }

    windows.forEachIndexed { i, window ->
        key(i) {
            Column(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatHour(window.startHour) + " → " + formatHour(window.endHour),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onWindows(windows.filterIndexed { j, _ -> j != i }) }
                    ) { Text("Remove") }
                }
                HourSlider("Start", window.startHour, 23) { h ->
                    onWindows(windows.replaceAt(i, window.copy(startHour = h)))
                }
                HourSlider("End", window.endHour, 24) { h ->
                    onWindows(windows.replaceAt(i, window.copy(endHour = h)))
                }
            }
        }
    }

    OutlinedButton(
        onClick = { onWindows(windows + newWindow) },
        modifier = Modifier.padding(top = 8.dp)
    ) { Text("Add window") }
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    mapIndexed { i, existing -> if (i == index) value else existing }

/** Hours are whole numbers, so the slider gets steps and snaps rather than sliding smoothly. */
@Composable
private fun HourSlider(label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp))
        Slider(
            value = value.coerceIn(0, max).toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..max.toFloat(),
            steps = max - 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            formatHour(value),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End
        )
    }
}

private fun formatHour(h: Int): String = String.format(Locale.US, "%02d:00", h)

/**
 * Which apps charge to open, and how much. Deliberately empty until you fill it: the
 * gate is worth having in front of the two or three apps you actually lose time to, and
 * actively harmful in front of the dialler.
 */
@Composable
private fun CoverCharges(
    cfg: EconomyConfig,
    apply: (EconomyConfig) -> Unit
) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }

    Text(
        "Cover charge",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        "A one-off charge to open these apps, taken before the per-minute cost starts. " +
            "Leaving an app ends the visit, so coming back pays again. Apps not listed " +
            "here open free.",
        style = MaterialTheme.typography.bodySmall
    )

    cfg.coverCharges.entries.sortedBy { labelFor(ctx, it.key).lowercase() }.forEach { (pkg, cover) ->
        key(pkg) {
            Column(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(labelFor(ctx, pkg), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Text(formatMoney(cover), fontWeight = FontWeight.SemiBold)
                    TextButton(
                        onClick = { apply(cfg.copy(coverCharges = cfg.coverCharges - pkg)) }
                    ) { Text("Remove") }
                }
                Slider(
                    value = cover.toFloat().coerceIn(0f, 50f),
                    onValueChange = {
                        apply(cfg.copy(coverCharges = cfg.coverCharges + (pkg to it.toDouble())))
                    },
                    valueRange = 0f..50f
                )
            }
        }
    }

    OutlinedButton(
        onClick = { picking = true },
        modifier = Modifier.padding(top = 8.dp)
    ) { Text("Add app") }

    if (picking) {
        AppPickerDialog(
            title = "Charge to open…",
            alreadyPriced = cfg.coverCharges.keys,
            onPick = { app ->
                apply(
                    cfg.copy(coverCharges = cfg.coverCharges + (app.packageName to DEFAULT_COVER))
                )
                picking = false
            },
            onDismiss = { picking = false }
        )
    }
}

/** What a freshly picked app is seeded at — five minutes of restraint at the $1/min unit. */
private const val DEFAULT_COVER = 5.0

/**
 * Per-app price list. Anything not listed here is charged the default app cost,
 * so an empty list is the normal starting state rather than an error.
 */
@Composable
private fun PerAppCosts(
    cfg: EconomyConfig,
    apply: (EconomyConfig) -> Unit
) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }

    Text(
        "Per-app costs",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        "Apps priced above the default of ${formatMoney(cfg.appCostPerMin)}/min.",
        style = MaterialTheme.typography.bodySmall
    )

    cfg.appOverrides.entries.sortedBy { labelFor(ctx, it.key).lowercase() }.forEach { (pkg, cost) ->
        key(pkg) {
            Column(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(labelFor(ctx, pkg), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${formatMoney(cost)}/min",
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = { apply(cfg.copy(appOverrides = cfg.appOverrides - pkg)) }
                    ) { Text("Remove") }
                }
                Slider(
                    value = cost.toFloat().coerceIn(0f, 60f),
                    onValueChange = {
                        apply(cfg.copy(appOverrides = cfg.appOverrides + (pkg to it.toDouble())))
                    },
                    valueRange = 0f..60f
                )
            }
        }
    }

    OutlinedButton(
        onClick = { picking = true },
        modifier = Modifier.padding(top = 8.dp)
    ) { Text("Add app") }

    if (picking) {
        AppPickerDialog(
            title = "Charge extra for…",
            alreadyPriced = cfg.appOverrides.keys,
            onPick = { app ->
                // Seed at double the default so the entry does something immediately.
                apply(
                    cfg.copy(
                        appOverrides = cfg.appOverrides +
                            (app.packageName to cfg.appCostPerMin * 2)
                    )
                )
                picking = false
            },
            onDismiss = { picking = false }
        )
    }
}
