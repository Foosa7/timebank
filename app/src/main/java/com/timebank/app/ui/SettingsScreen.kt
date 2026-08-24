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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timebank.app.data.AppGraph
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
