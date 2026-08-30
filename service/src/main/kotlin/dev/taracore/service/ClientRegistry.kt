package dev.taracore.service

import android.content.Context
import android.os.Binder
import java.util.concurrent.ConcurrentHashMap

/** What one calling app has asked of the engine. Shown on the Dashboard. */
data class ClientUsage(
    val uid: Int,
    val packageName: String,
    val requests: Int = 0,
    val tokensGenerated: Long = 0,
    val lastSeenAt: Long = 0,
)

/**
 * Per-caller accounting, keyed by Binder uid.
 *
 * The point is accountability: the user is being asked to let a foreground service
 * burn their battery, and "which app is doing this" is the first thing they will
 * want to know. `Binder.getCallingUid` is the only trustworthy caller identity --
 * anything the caller passes in a parcel can be forged.
 */
class ClientRegistry(private val context: Context) {

    private val usage = ConcurrentHashMap<Int, ClientUsage>()

    /** Resolved uid -> package, cached because the PackageManager call is not free. */
    private val nameCache = ConcurrentHashMap<Int, String>()

    fun packageNameFor(uid: Int): String = nameCache.getOrPut(uid) {
        // A uid can host several packages when they share a sharedUserId; joining
        // them is more honest than picking the first arbitrarily.
        context.packageManager.getPackagesForUid(uid)
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?: "uid:$uid"
    }

    fun recordRequest(uid: Int = Binder.getCallingUid()): ClientUsage {
        val name = packageNameFor(uid)
        return usage.compute(uid) { _, prev ->
            (prev ?: ClientUsage(uid, name)).copy(
                requests = (prev?.requests ?: 0) + 1,
                lastSeenAt = System.currentTimeMillis(),
            )
        }!!
    }

    fun recordTokens(uid: Int, tokens: Int) {
        usage.computeIfPresent(uid) { _, prev ->
            prev.copy(tokensGenerated = prev.tokensGenerated + tokens)
        }
    }

    fun snapshot(): List<ClientUsage> = usage.values.sortedByDescending { it.lastSeenAt }

    fun clear() = usage.clear()
}
