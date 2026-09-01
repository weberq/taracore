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
        // Downloaded first, then the recommended one, then by family and size: what
        // you can use now is what you want to see first, and the model we would pick
        // for you should be easy to find.
        compareByDescending<ModelEntity> { it.downloaded }
            .thenByDescending { it.recommended }
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
                    "${formatBytes(device.availableRamBytes)} memory free · " +
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
                totalRam = device.totalRamBytes,
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

/**
 * Quantisation level in words.
 *
 * The same model appears twice in the catalogue at different quantisations, so users
 * do need to tell them apart -- but "Q4_K_M" tells them nothing. What it actually
 * means to them is a trade between size and quality.
 */
private fun qualityLabel(quant: String): String = when (quant.uppercase()) {
    "Q4_K_M", "Q4_K_S", "Q4_0" -> "Standard quality"
    "Q8_0" -> "Higher quality"
    "Q5_K_M", "Q5_K_S", "Q6_K" -> "High quality"
    "Q2_K", "Q3_K_M", "Q3_K_S", "Q3_K_L" -> "Smaller, lower quality"
    "F16", "BF16", "F32" -> "Full quality"
    else -> quant
}

@Composable
private fun ModelCard(
    model: ModelEntity,
    progress: DownloadProgress?,
    availableRam: Long,
    totalRam: Long,
    isActive: Boolean,
    isLoaded: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit,
) {
    // Two different questions, and the old code asked the wrong one. The weights are
    // memory-mapped, so they do not need free memory: only the KV cache and compute
    // buffers do. Comparing the whole footprint against free memory told people that
    // models which run perfectly well would not fit.
    val nonEvictable = (model.estRamBytes - model.sizeBytes).coerceAtLeast(64L * 1000 * 1000)
    val tooBigForPhone = totalRam > 0 && model.estRamBytes > (totalRam * 0.75).toLong()
    val slowRightNow = !tooBigForPhone && nonEvictable > availableRam
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
                        "${qualityLabel(model.quant)} · ${formatBytes(model.sizeBytes)} · " +
                            model.license,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    isLoaded -> AssistChip(onClick = {}, label = { Text("in use") })
                    isActive -> AssistChip(onClick = {}, label = { Text("selected") })
                    model.recommended -> AssistChip(onClick = {}, label = { Text("recommended") })
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
                text = when {
                    tooBigForPhone ->
                        "Needs ${formatBytes(model.estRamBytes)} — too much for this phone"
                    slowRightNow ->
                        "Needs ${formatBytes(model.estRamBytes)}. Will work, but may be " +
                            "slow until you close some apps"
                    else -> "Needs ${formatBytes(model.estRamBytes)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (tooBigForPhone) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
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
                            DownloadProgress.State.VERIFYING -> "Checking the download…"
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
                            Text(if (isLoaded) "In use" else "Use this one")
                        }
                        TextButton(onClick = onDelete) { Text("Delete") }
                    }

                    model.url.isBlank() -> Text(
                        "Added by hand",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Button(onClick = onDownload) { Text("Download") }
                }
            }
        }
    }
}
