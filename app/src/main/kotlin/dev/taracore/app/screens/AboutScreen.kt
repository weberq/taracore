package dev.taracore.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.taracore.app.BuildConfig
import dev.taracore.app.MainViewModel
import dev.taracore.app.about.Licenses

private const val REPO = "https://github.com/weberq/taracore"
private const val PRIVACY = "$REPO/blob/main/docs/release/PRIVACY.md"
private const val ISSUES = "$REPO/issues"

/**
 * Version, legal notices and links.
 *
 * Every app needs this and it is usually an afterthought, which is how apps end up
 * shipping Apache-2.0 dependencies without the attribution that licence requires.
 */
@Composable
fun AboutScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("About", style = MaterialTheme.typography.headlineMedium)
            }
        }

        item {
            InfoCard("Tara Core") {
                StatRow("Version", BuildConfig.VERSION_NAME)
                StatRow("Build", BuildConfig.VERSION_CODE.toString())
                // Only worth showing when it is not the ordinary CPU build.
                if (BuildConfig.BACKEND_FLAVOUR != "cpu") {
                    StatRow("Edition", BuildConfig.BACKEND_FLAVOUR)
                }
                if (status.engineVersion.isNotBlank()) {
                    StatRow("AI engine", status.engineVersion)
                }
                Text(
                    "Runs AI models on your phone, so your other apps can use them " +
                        "without sending anything to the internet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        item {
            InfoCard("Legal") {
                LinkRow("Privacy policy") { uriHandler.openUri(PRIVACY) }
                HorizontalDivider()
                LinkRow("Open source licences", external = false, onClick = onOpenLicenses)
                HorizontalDivider()
                Text(
                    "Tara Core is free and open source, under the Apache License 2.0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        item {
            InfoCard("Your data") {
                Text(
                    "Nothing you type and nothing the AI says ever leaves this phone. " +
                        "There is no account and no tracking.\n\n" +
                        "The only things Tara Core downloads are the models you choose, " +
                        "and a once-a-day check for a new version, which you can turn " +
                        "off in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            InfoCard("Project") {
                LinkRow("Source code") { uriHandler.openUri(REPO) }
                HorizontalDivider()
                LinkRow("Report a problem") { uriHandler.openUri(ISSUES) }
            }
        }
    }
}

/**
 * Licence texts, in full.
 *
 * Apache-2.0 and MIT both require the notice to travel with the software, so this
 * ships the whole text rather than a link: a link is not a copy, and a user offline
 * on a plane still has the right to read it.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val data = remember { Licenses.load(context) }
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Licences", style = MaterialTheme.typography.headlineMedium)
            }
        }

        if (data == null) {
            item {
                Text(
                    "Could not load the licence information. It is also available at " +
                        "$REPO.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@LazyColumn
        }

        item {
            InfoCard("Tara Core") {
                Text(data.app.copyright, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Licensed under the ${data.app.license} licence.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            InfoCard("Built with") {
                Text(
                    "Tara Core uses these open source projects. Thank you to everyone " +
                        "who made them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        items(data.libraries.size) { index ->
            val lib = data.libraries[index]
            InfoCard(lib.name) {
                lib.note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Text(
                    lib.copyright,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${lib.license} licence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinkRow(lib.url) { uriHandler.openUri(lib.url) }
            }
        }

        item {
            InfoCard("Model licences") {
                Text(
                    "The AI models themselves are made by other people and carry their " +
                        "own terms. Downloading one is an agreement between you and " +
                        "whoever made it, not with us.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                data.modelLicenses.forEach { ml ->
                    Text(
                        ml.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(ml.models, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(ml.summary, style = MaterialTheme.typography.bodySmall)
                    ml.url?.let { LinkRow(it) { uriHandler.openUri(it) } }
                }
            }
        }

        data.licenses.forEach { (id, text) ->
            item {
                InfoCard("$id full text") {
                    // Legal texts are hard-wrapped at 80 columns with meaningful
                    // indentation. Letting a phone re-wrap them turns "Apache License
                    // Version 2.0" into ragged nonsense, so the block scrolls
                    // sideways instead and keeps the formatting it was written with.
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkRow(
    label: String,
    external: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 8.dp),
        )
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = if (external) "Opens in your browser" else "Opens the next screen",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
