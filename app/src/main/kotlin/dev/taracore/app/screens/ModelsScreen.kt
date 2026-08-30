package dev.taracore.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.taracore.app.MainViewModel
import dev.taracore.app.ui.formatBytes
import dev.taracore.app.ui.formatPercent
import dev.taracore.service.model.DownloadProgress
import dev.taracore.service.model.ModelEntity

/**
 * The model hub. Each card has to answer one question before anything else: will
 * this run on *this* phone? So the RAM estimate is shown against the device's actual
 * available memory, and a model that will not fit says so plainly.
 */
@Composable
fun ModelsScreen(viewModel: MainViewModel) {
    val models by viewModel.models.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val device by viewModel.deviceStats.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    val grouped = models.sortedWith(
        // Downloaded first, then by family and size: what you can use now is what
        // you want to see first.
        compareByDescending<ModelEntity> { it.downloaded }
            .thenBy { it.family }
            .thenBy { it.sizeBytes }
    )

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("Models", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${formatBytes(device.availableRamBytes)} of " +
                        "${formatBytes(device.totalRamBytes)} RAM free · " +
                        "${formatBytes(device.freeStorageBytes)} storage free",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(grouped, key = { it.id }) { model ->
            ModelCard(
                model = model,
                progress = downloads[model.id],
                availableRam = device.availableRamBytes,
                isActive = settings.activeModelId == model.id,
                isLoaded = status.loadedModelId == model.id,
                onDownload = { viewModel.download(model.id) },
                onCancel = { viewModel.cancelDownload(model.id) },
                onDelete = { viewModel.deleteModel(model.id) },
                onSetActive = { viewModel.setActive(model.id) },
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelEntity,
    progress: DownloadProgress?,
    availableRam: Long,
    isActive: Boolean,
    isLoaded: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit,
) {
    val fits = model.estRamBytes <= availableRam
    // Non-null exactly when a transfer is in flight, so the UI below needs one check
    // rather than a state test and a null test that the compiler knows are redundant.
    val inFlight = progress?.takeIf {
        it.state == DownloadProgress.State.DOWNLOADING ||
            it.state == DownloadProgress.State.QUEUED ||
            it.state == DownloadProgress.State.VERIFYING
    }
    val downloading = inFlight != null

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${model.quant} · ${formatBytes(model.sizeBytes)} · " +
                            "${model.ctxDefault} ctx · ${model.license}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isLoaded) {
                    AssistChip(onClick = {}, label = { Text("loaded") })
                } else if (isActive) {
                    AssistChip(onClick = {}, label = { Text("active") })
                }
            }

            if (model.description.isNotBlank()) {
                Text(
                    model.description,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Text(
                text = if (fits) {
                    "Needs about ${formatBytes(model.estRamBytes)} of RAM"
                } else {
                    "Needs about ${formatBytes(model.estRamBytes)} of RAM — more than the " +
                        "${formatBytes(availableRam)} free right now"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (fits) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (inFlight != null) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    LinearProgressIndicator(
                        progress = { inFlight.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        when (inFlight.state) {
                            DownloadProgress.State.QUEUED -> "Queued"
                            DownloadProgress.State.VERIFYING -> "Verifying checksum…"
                            else -> "${formatPercent(inFlight.fraction)} · " +
                                "${formatBytes(inFlight.bytesDownloaded)} of " +
                                formatBytes(inFlight.totalBytes)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (progress?.state == DownloadProgress.State.FAILED) {
                Text(
                    progress.message ?: "Download failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    downloading -> OutlinedButton(onClick = onCancel) { Text("Cancel") }

                    model.downloaded -> {
                        Button(onClick = onSetActive, enabled = !isLoaded) {
                            Text(if (isLoaded) "Loaded" else "Set active")
                        }
                        TextButton(onClick = onDelete) { Text("Delete") }
                    }

                    model.url.isBlank() -> Text(
                        "No download URL",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Button(onClick = onDownload) { Text("Download") }
                }
            }
        }
    }
}
