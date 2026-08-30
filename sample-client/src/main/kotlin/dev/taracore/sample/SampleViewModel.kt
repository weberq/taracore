package dev.taracore.sample

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.taracore.api.ChatMessageParcel
import dev.taracore.client.ChatParams
import dev.taracore.client.TaraCore
import dev.taracore.client.TaraCoreClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One transport's result, so the two tabs can be compared side by side. */
data class RunResult(
    val text: String = "",
    val running: Boolean = false,
    val firstTokenMs: Long = 0,
    val totalMs: Long = 0,
    val tokens: Int = 0,
    val error: String? = null,
) {
    val tokensPerSecond: Double
        get() = if (totalMs > 0) tokens * 1000.0 / totalMs else 0.0
}

/**
 * Drives both transports so a developer can see, on their own device, what the
 * choice between them actually costs.
 *
 * Throughput is nearly identical, because both share one engine. Time to first token
 * is the interesting number, and it does not favour the transport you would expect:
 * measured back to back, the *second* run usually wins whichever transport it used,
 * because the KV cache still holds the prompt's prefix and it skips the prompt eval.
 * The transport difference is real but small enough to be swamped by that, which is
 * exactly why this screen measures rather than asserts.
 */
class SampleViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "TaraCore/Sample"
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8080"
    }

    private val client = TaraCoreClient(app)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // No read timeout: a long generation is not a stalled connection.
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _installed = MutableStateFlow(TaraCore.isInstalled(app))
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models.asStateFlow()

    private val _aidl = MutableStateFlow(RunResult())
    val aidl: StateFlow<RunResult> = _aidl.asStateFlow()

    private val _httpResult = MutableStateFlow(RunResult())
    val httpResult: StateFlow<RunResult> = _httpResult.asStateFlow()

    private var aidlJob: Job? = null
    private var httpJob: Job? = null

    init {
        connect()
    }

    fun connect() {
        viewModelScope.launch {
            _installed.value = TaraCore.isInstalled(getApplication())
            if (!_installed.value) return@launch

            runCatching { client.connect() }
                .onSuccess {
                    _connected.value = true
                    runCatching { client.models() }
                        .onSuccess { list -> _models.value = list.filter { it.downloaded }.map { it.id } }
                }
                .onFailure {
                    _connected.value = false
                    _aidl.value = RunResult(error = "Could not connect: ${it.message}")
                }
        }
    }

    // ------------------------------------------------------------------ AIDL

    fun runAidl(prompt: String, maxTokens: Int) {
        aidlJob?.cancel()
        _aidl.value = RunResult(running = true)

        aidlJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            var firstToken = 0L
            var tokens = 0
            val text = StringBuilder()

            runCatching {
                client.chatStream(
                    messages = listOf(ChatMessageParcel(ChatMessageParcel.ROLE_USER, prompt)),
                    params = ChatParams(maxTokens = maxTokens),
                ).collect { piece ->
                    if (firstToken == 0L) firstToken = System.currentTimeMillis() - started
                    tokens++
                    text.append(piece)
                    _aidl.value = RunResult(
                        text = text.toString(),
                        running = true,
                        firstTokenMs = firstToken,
                        totalMs = System.currentTimeMillis() - started,
                        tokens = tokens,
                    )
                }
            }.onFailure { t ->
                if (t !is CancellationException) {
                    Log.e(TAG, "AIDL run failed", t)
                    _aidl.value = _aidl.value.copy(running = false, error = t.message)
                    return@launch
                }
            }

            _aidl.value = RunResult(
                text = text.toString(),
                running = false,
                firstTokenMs = firstToken,
                totalMs = System.currentTimeMillis() - started,
                tokens = tokens,
            )
        }
    }

    /** Cancelling the collector cancels the generation on the service. */
    fun cancelAidl() {
        aidlJob?.cancel()
        aidlJob = null
        _aidl.value = _aidl.value.copy(running = false)
    }

    // ------------------------------------------------------------------ HTTP

    fun runHttp(prompt: String, maxTokens: Int, token: String, model: String?, baseUrl: String) {
        httpJob?.cancel()
        _httpResult.value = RunResult(running = true)

        httpJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            var firstToken = 0L
            var tokens = 0
            val text = StringBuilder()

            runCatching {
                withContext(Dispatchers.IO) {
                    val payload = buildJsonObject {
                        put("model", model ?: _models.value.firstOrNull() ?: "")
                        put("messages", buildJsonArray {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", prompt)
                            })
                        })
                        put("max_tokens", maxTokens)
                        put("stream", true)
                    }

                    val request = Request.Builder()
                        .url("${baseUrl.trimEnd('/')}/v1/chat/completions")
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                        .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                        .build()

                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val body = response.body?.string().orEmpty()
                            error("HTTP ${response.code}: ${body.take(300)}")
                        }

                        val source = response.body?.source() ?: error("empty response body")
                        // Hand-rolled SSE: the format is two lines and a blank, and a
                        // dependency for that would be more code than this loop.
                        while (!source.exhausted()) {
                            // The coroutine's own state, not the `httpJob` field: that
                            // field is still null on the first pass, because launch
                            // has not returned yet.
                            if (!currentCoroutineContext().isActive) break
                            val line = source.readUtf8LineStrict()
                            if (!line.startsWith("data:")) continue

                            val payloadLine = line.removePrefix("data:").trim()
                            if (payloadLine == "[DONE]") break

                            val piece = runCatching {
                                json.parseToJsonElement(payloadLine).jsonObject["choices"]
                                    ?.jsonArray?.firstOrNull()?.jsonObject
                                    ?.get("delta")?.jsonObject
                                    ?.get("content")?.jsonPrimitive?.content
                            }.getOrNull()

                            if (!piece.isNullOrEmpty()) {
                                if (firstToken == 0L) firstToken = System.currentTimeMillis() - started
                                tokens++
                                text.append(piece)
                                _httpResult.value = RunResult(
                                    text = text.toString(),
                                    running = true,
                                    firstTokenMs = firstToken,
                                    totalMs = System.currentTimeMillis() - started,
                                    tokens = tokens,
                                )
                            }
                        }
                    }
                }
            }.onFailure { t ->
                if (t !is CancellationException) {
                    Log.e(TAG, "HTTP run failed", t)
                    _httpResult.value = _httpResult.value.copy(running = false, error = t.message)
                    return@launch
                }
            }

            _httpResult.value = RunResult(
                text = text.toString(),
                running = false,
                firstTokenMs = firstToken,
                totalMs = System.currentTimeMillis() - started,
                tokens = tokens,
            )
        }
    }

    /**
     * Closing the response body is what actually stops an SSE stream: the service
     * sees the disconnect and cancels generation. Cancelling the job alone would
     * leave the socket open until the coroutine unwound.
     */
    fun cancelHttp() {
        httpJob?.cancel()
        httpJob = null
        _httpResult.value = _httpResult.value.copy(running = false)
    }

    fun installIntent() = TaraCore.installIntent()

    override fun onCleared() {
        aidlJob?.cancel()
        httpJob?.cancel()
        client.close()
        super.onCleared()
    }
}
