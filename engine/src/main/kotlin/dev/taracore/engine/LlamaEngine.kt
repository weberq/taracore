package dev.taracore.engine

import android.util.Log

/**
 * Thin `external` mirror of the native engine. Nothing here is thread-safe on its
 * own; serialisation is [EngineController]'s job.
 *
 * Every method takes the opaque handle returned by [nativeCreate]. Calling any of
 * them with a stale handle is undefined behaviour, which is exactly why this object
 * is internal to the module and only [EngineController] holds a handle.
 */
internal object LlamaEngine {

    private const val TAG = "TaraCore/Engine"

    /** Set once [System.loadLibrary] has succeeded; false means no native inference. */
    val available: Boolean = runCatching {
        System.loadLibrary("taracore_jni")
    }.onFailure {
        Log.e(TAG, "failed to load libtaracore_jni.so", it)
    }.isSuccess

    external fun nativeCreate(): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeLoad(
        handle: Long,
        path: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
        nBatch: Int,
        useMmap: Boolean,
        useMlock: Boolean,
    ): NativeLoadResult

    external fun nativeUnload(handle: Long)

    external fun nativeIsLoaded(handle: Long): Boolean

    external fun nativeCancel(handle: Long)

    external fun nativeFormatChat(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
    ): String

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Long,
        stop: Array<String>,
        listener: TokenListener?,
    ): GenStats

    external fun nativeVersion(): String
}
