package dev.taracore.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Tara Core", style = MaterialTheme.typography.headlineMedium)
            Text(
                "One star every app steers by",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            InfoCard("Engine") {
                StatRow("Service", if (connected) stateLabel(status.state) else "disconnected")
                StatRow("Model", status.loadedModelId ?: "none loaded")
                StatRow(
                    "Backend",
                    // The flavour is what we compiled; the backend is what actually
                    // initialised. A gpu build on a device with no usable Vulkan
                    // driver silently runs on CPU, and that has to be visible.
                    if (status.backend.equals("CPU", ignoreCase = true) &&
                        BuildConfig.BACKEND_FLAVOUR == "gpu"
                    ) {
                        "CPU (gpu build, no GPU device found)"
                    } else {
                        "${status.backend} (${BuildConfig.BACKEND_FLAVOUR} build)"
                    },
                )
                StatRow("Context", if (status.contextSize > 0) "${status.contextSize} tokens" else "—")
                StatRow("llama.cpp", status.engineVersion.ifBlank { "—" })

                if (status.state == ServiceStatus.State.LOADING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }

        item {
            InfoCard("Memory") {
                StatRow("Model resident", formatBytes(status.modelRamBytes))
                StatRow("Device RAM", formatBytes(device.totalRamBytes))
                StatRow("Available", formatBytes(device.availableRamBytes))
                // This is the UI process's native heap. The model lives in the
                // :engine process, so it does not appear here -- which is the point
                // of the process split, and worth saying rather than confusing.
                StatRow("UI native heap", formatBytes(device.nativeHeapBytes))
                StatRow("Free storage", formatBytes(device.freeStorageBytes))

                if (status.unloadedUnderMemoryPressure) {
                    Text(
                        "The model was unloaded because the system was short of memory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item {
            InfoCard("Throughput") {
                StatRow("Last run", formatTokensPerSecond(status.lastTokensPerSecond))
                StatRow("Queue depth", status.queueDepth.toString())
                StatRow(
                    "Idle unload in",
                    when {
                        // Nothing resident means there is nothing for the timer to
                        // drop. Saying "disabled" here reads as a broken setting.
                        status.loadedModelId == null -> "nothing loaded"
                        status.idleUnloadInMs < 0 -> "disabled"
                        else -> formatDuration(status.idleUnloadInMs)
                    },
                )
            }
        }

        item {
            InfoCard("HTTP server") {
                if (status.httpServerRunning) {
                    StatRow("Listening on", "http://127.0.0.1:${status.httpPort}/v1")
                    StatRow("Auth", if (settings.httpAuthRequired) "bearer token" else "disabled")

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
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy token")
                            }
                        }
                    }
                } else {
                    Text(
                        "Off. Enable it in Settings to reach the engine over HTTP from " +
                            "any app on this device that speaks the OpenAI API.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun stateLabel(state: Int): String = when (state) {
    ServiceStatus.State.IDLE -> "idle"
    ServiceStatus.State.LOADING -> "loading"
    ServiceStatus.State.READY -> "ready"
    ServiceStatus.State.GENERATING -> "generating"
    ServiceStatus.State.ERROR -> "error"
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
