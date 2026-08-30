package dev.taracore.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
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
import dev.taracore.engine.ChatMessage
import dev.taracore.engine.EngineController
import dev.taracore.engine.EngineState
import dev.taracore.engine.GenEvent
import dev.taracore.engine.GenParams
import dev.taracore.engine.GenRequest
import dev.taracore.engine.ModelSpec
import dev.taracore.service.http.HttpServer
import dev.taracore.service.model.ModelRepository
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The device-wide inference host.
 *
 * Lifecycle in one paragraph: it is both started (so it survives the UI going away)
 * and bindable (so clients can talk to it and so it stays alive while they do). It
 * runs foreground with a `specialUse` type while a model is resident, drops the model
 * after an idle period, and returns to a bound-but-background state when nothing is
 * loaded and the HTTP server is off. `START_STICKY` brings it back if the system kills
 * it under pressure -- with no model loaded, which is the correct state to come back in.
 */
class TaraCoreService : Service() {

    companion object {
        private const val TAG = "TaraCore/Service"

        /** Extra on the start intent: load this model as soon as the service is up. */
        const val EXTRA_PRELOAD_MODEL = "dev.taracore.extra.PRELOAD_MODEL"

        fun startIntent(context: Context, preloadModelId: String? = null) =
            Intent(context, TaraCoreService::class.java).apply {
                preloadModelId?.let { putExtra(EXTRA_PRELOAD_MODEL, it) }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var settings: TaraSettings
    private lateinit var repository: ModelRepository
    private lateinit var notifications: ServiceNotifications
    private lateinit var clients: ClientRegistry
    private lateinit var engine: EngineController
    private lateinit var queue: RequestQueue
    private lateinit var idle: IdleUnloader

    private var httpServer: HttpServer? = null

    /** Cached so the notification and getStatus() can report it without a round trip. */
    @Volatile
    private var lastTokensPerSecond: Double = 0.0

    @Volatile
    private var currentSettings: SettingsSnapshot = SettingsSnapshot()

    /** Set when onTrimMemory forced an unload, cleared on the next successful load. */
    private val unloadedUnderPressure = AtomicBoolean(false)

    private val isForeground = AtomicBoolean(false)

    /** requestId -> the callback to notify, for streams currently in flight. */
    private val streamCallbacks = ConcurrentHashMap<String, ITokenCallback>()

    /** requestId -> the uid that asked, so token counts land on the right client. */
    private val requestOwners = ConcurrentHashMap<String, Int>()

    // ------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        settings = TaraSettings(this)
        repository = ModelRepository(this)
        notifications = ServiceNotifications(this)
        clients = ClientRegistry(this)

        notifications.ensureChannel()

        engine = EngineController(onStateChange = ::onEngineStateChanged)

        idle = IdleUnloader(scope) {
            Log.i(TAG, "idle timeout reached")
            engine.unload()
            maybeLeaveForeground()
        }

        queue = RequestQueue(scope)
        queue.start()

        scope.launch {
            // Before anything reads settings: drop the orphaned Preferences file the
            // migration left behind, which still holds a superseded HTTP token.
            settings.purgeLegacyPreferences()
            repository.sync()
            settings.ensureToken()
        }

        // Settings are observed rather than read once: changing the idle timeout or
        // toggling the HTTP server has to take effect without a restart.
        scope.launch {
            settings.flow.collect { snapshot ->
                val previous = currentSettings
                currentSettings = snapshot
                idle.setTimeout(snapshot.idleTimeoutMs)

                if (snapshot.httpEnabled != previous.httpEnabled ||
                    snapshot.httpPort != previous.httpPort
                ) {
                    applyHttpSetting(snapshot)
                }
                updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ServiceNotifications.ACTION_STOP -> {
                Log.i(TAG, "stop requested from the notification")
                stopEverything()
                return START_NOT_STICKY
            }
            ServiceNotifications.ACTION_UNLOAD -> {
                scope.launch { engine.unload(); maybeLeaveForeground() }
                return START_STICKY
            }
        }

        enterForeground()

        intent?.getStringExtra(EXTRA_PRELOAD_MODEL)?.let { modelId ->
            scope.launch { ensureModelLoaded(modelId, null) }
        }

        // STICKY: if the system kills us for memory, come back. We come back with no
        // model loaded, which is exactly right -- reloading several GB unasked, right
        // after the system told us memory was tight, would be perverse.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind ${intent?.action}")
        // Return true so onRebind is called: a client reconnecting should not have to
        // pay for a reload if the model is still resident.
        return true
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        queue.stop()
        idle.cancel()
        runBlocking { runCatching { httpServer?.stop() } }
        httpServer = null
        engine.close()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The memory contract with the rest of the system. A resident model is the
     * largest allocation on the device, so when the system says it is short we give
     * it back immediately rather than waiting for the idle timer.
     */
    // TRIM_MEMORY_RUNNING_* were deprecated in API 35, but they are still delivered
    // and they are still the only signal that says "the system is short of memory
    // right now". Until a replacement exists, holding 2 GB of model through a
    // critical trim would be the worse choice.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.i(TAG, "onTrimMemory level=$level")

        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            // RUNNING_CRITICAL and COMPLETE both mean "give memory back now". The
            // alternative is being killed, which loses the queue as well as the model.
            Log.w(TAG, "memory pressure (level $level): unloading model immediately")
            unloadedUnderPressure.set(true)
            engine.cancelAll()
            scope.launch {
                engine.unload()
                updateNotification()
            }
        }
    }

    // ------------------------------------------------------------ foreground

    private fun enterForeground() {
        if (isForeground.getAndSet(true)) return
        val notification = notifications.build(buildStatus(), lastTokensPerSecond)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ServiceNotifications.NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(ServiceNotifications.NOTIFICATION_ID, notification)
        }
    }

