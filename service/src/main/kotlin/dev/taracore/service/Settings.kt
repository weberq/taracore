package dev.taracore.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("taracore_settings")

/** Everything the user can change, as one immutable snapshot. */
data class SettingsSnapshot(
    val idleTimeoutMs: Long = TaraSettings.DEFAULT_IDLE_TIMEOUT_MS,
    val threads: Int = 0,               // 0 = derive from the CPU count
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
 * Preferences DataStore rather than Room: this is a flat bag of scalars read as a
 * Flow, with no queries and no relations. See docs/DECISIONS.md D10.
 */
class TaraSettings(private val context: Context) {

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1000L
        /** Idle timeout value meaning "never unload". */
        const val IDLE_NEVER = 0L

        private val IDLE_TIMEOUT = longPreferencesKey("idle_timeout_ms")
        private val THREADS = intPreferencesKey("threads")
        private val CONTEXT_SIZE = intPreferencesKey("context_size")
        private val GPU_LAYERS = intPreferencesKey("gpu_layers")
        private val HTTP_ENABLED = booleanPreferencesKey("http_enabled")
        private val HTTP_PORT = intPreferencesKey("http_port")
        private val HTTP_TOKEN = stringPreferencesKey("http_token")
        private val HTTP_AUTH_REQUIRED = booleanPreferencesKey("http_auth_required")
        private val AUTO_LOAD = booleanPreferencesKey("auto_load_on_request")
        private val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        private val ACTIVE_MODEL = stringPreferencesKey("active_model_id")
        private val USE_MMAP = booleanPreferencesKey("use_mmap")
        private val USE_MLOCK = booleanPreferencesKey("use_mlock")

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

    val flow: Flow<SettingsSnapshot> = context.dataStore.data.map { p ->
        SettingsSnapshot(
            idleTimeoutMs = p[IDLE_TIMEOUT] ?: DEFAULT_IDLE_TIMEOUT_MS,
            threads = p[THREADS] ?: 0,
            contextSize = p[CONTEXT_SIZE] ?: 4096,
            gpuLayers = p[GPU_LAYERS] ?: 0,
            httpEnabled = p[HTTP_ENABLED] ?: false,
            httpPort = p[HTTP_PORT] ?: DEFAULT_PORT,
            httpToken = p[HTTP_TOKEN] ?: "",
            httpAuthRequired = p[HTTP_AUTH_REQUIRED] ?: true,
            autoLoadOnRequest = p[AUTO_LOAD] ?: true,
            startOnBoot = p[START_ON_BOOT] ?: false,
            activeModelId = p[ACTIVE_MODEL],
            useMmap = p[USE_MMAP] ?: true,
            useMlock = p[USE_MLOCK] ?: false,
        )
    }

    /**
     * Read the token, minting one on first access. Done here rather than at install
     * time so the token exists the moment anything needs it, and exactly once.
     */
    suspend fun ensureToken(): String {
        var token = ""
        context.dataStore.edit { p ->
            val existing = p[HTTP_TOKEN]
            if (existing.isNullOrBlank()) {
                token = generateToken()
                p[HTTP_TOKEN] = token
            } else {
                token = existing
            }
        }
        return token
    }

    suspend fun regenerateToken(): String {
        val token = generateToken()
        context.dataStore.edit { it[HTTP_TOKEN] = token }
        return token
    }

    suspend fun setIdleTimeout(ms: Long) = edit { it[IDLE_TIMEOUT] = ms.coerceAtLeast(0) }

    suspend fun setThreads(n: Int) = edit { it[THREADS] = n.coerceIn(0, 16) }

    suspend fun setContextSize(n: Int) = edit { it[CONTEXT_SIZE] = n.coerceIn(256, 131072) }

    suspend fun setGpuLayers(n: Int) = edit { it[GPU_LAYERS] = n.coerceIn(0, 999) }

    suspend fun setHttpEnabled(on: Boolean) = edit { it[HTTP_ENABLED] = on }

    suspend fun setHttpPort(port: Int) = edit {
        // Below 1024 needs root; above 65535 does not exist.
        it[HTTP_PORT] = port.coerceIn(1024, 65535)
    }

    suspend fun setHttpAuthRequired(required: Boolean) = edit { it[HTTP_AUTH_REQUIRED] = required }

    suspend fun setAutoLoad(on: Boolean) = edit { it[AUTO_LOAD] = on }

    suspend fun setStartOnBoot(on: Boolean) = edit { it[START_ON_BOOT] = on }

    suspend fun setActiveModel(id: String?) = edit {
        if (id == null) it.remove(ACTIVE_MODEL) else it[ACTIVE_MODEL] = id
    }

    suspend fun setUseMmap(on: Boolean) = edit { it[USE_MMAP] = on }

    suspend fun setUseMlock(on: Boolean) = edit { it[USE_MLOCK] = on }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
