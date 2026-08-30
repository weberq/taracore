package dev.taracore.service.model

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.room.Room
import dev.taracore.api.ModelInfo
import dev.taracore.service.model.CatalogLoader.toEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The registry: what models exist, which are on disk, and whether one will fit.
 *
 * Files live in `getExternalFilesDir("models")` -- app-private, removed on uninstall,
 * and on the volume that actually has room for several gigabytes. Room holds the
 * metadata; the filesystem is the source of truth for existence, and [sync]
 * reconciles the two on every start (a user can delete files with a file manager).
 */
class ModelRepository(private val context: Context) {

    companion object {
        private const val TAG = "TaraCore/Models"
        private const val MODELS_DIR = "models"

        /** Refuse a download unless free space is at least size * this. */
        const val FREE_SPACE_HEADROOM = 1.1
    }

    private val db: ModelDatabase by lazy {
        Room.databaseBuilder(context.applicationContext, ModelDatabase::class.java, "taracore-models")
            // The registry is a cache of the catalog plus file locations; if a future
            // schema change makes migration awkward, rebuilding it costs nothing but
            // a rescan. Downloaded files are never touched by this.
            .fallbackToDestructiveMigration()
            .build()
    }

    private val dao get() = db.modelDao()

    val modelsDir: File
        get() = File(context.getExternalFilesDir(null), MODELS_DIR).apply { mkdirs() }

    fun observeAll(): Flow<List<ModelEntity>> = dao.observeAll()

    fun observeModelInfo(): Flow<List<ModelInfo>> = dao.observeAll().map { list ->
        list.map { it.toModelInfo(loadedId = null) }
    }

    /**
     * Seed from `catalog.json`, then reconcile against the filesystem.
     *
     * Catalog rows are inserted with IGNORE so a user's own edits and side-loaded
     * models survive an app update that ships a new catalog.
     */
    suspend fun sync() = withContext(Dispatchers.IO) {
        val catalog = CatalogLoader.load(context)
        if (catalog.isNotEmpty()) {
            dao.insertIfAbsent(catalog.map { it.toEntity() })
        }

        val dir = modelsDir
        val onDisk = dir.listFiles { f -> f.isFile && f.extension == "gguf" }
            ?.associateBy { it.name }.orEmpty()

        for (entity in dao.all()) {
            val expected = File(dir, "${entity.id}.gguf")
            when {
                // Registered as downloaded but the file is gone: someone deleted it.
                entity.path != null && !File(entity.path).isFile -> {
                    Log.i(TAG, "${entity.id}: file vanished, marking not downloaded")
                    dao.markNotDownloaded(entity.id)
                }
                entity.path == null && expected.isFile -> {
                    Log.i(TAG, "${entity.id}: found ${expected.name} on disk, registering")
                    dao.markDownloaded(entity.id, expected.absolutePath, expected.lastModified())
                }
            }
        }

        // Side-loaded GGUFs the catalog knows nothing about. Adopting them means a
        // user can drop a file in over adb and see it in the picker.
        val known = dao.all().mapNotNull { it.path?.let(::File)?.name }.toSet()
        for ((name, file) in onDisk) {
            if (name in known) continue
            val id = file.nameWithoutExtension
            if (dao.byId(id) != null) continue
            Log.i(TAG, "adopting side-loaded model $name")
            dao.upsert(
                ModelEntity(
                    id = id,
                    displayName = id,
                    family = "Side-loaded",
                    quant = guessQuant(name),
                    url = "",
                    sizeBytes = file.length(),
                    sha256 = "",
                    ctxDefault = 4096,
                    estRamBytes = (file.length() * 1.15).toLong() + 220L * 1024 * 1024,
                    license = "unknown",
                    description = "Added by hand to ${dir.absolutePath}",
                    path = file.absolutePath,
                    downloadedAt = file.lastModified(),
                    userSupplied = true,
                )
            )
        }
    }

    suspend fun byId(id: String): ModelEntity? = withContext(Dispatchers.IO) { dao.byId(id) }

    suspend fun all(): List<ModelEntity> = withContext(Dispatchers.IO) { dao.all() }

    suspend fun downloaded(): List<ModelEntity> = withContext(Dispatchers.IO) { dao.downloaded() }

    suspend fun listModelInfo(loadedId: String?): List<ModelInfo> =
        withContext(Dispatchers.IO) { dao.all().map { it.toModelInfo(loadedId) } }

    fun targetFileFor(entity: ModelEntity): File = File(modelsDir, "${entity.id}.gguf")

    suspend fun markDownloaded(id: String, file: File) = withContext(Dispatchers.IO) {
        dao.markDownloaded(id, file.absolutePath, System.currentTimeMillis())
    }

    /** Delete the weights, keeping the catalog row so it can be re-downloaded. */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val entity = dao.byId(id) ?: return@withContext false
        val removed = entity.path?.let { File(it).delete() } ?: false
        dao.markNotDownloaded(id)
        if (entity.userSupplied) dao.deleteUserSupplied(id)
        Log.i(TAG, "deleted $id (file removed: $removed)")
        removed
    }

    // ------------------------------------------------------------ capacity

    /** Free bytes on the volume holding [modelsDir]. */
    fun freeSpaceBytes(): Long = runCatching {
        val stat = StatFs(modelsDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    fun availableMemoryBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    fun totalMemoryBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    /** Whether there is room to download, with the 10% headroom applied. */
    fun hasSpaceFor(sizeBytes: Long): Boolean =
        freeSpaceBytes() >= (sizeBytes * FREE_SPACE_HEADROOM).toLong()

    /**
     * Whether the model is likely to fit in RAM. Advisory: the caller shows a
     * warning, it does not block, because `availMem` is a moving target and the
     * kernel will happily evict other apps' pages to make room.
     */
    fun likelyFitsInMemory(entity: ModelEntity): Boolean =
        entity.estRamBytes <= availableMemoryBytes()

    private fun guessQuant(fileName: String): String {
        val n = fileName.uppercase()
        return listOf("Q2_K", "Q3_K_S", "Q3_K_M", "Q3_K_L", "Q4_0", "Q4_K_S", "Q4_K_M",
            "Q5_K_S", "Q5_K_M", "Q6_K", "Q8_0", "F16", "BF16", "F32")
            .firstOrNull { it in n } ?: "unknown"
    }
}

fun ModelEntity.toModelInfo(loadedId: String?) = ModelInfo(
    id = id,
    displayName = displayName,
    family = family,
    quant = quant,
    sizeBytes = sizeBytes,
    estRamBytes = estRamBytes,
    ctxDefault = ctxDefault,
    downloaded = downloaded,
    loaded = id == loadedId,
    license = license,
)
