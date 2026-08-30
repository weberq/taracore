package dev.taracore.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.taracore.api.ChatMessageParcel
import dev.taracore.api.GenerationRequest
import dev.taracore.api.GenerationResult
import dev.taracore.api.IModelCallback
import dev.taracore.api.ITaraCore
import dev.taracore.api.ITokenCallback
import dev.taracore.api.ModelInfo
import dev.taracore.api.ServiceStatus
import dev.taracore.api.TaraCoreContract
import dev.taracore.api.TaraCoreErrors
import dev.taracore.api.Gbnf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Progress of a model load. */
sealed interface LoadProgress {
    data class Progress(val modelId: String, val fraction: Float) : LoadProgress
    data class Loaded(val modelId: String, val ramBytes: Long, val backend: String) : LoadProgress
    data class Failed(val modelId: String, val code: Int, val message: String) : LoadProgress
}

/** The service went away -- crashed, was updated, or the user force-stopped it. */
class ServiceDisconnectedException(message: String = "Tara Core disconnected") :
    Exception(message)

/** Tara Core is not installed, or is installed but disabled. */
class ServiceNotInstalledException(
    message: String = "Tara Core is not installed on this device",
) : Exception(message)

/** The engine reported a failure. [code] is one of [TaraCoreErrors]. */
class InferenceException(val code: Int, message: String) : Exception(message)

/**
 * Coroutine client for Tara Core.
 *
 * ```kotlin
 * val client = TaraCoreClient(context)
 * client.connect()
 * client.chatStream(listOf(ChatMessageParcel("user", "Hello")))
 *     .collect { print(it) }
 * client.close()
 * ```
 *
 * One instance holds one binding. It is safe to share across coroutines; it is not
 * safe to use after [close].
 */
class TaraCoreClient(context: Context) : AutoCloseable {

    private companion object {
        const val TAG = "TaraCore/Client"
        const val CONNECT_TIMEOUT_MS = 10_000L
    }

    private val appContext = context.applicationContext

    @Volatile
    private var service: ITaraCore? = null

    private val closed = AtomicBoolean(false)

    /** Continuation waiting for onServiceConnected, if a connect is in flight. */
    @Volatile
    private var pending: CancellableContinuation<ITaraCore>? = null

    /**
     * Streams in flight. On binder death every one is failed rather than left
     * hanging -- a caller waiting forever on a dead service is the worst outcome.
     */
    private val liveStreams = ConcurrentHashMap<String, (Throwable) -> Unit>()

    val isConnected: Boolean get() = service != null

