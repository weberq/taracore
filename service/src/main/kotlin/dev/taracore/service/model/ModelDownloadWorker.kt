package dev.taracore.service.model

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Progress of one download, published on [DownloadRegistry.progress]. */
data class DownloadProgress(
    val modelId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val state: State,
    val message: String? = null,
) {
    enum class State { QUEUED, DOWNLOADING, VERIFYING, DONE, FAILED, CANCELLED }

    val fraction: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Process-wide view of in-flight downloads.
 *
 * WorkManager's own progress API round-trips through its database and is observed
 * per-WorkInfo; the UI wants one flow of everything, so the worker publishes here as
 * well. WorkManager remains the thing that survives process death and retries.
 */
object DownloadRegistry {

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    internal fun publish(p: DownloadProgress) {
        _progress.value = _progress.value + (p.modelId to p)
    }

    internal fun clear(modelId: String) {
        _progress.value = _progress.value - modelId
    }

    fun of(modelId: String): DownloadProgress? = _progress.value[modelId]

    fun workName(modelId: String) = "taracore-download-$modelId"

    /**
     * Queue a download. KEEP rather than REPLACE so tapping "Download" twice does not
     * restart a transfer that is already 3 GB in.
     */
    fun enqueue(context: Context, modelId: String) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to modelId))
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(ModelDownloadWorker.TAG_DOWNLOAD)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(modelId), ExistingWorkPolicy.KEEP, request)

        publish(DownloadProgress(modelId, 0, 0, DownloadProgress.State.QUEUED))
    }

    fun cancel(context: Context, modelId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(modelId))
        publish(DownloadProgress(modelId, 0, 0, DownloadProgress.State.CANCELLED))
    }
}

