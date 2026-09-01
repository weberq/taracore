package dev.taracore.service.model

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * A model the device knows about. Rows exist for catalog entries that have not been
 * downloaded too -- `path` is null until the file lands, which is what `downloaded`
 * really means.
 */
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val family: String,
    val quant: String,
    val url: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val sha256: String,
    @ColumnInfo(name = "ctx_default") val ctxDefault: Int,
    @ColumnInfo(name = "est_ram_bytes") val estRamBytes: Long,
    val license: String,
    val description: String = "",
    /**
     * The model to fall back to when the user has not chosen one.
     *
     * Not simply "the smallest downloaded". A first-time user judges the whole app on
     * whatever answers them first, and the smallest model answers badly enough to
     * look like the app is broken rather than the model being small.
     */
    val recommended: Boolean = false,
    /** Absolute path once downloaded; null while it is only a catalog entry. */
    val path: String? = null,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long = 0,
    /** True for models the user side-loaded rather than took from the catalog. */
    @ColumnInfo(name = "user_supplied") val userSupplied: Boolean = false,
) {
    val downloaded: Boolean get() = path != null
}

@Dao
interface ModelDao {

    @Query("SELECT * FROM models ORDER BY family, size_bytes")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models ORDER BY family, size_bytes")
    suspend fun all(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE path IS NOT NULL ORDER BY family, size_bytes")
    suspend fun downloaded(): List<ModelEntity>

    /**
     * The best default among what is actually on disk: the recommended model if it is
     * downloaded, otherwise the largest one, which is the best this device has.
     */
    @Query(
        """
        SELECT * FROM models WHERE path IS NOT NULL
        ORDER BY recommended DESC, size_bytes DESC
        LIMIT 1
        """
    )
    suspend fun bestDownloaded(): ModelEntity?

    @Query("SELECT * FROM models WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(models: List<ModelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ModelEntity)

    @Query("UPDATE models SET path = :path, downloaded_at = :at WHERE id = :id")
    suspend fun markDownloaded(id: String, path: String, at: Long)

    @Query("UPDATE models SET path = NULL, downloaded_at = 0 WHERE id = :id")
    suspend fun markNotDownloaded(id: String)

    @Query("DELETE FROM models WHERE id = :id AND user_supplied = 1")
    suspend fun deleteUserSupplied(id: String)
}

// version 2: added ModelEntity.recommended
@Database(entities = [ModelEntity::class], version = 2, exportSchema = false)
abstract class ModelDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
}
