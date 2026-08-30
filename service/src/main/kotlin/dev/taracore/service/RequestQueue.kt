package dev.taracore.service

import android.util.Log
import dev.taracore.api.TaraCoreErrors
import dev.taracore.engine.GenRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * FIFO in front of the engine.
 *
 * The engine can only run one generation at a time (see docs/DECISIONS.md D5), and
 * two clients asking at once is the normal case rather than an edge case. Without a
 * queue they would serialise anyway, but inside a mutex where neither could be
 * cancelled and neither could be told its position. So: an explicit queue, one worker
 * coroutine, and cancellation that works whether the request is running or waiting.
 */
class RequestQueue(
    private val scope: CoroutineScope,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val execute: suspend (QueuedRequest) -> Unit,
) {

    companion object {
        private const val TAG = "TaraCore/Queue"

        /**
         * Deep enough that a burst from several apps queues rather than fails, shallow
         * enough that a runaway client gets QUEUE_FULL instead of building an unbounded
         * backlog the user cannot see.
         */
        const val DEFAULT_CAPACITY = 32
    }

    data class QueuedRequest(
        val request: GenRequest,
        val callerUid: Int,
        val callerPackage: String?,
        /** Invoked exactly once, whatever the outcome. */
        val onRejected: (code: Int, message: String) -> Unit = { _, _ -> },
    )

    private val channel = Channel<QueuedRequest>(capacity = capacity)

    /** Ids that were cancelled before the worker picked them up. */
    private val cancelledBeforeStart = ConcurrentHashMap.newKeySet<String>()

    private val _depth = MutableStateFlow(0)
    val depth: StateFlow<Int> = _depth.asStateFlow()

    private val pendingCount = AtomicInteger(0)

    /** Id of the request the worker is running, or null. */
    @Volatile
    var currentRequestId: String? = null
        private set

    private var worker: Job? = null

    fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (queued in channel) {
                pendingCount.decrementAndGet()
                _depth.value = pendingCount.get()

                val id = queued.request.requestId
                if (cancelledBeforeStart.remove(id)) {
                    Log.i(TAG, "$id was cancelled while queued; skipping")
                    queued.onRejected(TaraCoreErrors.CANCELLED, "cancelled while queued")
                    continue
                }

                currentRequestId = id
                try {
                    execute(queued)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // One failed request must not take the worker down with it --
                    // every later request would silently hang.
                    Log.e(TAG, "request $id failed", t)
                    queued.onRejected(
                        TaraCoreErrors.ENGINE_FAILURE,
                        t.message ?: "generation failed",
                    )
                } finally {
                    currentRequestId = null
                }
            }
        }
    }

    /** @return true if queued; false when the queue is full (caller gets QUEUE_FULL). */
    fun submit(queued: QueuedRequest): Boolean {
        val result = channel.trySend(queued)
        return if (result.isSuccess) {
            _depth.value = pendingCount.incrementAndGet()
            Log.i(TAG, "queued ${queued.request.requestId} from " +
                "${queued.callerPackage ?: queued.callerUid} (depth ${_depth.value})")
            true
        } else {
            Log.w(TAG, "rejected ${queued.request.requestId}: queue full at $capacity")
            queued.onRejected(TaraCoreErrors.QUEUE_FULL, "inference queue is full")
            false
        }
    }

    /**
     * Mark [requestId] cancelled. Returns true when it was still queued -- the caller
     * then knows not to bother cancelling the engine.
     */
    fun cancelQueued(requestId: String): Boolean {
        if (currentRequestId == requestId) return false
        cancelledBeforeStart.add(requestId)
        return true
    }

    fun stop() {
        worker?.cancel()
        worker = null
        channel.close()
    }
}
