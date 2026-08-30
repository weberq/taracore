package dev.taracore.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.taracore.api.ServiceStatus
import dev.taracore.app.ChatTurn
import dev.taracore.app.MainViewModel
import dev.taracore.app.ui.formatTokensPerSecond

/**
 * A chat that dogfoods the public path: it goes through `:client-sdk` and the AIDL
 * contract, exactly as a third-party app would, rather than reaching into `:service`
 * for a shortcut. A regression in the integration surface breaks this screen first.
 */
@Composable
fun PlaygroundScreen(viewModel: MainViewModel) {
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val tinyModel by viewModel.loadedModelIsTiny.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(turns.size, turns.lastOrNull()?.text?.length) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Playground", style = MaterialTheme.typography.titleLarge)
                Text(
                    status.loadedModelId ?: "no model loaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (turns.isNotEmpty()) {
                TextButton(onClick = viewModel::clearChat) { Text("Clear") }
            }
        }

        if (tinyModel) {
            // The user is otherwise left to conclude the app is broken, when what
            // they have loaded is a model small enough to be a build artefact.
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "This model is a smoke test, not an assistant",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "It has too few parameters to stay on topic, and it will " +
                            "invent facts confidently. Download a 0.5B model or larger " +
                            "on the Models tab for answers worth reading.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (turns.isEmpty()) {
                EmptyState(connected, status)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(turns) { TurnBubble(it) }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask something") },
                enabled = connected,
                maxLines = 5,
            )

            FilledIconButton(
                onClick = {
                    if (busy) {
                        viewModel.stopGeneration()
                    } else {
                        viewModel.send(input)
                        input = ""
                    }
                },
                enabled = connected && (busy || input.isNotBlank()),
            ) {
                // One button, two jobs: sending and stopping are never both available,
                // and a Stop that is a long reach from Send is a Stop you do not press.
                Icon(
                    imageVector = if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (busy) "Stop generating" else "Send",
                )
            }
        }
    }
}

@Composable
private fun EmptyState(connected: Boolean, status: ServiceStatus) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                when {
                    !connected -> "Not connected to the engine"
                    status.loadedModelId == null -> "No model loaded"
                    else -> "Ready"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                when {
                    !connected -> "The service is starting. This screen reconnects on its own."
                    status.loadedModelId == null ->
                        "Download one on the Models tab and set it active, or just send a " +
                            "message — the engine loads the active model on demand."
                    else -> "Ask it something."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TurnBubble(turn: ChatTurn) {
    val isUser = turn.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    if (isUser) "You" else "Assistant",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = turn.text.ifBlank { if (turn.streaming) "…" else "" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!isUser && turn.tokensPerSecond > 0) {
                    Text(
                        formatTokensPerSecond(turn.tokensPerSecond) +
                            if (turn.streaming) " …" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
