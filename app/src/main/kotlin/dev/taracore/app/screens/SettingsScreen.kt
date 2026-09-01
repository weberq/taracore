package dev.taracore.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import dev.taracore.app.BuildConfig
import dev.taracore.app.MainViewModel
import dev.taracore.engine.ModelSpec

@Composable
fun SettingsScreen(viewModel: MainViewModel, onOpenAbout: () -> Unit = {}) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
        // Ordered by who needs it. Everything a non-technical person might reasonably
        // want to change comes first; the knobs that need you to know what a
        // processor core is are grouped at the bottom under Advanced.

        item {
            InfoCard("Memory") {
                Text("Free up memory after", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
                    "Gives memory back to your other apps when you're not using the AI. " +
                        "It starts up again next time you ask something.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        item {
            InfoCard("Conversation") {
                Text("How much it remembers", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Plain words rather than token counts. 4096 means nothing to
                    // someone who has not read a model card.
                    listOf(
                        "Short" to 2048,
                        "Medium" to 4096,
                        "Long" to 8192,
                        "Longest" to 16384,
                    ).forEach { (label, size) ->
                        FilterChip(
                            selected = settings.contextSize == size,
                            onClick = { viewModel.setContextSize(size) },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "How far back the AI can remember in a single conversation. " +
                        "Remembering more uses more memory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        item {
            InfoCard("Notifications") {
                SettingSwitch(
                    label = "Always show a notification",
                    description = "Keeps the model and speed on screen at all times.",
                    checked = settings.showLiveNotification,
                    onChange = viewModel::setShowLiveNotification,
                )
                Text(
                    "Otherwise a notification only appears while the AI is working, " +
                        "and disappears when it finishes. You can also add the home " +
                        "screen widget to keep an eye on it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        item {
            InfoCard("General") {
                SettingSwitch(
                    label = "Switch models automatically",
                    description = "Lets apps ask for a different model. " +
                        "That request will take a few seconds longer.",
                    checked = settings.autoLoadOnRequest,
                    onChange = viewModel::setAutoLoad,
                )
                SettingSwitch(
                    label = "Check for updates",
                    description = "Looks for a new version once a day. " +
                        "Nothing about you or your phone is sent.",
                    checked = settings.checkForUpdates,
                    onChange = viewModel::setCheckForUpdates,
                )
                SettingSwitch(
                    label = "Start when the phone turns on",
                    description = "Ready to use straight away, without opening the app.",
                    checked = settings.startOnBoot,
                    onChange = viewModel::setStartOnBoot,
                )
            }
        }

        item {
            InfoCard("Other apps") {
                SettingSwitch(
                    label = "Let other apps connect",
                    description = "Starts a server on this phone that other apps can " +
                        "use. Nothing is shared over the internet.",
                    checked = settings.httpEnabled,
                    onChange = viewModel::setHttpEnabled,
                )

                if (settings.httpEnabled) {
                    SettingSwitch(
                        label = "Require a key",
                        description = if (settings.httpAuthRequired) {
                            "Apps need the key below to connect."
                        } else {
                            "Not recommended. Any app on this phone can use the AI."
                        },
                        checked = settings.httpAuthRequired,
                        onChange = viewModel::setHttpAuthRequired,
                    )

                    if (settings.httpToken.isNotBlank()) {
                        Text(
                            "Key",
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
                            Text("Create a new key")
                        }
                        Text(
                            "Apps using the old key will stop working until you give " +
                                "them the new one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    PortField(port = settings.httpPort, onCommit = viewModel::setHttpPort)
                }
            }
        }

        item {
            InfoCard("About") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAbout)
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Version and licences", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Tara Core ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            InfoCard("Advanced") {
                Text(
                    "You should not need to change these. They can make the AI slower " +
                        "or stop it working.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val threads = if (settings.threads > 0) settings.threads
                else ModelSpec.defaultThreads()

                Text(
                    "Processor cores: $threads",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Slider(
                    value = threads.toFloat(),
                    onValueChange = { viewModel.setThreads(it.toInt()) },
                    valueRange = 1f..16f,
                    steps = 14,
                )

                Text(
                    "Graphics chip: ${settings.gpuLayers}",
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
                    "Only works on some phones. Leave at 0 if you're not sure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SettingSwitch(
                    label = "Save memory",
                    description = "Loads the model in pieces as it's needed. " +
                        "Leave this on.",
                    checked = settings.useMmap,
                    onChange = viewModel::setUseMmap,
                )
                SettingSwitch(
                    label = "Keep model in memory",
                    description = "Stops other apps pushing the model out. Faster, " +
                        "but uses more memory.",
                    checked = settings.useMlock,
                    onChange = viewModel::setUseMlock,
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
        supportingText = { Text("Leave this alone unless an app asks you to change it.") },
    )
}
