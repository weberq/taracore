package dev.taracore.engine

/** How a model should be brought up. Mirrors the arguments of `Engine::load`. */
data class ModelSpec(
    val modelId: String,
    val path: String,
    val nCtx: Int = 4096,
    val nThreads: Int = defaultThreads(),
    val nGpuLayers: Int = 0,
    val nBatch: Int = 512,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
) {
    companion object {
        /**
         * Big-core count on a big.LITTLE phone is roughly half the reported CPUs, and
         * saturating the little cores costs more in scheduling than it buys in
         * throughput. Clamped to [2, 8].
         */
        fun defaultThreads(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)
    }
}

data class ChatMessage(val role: String, val content: String) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

data class GenParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val seed: Long = -1L,
    val stop: List<String> = emptyList(),
    /**
     * GBNF grammar constraining the output. Null or blank means unconstrained.
     *
     * Build one with `dev.taracore.api.Gbnf` rather than by hand; the escaping
     * rules are easy to get subtly wrong and a malformed grammar fails the request.
     */
    val grammar: String? = null,
)

/**
 * Constructed from JNI. The field order and types are pinned by the constructor
 * signature cached in `JNI_OnLoad` -- changing them means changing jni.cpp too.
 */
data class GenStats(
    val promptTokens: Int,
    val genTokens: Int,
    val promptMs: Long,
    val genMs: Long,
    val cancelled: Boolean,
    val stopped: Boolean,
    val ok: Boolean,
    val error: String,
) {
    val genTokensPerSecond: Double
        get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0

    val promptTokensPerSecond: Double
        get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0
}

/** Constructed from JNI; see the note on [GenStats]. */
data class NativeLoadResult(
    val ok: Boolean,
    val error: String,
    val modelSizeBytes: Long,
    val vocabSize: Int,
    val nCtx: Int,
    val backendName: String,
    val description: String,
)

/** Implemented on the Kotlin side, called from the native sampling loop. */
fun interface TokenListener {
    /** @return false to abort generation after this token. */
    fun onToken(piece: String): Boolean
}

/** What the engine is doing right now. */
sealed interface EngineState {
    data object Unloaded : EngineState

    data class Loading(val modelId: String, val progress: Float) : EngineState

    data class Ready(
        val modelId: String,
        val backend: String,
        val ramBytes: Long,
        val nCtx: Int,
    ) : EngineState

    data class Generating(val modelId: String, val requestId: String) : EngineState

    data class Failed(val modelId: String?, val message: String) : EngineState
}

/** One event in a streaming generation. */
sealed interface GenEvent {
    data class Token(val piece: String) : GenEvent

    data class Done(val stats: GenStats, val text: String) : GenEvent

    data class Error(val message: String, val cause: Throwable? = null) : GenEvent
}

/** A generation to run. */
data class GenRequest(
    val requestId: String,
    val messages: List<ChatMessage>,
    val params: GenParams = GenParams(),
    /** When set, used verbatim instead of applying the model's chat template. */
    val rawPrompt: String? = null,
)
