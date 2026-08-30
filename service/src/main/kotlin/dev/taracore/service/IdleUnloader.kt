package dev.taracore.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drops the model after a period with no requests.
 *
 * A loaded 3B model holds 2 GB of mmap'd pages that the kernel will keep resident as
 * long as nothing else needs them -- which on a phone means until something does, at
 * which point some *other* app pays for our idleness. Unloading on a timer keeps the
 * cost proportional to use. Reloading is lazy and reported as progress, so the price
 * of being wrong is a few seconds on the next request.
 */
class IdleUnloader(
    private val scope: CoroutineScope,
    private val onIdle: suspend () -> Unit,
) {

    private companion object {
        const val TAG = "TaraCore/Idle"

        /** Coarse enough to be cheap, fine enough for a countdown in the UI. */
        const val TICK_MS = 1_000L
    }

    private var timer: Job? = null

    @Volatile
    private var deadline: Long = 0

    @Volatile
    private var timeoutMs: Long = TaraSettings.DEFAULT_IDLE_TIMEOUT_MS

    /** Milliseconds until the unload fires; -1 when idle unloading is disabled. */
    val remainingMs: Long
        get() = if (timeoutMs <= 0 || deadline == 0L) -1L
        else (deadline - System.currentTimeMillis()).coerceAtLeast(0)

    fun setTimeout(ms: Long) {
        timeoutMs = ms
        if (ms <= 0) {
            Log.i(TAG, "idle unloading disabled")
            cancel()
        } else if (timer != null) {
            // Apply the new timeout to the countdown already running.
            touch()
        }
    }

    /** Called on every request. Restarts the countdown. */
    fun touch() {
        if (timeoutMs <= 0) return
        deadline = System.currentTimeMillis() + timeoutMs
        if (timer?.isActive == true) return

        timer = scope.launch {
            while (isActive) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                // Sleep to the deadline, but wake at least once a second so the
                // dashboard countdown is honest and a changed timeout takes effect.
                delay(minOf(remaining, TICK_MS))
            }
            if (isActive && timeoutMs > 0) {
                Log.i(TAG, "idle for ${timeoutMs}ms; unloading model")
                runCatching { onIdle() }
                    .onFailure { Log.e(TAG, "idle unload failed", it) }
                deadline = 0
            }
        }
    }

    fun cancel() {
        timer?.cancel()
        timer = null
        deadline = 0
    }
}