/**
 * Downloads one GGUF, resumably, verifies it, and registers it.
 *
 * The interesting parts are all failure handling: these files are gigabytes over
 * mobile networks, so a partial transfer is the normal case, not the exception.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "TaraCore/Download"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_ERROR = "error"
        const val TAG_DOWNLOAD = "taracore-download"

        private const val BUFFER = 128 * 1024

        /** Publish at most this often; a 5 GB file is otherwise 40 000 UI updates. */
        private const val PROGRESS_INTERVAL_MS = 250L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // No read timeout: a slow mobile link is not an error, and cancellation is
        // handled by isStopped rather than by timing out.
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return@withContext fail("no model id in input data", null)

        val repo = ModelRepository(applicationContext)
        val entity = repo.byId(modelId)
            ?: return@withContext fail("unknown model: $modelId", modelId)

        if (entity.url.isBlank()) {
            return@withContext fail("model $modelId has no download url", modelId)
        }

        val target = repo.targetFileFor(entity)
        // Download to .part and rename only on success, so an interrupted transfer
        // can never be mistaken for a complete model.
        val part = File(target.parentFile, "${target.name}.part")

        if (target.isFile && target.length() > 0) {
            Log.i(TAG, "$modelId already present at ${target.absolutePath}")
            repo.markDownloaded(modelId, target)
            DownloadRegistry.publish(
                DownloadProgress(modelId, target.length(), target.length(), DownloadProgress.State.DONE)
            )
            return@withContext Result.success()
        }

        val alreadyHave = if (part.isFile) part.length() else 0L
        val needed = (entity.sizeBytes - alreadyHave).coerceAtLeast(0)
        if (!repo.hasSpaceFor(needed)) {
            return@withContext fail(
                "not enough free space: need ~${needed / 1_000_000} MB plus headroom, " +
                    "have ${repo.freeSpaceBytes() / 1_000_000} MB",
                modelId,
            )
        }

        if (!repo.likelyFitsInMemory(entity)) {
            // A warning, not a refusal: availMem moves, and the user may well want the
            // file on disk for a device that will have room later.
            Log.w(TAG, "$modelId estimated ${entity.estRamBytes / 1_000_000} MB of RAM " +
                "but only ${repo.availableMemoryBytes() / 1_000_000} MB is available")
        }

        val builder = Request.Builder().url(entity.url)
        if (alreadyHave > 0) {
            Log.i(TAG, "resuming $modelId from $alreadyHave bytes")
            builder.header("Range", "bytes=$alreadyHave-")
        }

        try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext fail("HTTP ${response.code} from ${entity.url}", modelId)
                }

                // 206 means the server honoured the Range header. A 200 in reply to a
                // Range request means it ignored it, so the old bytes are worthless.
                val resuming = response.code == 206 && alreadyHave > 0
                if (alreadyHave > 0 && !resuming) {
                    Log.w(TAG, "server ignored Range; restarting $modelId from zero")
                    part.delete()
                }

                val startAt = if (resuming) alreadyHave else 0L
                val body = response.body ?: return@withContext fail("empty response body", modelId)
                val contentLength = body.contentLength()
                // Server's Content-Length is authoritative; the catalog's size_bytes is
                // only an estimate used for the free-space check above.
                val total = if (contentLength > 0) startAt + contentLength else entity.sizeBytes

                DownloadProgress(modelId, startAt, total, DownloadProgress.State.DOWNLOADING)
                    .let(DownloadRegistry::publish)

                val digest = MessageDigest.getInstance("SHA-256")
                // A resumed transfer cannot produce a whole-file digest without
                // re-reading what is already on disk. Do that -- it is far cheaper
                // than downloading gigabytes again.
                if (resuming && entity.sha256.isNotBlank()) {
                    part.inputStream().use { existing ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val n = existing.read(buf)
                            if (n <= 0) break
                            digest.update(buf, 0, n)
                        }
                    }
                }

                var written = startAt
                var lastPublish = 0L

                body.byteStream().use { input ->
                    java.io.FileOutputStream(part, resuming).use { output ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            if (isStopped) {
                                Log.i(TAG, "$modelId cancelled at $written bytes; .part kept for resume")
                                DownloadRegistry.publish(
                                    DownloadProgress(modelId, written, total,
                                        DownloadProgress.State.CANCELLED)
                                )
                                return@withContext Result.failure()
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            if (entity.sha256.isNotBlank()) digest.update(buf, 0, n)
                            written += n

                            val now = System.currentTimeMillis()
                            if (now - lastPublish >= PROGRESS_INTERVAL_MS) {
                                lastPublish = now
                                DownloadRegistry.publish(
                                    DownloadProgress(modelId, written, total,
                                        DownloadProgress.State.DOWNLOADING)
                                )
                                setProgress(
                                    Data.Builder()
                                        .putLong("bytes", written)
                                        .putLong("total", total)
                                        .build()
                                )
                            }
                        }
                        output.fd.sync()
                    }
                }

                if (entity.sha256.isNotBlank()) {
                    DownloadRegistry.publish(
                        DownloadProgress(modelId, written, total, DownloadProgress.State.VERIFYING)
                    )
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(entity.sha256, ignoreCase = true)) {
                        part.delete()
                        return@withContext fail(
                            "checksum mismatch for $modelId: expected ${entity.sha256}, got $actual",
                            modelId,
                        )
                    }
                    Log.i(TAG, "$modelId verified: $actual")
                } else {
                    // Not fatal, but the user should know an unverified multi-gigabyte
                    // binary is about to be mapped into memory.
                    Log.w(TAG, "$modelId has no pinned sha256; downloaded without verification")
                }

                // Atomic within a filesystem: after this the file is either absent or
                // complete, never half-written under its real name.
                if (!part.renameTo(target)) {
                    return@withContext fail("could not rename ${part.name} to ${target.name}", modelId)
                }

                repo.markDownloaded(modelId, target)
                DownloadRegistry.publish(
                    DownloadProgress(modelId, written, total, DownloadProgress.State.DONE)
                )
                Log.i(TAG, "$modelId downloaded to ${target.absolutePath} ($written bytes)")
                Result.success()
            }
        } catch (e: IOException) {
            // The .part file stays on disk: the retry resumes rather than restarting.
            fail("network error: ${e.message}", modelId, e)
        } catch (e: Exception) {
            fail("unexpected error: ${e.message}", modelId, e)
        }
    }

    private fun fail(message: String, modelId: String?, cause: Throwable? = null): Result {
        Log.e(TAG, message, cause)
        if (modelId != null) {
            DownloadRegistry.publish(
                DownloadProgress(modelId, 0, 0, DownloadProgress.State.FAILED, message)
            )
        }
        return Result.failure(workDataOf(KEY_ERROR to message))
    }
}
