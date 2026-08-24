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
import androidx.compose.ui.unit.dp
import com.timebank.app.data.AppGraph
import com.timebank.app.data.labelFor
import com.timebank.app.data.Economy
import com.timebank.app.data.EconomyConfig
import com.timebank.app.util.formatMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsScreen() {
    val cfg by Economy.config.collectAsState()
    val balance by Economy.balance.collectAsState()
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
            range = 0f..120f,
            suffix = "/min"
        ) { apply(cfg.copy(offRatePerMin = it)) }

        RateSlider(
            label = "Media (YouTube / music) rate",
            value = cfg.mediaRatePerMin,
            range = -60f..60f,
            suffix = "/min"
        ) { apply(cfg.copy(mediaRatePerMin = it)) }

        RateSlider(
            label = "App-open cost",
            value = cfg.appCostPerMin,
            range = 0f..120f,
            suffix = "/min"
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
            range = 0f..240f,
            suffix = "/min"
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
                    value = cost.toFloat().coerceIn(0f, 240f),
                    onValueChange = {
                        apply(cfg.copy(appOverrides = cfg.appOverrides + (pkg to it.toDouble())))
                    },
                    valueRange = 0f..240f
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
