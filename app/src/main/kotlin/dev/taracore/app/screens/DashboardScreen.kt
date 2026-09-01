package dev.taracore.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.taracore.api.ServiceStatus
import dev.taracore.app.BuildConfig
import dev.taracore.app.MainViewModel
import dev.taracore.app.ui.formatBytes
import dev.taracore.app.ui.formatDuration
import dev.taracore.app.ui.formatTokensPerSecond

/**
 * What the engine is doing and what it is costing. The numbers here are the ones a
 * user needs to decide whether to keep a model resident: RAM, storage, speed, and
 * how long until it unloads itself.
 */
@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val device by viewModel.deviceStats.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val update by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Tara Core", style = MaterialTheme.typography.headlineMedium)
            Text(
                "AI that runs on your phone",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        update?.let { available ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "New version available: ${available.name}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (available.notes.isNotBlank()) {
                            Text(
                                available.notes.lineSequence().take(4).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { uriHandler.openUri(available.htmlUrl) }) {
                                Text("View release")
                            }
                            TextButton(onClick = viewModel::skipUpdate) { Text("Skip") }
                            TextButton(onClick = viewModel::dismissUpdate) { Text("Later") }
                        }
                    }
                }
            }
        }

        item {
            InfoCard("Status") {
                val hasModel = status.loadedModelId != null

                StatRow(
                    "Tara Core",
                    when {
                        !connected -> "not running"
                        !hasModel -> "waiting for a model"
                        else -> stateLabel(status.state)
                    },
                )
                StatRow("Model", viewModel.displayName(status.loadedModelId) ?: "none yet")

                // Everything below describes a loaded model. With none, these are
                // four rows of dashes: noise for someone who just opened the app and
                // needs to be pointed at the Models tab instead.
                if (hasModel) {
                    StatRow(
                        "Running on",
                        if (status.backend.equals("CPU", ignoreCase = true)) "Processor"
                        else status.backend,
                    )
                    if (status.contextSize > 0) {
                        StatRow("Conversation memory", contextLabel(status.contextSize))
                    }
                } else {
                    Text(
                        "Pick a model on the Models tab to get started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (status.state == ServiceStatus.State.LOADING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }

        item {
            InfoCard("Memory") {
                if (status.modelRamBytes > 0) {
                    StatRow("Model is using", formatBytes(status.modelRamBytes))
                }
                StatRow("Free on this phone", formatBytes(device.availableRamBytes))
                StatRow("Total on this phone", formatBytes(device.totalRamBytes))
                StatRow("Free storage", formatBytes(device.freeStorageBytes))

                if (status.unloadedUnderMemoryPressure) {
                    Text(
                        "The model was closed because your phone ran low on memory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item {
            InfoCard("Speed") {
                StatRow(
                    "Last reply",
                    formatTokensPerSecond(status.lastTokensPerSecond).let {
                        if (it == "—") "nothing yet" else it
                    },
                )
                if (status.queueDepth > 0) {
                    StatRow("Requests waiting", status.queueDepth.toString())
                }
                // Only while there is actually something to free.
                if (status.loadedModelId != null) {
                    StatRow(
                        "Frees memory in",
                        if (status.idleUnloadInMs < 0) "never"
                        else formatDuration(status.idleUnloadInMs),
                    )
                }
            }
        }

        item {
            InfoCard("Local server") {
                if (status.httpServerRunning) {
                    StatRow("Address", "http://127.0.0.1:${status.httpPort}/v1")
                    StatRow("Key required", if (settings.httpAuthRequired) "yes" else "no")

                    if (settings.httpToken.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = settings.httpToken,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(settings.httpToken))
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy key")
                            }
                        }
                    }
                } else {
                    Text(
                        "Off. Turn it on in Settings to let other apps on this phone " +
                            "use the AI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Matches the wording of the chips on the Settings screen. */
private fun contextLabel(size: Int): String = when {
    size <= 2048 -> "Short"
    size <= 4096 -> "Medium"
    size <= 8192 -> "Long"
    else -> "Longest"
}

private fun stateLabel(state: Int): String = when (state) {
    ServiceStatus.State.IDLE -> "ready"
    ServiceStatus.State.LOADING -> "opening model"
    ServiceStatus.State.READY -> "ready"
    ServiceStatus.State.GENERATING -> "thinking"
    ServiceStatus.State.ERROR -> "something went wrong"
    else -> "unknown"
}

@Composable
fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            content()
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
