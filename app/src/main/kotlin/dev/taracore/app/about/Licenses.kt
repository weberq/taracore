package dev.taracore.app.about

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppLicense(
    val name: String,
    val copyright: String,
    val license: String,
    val url: String,
)

@Serializable
data class LibraryLicense(
    val name: String,
    val copyright: String,
    val license: String,
    val url: String,
    /** What it does here, in the user's terms. Absent for the unremarkable ones. */
    val note: String? = null,
)

@Serializable
data class ModelLicense(
    val name: String,
    val models: String,
    val summary: String,
    val url: String? = null,
)

@Serializable
data class LicenseData(
    val app: AppLicense,
    val libraries: List<LibraryLicense> = emptyList(),
    /** Full licence text, keyed by identifier. Apache-2.0 requires shipping it. */
    val licenses: Map<String, String> = emptyMap(),
    @SerialName("modelLicenses") val modelLicenses: List<ModelLicense> = emptyList(),
)

/**
 * Reads `assets/licenses.json`.
 *
 * Hand-maintained rather than generated. The generated options either need Google
 * Play services on the classpath or scrape POMs that half our dependencies do not
 * publish usefully, and the list is short enough that keeping it honest by hand is
 * less work than keeping a generator honest. Adding a dependency means adding a line
 * here -- see CONTRIBUTING.md.
 */
object Licenses {

    private const val TAG = "TaraCore/Licenses"
    private const val ASSET = "licenses.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: LicenseData? = null

    fun load(context: Context): LicenseData? {
        cached?.let { return it }
        return runCatching {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            json.decodeFromString<LicenseData>(text).also { cached = it }
        }.onFailure { Log.e(TAG, "could not read $ASSET", it) }.getOrNull()
    }
}
