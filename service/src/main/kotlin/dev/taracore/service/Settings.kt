package dev.taracore.service

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the user can change, as one immutable snapshot.
 *
 * Serialized whole rather than as individual keys, because the store below writes the
 * whole file atomically anyway and a single object is far easier to reason about
 * across two processes than a bag of independently-versioned keys.
 */
@Serializable
data class SettingsSnapshot(
    val idleTimeoutMs: Long = TaraSettings.DEFAULT_IDLE_TIMEOUT_MS,
    /** 0 = derive from the CPU count. */
    val threads: Int = 0,
    val contextSize: Int = 4096,
    val gpuLayers: Int = 0,
    val httpEnabled: Boolean = false,
    val httpPort: Int = TaraSettings.DEFAULT_PORT,
    val httpToken: String = "",
    val httpAuthRequired: Boolean = true,
    val autoLoadOnRequest: Boolean = true,
    val startOnBoot: Boolean = false,
    val activeModelId: String? = null,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
)

/**
 * Settings, shared between the UI process and the `:engine` process.
 *
 * ## Why not Preferences DataStore
 *
 * Because it is **not multi-process safe**, and the failure is silent. Each process
 * gets its own instance with its own in-memory cache and its own file watcher; a
 * write in the UI process is never observed in `:engine`. This was not a theoretical
 * concern -- the first build shipped Preferences DataStore, and toggling "enable the
 * HTTP server" in Settings updated the UI, persisted to disk, and did absolutely
 * nothing, because the service that owns the server never heard about it. Nothing
 * logged an error.
 *
 * [MultiProcessDataStoreFactory] is the supported answer: it coordinates through an
 * exclusive file lock and a shared counter, so a write in one process invalidates the
 * other's cache and re-emits on its flow.
 */
class TaraSettings(context: Context) {

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1000L

        /** Idle timeout value meaning "never unload". */
        const val IDLE_NEVER = 0L

        private const val FILE_NAME = "taracore_settings.json"

        /** Guarded so both processes share one instance rather than two watchers. */
        @Volatile
        private var instance: DataStore<SettingsSnapshot>? = null

        private fun store(context: Context): DataStore<SettingsSnapshot> =
            instance ?: synchronized(this) {
                instance ?: MultiProcessDataStoreFactory.create(
                    serializer = SettingsSerializer,
                    produceFile = {
                        // Internal storage, not the cache: settings must survive the
                        // system reclaiming cache space.
                        File(context.applicationContext.filesDir, FILE_NAME)
                    },
                ).also { instance = it }
            }

        /**
         * 32 bytes from SecureRandom, base64url without padding. Long enough that
         * guessing it is not a threat model, short enough to copy by hand.
         */
        fun generateToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

    private val dataStore = store(context)

    val flow: Flow<SettingsSnapshot> = dataStore.data

    /**
     * Read the token, minting one on first access. Done lazily rather than at install
     * time so the token exists the moment anything needs it, and exactly once.
     */
    suspend fun ensureToken(): String {
        var token = ""
        dataStore.updateData { current ->
            token = current.httpToken.ifBlank { generateToken() }
            if (current.httpToken.isBlank()) current.copy(httpToken = token) else current
        }
        return token
    }

    suspend fun regenerateToken(): String {
        val token = generateToken()
        dataStore.updateData { it.copy(httpToken = token) }
        return token
    }

    suspend fun setIdleTimeout(ms: Long) = update { it.copy(idleTimeoutMs = ms.coerceAtLeast(0)) }

    suspend fun setThreads(n: Int) = update { it.copy(threads = n.coerceIn(0, 16)) }

    suspend fun setContextSize(n: Int) = update { it.copy(contextSize = n.coerceIn(256, 131072)) }

    suspend fun setGpuLayers(n: Int) = update { it.copy(gpuLayers = n.coerceIn(0, 999)) }

    suspend fun setHttpEnabled(on: Boolean) = update { it.copy(httpEnabled = on) }

    // Below 1024 needs root; above 65535 does not exist.
    suspend fun setHttpPort(port: Int) = update { it.copy(httpPort = port.coerceIn(1024, 65535)) }

    suspend fun setHttpAuthRequired(required: Boolean) =
        update { it.copy(httpAuthRequired = required) }

    suspend fun setAutoLoad(on: Boolean) = update { it.copy(autoLoadOnRequest = on) }

    suspend fun setStartOnBoot(on: Boolean) = update { it.copy(startOnBoot = on) }

    suspend fun setActiveModel(id: String?) = update { it.copy(activeModelId = id) }

    suspend fun setUseMmap(on: Boolean) = update { it.copy(useMmap = on) }

    suspend fun setUseMlock(on: Boolean) = update { it.copy(useMlock = on) }

    private suspend fun update(block: (SettingsSnapshot) -> SettingsSnapshot) {
        dataStore.updateData(block)
    }
}

/**
 * JSON on disk. Chosen over protobuf because the file is tiny, read once per change,
 * and being able to `cat` it while debugging a cross-process problem is worth more
 * than the bytes.
 */
private object SettingsSerializer : Serializer<SettingsSnapshot> {

    private val json = Json {
        ignoreUnknownKeys = true   // tolerate a downgrade after a new field is added
        encodeDefaults = true
    }

    override val defaultValue = SettingsSnapshot()

    override suspend fun readFrom(input: InputStream): SettingsSnapshot = try {
        json.decodeFromString(SettingsSnapshot.serializer(), input.readBytes().decodeToString())
    } catch (t: Throwable) {
        // A CorruptionException lets DataStore fall back to defaults rather than
        // wedging every read forever. Losing settings is recoverable; a service that
        // cannot start is not.
        throw CorruptionException("could not read $t", t)
    }

    override suspend fun writeTo(t: SettingsSnapshot, output: OutputStream) {
        output.write(json.encodeToString(SettingsSnapshot.serializer(), t).encodeToByteArray())
    }
}