    private val deathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "Tara Core died")
        service = null
        val error = ServiceDisconnectedException()
        liveStreams.values.toList().forEach { runCatching { it(error) } }
        liveStreams.clear()
        pending?.let { if (it.isActive) it.resumeWithException(error) }
        pending = null
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val stub = ITaraCore.Stub.asInterface(binder)
            service = stub
            runCatching { binder?.linkToDeath(deathRecipient, 0) }
                .onFailure { Log.w(TAG, "could not link to death; already dead?", it) }
            Log.i(TAG, "connected to Tara Core")
            pending?.let { if (it.isActive) it.resume(stub) }
            pending = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "disconnected from Tara Core")
            service = null
            val error = ServiceDisconnectedException()
            liveStreams.values.toList().forEach { runCatching { it(error) } }
            liveStreams.clear()
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.e(TAG, "Tara Core returned a null binding (permission missing?)")
            pending?.let {
                if (it.isActive) it.resumeWithException(
                    SecurityException(
                        "Tara Core refused the binding. Does your manifest declare " +
                            "${TaraCoreContract.PERMISSION}?"
                    )
                )
            }
            pending = null
        }
    }

    /**
     * Bind to the service, suspending until it is ready.
     *
     * @throws ServiceNotInstalledException when Tara Core is absent
     * @throws SecurityException when the caller lacks the permission
     */
    suspend fun connect() {
        check(!closed.get()) { "this client has been closed" }
        if (service != null) return

        if (!TaraCore.isInstalled(appContext)) throw ServiceNotInstalledException()

        withTimeout(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                pending = cont

                val intent = Intent(TaraCoreContract.ACTION_BIND).apply {
                    // Explicit package: an implicit service intent is illegal on
                    // Android 5+ and would throw rather than resolve.
                    setPackage(TaraCoreContract.SERVICE_PACKAGE)
                }

                val bound = try {
                    appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                } catch (e: SecurityException) {
                    pending = null
                    cont.resumeWithException(e)
                    return@suspendCancellableCoroutine
                }

                if (!bound) {
                    pending = null
                    // bindService returning false rather than throwing is the usual
                    // symptom of a missing <queries> entry or a missing permission.
                    cont.resumeWithException(
                        ServiceNotInstalledException(
                            "bindService returned false. Tara Core may not be installed, or " +
                                "your manifest may be missing the <queries> entry or " +
                                "${TaraCoreContract.PERMISSION}."
                        )
                    )
                }

                cont.invokeOnCancellation {
                    pending = null
                    runCatching { appContext.unbindService(connection) }
                }
            }
        }
    }

    private fun require(): ITaraCore =
        service ?: throw ServiceDisconnectedException("not connected; call connect() first")

    // ------------------------------------------------------------------ api

    suspend fun apiVersion(): Int = withContext(Dispatchers.IO) { require().apiVersion }

    suspend fun status(): ServiceStatus = withContext(Dispatchers.IO) { require().status }

    suspend fun models(): List<ModelInfo> = withContext(Dispatchers.IO) { require().listModels() }

    /**
     * Load a model, emitting progress. The flow completes on [LoadProgress.Loaded]
     * and throws on failure.
     */
    fun load(modelId: String): Flow<LoadProgress> = callbackFlow {
        val svc = require()

        val callback = object : IModelCallback.Stub() {
            override fun onProgress(id: String?, progress: Float) {
                trySend(LoadProgress.Progress(id.orEmpty(), progress))
            }

            override fun onLoaded(id: String?, ramBytes: Long, backend: String?) {
                trySend(LoadProgress.Loaded(id.orEmpty(), ramBytes, backend.orEmpty()))
                close()
            }

            override fun onError(id: String?, code: Int, message: String?) {
                trySend(LoadProgress.Failed(id.orEmpty(), code, message.orEmpty()))
                close(InferenceException(code, message ?: "model load failed"))
            }
        }

        val onDeath: (Throwable) -> Unit = { close(it) }
        liveStreams["load:$modelId"] = onDeath

        withContext(Dispatchers.IO) { svc.loadModel(modelId, callback) }

        awaitClose { liveStreams.remove("load:$modelId") }
    }

    suspend fun unload() = withContext(Dispatchers.IO) { require().unloadModel() }

    /**
     * Run a completion and return the whole answer.
     *
     * @throws InferenceException when the engine reports a failure
     */
    suspend fun chat(
        messages: List<ChatMessageParcel>,
        params: ChatParams = ChatParams(),
    ): String = withContext(Dispatchers.IO) {
        val result = require().generate(buildRequest(messages, params))
        if (result.isError) {
            throw InferenceException(result.errorCode, result.errorMessage ?: "generation failed")
        }
        result.text
    }

    /** [chat], but returning the full result including timings and token counts. */
    suspend fun chatDetailed(
        messages: List<ChatMessageParcel>,
        params: ChatParams = ChatParams(),
    ): GenerationResult = withContext(Dispatchers.IO) {
        require().generate(buildRequest(messages, params))
    }

    /**
     * Stream a completion, one piece of text per emission.
     *
     * Cold: nothing starts until collection begins. Cancelling the collector cancels
     * the generation on the service, so a user navigating away stops costing them
     * battery within one token.
     */
    fun chatStream(
        messages: List<ChatMessageParcel>,
        params: ChatParams = ChatParams(),
    ): Flow<String> = callbackFlow {
        val svc = require()
        var requestId: String? = null

        val callback = object : ITokenCallback.Stub() {
            override fun onToken(id: String?, piece: String?) {
                piece?.let { trySend(it) }
            }

            override fun onDone(id: String?, result: GenerationResult?) {
                if (result != null && result.isError) {
                    close(InferenceException(result.errorCode,
                        result.errorMessage ?: "generation failed"))
                } else {
                    close()
                }
            }

            override fun onError(id: String?, code: Int, message: String?) {
                close(InferenceException(code, message ?: "generation failed"))
            }
        }

        requestId = withContext(Dispatchers.IO) {
            svc.startStream(buildRequest(messages, params), callback)
        }

        requestId?.let { id -> liveStreams[id] = { close(it) } }

        awaitClose {
            requestId?.let { id ->
                liveStreams.remove(id)
                // Best effort: if the service is already gone there is nothing to
                // cancel, and throwing here would mask the collector's own outcome.
                runCatching { service?.cancel(id) }
            }
        }
    // Buffered so a slow collector never applies backpressure across Binder -- the
    // service's onToken is oneway and cannot wait for us anyway.
    }.buffer(capacity = 256)

    /**
     * Streaming variant that also hands back the final result (timings, token
     * counts), which [chatStream] discards.
     */
    fun chatStreamDetailed(
        messages: List<ChatMessageParcel>,
        params: ChatParams = ChatParams(),
    ): Flow<StreamEvent> = callbackFlow {
        val svc = require()
        var requestId: String? = null

        val callback = object : ITokenCallback.Stub() {
            override fun onToken(id: String?, piece: String?) {
                piece?.let { trySend(StreamEvent.Token(it)) }
            }

            override fun onDone(id: String?, result: GenerationResult?) {
                if (result != null && result.isError) {
                    close(InferenceException(result.errorCode,
                        result.errorMessage ?: "generation failed"))
                } else {
                    result?.let { trySend(StreamEvent.Done(it)) }
                    close()
                }
            }

            override fun onError(id: String?, code: Int, message: String?) {
                close(InferenceException(code, message ?: "generation failed"))
            }
        }

        requestId = withContext(Dispatchers.IO) {
            svc.startStream(buildRequest(messages, params), callback)
        }
        requestId?.let { id -> liveStreams[id] = { close(it) } }

        awaitClose {
            requestId?.let { id ->
                liveStreams.remove(id)
                runCatching { service?.cancel(id) }
            }
        }
    }.buffer(capacity = 256)

    /** Cancel a request by id. Rarely needed -- cancelling the flow does this. */
    suspend fun cancel(requestId: String) = withContext(Dispatchers.IO) {
        runCatching { service?.cancel(requestId) }
        Unit
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        liveStreams.clear()
        service?.asBinder()?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
        service = null
        runCatching { appContext.unbindService(connection) }
            .onFailure { Log.w(TAG, "unbind failed; already unbound?", it) }
    }

    // -------------------------------------------------------------- helpers

    /**
     * Build the parcel, moving oversized prompts to a pipe.
     *
     * The 1 MB Binder buffer is shared across the whole calling process, so a large
     * inline prompt can fail because of traffic that has nothing to do with us. Above
     * 512 KB we render the messages ourselves and push the bytes through a pipe --
     * see docs/API.md.
     */
    private fun buildRequest(
        messages: List<ChatMessageParcel>,
        params: ChatParams,
    ): GenerationRequest {
        val base = GenerationRequest(
            messages = messages,
            modelId = params.modelId,
            maxTokens = params.maxTokens,
            temperature = params.temperature,
            topP = params.topP,
            topK = params.topK,
            repeatPenalty = params.repeatPenalty,
            stop = params.stop,
            seed = params.seed,
            allowAutoLoad = params.allowAutoLoad,
            grammar = params.grammar,
        )

        if (base.approximateInlineBytes() <= TaraCoreContract.INLINE_PROMPT_LIMIT_BYTES) {
            return base
        }

        Log.i(TAG, "prompt exceeds ${TaraCoreContract.INLINE_PROMPT_LIMIT_BYTES} bytes; " +
            "sending it through a pipe")

        val rendered = messages.joinToString("\n") { "${it.role}: ${it.content}" }
        val bytes = rendered.toByteArray(Charsets.UTF_8)
        val pipe = ParcelFileDescriptor.createPipe()

        // A dedicated thread, not a coroutine: the write blocks on the pipe's 64 KB
        // buffer until the service drains it, and parking a Dispatchers.IO thread for
        // an unbounded time to feed one request is worse than one short-lived thread.
        Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
            }.onFailure { Log.w(TAG, "failed writing the large prompt", it) }
        }, "taracore-large-prompt").apply { isDaemon = true }.start()

        return base.copy(messages = emptyList(), largePrompt = pipe[0])
    }
}

