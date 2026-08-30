package dev.taracore.api

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/**
 * A completion to run.
 *
 * ## Binder size limit
 *
 * A Binder transaction buffer is 1 MB and is shared by every transaction in flight in
 * the *calling process*, so a large parcel can fail for reasons that have nothing to
 * do with this request. Anything over
 * [TaraCoreContract.INLINE_PROMPT_LIMIT_BYTES] (512 KB) must therefore be sent out of
 * band: put the UTF-8 prompt bytes into a pipe and pass the read end as
 * [largePrompt], leaving [messages] empty. The service reads the pipe to EOF on a
 * worker thread and treats the bytes as a pre-rendered prompt -- no chat template is
 * applied to it.
 *
 * `:client-sdk` does this switch automatically; direct AIDL callers must do it
 * themselves.
 */
data class GenerationRequest(
    @JvmField val messages: List<ChatMessageParcel> = emptyList(),
    /** Model to run. When null the currently loaded model is used. */
    @JvmField val modelId: String? = null,
    @JvmField val maxTokens: Int = 512,
    @JvmField val temperature: Float = 0.8f,
    @JvmField val topP: Float = 0.95f,
    @JvmField val topK: Int = 40,
    @JvmField val repeatPenalty: Float = 1.1f,
    @JvmField val stop: List<String> = emptyList(),
    /** Negative means "choose randomly". */
    @JvmField val seed: Long = -1L,
    /** Read end of a pipe carrying a prompt too large to inline. See the class doc. */
    @JvmField val largePrompt: ParcelFileDescriptor? = null,
    /**
     * When true and [modelId] names a model other than the loaded one, the service
     * swaps models instead of failing. Costs a full load, so it is opt-in per request
     * as well as globally in Settings.
     */
    @JvmField val allowAutoLoad: Boolean = true,
) : Parcelable {

    constructor(source: Parcel) : this(
        messages = ArrayList<ChatMessageParcel>().also {
            source.readTypedList(it, ChatMessageParcel.CREATOR)
        },
        modelId = source.readString(),
        maxTokens = source.readInt(),
        temperature = source.readFloat(),
        topP = source.readFloat(),
        topK = source.readInt(),
        repeatPenalty = source.readFloat(),
        stop = ArrayList<String>().also { source.readStringList(it) },
        seed = source.readLong(),
        largePrompt = source.readParcelable(
            ParcelFileDescriptor::class.java.classLoader,
        ),
        allowAutoLoad = source.readInt() != 0,
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeTypedList(messages)
        dest.writeString(modelId)
        dest.writeInt(maxTokens)
        dest.writeFloat(temperature)
        dest.writeFloat(topP)
        dest.writeInt(topK)
        dest.writeFloat(repeatPenalty)
        dest.writeStringList(stop)
        dest.writeLong(seed)
        dest.writeParcelable(largePrompt, flags)
        dest.writeInt(if (allowAutoLoad) 1 else 0)
    }

    /** A file descriptor must be declared so Binder dups it instead of copying bytes. */
    override fun describeContents(): Int =
        if (largePrompt != null) Parcelable.CONTENTS_FILE_DESCRIPTOR else 0

    /** Rough UTF-8 size of the inline payload, for the 512 KB decision. */
    fun approximateInlineBytes(): Int =
        messages.sumOf { it.role.length + it.content.length * 3 + 8 }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<GenerationRequest> =
            object : Parcelable.Creator<GenerationRequest> {
                override fun createFromParcel(source: Parcel) = GenerationRequest(source)
                override fun newArray(size: Int) = arrayOfNulls<GenerationRequest>(size)
            }
    }
}
