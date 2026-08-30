package dev.taracore.api

import android.os.Parcel
import android.os.Parcelable

/**
 * A snapshot of what the service is doing. Cheap to fetch -- it reads in-memory state
 * only -- so clients may poll it, though the dashboard prefers a bound observer.
 */
data class ServiceStatus(
    @JvmField val apiVersion: Int,
    /** One of the [State] constants. */
    @JvmField val state: Int,
    @JvmField val loadedModelId: String? = null,
    /** "CPU", "Vulkan0", "GPUOpenCL", ... -- the backend that actually initialised. */
    @JvmField val backend: String = "none",
    @JvmField val modelRamBytes: Long = 0,
    @JvmField val contextSize: Int = 0,
    /** Requests waiting behind the one in flight. */
    @JvmField val queueDepth: Int = 0,
    @JvmField val lastTokensPerSecond: Double = 0.0,
    /** Milliseconds until the idle unloader fires; -1 when idle unloading is off. */
    @JvmField val idleUnloadInMs: Long = -1,
    @JvmField val httpServerRunning: Boolean = false,
    @JvmField val httpPort: Int = 0,
    /** True when the last unload was forced by onTrimMemory rather than the timer. */
    @JvmField val unloadedUnderMemoryPressure: Boolean = false,
    @JvmField val engineVersion: String = "",
) : Parcelable {

    object State {
        const val IDLE = 0
        const val LOADING = 1
        const val READY = 2
        const val GENERATING = 3
        const val ERROR = 4
    }

    constructor(source: Parcel) : this(
        apiVersion = source.readInt(),
        state = source.readInt(),
        loadedModelId = source.readString(),
        backend = source.readString().orEmpty(),
        modelRamBytes = source.readLong(),
        contextSize = source.readInt(),
        queueDepth = source.readInt(),
        lastTokensPerSecond = source.readDouble(),
        idleUnloadInMs = source.readLong(),
        httpServerRunning = source.readInt() != 0,
        httpPort = source.readInt(),
        unloadedUnderMemoryPressure = source.readInt() != 0,
        engineVersion = source.readString().orEmpty(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(apiVersion)
        dest.writeInt(state)
        dest.writeString(loadedModelId)
        dest.writeString(backend)
        dest.writeLong(modelRamBytes)
        dest.writeInt(contextSize)
        dest.writeInt(queueDepth)
        dest.writeDouble(lastTokensPerSecond)
        dest.writeLong(idleUnloadInMs)
        dest.writeInt(if (httpServerRunning) 1 else 0)
        dest.writeInt(httpPort)
        dest.writeInt(if (unloadedUnderMemoryPressure) 1 else 0)
        dest.writeString(engineVersion)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ServiceStatus> =
            object : Parcelable.Creator<ServiceStatus> {
                override fun createFromParcel(source: Parcel) = ServiceStatus(source)
                override fun newArray(size: Int) = arrayOfNulls<ServiceStatus>(size)
            }
    }
}
