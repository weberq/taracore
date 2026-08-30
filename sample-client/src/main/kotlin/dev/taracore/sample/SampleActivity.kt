package dev.taracore.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

/**
 * Demonstrates both ways into Tara Core, side by side, so the trade-off is something
 * you can measure on your own device rather than take on trust.
 *
 * This app depends on `:client-sdk` and nothing else from Tara Core. It has no access
 * to `:service` or `:engine` -- it is a third-party client in every sense but the
 * repository it happens to live in.
 */
class SampleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { SampleApp() }
            }
        }
    }
}

@Composable
private fun SampleApp(viewModel: SampleViewModel = viewModel()) {
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val aidl by viewModel.aidl.collectAsStateWithLifecycle()
    val http by viewModel.httpResult.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by remember { mutableIntStateOf(0) }
    var prompt by remember { mutableStateOf("Explain what a language model is, in two sentences.") }
    var maxTokens by remember { mutableStateOf("128") }
    var token by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("http://127.0.0.1:8080") }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Tara Core Sample", style = MaterialTheme.typography.headlineSmall)

            if (!installed) {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Tara Core is not installed", fontWeight = FontWeight.SemiBold)
                        Text(
                            "This app needs the Tara Core engine app on the device. " +
                                "Install it and come back.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { context.startActivity(viewModel.installIntent()) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Get Tara Core") }
                    }
                }
                return@Column
            }

            Text(
                if (connected) "Connected · ${models.size} model(s) available"
                else "Not connected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
            )

            OutlinedTextField(
                value = maxTokens,
                onValueChange = { maxTokens = it.filter(Char::isDigit).take(5) },
                label = { Text("Max tokens") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("AIDL") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("HTTP") })
            }

            val limit = maxTokens.toIntOrNull()?.coerceIn(1, 4096) ?: 128

            when (tab) {
                0 -> TransportPanel(
                    explanation = "Binds ITaraCore through :client-sdk. No network " +
                        "permission, no serialisation of the prompt, and cancelling the " +
                        "flow stops generation within one token.",
                    result = aidl,
                    enabled = connected,
                    onRun = { viewModel.runAidl(prompt, limit) },
                    onCancel = viewModel::cancelAidl,
                )

                1 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Bearer token") },
                        placeholder = { Text("Copy it from Tara Core → Settings") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TransportPanel(
                        explanation = "Plain OkHttp against the OpenAI-compatible endpoint, " +
                            "parsing SSE by hand. Enable the server in Tara Core → Settings " +
                            "first, and note the cleartext exception for 127.0.0.1 in this " +
                            "app's network security config.",
                        result = http,
                        enabled = true,
                        onRun = {
                            viewModel.runHttp(prompt, limit, token, models.firstOrNull(), baseUrl)
                        },
                        onCancel = viewModel::cancelHttp,
                    )
                }
            }

            if (aidl.tokens > 0 && http.tokens > 0) {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Side by side", fontWeight = FontWeight.SemiBold)
                        Text(
                            "AIDL: first token %dms · %.1f tok/s".format(
                                aidl.firstTokenMs, aidl.tokensPerSecond
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "HTTP: first token %dms · %.1f tok/s".format(
                                http.firstTokenMs, http.tokensPerSecond
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Throughput should be nearly identical -- both share one " +
                                "engine. Time to first token is where they differ.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportPanel(
    explanation: String,
    result: RunResult,
    enabled: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onRun, enabled = enabled && !result.running) { Text("Run") }
            OutlinedButton(onClick = onCancel, enabled = result.running) { Text("Cancel") }
            if (result.running) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp))
            }
        }

        if (result.tokens > 0 || result.running) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        String.format(
                            Locale.US,
                            "%d tokens · first in %dms · %.1f tok/s · %dms total",
                            result.tokens,
                            result.firstTokenMs,
                            result.tokensPerSecond,
                            result.totalMs,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        result.text.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        result.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
