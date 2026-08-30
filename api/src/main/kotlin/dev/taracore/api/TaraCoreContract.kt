package dev.taracore.api

/**
 * Wire-level constants shared by the service and every client.
 *
 * These strings are part of the public contract: changing one breaks every installed
 * client, so they are versioned rather than edited. See docs/API.md.
 */
object TaraCoreContract {

    /** Package that hosts the service. Used for explicit intents and `<queries>`. */
    const val SERVICE_PACKAGE = "dev.taracore"

    /** Fully-qualified service class, for building an explicit bind intent. */
    const val SERVICE_CLASS = "dev.taracore.service.TaraCoreService"

    /** Action clients bind with. */
    const val ACTION_BIND = "dev.taracore.action.BIND"

    /** Permission a client must hold to bind and to call any method. */
    const val PERMISSION = "dev.taracore.permission.BIND_INFERENCE"

    /**
     * Contract version implemented by this build of `:api`.
     *
     * Compare against [ITaraCore.getApiVersion] to find out what the *service*
     * supports, which may be older or newer than the client's copy.
     *
     * - **1** — the original contract.
     * - **2** — adds [GenerationRequest.grammar] for constrained decoding. A v1
     *   service ignores the field, so a client that depends on the constraint must
     *   check the version rather than assume: unconstrained output is
     *   indistinguishable from a model that simply disobeyed.
     * - **3** — adds [ITaraCore.warmUp] and [ServiceStatus.activeModelId]. Calling
     *   warmUp on a v2 service throws; the field simply reads null.
     */
    const val API_VERSION = 3

    /** Prompts larger than this must travel by [GenerationRequest.largePrompt]. */
    const val INLINE_PROMPT_LIMIT_BYTES = 512 * 1024
}

/**
 * Error codes reported through [ITokenCallback.onError] and [IModelCallback.onError],
 * and carried in [GenerationResult.errorCode].
 *
 * Values are stable and append-only. Unknown codes must be treated as [UNKNOWN].
 */
object TaraCoreErrors {
    const val NONE = 0
    const val UNKNOWN = 1

    /** No model is loaded and auto-load is disabled or failed. */
    const val NO_MODEL_LOADED = 2

    /** The requested modelId is not in the registry or is not downloaded. */
    const val MODEL_NOT_FOUND = 3

    /** The model file exists but could not be loaded (corrupt, unsupported, OOM). */
    const val MODEL_LOAD_FAILED = 4

    /** Prompt is longer than the loaded model's context window. */
    const val CONTEXT_OVERFLOW = 5

    /** Generation stopped because the caller cancelled it. */
    const val CANCELLED = 6

    /** The caller does not hold [TaraCoreContract.PERMISSION]. */
    const val PERMISSION_DENIED = 7

    /** The service ran out of memory and unloaded the model to survive. */
    const val OUT_OF_MEMORY = 8

    /** The request was malformed (empty messages, negative maxTokens, ...). */
    const val INVALID_REQUEST = 9

    /** The engine failed in a way the service could not classify. */
    const val ENGINE_FAILURE = 10

    /** The queue is full and the request was rejected rather than queued forever. */
    const val QUEUE_FULL = 11

    /**
     * The requested model is downloaded but not resident, and the caller declined to
     * let the service swap. Distinct from [MODEL_NOT_FOUND]: retrying with auto-load
     * enabled would succeed, at the cost of a load taking tens of seconds.
     */
    const val MODEL_NOT_LOADED = 12

    fun name(code: Int): String = when (code) {
        NONE -> "NONE"
        NO_MODEL_LOADED -> "NO_MODEL_LOADED"
        MODEL_NOT_FOUND -> "MODEL_NOT_FOUND"
        MODEL_LOAD_FAILED -> "MODEL_LOAD_FAILED"
        CONTEXT_OVERFLOW -> "CONTEXT_OVERFLOW"
        CANCELLED -> "CANCELLED"
        PERMISSION_DENIED -> "PERMISSION_DENIED"
        OUT_OF_MEMORY -> "OUT_OF_MEMORY"
        INVALID_REQUEST -> "INVALID_REQUEST"
        ENGINE_FAILURE -> "ENGINE_FAILURE"
        QUEUE_FULL -> "QUEUE_FULL"
        MODEL_NOT_LOADED -> "MODEL_NOT_LOADED"
        else -> "UNKNOWN"
    }
}