    /**
     * Drop out of the foreground when there is nothing to justify it: no model
     * resident and no HTTP server listening. The service stays alive for bound
     * clients -- it just stops claiming foreground priority and stops showing a
     * notification for work it is not doing.
     */
    private fun maybeLeaveForeground() {
        val nothingResident = engine.loadedModelId == null
        val noServer = httpServer?.isRunning != true
        if (nothingResident && noServer && isForeground.compareAndSet(true, false)) {
            Log.i(TAG, "nothing loaded and no server; leaving the foreground")
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            updateNotification()
        }
    }

    private fun stopEverything() {
        scope.launch {
            engine.cancelAll()
            engine.unload()
            runCatching { httpServer?.stop() }
            httpServer = null
            isForeground.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun onEngineStateChanged(state: EngineState) {
        if (state is EngineState.Ready) unloadedUnderPressure.set(false)
        updateNotification()
    }

    private fun updateNotification() {
        if (!isForeground.get()) return
        runCatching { notifications.update(buildStatus(), lastTokensPerSecond) }
            .onFailure { Log.w(TAG, "could not update the notification", it) }
    }

    private suspend fun applyHttpSetting(snapshot: SettingsSnapshot) {
        if (snapshot.httpEnabled) {
            if (httpServer?.isRunning == true) httpServer?.stop()
            val server = HttpServer(
                port = snapshot.httpPort,
                tokenProvider = { currentSettings.httpToken },
                authRequired = { currentSettings.httpAuthRequired },
                bridge = httpBridge,
            )
            httpServer = server
            runCatching { server.start() }
                .onSuccess {
                    Log.i(TAG, "HTTP server listening on 127.0.0.1:${snapshot.httpPort}")
                    enterForeground()
                }
                .onFailure {
                    Log.e(TAG, "could not start the HTTP server on port ${snapshot.httpPort}", it)
                    httpServer = null
                }
        } else {
            httpServer?.let {
                Log.i(TAG, "stopping the HTTP server")
                runCatching { it.stop() }
            }
            httpServer = null
            maybeLeaveForeground()
        }
        updateNotification()
    }

    // ---------------------------------------------------------------- status

    private fun buildStatus(): ServiceStatus {
        val engineState = engine.state.value
        val state = when (engineState) {
            is EngineState.Unloaded -> ServiceStatus.State.IDLE
            is EngineState.Loading -> ServiceStatus.State.LOADING
            is EngineState.Ready -> ServiceStatus.State.READY
            is EngineState.Generating -> ServiceStatus.State.GENERATING
            is EngineState.Failed -> ServiceStatus.State.ERROR
        }
        val ready = engineState as? EngineState.Ready

        return ServiceStatus(
            apiVersion = TaraCoreContract.API_VERSION,
            state = state,
            loadedModelId = engine.loadedModelId,
            backend = engine.backend,
            modelRamBytes = ready?.ramBytes ?: 0,
            contextSize = ready?.nCtx ?: 0,
            queueDepth = queue.depth.value,
            lastTokensPerSecond = lastTokensPerSecond,
            idleUnloadInMs = idle.remainingMs,
            httpServerRunning = httpServer?.isRunning == true,
            httpPort = currentSettings.httpPort,
            unloadedUnderMemoryPressure = unloadedUnderPressure.get(),
            engineVersion = engine.llamaVersion,
        )
    }

    // ------------------------------------------------------------- inference

    private fun specFor(modelId: String, path: String): ModelSpec {
        val s = currentSettings
        return ModelSpec(
            modelId = modelId,
            path = path,
            nCtx = s.contextSize,
            nThreads = if (s.threads > 0) s.threads else ModelSpec.defaultThreads(),
            nGpuLayers = s.gpuLayers,
            useMmap = s.useMmap,
            useMlock = s.useMlock,
        )
    }

    /**
     * Make [modelId] (or the active model, or the only downloaded one) resident.
     * @return null on success, or an (errorCode, message) pair.
     */
    private suspend fun ensureModelLoaded(
        modelId: String?,
        progress: IModelCallback?,
        allowAutoLoad: Boolean = true,
    ): Pair<Int, String>? {
        // A named model that is already resident is the fast path, and it must be
        // checked before the auto-load decision: "use qwen, do not swap" is a
        // perfectly reasonable request when qwen is the model already loaded.
        if (modelId != null && engine.loadedModelId == modelId && engine.isLoaded()) return null

        // The caller named a model, it is not the resident one, and it said not to
        // swap. Answering from whatever happens to be loaded would silently return
        // the wrong model's opinion, so refuse instead -- immediately, because the
        // whole point of the flag is to avoid a caller blocking for a minute or two.
        if (!allowAutoLoad && modelId != null) {
            // Existence is checked first so that a name that is simply wrong always
            // reports MODEL_NOT_FOUND. Reporting "not loaded" for a typo would send
            // the client off to retry with auto-load enabled, wait for nothing, and
            // only then discover the real problem.
            val known = repository.byId(modelId)
            if (known?.path == null) {
                return TaraCoreErrors.MODEL_NOT_FOUND to
                    if (known == null) "unknown model: $modelId"
                    else "model $modelId is not downloaded"
            }
            val resident = engine.loadedModelId
            return TaraCoreErrors.MODEL_NOT_LOADED to
                "model $modelId is not loaded and allow_auto_load is false" +
                (resident?.let { " (currently loaded: $it)" } ?: " (no model is loaded)")
        }

        val wanted = modelId
            ?: engine.loadedModelId
            ?: currentSettings.activeModelId
            ?: repository.downloaded().firstOrNull()?.id
            ?: return TaraCoreErrors.NO_MODEL_LOADED to
                "no model is loaded and none is downloaded"

        if (engine.loadedModelId == wanted && engine.isLoaded()) return null

        // Nothing is resident and the caller declined a load. There is no model to
        // answer from at all, so this is a different failure from the one above.
        if (!allowAutoLoad) {
            return TaraCoreErrors.MODEL_NOT_LOADED to
                "no model is loaded and allow_auto_load is false"
        }

        val entity = repository.byId(wanted)
            ?: return TaraCoreErrors.MODEL_NOT_FOUND to "unknown model: $wanted"
        val path = entity.path
            ?: return TaraCoreErrors.MODEL_NOT_FOUND to "model $wanted is not downloaded"

        progress?.let { runCatching { it.onProgress(wanted, 0f) } }
        enterForeground()

        val result = engine.load(specFor(wanted, path))
        return if (result.ok) {
            unloadedUnderPressure.set(false)
            progress?.let {
                runCatching {
                    it.onProgress(wanted, 1f)
                    it.onLoaded(wanted, result.modelSizeBytes, result.backendName)
                }
            }
            settings.setActiveModel(wanted)
            null
        } else {
            progress?.let {
                runCatching { it.onError(wanted, TaraCoreErrors.MODEL_LOAD_FAILED, result.error) }
            }
            TaraCoreErrors.MODEL_LOAD_FAILED to result.error
        }
    }

    /** Read a prompt sent out of band through a pipe. See docs/API.md. */
    private suspend fun readLargePrompt(pfd: ParcelFileDescriptor): String =
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                FileInputStream(stream.fd).bufferedReader().readText()
            }
        }

