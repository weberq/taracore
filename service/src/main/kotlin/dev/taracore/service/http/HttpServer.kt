package dev.taracore.service.http

import android.util.Log
import dev.taracore.api.ChatMessageParcel
import dev.taracore.api.ModelInfo
import dev.taracore.api.ServiceStatus
import dev.taracore.api.TaraCoreErrors
import dev.taracore.engine.GenEvent
import dev.taracore.engine.GenParams
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * OpenAI-compatible HTTP surface, bound to loopback only.
 *
 * ## Why this exists alongside AIDL
 *
 * AIDL is the better interface for an Android app: typed, cancellable, no
 * serialisation of large prompts. But an enormous amount of software already speaks
 * the OpenAI wire format, and a Flutter or React Native app cannot easily bind a
 * Binder interface. Speaking a protocol every client already knows costs one Ktor
 * dependency and buys the whole ecosystem.
 *
 * ## Security
 *
 * The socket binds `127.0.0.1` and every request is checked against the remote
 * address before routing. But loopback is **not** a permission boundary on Android:
 * any app can open a socket to `127.0.0.1` with no permission at all, so the bearer
 * token -- not the bind address -- is what actually gates access.
 */
class HttpServer(
    private val port: Int,
    private val tokenProvider: () -> String,
    private val authRequired: () -> Boolean,
    private val bridge: Bridge,
) {

    companion object {
        private const val TAG = "TaraCore/Http"
        private const val LOOPBACK = "127.0.0.1"

        /** SSE keeps the connection open; anything shorter would kill long generations. */
        private const val NO_TIMEOUT_SECONDS = 0L
    }

    /** What the server may ask of the service. Kept narrow on purpose. */
    interface Bridge {
        suspend fun listModels(): List<ModelInfo>
        fun status(): ServiceStatus
        /** @return null on success, or (errorCode, message). */
        suspend fun ensureLoaded(modelId: String?): Pair<Int, String>?
        fun newRequestId(): String
        fun stream(
            requestId: String,
            messages: List<ChatMessageParcel>,
            rawPrompt: String?,
            params: GenParams,
        ): Flow<GenEvent>
        fun onFinished(tokensPerSecond: Double)
        fun cancel(requestId: String)
        fun loadedModelId(): String?
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private var server: ApplicationEngine? = null
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.get()) return

        server = embeddedServer(CIO, port = port, host = LOOPBACK) {
            install(ContentNegotiation) { json(json) }

            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    Log.e(TAG, "unhandled error on ${call.request.uri}", cause)
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        cause.message ?: "internal error",
                        "internal_error",
                    )
                }
            }

            routing {
                get("/health") {
                    // Deliberately unauthenticated: a client needs to find out whether
                    // the server is up before it asks the user for a token. It leaks
                    // only the model name, to a caller already on the device.
                    if (!call.assertLoopback()) return@get
                    val s = bridge.status()
                    call.respond(
                        HealthResponse(
                            status = if (s.state == ServiceStatus.State.ERROR) "error" else "ok",
                            model = s.loadedModelId,
                            backend = s.backend,
                            apiVersion = s.apiVersion,
                            engine = s.engineVersion,
                        )
                    )
                }

                get("/v1/models") {
                    if (!call.authorise()) return@get
                    val models = bridge.listModels().filter { it.downloaded }
                    val created = System.currentTimeMillis() / 1000
                    call.respond(ModelListResponse(data = models.map {
                        ModelDto(id = it.id, created = created)
                    }))
                }

                post("/v1/chat/completions") {
                    if (!call.authorise()) return@post
                    handleChatCompletion(call)
                }

                post("/v1/completions") {
                    if (!call.authorise()) return@post
                    handleCompletion(call)
                }
            }
        }

        server?.start(wait = false)
        running.set(true)
        Log.i(TAG, "listening on $LOOPBACK:$port")
    }

    suspend fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "stopping")
        // Grace then hard stop: an in-flight SSE stream gets a moment to finish, but a
        // wedged one must not keep the port bound.
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        server = null
    }

    // ------------------------------------------------------------- handlers

    private suspend fun handleChatCompletion(call: ApplicationCall) {
        val request = try {
            call.receive<ChatCompletionRequest>()
        } catch (t: Throwable) {
            call.respondError(HttpStatusCode.BadRequest,
                "could not parse the request body: ${t.message}", "invalid_request_error")
            return
        }

        if (request.messages.isEmpty()) {
            call.respondError(HttpStatusCode.BadRequest,
                "messages must not be empty", "invalid_request_error", param = "messages")
            return
        }

        bridge.ensureLoaded(request.model)?.let { (code, message) ->
            call.respondLoadFailure(code, message)
            return
        }

        val modelId = bridge.loadedModelId().orEmpty()
        val requestId = "chatcmpl-${bridge.newRequestId()}"
        val messages = request.messages.map {
            ChatMessageParcel(it.role, it.content.orEmpty())
        }
        val params = GenParams(
            maxTokens = request.effectiveMaxTokens().coerceIn(1, 32_768),
            temperature = request.temperature ?: 0.8f,
            topP = request.topP ?: 0.95f,
            topK = request.topK ?: 40,
            seed = request.seed ?: -1L,
            stop = request.stopStrings(),
        )

        if (request.stream) {
            streamChat(call, requestId, modelId, messages, params)
        } else {
            completeChat(call, requestId, modelId, messages, params)
        }
    }

    private suspend fun completeChat(
        call: ApplicationCall,
        requestId: String,
        modelId: String,
        messages: List<ChatMessageParcel>,
        params: GenParams,
    ) {
        val text = StringBuilder()
        var done: GenEvent.Done? = null
        var error: String? = null

        bridge.stream(requestId, messages, null, params).collect { event ->
            when (event) {
                is GenEvent.Token -> text.append(event.piece)
                is GenEvent.Done -> done = event
                is GenEvent.Error -> error = event.message
            }
        }

        error?.let {
            call.respondError(HttpStatusCode.InternalServerError, it, "engine_error")
            return
        }

        val stats = done?.stats
        stats?.let { bridge.onFinished(it.genTokensPerSecond) }

        call.respond(
            ChatCompletionResponse(
                id = requestId,
                created = System.currentTimeMillis() / 1000,
                model = modelId,
                choices = listOf(
                    ChatChoice(
                        message = ChatMessageDto(role = "assistant", content = text.toString()),
                        finishReason = finishReasonFor(stats?.stopped, stats?.cancelled,
                            stats?.genTokens, params.maxTokens),
                    )
                ),
                usage = Usage(
                    promptTokens = stats?.promptTokens ?: 0,
                    completionTokens = stats?.genTokens ?: 0,
                    totalTokens = (stats?.promptTokens ?: 0) + (stats?.genTokens ?: 0),
                ),
                timings = stats?.let {
                    Timings(it.promptMs, it.genMs, it.genTokensPerSecond)
                },
            )
        )
    }

    private suspend fun streamChat(
        call: ApplicationCall,
        requestId: String,
        modelId: String,
        messages: List<ChatMessageParcel>,
        params: GenParams,
    ) {
        val created = System.currentTimeMillis() / 1000

        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            suspend fun send(chunk: ChatCompletionChunk) {
                write("data: ${json.encodeToString(chunk)}\n\n")
                // Without an explicit flush the chunk sits in the writer's buffer and
                // "streaming" delivers everything at once when the response closes.
                flush()
            }

            try {
                // OpenAI's first chunk carries the role and no content; clients rely
                // on it to open the assistant message.
                send(
                    ChatCompletionChunk(
                        id = requestId,
                        created = created,
                        model = modelId,
                        choices = listOf(ChatChunkChoice(delta = Delta(role = "assistant"))),
                    )
                )

                var finish = "stop"
                var tps = 0.0

                bridge.stream(requestId, messages, null, params).collect { event ->
                    when (event) {
                        is GenEvent.Token -> send(
                            ChatCompletionChunk(
                                id = requestId,
                                created = created,
                                model = modelId,
                                choices = listOf(
                                    ChatChunkChoice(delta = Delta(content = event.piece))
                                ),
                            )
                        )

                        is GenEvent.Done -> {
                            tps = event.stats.genTokensPerSecond
                            finish = finishReasonFor(
                                event.stats.stopped, event.stats.cancelled,
                                event.stats.genTokens, params.maxTokens,
                            )
                        }

                        is GenEvent.Error -> {
                            Log.e(TAG, "stream $requestId failed: ${event.message}")
                            finish = "error"
                        }
                    }
                }

                send(
                    ChatCompletionChunk(
                        id = requestId,
                        created = created,
                        model = modelId,
                        choices = listOf(
                            ChatChunkChoice(delta = Delta(), finishReason = finish)
                        ),
                    )
                )
                write("data: [DONE]\n\n")
                flush()
                bridge.onFinished(tps)
            } catch (t: Throwable) {
                // The client hung up. Stop generating rather than burning the battery
                // producing tokens nobody will read.
                Log.i(TAG, "client disconnected from $requestId; cancelling", t)
                bridge.cancel(requestId)
                throw t
            }
        }
    }

    private suspend fun handleCompletion(call: ApplicationCall) {
        val request = try {
            call.receive<CompletionRequest>()
        } catch (t: Throwable) {
            call.respondError(HttpStatusCode.BadRequest,
                "could not parse the request body: ${t.message}", "invalid_request_error")
            return
        }

        val prompt = request.promptText()
        if (prompt.isBlank()) {
            call.respondError(HttpStatusCode.BadRequest,
                "prompt must not be empty", "invalid_request_error", param = "prompt")
            return
        }

        bridge.ensureLoaded(request.model)?.let { (code, message) ->
            call.respondLoadFailure(code, message)
            return
        }

        val modelId = bridge.loadedModelId().orEmpty()
        val requestId = "cmpl-${bridge.newRequestId()}"
        val params = GenParams(
            maxTokens = (request.maxTokens ?: 512).coerceIn(1, 32_768),
            temperature = request.temperature ?: 0.8f,
            topP = request.topP ?: 0.95f,
            topK = request.topK ?: 40,
            seed = request.seed ?: -1L,
            stop = request.stopStrings(),
        )

        val created = System.currentTimeMillis() / 1000

        if (request.stream) {
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                try {
                    var tps = 0.0
                    bridge.stream(requestId, emptyList(), prompt, params).collect { event ->
                        when (event) {
                            is GenEvent.Token -> {
                                val chunk = CompletionResponse(
                                    id = requestId,
                                    created = created,
                                    model = modelId,
                                    choices = listOf(TextChoice(text = event.piece)),
                                    usage = Usage(0, 0, 0),
                                )
                                write("data: ${json.encodeToString(chunk)}\n\n")
                                flush()
                            }
                            is GenEvent.Done -> tps = event.stats.genTokensPerSecond
                            is GenEvent.Error -> Log.e(TAG, "stream failed: ${event.message}")
                        }
                    }
                    write("data: [DONE]\n\n")
                    flush()
                    bridge.onFinished(tps)
                } catch (t: Throwable) {
                    bridge.cancel(requestId)
                    throw t
                }
            }
            return
        }

        val text = StringBuilder()
        var done: GenEvent.Done? = null
        var error: String? = null

        // rawPrompt: /v1/completions is the pre-chat API, so the prompt is used
        // verbatim with no chat template applied.
        bridge.stream(requestId, emptyList(), prompt, params).collect { event ->
            when (event) {
                is GenEvent.Token -> text.append(event.piece)
                is GenEvent.Done -> done = event
                is GenEvent.Error -> error = event.message
            }
        }

        error?.let {
            call.respondError(HttpStatusCode.InternalServerError, it, "engine_error")
            return
        }

        val stats = done?.stats
        stats?.let { bridge.onFinished(it.genTokensPerSecond) }

        call.respond(
            CompletionResponse(
                id = requestId,
                created = created,
                model = modelId,
                choices = listOf(
                    TextChoice(
                        text = text.toString(),
                        finishReason = finishReasonFor(stats?.stopped, stats?.cancelled,
                            stats?.genTokens, params.maxTokens),
                    )
                ),
                usage = Usage(
                    promptTokens = stats?.promptTokens ?: 0,
                    completionTokens = stats?.genTokens ?: 0,
                    totalTokens = (stats?.promptTokens ?: 0) + (stats?.genTokens ?: 0),
                ),
                timings = stats?.let { Timings(it.promptMs, it.genMs, it.genTokensPerSecond) },
            )
        )
    }

    // -------------------------------------------------------------- helpers

    private fun finishReasonFor(
        stopped: Boolean?,
        cancelled: Boolean?,
        generated: Int?,
        maxTokens: Int,
    ): String = when {
        cancelled == true -> "stop"
        generated != null && generated >= maxTokens -> "length"
        else -> "stop"
    }

    /**
     * Reject anything that did not come from loopback. The socket is already bound to
     * 127.0.0.1 so this should be unreachable -- which is the point: if a future
     * change to the bind address slips through, requests fail closed rather than
     * quietly exposing the engine to the network.
     */
    private suspend fun ApplicationCall.assertLoopback(): Boolean {
        val remote = request.local.remoteHost
        val ok = remote == LOOPBACK || remote == "::1" || remote == "localhost"
        if (!ok) {
            Log.w(TAG, "rejected a non-loopback request from $remote")
            respondError(HttpStatusCode.Forbidden, "only loopback clients are accepted",
                "permission_error")
        }
        return ok
    }

    private suspend fun ApplicationCall.authorise(): Boolean {
        if (!assertLoopback()) return false
        if (!authRequired()) return true

        val expected = tokenProvider()
        if (expected.isBlank()) {
            // Auth is on but no token exists. Fail closed: serving unauthenticated
            // because of a misconfiguration is the worst possible reading.
            respondError(HttpStatusCode.Unauthorized,
                "the server has no API token configured", "invalid_api_key")
            return false
        }

        val header = request.headers["Authorization"].orEmpty()
        val presented = header.removePrefix("Bearer ").trim()
        // Constant-time: the token is a 256-bit secret and a timing oracle on a
        // shared device is not far-fetched.
        if (presented.length != expected.length || !constantTimeEquals(presented, expected)) {
            respondError(HttpStatusCode.Unauthorized,
                "incorrect API key provided", "invalid_api_key")
            return false
        }
        return true
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private suspend fun ApplicationCall.respondError(
        status: HttpStatusCode,
        message: String,
        type: String,
        param: String? = null,
    ) {
        respond(status, ErrorBody(ErrorDetail(message = message, type = type, param = param)))
    }

    private suspend fun ApplicationCall.respondLoadFailure(code: Int, message: String) {
        val status = when (code) {
            TaraCoreErrors.MODEL_NOT_FOUND, TaraCoreErrors.NO_MODEL_LOADED ->
                HttpStatusCode.NotFound
            TaraCoreErrors.OUT_OF_MEMORY -> HttpStatusCode.ServiceUnavailable
            else -> HttpStatusCode.InternalServerError
        }
        respondError(status, message, "invalid_request_error", param = "model")
    }
}
