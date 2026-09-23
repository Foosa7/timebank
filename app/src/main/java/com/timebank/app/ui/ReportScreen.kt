package com.timebank.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timebank.app.data.AppGraph
import com.timebank.app.data.DEFAULT_NEUTRAL_FRACTION
import com.timebank.app.data.Economy
import com.timebank.app.data.LlmClient
import com.timebank.app.data.LlmConfig
import com.timebank.app.data.LlmProvider
import com.timebank.app.data.Observations
import com.timebank.app.data.buildDigest
import com.timebank.app.data.preview
import com.timebank.app.data.pricingContext
import com.timebank.app.data.excludingWork
import com.timebank.app.data.readObservations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hand the measurements to an LLM and read what it makes of them.
 *
 * This is the outer loop of the design: the arithmetic in `Pricing.kt` handles the part that
 * is a solved scalar problem, and the model handles the part that is not — which knob to
 * reach for, whether a habit has shifted, what a number means for someone's week. It returns
 * a report, never a config. Nothing on this screen writes a price.
 *
 * The payload is shown in full before anything is sent. Usage data is personal enough that
 * "trust me" is the wrong design, and a preview is cheaper than a policy.
 */
@Composable
fun ReportScreen() {
    val context = LocalContext.current
    val economy by Economy.config.collectAsState()
    val scope = rememberCoroutineScope()

    var llm by remember { mutableStateOf<LlmConfig?>(null) }
    var obs by remember { mutableStateOf<Observations?>(null) }
    var showPayload by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Two independent loads: the saved credentials, and the measurements to send.
    LaunchedEffect(Unit) {
        AppGraph.settings.llmConfigFlow.collect { llm = it }
    }
    LaunchedEffect(Unit) {
        obs = withContext(Dispatchers.IO) { readObservations(context) }
    }

    val cfg = llm
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        Text("Report", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Sends your measured usage to an LLM and asks what it makes of it. The model " +
                "reads the numbers and writes a report — it never changes your config.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))

        if (cfg == null) {
            Text("Loading…", style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        fun save(next: LlmConfig) {
            llm = next
            scope.launch(Dispatchers.IO) { AppGraph.settings.saveLlmConfig(next) }
        }

        Text("Provider", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row {
            LlmProvider.entries.forEach { p ->
                FilterChip(
                    selected = cfg.provider == p,
                    // Switching provider carries the old model name over unless it was the
                    // other provider's default, which would otherwise 404 on the first send.
                    onClick = {
                        val keepModel = cfg.model != cfg.defaultModelFor(cfg.provider)
                        save(
                            cfg.copy(
                                provider = p,
                                model = if (keepModel) cfg.model else cfg.defaultModelFor(p)
                            )
                        )
                    },
                    label = { Text(p.label) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = cfg.apiKey,
            onValueChange = { save(cfg.copy(apiKey = it.trim())) },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Stored unencrypted in this app's private data. Use a key scoped to this app " +
                "that you can revoke, not your main one.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = cfg.model,
            onValueChange = { save(cfg.copy(model = it.trim())) },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = cfg.goal,
            onValueChange = { save(cfg.copy(goal = it)) },
            label = { Text("What are you trying to achieve?") },
            placeholder = { Text("e.g. off Instagram before bed, and I need WhatsApp") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        // The one thing measurement cannot supply. Usage says what someone does; it never
        // says which of it they regret, or what they would trade to be rid of it — and a
        // report written without that can only comment on numbers.
        Text(
            "The numbers say what you do, never why it bothers you. Without this the " +
                "report can only be generic.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { showPrompt = !showPrompt }) {
            Text(if (showPrompt) "Hide the brief" else "Edit the brief")
        }
        if (showPrompt) {
            Text(
                "The standing instructions sent with every report — what TimeBank is, " +
                    "what it is for, and what the model may not propose. Leave it blank to " +
                    "use the built-in one.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = cfg.effectiveSystemPrompt,
                onValueChange = { save(cfg.copy(systemPrompt = it)) },
                label = { Text("System prompt") },
                minLines = 8,
                maxLines = 20,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                modifier = Modifier.fillMaxWidth()
            )
            if (cfg.systemPrompt.isNotBlank()) {
                TextButton(onClick = { save(cfg.copy(systemPrompt = "")) }) {
                    Text("Reset to the built-in brief")
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        val ready = remember(obs, economy) { obs?.excludingWork(economy) }
        when {
            ready == null -> Text("Reading your usage…", style = MaterialTheme.typography.bodySmall)

            ready.baseline.isEmpty -> Text(
                "No usage history to send yet — this needs the Usage access permission " +
                    "on the Settings tab.",
                style = MaterialTheme.typography.bodySmall
            )

            else -> {
                val ctx = ready.baseline.pricingContext(
                    economy,
                    ready.shape.neutralFraction ?: DEFAULT_NEUTRAL_FRACTION
                )
                val digest = remember(ready, economy) {
                    buildDigest(context, ready, economy, ctx)
                }

                TextButton(onClick = { showPayload = !showPayload }) {
                    Text(if (showPayload) "Hide what will be sent" else "Show what will be sent")
                }
                if (showPayload) {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            digest.preview(),
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Button(
                    enabled = cfg.isConfigured && !busy,
                    onClick = {
                        busy = true; error = null; report = null
                        scope.launch {
                            LlmClient.analyse(cfg, digest)
                                .onSuccess { report = it }
                                .onFailure { error = it.message ?: it.toString() }
                            busy = false
                        }
                    }
                ) {
                    Text(if (busy) "Analysing…" else "Analyse")
                }
                if (!cfg.isConfigured) {
                    Text(
                        "Add an API key above to enable this.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (busy) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(
                "This is a single non-streamed request, so the whole answer arrives at " +
                    "once — it can take a minute.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Failed", fontWeight = FontWeight.Bold)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        report?.let {
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Text(it, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "A reading of your numbers, not an instruction. Anything you agree with, " +
                    "you set yourself on the Charges or Settings tab.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