    private suspend fun toGenRequest(req: GenerationRequest, requestId: String): GenRequest {
        val rawPrompt = req.largePrompt?.let { readLargePrompt(it) }
        return GenRequest(
            requestId = requestId,
            messages = req.messages.map { ChatMessage(it.role, it.content) },
            params = GenParams(
                maxTokens = req.maxTokens.coerceIn(1, 32_768),
                temperature = req.temperature,
                topP = req.topP,
                topK = req.topK,
                repeatPenalty = req.repeatPenalty,
                seed = req.seed,
                stop = req.stop,
                grammar = req.grammar,
            ),
            rawPrompt = rawPrompt,
        )
    }

    /** Runs on the queue's single worker coroutine. */
    private suspend fun runQueued(queued: RequestQueue.QueuedRequest) {
        val req = queued.request
        val callback = streamCallbacks[req.requestId]
        idle.touch()

        val text = StringBuilder()
        var finished = false

        engine.stream(req).collect { event ->
            when (event) {
                is GenEvent.Token -> {
                    text.append(event.piece)
                    // oneway: a dead or slow client cannot stall the sampling loop.
                    // A DeadObjectException here means the client vanished mid-stream.
                    runCatching { callback?.onToken(req.requestId, event.piece) }
                        .onFailure {
                            Log.w(TAG, "client for ${req.requestId} is gone; cancelling")
                            engine.cancel(req.requestId)
                        }
                }

                is GenEvent.Done -> {
                    finished = true
                    lastTokensPerSecond = event.stats.genTokensPerSecond
                    requestOwners[req.requestId]?.let {
                        clients.recordTokens(it, event.stats.genTokens)
                    }
                    val result = GenerationResult(
                        requestId = req.requestId,
                        text = event.text,
                        modelId = engine.loadedModelId.orEmpty(),
                        promptTokens = event.stats.promptTokens,
                        generatedTokens = event.stats.genTokens,
                        promptMs = event.stats.promptMs,
                        generationMs = event.stats.genMs,
                        cancelled = event.stats.cancelled,
                        stopped = event.stats.stopped,
                    )
                    runCatching { callback?.onDone(req.requestId, result) }
                    updateNotification()
                }

                is GenEvent.Error -> {
                    finished = true
                    runCatching {
                        callback?.onError(req.requestId, TaraCoreErrors.ENGINE_FAILURE, event.message)
                    }
                }
            }
        }

        if (!finished) {
            // The flow completed without a terminal event -- the collector was
            // cancelled. Tell the client rather than leaving it waiting forever.
            runCatching {
                callback?.onDone(
                    req.requestId,
                    GenerationResult(
                        requestId = req.requestId,
                        text = text.toString(),
                        modelId = engine.loadedModelId.orEmpty(),
                        promptTokens = 0,
                        generatedTokens = 0,
                        promptMs = 0,
                        generationMs = 0,
                        cancelled = true,
                    ),
                )
            }
        }

        streamCallbacks.remove(req.requestId)
        requestOwners.remove(req.requestId)
        idle.touch()
    }