/** Sampling and routing knobs for one request. */
data class ChatParams(
    /** null uses whatever is loaded, and never triggers a model swap. */
    val modelId: String? = null,
    val maxTokens: Int = 512,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val stop: List<String> = emptyList(),
    /** Negative means "choose randomly". */
    val seed: Long = -1L,
    /**
     * Let the service swap models when [modelId] is not the resident one.
     *
     * Set false when a slow answer is worse than no answer: a swap reads gigabytes
     * from storage and takes tens of seconds, and the call simply blocks for all of
     * it. With this false and the model not resident, the request fails immediately
     * with [TaraCoreErrors.MODEL_NOT_LOADED].
     */
    val allowAutoLoad: Boolean = true,
    /**
     * Constrains what the model may emit. Null leaves it unconstrained.
     *
     * Build one with [Constraint] rather than by hand. Requires a service reporting
     * API version 2 or higher; an older one ignores it silently, so check
     * [TaraCoreClient.apiVersion] if the constraint is load-bearing.
     */
    val grammar: String? = null,
)

/**
 * Ready-made output constraints.
 *
 * These exist because "answer with just the digit" is a request a small model cannot
 * reliably honour, however firmly it is phrased. A constraint makes the other tokens
 * unsamplable, which turns an unreliable 0.5B classifier into a reliable one.
 *
 * ```kotlin
 * val category = client.chat(
 *     messages = listOf(ChatMessageParcel("user", "Merchant: Swiggy Instamart\nCategory:")),
 *     params = ChatParams(grammar = Constraint.oneOf("1", "2", "3", "4", "5", "6")),
 * )
 * // category is exactly one of those six strings. No parsing, no retry.
 * ```
 */
object Constraint {

    /** The answer must be exactly one of [options]. */
    @JvmStatic
    fun oneOf(vararg options: String): String = Gbnf.choice(options.toList())

    /** The answer must be exactly one of [options]. */
    @JvmStatic
    fun oneOf(options: List<String>): String = Gbnf.choice(options)

    /** The answer must be a well-formed JSON object. */
    @JvmStatic
    fun json(): String = Gbnf.jsonObject()

    /** Raw GBNF, for anything the helpers above cannot express. */
    @JvmStatic
    fun gbnf(grammar: String): String = grammar
}

/** Emission of [TaraCoreClient.chatStreamDetailed]. */
sealed interface StreamEvent {
    data class Token(val piece: String) : StreamEvent
    data class Done(val result: GenerationResult) : StreamEvent
}
