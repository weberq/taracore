package dev.taracore.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.taracore.app.MainViewModel
import dev.taracore.engine.ModelSpec

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }

        item {
            InfoCard("Memory") {
                Text("Unload the model after", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Presets rather than a free-form field: the meaningful choices
                    // are "soon", "a while" and "never", and a minute of precision
                    // here changes nothing a user can feel.
                    listOf(
                        "1 min" to 60_000L,
                        "5 min" to 300_000L,
                        "30 min" to 1_800_000L,
                        "Never" to 0L,
                    ).forEach { (label, ms) ->
                        FilterChip(
                            selected = settings.idleTimeoutMs == ms,
                            onClick = { viewModel.setIdleTimeout(ms) },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "A resident model is the largest allocation on the device. Unloading " +
                        "it when idle gives the memory back to whatever you are actually " +
                        "using; the next request reloads it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                SettingSwitch(
                    label = "Memory-map weights",
                    description = "Lets the kernel page weights in on demand instead of " +
                        "reading the whole file. Almost always the right choice.",
                    checked = settings.useMmap,
                    onChange = viewModel::setUseMmap,
                )
                SettingSwitch(
                    label = "Lock weights in RAM",
                    description = "Stops the kernel evicting the model under pressure. " +
                        "Faster, but it takes the memory away from everything else.",
                    checked = settings.useMlock,
                    onChange = viewModel::setUseMlock,
                )
            }
        }

        item {
            InfoCard("Engine") {
                val threads = if (settings.threads > 0) settings.threads
                else ModelSpec.defaultThreads()

                Text("Threads: $threads", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = threads.toFloat(),
                    onValueChange = { viewModel.setThreads(it.toInt()) },
                    valueRange = 1f..16f,
                    steps = 14,
                )
                Text(
                    "More threads is not always faster: on a big.LITTLE phone, spilling " +
                        "onto the little cores costs more in scheduling than it gains. " +
                        "The default is half the CPU count.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    "Context: ${settings.contextSize} tokens",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(2048, 4096, 8192, 16384).forEach { size ->
                        FilterChip(
                            selected = settings.contextSize == size,
                            onClick = { viewModel.setContextSize(size) },
                            label = { Text("$size") },
                        )
                    }
                }
                Text(
                    "The KV cache grows with the context, and it is not memory-mapped. " +
                        "Doubling this roughly doubles the RAM the model costs beyond " +
                        "its weights.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Text(
                    "GPU layers: ${settings.gpuLayers}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Slider(
                    value = settings.gpuLayers.toFloat(),
                    onValueChange = { viewModel.setGpuLayers(it.toInt()) },
                    valueRange = 0f..64f,
                    steps = 63,
                )
                Text(
                    "Only meaningful in a gpu build with a working Vulkan driver. " +
                        "0 keeps everything on the CPU.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            InfoCard("HTTP server") {
                SettingSwitch(
                    label = "Enable the OpenAI-compatible server",
                    description = "Binds 127.0.0.1 only. Any app on this device can reach " +
                        "loopback without a permission, so leave the token on.",
                    checked = settings.httpEnabled,
                    onChange = viewModel::setHttpEnabled,
                )

                if (settings.httpEnabled) {
                    PortField(
                        port = settings.httpPort,
                        onCommit = viewModel::setHttpPort,
                    )

                    SettingSwitch(
                        label = "Require a bearer token",
                        description = if (settings.httpAuthRequired) {
                            "Clients must send Authorization: Bearer <token>."
                        } else {
                            "Unsafe: any app on this device can use the engine freely."
                        },
                        checked = settings.httpAuthRequired,
                        onChange = viewModel::setHttpAuthRequired,
                    )

                    if (settings.httpToken.isNotBlank()) {
                        Text(
                            "Token",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            settings.httpToken,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Button(
                            onClick = viewModel::regenerateToken,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Regenerate token")
                        }
                        Text(
                            "Regenerating immediately invalidates the old token; every " +
                                "client using it starts getting 401.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            InfoCard("Notifications") {
                SettingSwitch(
                    label = "Keep a live status notification",
                    description = "Off by default. Tara Core normally shows a " +
                        "notification only while a model is loading or answering, and " +
                        "hides it from the status bar. Turn this on to keep a " +
                        "permanent one showing the model, backend and speed.",
                    checked = settings.showLiveNotification,
                    onChange = viewModel::setShowLiveNotification,
                )
                Text(
                    "Android requires a foreground service to show a notification, so " +
                        "one appears briefly whenever the engine is working. There is " +
                        "no way around that — but it is gone again as soon as the work " +
                        "is. The Dashboard has the same information without it, and " +
                        "there is a home screen widget if you want it at a glance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        item {
            InfoCard("Behaviour") {
                SettingSwitch(
                    label = "Load models on demand",
                    description = "When a client asks for a model that is not resident, " +
                        "load it instead of failing. Costs a full load on the request " +
                        "that triggers it.",
                    checked = settings.autoLoadOnRequest,
                    onChange = viewModel::setAutoLoad,
                )
                SettingSwitch(
                    label = "Check for updates",
                    description = "Asks GitHub once a day whether a newer release " +
                        "exists. No account, no analytics, and nothing about you is " +
                        "sent — it is a plain read of the public releases list.",
                    checked = settings.checkForUpdates,
                    onChange = viewModel::setCheckForUpdates,
                )
                SettingSwitch(
                    label = "Start on boot",
                    description = "Off by default. On, Tara Core starts a foreground " +
                        "service every time the device boots.",
                    checked = settings.startOnBoot,
                    onChange = viewModel::setStartOnBoot,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PortField(port: Int, onCommit: (Int) -> Unit) {
    var text by remember { mutableStateOf(port.toString()) }
    // Re-sync when the stored value changes underneath us (a clamp, or another
    // screen) so the field never disagrees with what the server is actually using.
    LaunchedEffect(port) { text = port.toString() }

    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter { it.isDigit() }.take(5)
            // Commit only a value the settings store will not clamp, so typing "8"
            // on the way to "8080" does not bounce the server to port 1024.
            text.toIntOrNull()?.let { if (it in 1024..65535) onCommit(it) }
        },
        label = { Text("Port") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        supportingText = { Text("1024–65535. Ports below 1024 need root.") },
    )
}