    // ---------------------------------------------------------------- binder

    /**
     * Every method re-checks the permission. The manifest's `android:permission`
     * already gates `bindService`, but defence in depth is cheap here and the
     * manifest attribute alone would not survive someone passing a live binder to a
     * third app -- which is a legitimate thing for an app to do, and not something we
     * want to silently honour.
     */
    private fun enforcePermission() {
        if (checkCallingOrSelfPermission(TaraCoreContract.PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException(
                "caller ${Binder.getCallingUid()} does not hold ${TaraCoreContract.PERMISSION}"
            )
        }
    }

    private val binder = object : ITaraCore.Stub() {

        override fun getApiVersion(): Int {
            enforcePermission()
            return TaraCoreContract.API_VERSION
        }

        override fun listModels(): List<ModelInfo> {
            enforcePermission()
            clients.recordRequest()
            return runBlocking { repository.listModelInfo(engine.loadedModelId) }
        }

        override fun loadModel(modelId: String?, cb: IModelCallback?) {
            enforcePermission()
            clients.recordRequest()
            val id = modelId ?: run {
                cb?.onError("", TaraCoreErrors.INVALID_REQUEST, "modelId must not be null")
                return
            }
            enterForeground()
            scope.launch {
                idle.touch()
                ensureModelLoaded(id, cb)
            }
        }

        override fun unloadModel() {
            enforcePermission()
            clients.recordRequest()
            scope.launch {
                engine.unload()
                maybeLeaveForeground()
            }
        }

        override fun getStatus(): ServiceStatus {
            enforcePermission()
            return buildStatus()
        }

        override fun generate(req: GenerationRequest?): GenerationResult {
            enforcePermission()
            val uid = Binder.getCallingUid()
            clients.recordRequest(uid)

            val request = req ?: return GenerationResult.error(
                "", TaraCoreErrors.INVALID_REQUEST, "request must not be null"
            )
            val requestId = UUID.randomUUID().toString()

            // Synchronous by contract: the caller's Binder thread is parked here for
            // the whole completion. Documented in docs/API.md as main-thread-hostile.
            return runBlocking {
                idle.touch()
                enterForeground()

                ensureModelLoaded(
                    request.modelId,
                    null,
                    allowAutoLoad = request.allowAutoLoad && currentSettings.autoLoadOnRequest,
                )?.let { (code, message) ->
                    return@runBlocking GenerationResult.error(requestId, code, message)
                }

                val genRequest = toGenRequest(request, requestId)
                requestOwners[requestId] = uid

                // Through the queue like every other path, so a synchronous caller
                // waits its turn rather than overtaking clients that asked first.
                // This is why docs/API.md calls generate() main-thread-hostile: the
                // wait now includes the queue as well as the generation.
                val outcome = CompletableDeferred<GenerationResult>()

                val accepted = queue.submit(
                    RequestQueue.QueuedRequest(
                        request = genRequest,
                        callerUid = uid,
                        callerPackage = clients.packageNameFor(uid),
                        run = {
                            var result: GenerationResult? = null
                            engine.stream(genRequest).collect { event ->
                                when (event) {
                                    is GenEvent.Token -> Unit
                                    is GenEvent.Done -> {
                                        lastTokensPerSecond = event.stats.genTokensPerSecond
                                        clients.recordTokens(uid, event.stats.genTokens)
                                        result = GenerationResult(
                                            requestId = requestId,
                                            text = event.text,
                                            modelId = engine.loadedModelId.orEmpty(),
                                            promptTokens = event.stats.promptTokens,
                                            generatedTokens = event.stats.genTokens,
                                            promptMs = event.stats.promptMs,
                                            generationMs = event.stats.genMs,
                                            cancelled = event.stats.cancelled,
                                            stopped = event.stats.stopped,
                                        )
                                    }
                                    is GenEvent.Error -> result = GenerationResult.error(
                                        requestId, TaraCoreErrors.ENGINE_FAILURE, event.message
                                    )
                                }
                            }
                            outcome.complete(
                                result ?: GenerationResult.error(
                                    requestId,
                                    TaraCoreErrors.ENGINE_FAILURE,
                                    "generation produced no result",
                                )
                            )
                        },
                        onRejected = { code, message ->
                            outcome.complete(GenerationResult.error(requestId, code, message))
                        },
                    )
                )

                // submit() already reported the rejection through onRejected; this
                // only guards against the deferred never being completed at all.
                if (!accepted && !outcome.isCompleted) {
                    outcome.complete(
                        GenerationResult.error(
                            requestId, TaraCoreErrors.QUEUE_FULL, "inference queue is full"
                        )
                    )
                }

                val result = outcome.await()

                requestOwners.remove(requestId)
                idle.touch()
                updateNotification()
                result
            }
        }

        override fun startStream(req: GenerationRequest?, cb: ITokenCallback?): String {
            enforcePermission()
            val uid = Binder.getCallingUid()
            clients.recordRequest(uid)

            val requestId = UUID.randomUUID().toString()
            if (req == null || cb == null) {
                cb?.onError(requestId, TaraCoreErrors.INVALID_REQUEST,
                    "request and callback must not be null")
                return requestId
            }

            streamCallbacks[requestId] = cb
            requestOwners[requestId] = uid
            enterForeground()

            scope.launch {
                idle.touch()

                ensureModelLoaded(
                    req.modelId,
                    null,
                    allowAutoLoad = req.allowAutoLoad && currentSettings.autoLoadOnRequest,
                )?.let { (code, message) ->
                    streamCallbacks.remove(requestId)
                    requestOwners.remove(requestId)
                    runCatching { cb.onError(requestId, code, message) }
                    return@launch
                }

                val genRequest = try {
                    toGenRequest(req, requestId)
                } catch (t: Throwable) {
                    streamCallbacks.remove(requestId)
                    runCatching {
                        cb.onError(requestId, TaraCoreErrors.INVALID_REQUEST,
                            t.message ?: "could not read the request")
                    }
                    return@launch
                }

                queue.submit(
                    RequestQueue.QueuedRequest(
                        request = genRequest,
                        callerUid = uid,
                        callerPackage = clients.packageNameFor(uid),
                        run = { queued -> runQueued(queued) },
                        onRejected = { code, message ->
                            streamCallbacks.remove(requestId)
                            requestOwners.remove(requestId)
                            runCatching { cb.onError(requestId, code, message) }
                        },
                    )
                )
            }

            return requestId
        }

        override fun cancel(requestId: String?) {
            enforcePermission()
            val id = requestId ?: return
            Log.i(TAG, "cancel requested for $id")
            // Try the queue first: a request that has not started needs removing, not
            // interrupting, and cancelling the engine would hit whatever is running.
            if (!queue.cancelQueued(id)) engine.cancel(id)
        }
    }

    // ------------------------------------------------- bridge for the HTTP layer

    /**
     * What the Ktor layer is allowed to do. Deliberately narrow: the HTTP surface
     * gets the same queue and the same engine as AIDL clients, not a private path.
     */
    private val httpBridge = object : HttpServer.Bridge {

        override suspend fun listModels(): List<ModelInfo> =
            repository.listModelInfo(engine.loadedModelId)

        override fun status(): ServiceStatus = buildStatus()

        override suspend fun ensureLoaded(
            modelId: String?,
            allowAutoLoad: Boolean?,
        ): Pair<Int, String>? {
            enterForeground()
            idle.touch()
            // Per-request wins; null defers to the global setting, so nothing changes
            // for callers that do not send the field.
            val effective = allowAutoLoad ?: currentSettings.autoLoadOnRequest
            return ensureModelLoaded(modelId, null, allowAutoLoad = effective)
        }

        override fun newRequestId(): String = UUID.randomUUID().toString()

        /**
         * HTTP requests go through the same FIFO as AIDL requests. The engine's own
         * mutex would serialise them anyway, but queueing means an HTTP caller shows
         * up in `queueDepth`, is subject to the same capacity limit, and cannot jump
         * ahead of a bound client that asked first.
         */
        override fun stream(
            requestId: String,
            messages: List<ChatMessageParcel>,
            rawPrompt: String?,
            params: GenParams,
        ): Flow<GenEvent> = callbackFlow {
            val genRequest = GenRequest(
                requestId = requestId,
                messages = messages.map { ChatMessage(it.role, it.content) },
                params = params,
                rawPrompt = rawPrompt,
            )

            val accepted = queue.submit(
                RequestQueue.QueuedRequest(
                    request = genRequest,
                    // The HTTP server is in-process, so there is no caller uid to
                    // attribute this to beyond our own.
                    callerUid = android.os.Process.myUid(),
                    callerPackage = "http://127.0.0.1",
                    run = {
                        engine.stream(genRequest).collect { event ->
                            trySend(event)
                            if (event is GenEvent.Done) lastTokensPerSecond =
                                event.stats.genTokensPerSecond
                        }
                        close()
                    },
                    onRejected = { code, message ->
                        trySend(GenEvent.Error("[$code] $message"))
                        close()
                    },
                )
            )

            if (!accepted) close()

            awaitClose {
                // The client hung up, or the response finished. Either way the queue
                // entry must not outlive it.
                if (!queue.cancelQueued(requestId)) engine.cancel(requestId)
            }
        }

        override fun onFinished(tokensPerSecond: Double) {
            lastTokensPerSecond = tokensPerSecond
            idle.touch()
            updateNotification()
        }

        override fun cancel(requestId: String) {
            if (!queue.cancelQueued(requestId)) engine.cancel(requestId)
        }

        override fun loadedModelId(): String? = engine.loadedModelId
    }
}
