package dev.taracore.api

import android.os.Parcel
import android.os.Parcelable

/**
 * Outcome of a completion. Returned by [ITaraCore.generate] and delivered as the
 * final event of a stream through [ITokenCallback.onDone].
 *
 * A cancelled generation is a *success* with [cancelled] set and whatever text had
 * been produced: callers that stream want to keep the partial answer.
 */
data class GenerationResult(
    @JvmField val requestId: String,
    @JvmField val text: String,
    @JvmField val modelId: String,
    @JvmField val promptTokens: Int,
    @JvmField val generatedTokens: Int,
    @JvmField val promptMs: Long,
    @JvmField val generationMs: Long,
    @JvmField val cancelled: Boolean = false,
    /** True when generation ended on one of the request's stop strings. */
    @JvmField val stopped: Boolean = false,
    @JvmField val errorCode: Int = TaraCoreErrors.NONE,
    @JvmField val errorMessage: String? = null,
) : Parcelable {

    val isError: Boolean get() = errorCode != TaraCoreErrors.NONE

    val tokensPerSecond: Double
        get() = if (generationMs > 0) generatedTokens * 1000.0 / generationMs else 0.0

    val promptTokensPerSecond: Double
        get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0

    /** OpenAI-style total, for the HTTP layer's `usage` block. */
    val totalTokens: Int get() = promptTokens + generatedTokens

    constructor(source: Parcel) : this(
        requestId = source.readString().orEmpty(),
        text = source.readString().orEmpty(),
        modelId = source.readString().orEmpty(),
        promptTokens = source.readInt(),
        generatedTokens = source.readInt(),
        promptMs = source.readLong(),
        generationMs = source.readLong(),
        cancelled = source.readInt() != 0,
        stopped = source.readInt() != 0,
        errorCode = source.readInt(),
        errorMessage = source.readString(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(requestId)
        dest.writeString(text)
        dest.writeString(modelId)
        dest.writeInt(promptTokens)
        dest.writeInt(generatedTokens)
        dest.writeLong(promptMs)
        dest.writeLong(generationMs)
        dest.writeInt(if (cancelled) 1 else 0)
        dest.writeInt(if (stopped) 1 else 0)
        dest.writeInt(errorCode)
        dest.writeString(errorMessage)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmStatic
        fun error(requestId: String, code: Int, message: String) = GenerationResult(
            requestId = requestId,
            text = "",
            modelId = "",
            promptTokens = 0,
            generatedTokens = 0,
            promptMs = 0,
            generationMs = 0,
            errorCode = code,
            errorMessage = message,
        )

        @JvmField
        val CREATOR: Parcelable.Creator<GenerationResult> =
            object : Parcelable.Creator<GenerationResult> {
                override fun createFromParcel(source: Parcel) = GenerationResult(source)
                override fun newArray(size: Int) = arrayOfNulls<GenerationResult>(size)
            }
    }
}
