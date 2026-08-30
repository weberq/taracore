package dev.taracore.service.model

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One entry of `assets/catalog.json`. */
@Serializable
data class CatalogEntry(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val family: String,
    val quant: String,
    val url: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
    @SerialName("ctx_default") val ctxDefault: Int = 4096,
    @SerialName("est_ram_bytes") val estRamBytes: Long,
    val license: String,
    val description: String = "",
)

@Serializable
data class Catalog(
    val version: Int,
    val models: List<CatalogEntry>,
)

object CatalogLoader {

    private const val TAG = "TaraCore/Catalog"
    private const val ASSET = "catalog.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Read the bundled catalog. A malformed catalog is logged and treated as empty
     * rather than thrown: a bad asset should degrade the model picker, not prevent
     * the service from starting with an already-downloaded model.
     */
    fun load(context: Context): List<CatalogEntry> = try {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        json.decodeFromString<Catalog>(text).models
    } catch (t: Throwable) {
        Log.e(TAG, "failed to read $ASSET; continuing with an empty catalog", t)
        emptyList()
    }

    fun CatalogEntry.toEntity() = ModelEntity(
        id = id,
        displayName = displayName,
        family = family,
        quant = quant,
        url = url,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        ctxDefault = ctxDefault,
        estRamBytes = estRamBytes,
        license = license,
        description = description,
    )
}
