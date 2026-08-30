package dev.taracore.app

import android.app.Application
import android.os.Debug
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.taracore.api.ChatMessageParcel
import dev.taracore.api.ServiceStatus
import dev.taracore.client.ChatParams
import dev.taracore.client.LoadProgress
import dev.taracore.client.TaraCoreClient
import dev.taracore.service.BootReceiver
import dev.taracore.service.SettingsSnapshot
import dev.taracore.service.TaraCoreService
import dev.taracore.service.TaraSettings
import dev.taracore.service.model.DownloadProgress
import dev.taracore.service.model.DownloadRegistry
import dev.taracore.service.model.ModelEntity
import dev.taracore.service.model.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One turn shown in the Playground. */
data class ChatTurn(
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val tokensPerSecond: Double = 0.0,
)

/** Everything the Dashboard shows that is not in [ServiceStatus]. */
data class DeviceStats(
    val totalRamBytes: Long = 0,
    val availableRamBytes: Long = 0,
    val nativeHeapBytes: Long = 0,
    val freeStorageBytes: Long = 0,
)

/**
 * State for all four screens.
 *
 * The Playground goes through [TaraCoreClient] rather than reaching into `:service`
 * directly. That is deliberate: the integration path third-party apps use is the one
 * we exercise on every run, so a regression in the public API shows up here first.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "TaraCore/UI"
        const val POLL_INTERVAL_MS = 1_000L
    }

    private val settingsStore = TaraSettings(app)
    private val repository = ModelRepository(app)
    private val client = TaraCoreClient(app)

    val settings: StateFlow<SettingsSnapshot> = settingsStore.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsSnapshot())

    val models: StateFlow<List<ModelEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloads: StateFlow<Map<String, DownloadProgress>> = DownloadRegistry.progress

    private val _status = MutableStateFlow(
        ServiceStatus(apiVersion = 0, state = ServiceStatus.State.IDLE)
    )
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _deviceStats = MutableStateFlow(DeviceStats())
    val deviceStats: StateFlow<DeviceStats> = _deviceStats.asStateFlow()

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _loadProgress = MutableStateFlow<LoadProgress?>(null)
    val loadProgress: StateFlow<LoadProgress?> = _loadProgress.asStateFlow()

    /** The in-flight generation, so the Stop button has something to cancel. */
    private var generationJob: Job? = null

    init {
        viewModelScope.launch { repository.sync() }
        startService()
        connect()
        pollStatus()
    }

    // ------------------------------------------------------------ connection

    private fun startService() {
        runCatching {
            ContextCompat.startForegroundService(
                getApplication(),
                TaraCoreService.startIntent(getApplication()),
            )
        }.onFailure { Log.e(TAG, "could not start the service", it) }
    }

    fun connect() {
        viewModelScope.launch {
            runCatching { client.connect() }
                .onSuccess { _connected.value = true }
                .onFailure {
                    _connected.value = false
                    Log.e(TAG, "could not bind to the service", it)
                    _message.value = "Could not connect to the engine: ${it.message}"
                }
        }
    }

    /**
     * Poll rather than observe: ServiceStatus crosses a Binder and there is no
     * callback for it. One transaction a second while the UI is visible is cheap,
     * and the countdown and tokens/s need to tick anyway.
     */
    private fun pollStatus() {
        viewModelScope.launch {
            while (isActive) {
                if (_connected.value) {
                    runCatching { client.status() }
                        .onSuccess { _status.value = it }
                        .onFailure {
                            _connected.value = false
                            Log.w(TAG, "status poll failed; will reconnect", it)
                            connect()
                        }
                }
                _deviceStats.value = DeviceStats(
                    totalRamBytes = repository.totalMemoryBytes(),
                    availableRamBytes = repository.availableMemoryBytes(),
                    // The UI process, not the engine process, so this measures our own
                    // footprint. The model's RAM is reported separately by the engine.
                    nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
                    freeStorageBytes = repository.freeSpaceBytes(),
                )
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // ---------------------------------------------------------------- models

    fun download(modelId: String) {
        val entity = models.value.firstOrNull { it.id == modelId } ?: return
        if (!repository.hasSpaceFor(entity.sizeBytes)) {
            _message.value = "Not enough free space for ${entity.displayName}"
            return
        }
        if (!repository.likelyFitsInMemory(entity)) {
            _message.value = "${entity.displayName} may not fit in this device's memory"
        }
        DownloadRegistry.enqueue(getApplication(), modelId)
    }

    fun cancelDownload(modelId: String) = DownloadRegistry.cancel(getApplication(), modelId)

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            if (status.value.loadedModelId == modelId) {
                runCatching { client.unload() }
            }
            repository.delete(modelId)
            _message.value = "Deleted"
        }
    }

    fun setActive(modelId: String) {
        viewModelScope.launch {
            settingsStore.setActiveModel(modelId)
            _loadProgress.value = null
            runCatching {
                client.load(modelId).collect { _loadProgress.value = it }
            }.onFailure {
                if (it !is CancellationException) {
                    _message.value = "Could not load: ${it.message}"
                }
            }
        }
    }

    fun unload() {
        viewModelScope.launch {
            runCatching { client.unload() }
            _loadProgress.value = null
        }
    }

    // ------------------------------------------------------------ playground

    fun send(prompt: String) {
        if (prompt.isBlank() || _busy.value) return

        val history = _turns.value + ChatTurn("user", prompt)
        _turns.value = history + ChatTurn("assistant", "", streaming = true)
        _busy.value = true

        generationJob = viewModelScope.launch {
            val messages = history.map { ChatMessageParcel(it.role, it.text) }
            val started = System.currentTimeMillis()
            val builder = StringBuilder()
            var tokens = 0

            runCatching {
                client.chatStream(messages, ChatParams(maxTokens = 512)).collect { piece ->
                    builder.append(piece)
                    tokens++
                    val elapsed = System.currentTimeMillis() - started
                    _turns.value = _turns.value.dropLast(1) + ChatTurn(
                        role = "assistant",
                        text = builder.toString(),
                        streaming = true,
                        tokensPerSecond = if (elapsed > 0) tokens * 1000.0 / elapsed else 0.0,
                    )
                }
            }.onFailure { t ->
                if (t !is CancellationException) {
                    Log.e(TAG, "generation failed", t)
                    _message.value = t.message ?: "Generation failed"
                }
            }

            // Settle the last turn: streaming flag off, final rate kept.
            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
            _turns.value = _turns.value.dropLast(1) + ChatTurn(
                role = "assistant",
                text = builder.toString().ifBlank { "(no output)" },
                streaming = false,
                tokensPerSecond = tokens * 1000.0 / elapsed,
            )
            _busy.value = false
        }
    }

    /** Cancelling the collector is what cancels the service-side generation. */
    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        _busy.value = false
    }

    fun clearChat() {
        stopGeneration()
        _turns.value = emptyList()
    }

    // -------------------------------------------------------------- settings

    fun setIdleTimeout(ms: Long) = viewModelScope.launch { settingsStore.setIdleTimeout(ms) }

    fun setThreads(n: Int) = viewModelScope.launch { settingsStore.setThreads(n) }

    fun setContextSize(n: Int) = viewModelScope.launch { settingsStore.setContextSize(n) }

    fun setGpuLayers(n: Int) = viewModelScope.launch { settingsStore.setGpuLayers(n) }

    fun setHttpEnabled(on: Boolean) = viewModelScope.launch { settingsStore.setHttpEnabled(on) }

    fun setHttpPort(port: Int) = viewModelScope.launch { settingsStore.setHttpPort(port) }

    fun setHttpAuthRequired(on: Boolean) =
        viewModelScope.launch { settingsStore.setHttpAuthRequired(on) }

    fun setAutoLoad(on: Boolean) = viewModelScope.launch { settingsStore.setAutoLoad(on) }

    fun regenerateToken() {
        viewModelScope.launch {
            settingsStore.regenerateToken()
            _message.value = "New token generated. Existing clients will get 401."
        }
    }

    fun setStartOnBoot(on: Boolean) {
        viewModelScope.launch {
            settingsStore.setStartOnBoot(on)
            // The receiver ships disabled; enabling the setting enables the component
            // so a user who never asked never has one woken at boot.
            BootReceiver.setEnabled(getApplication(), on)
        }
    }

    fun setUseMmap(on: Boolean) = viewModelScope.launch { settingsStore.setUseMmap(on) }

    fun setUseMlock(on: Boolean) = viewModelScope.launch { settingsStore.setUseMlock(on) }

    fun dismissMessage() { _message.value = null }

    override fun onCleared() {
        stopGeneration()
        client.close()
        super.onCleared()
    }
}
