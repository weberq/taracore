package dev.taracore.api

import android.os.Parcel
import android.os.Parcelable

/**
 * One entry of the model registry, whether or not it has been downloaded.
 *
 * [estRamBytes] is deliberately larger than [sizeBytes]: the weights are mmap'd, but
 * the KV cache, compute buffers and the tokenizer are not, and a client deciding
 * whether a model will fit needs the total.
 */
data class ModelInfo(
    @JvmField val id: String,
    @JvmField val displayName: String,
    @JvmField val family: String,
    @JvmField val quant: String,
    @JvmField val sizeBytes: Long,
    @JvmField val estRamBytes: Long,
    @JvmField val ctxDefault: Int,
    @JvmField val downloaded: Boolean,
    @JvmField val loaded: Boolean,
    @JvmField val license: String = "",
) : Parcelable {

    constructor(source: Parcel) : this(
        id = source.readString().orEmpty(),
        displayName = source.readString().orEmpty(),
        family = source.readString().orEmpty(),
        quant = source.readString().orEmpty(),
        sizeBytes = source.readLong(),
        estRamBytes = source.readLong(),
        ctxDefault = source.readInt(),
        downloaded = source.readInt() != 0,
        loaded = source.readInt() != 0,
        license = source.readString().orEmpty(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(displayName)
        dest.writeString(family)
        dest.writeString(quant)
        dest.writeLong(sizeBytes)
        dest.writeLong(estRamBytes)
        dest.writeInt(ctxDefault)
        dest.writeInt(if (downloaded) 1 else 0)
        dest.writeInt(if (loaded) 1 else 0)
        dest.writeString(license)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ModelInfo> =
            object : Parcelable.Creator<ModelInfo> {
                override fun createFromParcel(source: Parcel) = ModelInfo(source)
                override fun newArray(size: Int) = arrayOfNulls<ModelInfo>(size)
            }
    }
}
