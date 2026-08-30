package dev.taracore.engine

import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The only thing in the process allowed to touch [LlamaEngine].
 *
 * Two invariants:
 *  1. Every native call happens on [engineDispatcher], a single thread. llama.cpp
 *     keeps thread-local state in its context and a context must not be entered from
 *     two threads even sequentially without a barrier; one thread makes that free.
 *  2. Only one generation runs at a time, enforced by [genMutex]. Callers that want
 *     fairness should queue ahead of this (see the service's RequestQueue) rather
 *     than piling up here.
 */
class EngineController(
    private val onStateChange: (EngineState) -> Unit = {},
) : AutoCloseable {

    private companion object {
        const val TAG = "TaraCore/Engine"
    }

    /**
     * Single thread, named so it is identifiable in an ANR trace -- a stuck
     * generation is the most likely cause of one in this app.
     */
    private val engineExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "taracore-engine").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()

    private val genMutex = Mutex()

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Handle to the native Engine. 0 until [ensureHandle] runs. */
    private var handle: Long = 0L

    private var loadedSpec: ModelSpec? = null
    private var loadedResult: NativeLoadResult? = null

    /** Set while a generation is in flight so [cancel] knows which id is running. */
    private val activeRequestId = AtomicReference<String?>(null)

    val isNativeAvailable: Boolean get() = LlamaEngine.available

    val loadedModelId: String? get() = loadedSpec?.modelId

    val backend: String get() = loadedResult?.backendName ?: "none"

    val llamaVersion: String
        get() = if (LlamaEngine.available) runCatching { LlamaEngine.nativeVersion() }
            .getOrDefault("unknown") else "unavailable"

    private fun publish(s: EngineState) {
        _state.value = s
        onStateChange(s)
    }

    private fun ensureHandle() {
        check(LlamaEngine.available) { "libtaracore_jni.so is not loaded on this device" }
        if (handle == 0L) handle = LlamaEngine.nativeCreate()
    }

    // ------------------------------------------------------------------ loading

    /**
     * Bring [spec] up, replacing whatever is loaded. Suspends for the whole read,
     * which for a 4 GB model on slow storage can be tens of seconds.
     */
    suspend fun load(spec: ModelSpec): NativeLoadResult = withContext(engineDispatcher) {
        genMutex.withLock {
            publish(EngineState.Loading(spec.modelId, 0f))

            val file = File(spec.path)
            if (!file.isFile) {
                val msg = "model file does not exist: ${spec.path}"
                publish(EngineState.Failed(spec.modelId, msg))
                return@withLock NativeLoadResult(false, msg, 0, 0, 0, "", "")
            }

            ensureHandle()
            Log.i(TAG, "loading ${spec.modelId} (${file.length()} bytes)")

            val result = LlamaEngine.nativeLoad(
                handle = handle,
                path = spec.path,
                nCtx = spec.nCtx,
                nThreads = spec.nThreads,
                nGpuLayers = spec.nGpuLayers,
                nBatch = spec.nBatch,
                useMmap = spec.useMmap,
                useMlock = spec.useMlock,
            )

            if (result.ok) {
                loadedSpec = spec
                loadedResult = result
                publish(
                    EngineState.Ready(
                        modelId = spec.modelId,
                        backend = result.backendName,
                        ramBytes = result.modelSizeBytes,
                        nCtx = result.nCtx,
                    )
                )
            } else {
                loadedSpec = null
                loadedResult = null
                publish(EngineState.Failed(spec.modelId, result.error))
            }
            result
        }
    }

    /** Free the model. Safe when nothing is loaded and safe to call repeatedly. */
    suspend fun unload() = withContext(engineDispatcher) {
        genMutex.withLock {
            if (handle != 0L) LlamaEngine.nativeUnload(handle)
            loadedSpec = null
            loadedResult = null
            publish(EngineState.Unloaded)
        }
    }

    suspend fun isLoaded(): Boolean = withContext(engineDispatcher) {
        handle != 0L && LlamaEngine.nativeIsLoaded(handle)
    }

    // -------------------------------------------------------------- prompting

    /** Render [messages] with the loaded model's chat template. */
    suspend fun formatChat(messages: List<ChatMessage>): String = withContext(engineDispatcher) {
        ensureHandle()
        LlamaEngine.nativeFormatChat(
            handle,
            messages.map { it.role }.toTypedArray(),
            messages.map { it.content }.toTypedArray(),
        )
    }

    // ------------------------------------------------------------- generating

    /**
     * Stream a completion. Cold: nothing runs until collection starts, and
     * cancelling the collector cancels the native generation within one token.
     */
    fun stream(req: GenRequest): Flow<GenEvent> = callbackFlow {
        // NOTE: this builder deliberately runs in the *collector's* context, not on
        // engineDispatcher. Only the generation itself is dispatched to the engine
        // thread below. If the builder ran there too, `awaitClose` would be queued
        // behind the blocking native call and a cancel could not be delivered until
        // generation had already finished -- the exact opposite of what we want.
        val job = launch(engineDispatcher) {
            genMutex.withLock {
                val modelId = loadedSpec?.modelId
                if (handle == 0L || modelId == null) {
                    trySend(GenEvent.Error("no model is loaded"))
                    close()
                    return@withLock
                }

                activeRequestId.set(req.requestId)
                publish(EngineState.Generating(modelId, req.requestId))

                val prompt = req.rawPrompt ?: LlamaEngine.nativeFormatChat(
                    handle,
                    req.messages.map { it.role }.toTypedArray(),
                    req.messages.map { it.content }.toTypedArray(),
                )

                val text = StringBuilder()
                val listener = TokenListener { piece ->
                    text.append(piece)
                    // trySend can only fail once the flow is closed, which is exactly
                    // when we want the native loop to stop.
                    trySend(GenEvent.Token(piece)).isSuccess
                }

                val stats = try {
                    LlamaEngine.nativeGenerate(
                        handle = handle,
                        prompt = prompt,
                        maxTokens = req.params.maxTokens,
                        temperature = req.params.temperature,
                        topP = req.params.topP,
                        topK = req.params.topK,
                        repeatPenalty = req.params.repeatPenalty,
                        repeatLastN = req.params.repeatLastN,
                        seed = normaliseSeed(req.params.seed),
                        stop = req.params.stop.toTypedArray(),
                        grammar = req.params.grammar,
                        listener = listener,
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "native generate threw", t)
                    trySend(GenEvent.Error(t.message ?: "native generation failed", t))
                    null
                } finally {
                    activeRequestId.compareAndSet(req.requestId, null)
                    loadedResult?.let { r ->
                        publish(
                            EngineState.Ready(modelId, r.backendName, r.modelSizeBytes, r.nCtx)
                        )
                    }
                }

                if (stats != null) {
                    if (stats.ok) {
                        trySend(GenEvent.Done(stats, text.toString()))
                    } else {
                        trySend(GenEvent.Error(stats.error))
                    }
                }
                close()
            }
        }

        awaitClose {
            // Collector went away (cancelled, or took its last value). Tell the
            // native loop to stop; it checks the flag between tokens. nativeCancel
            // only flips an atomic, so it is safe -- and necessary -- to call it from
            // a thread other than the one blocked inside nativeGenerate.
            if (activeRequestId.get() == req.requestId && handle != 0L) {
                LlamaEngine.nativeCancel(handle)
            }
            job.cancel()
        }
    }.buffer(capacity = 256)

    /** Convenience wrapper: run [req] to completion and return the text. */
    suspend fun generate(req: GenRequest): String {
        val out = StringBuilder()
        var error: String? = null
        stream(req).collect { ev ->
            when (ev) {
                is GenEvent.Token -> out.append(ev.piece)
                is GenEvent.Done -> Unit
                is GenEvent.Error -> error = ev.message
            }
        }
        error?.let { throw EngineException(it) }
        return out.toString()
    }

    /**
     * Ask the running generation to stop. A no-op when [requestId] is not the one in
     * flight, so a late cancel for a finished request cannot kill the next one.
     */
    fun cancel(requestId: String) {
        if (activeRequestId.get() == requestId && handle != 0L) {
            Log.i(TAG, "cancelling $requestId")
            LlamaEngine.nativeCancel(handle)
        }
    }

    /** Cancel whatever is running, whatever its id. Used on shutdown and trim. */
    fun cancelAll() {
        if (handle != 0L) LlamaEngine.nativeCancel(handle)
    }

    override fun close() {
        cancelAll()
        // The destroy must run on the engine thread like every other native call.
        engineExecutor.execute {
            if (handle != 0L) {
                LlamaEngine.nativeDestroy(handle)
                handle = 0L
            }
        }
        engineExecutor.shutdown()
    }

    /** `-1` means "pick one"; llama treats UINT32_MAX as its own random sentinel. */
    private fun normaliseSeed(seed: Long): Long =
        if (seed < 0) (System.nanoTime() and 0xFFFFFFFFL) else seed
}

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)
