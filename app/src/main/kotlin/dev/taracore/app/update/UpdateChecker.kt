package dev.taracore.app.update

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** A release newer than the one installed. */
data class AvailableUpdate(
    val tag: String,
    val name: String,
    val notes: String,
    val htmlUrl: String,
    val apkUrl: String?,
)

/**
 * Asks GitHub whether a newer release exists.
 *
 * Deliberately one plain unauthenticated GET of a public endpoint. Nothing about the
 * user or the device is sent -- not an install id, not a version, not a model list.
 * The comparison happens here, on the answer. That matters for an app whose entire
 * pitch is that nothing leaves the device: an update check is the one network call
 * Tara Core makes that is not a model download, and it should be defensible in one
 * sentence.
 *
 * Play Store builds should use the Play in-app update API instead; see
 * [shouldCheck], which is false for them.
 */
class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "TaraCore/Update"
        private const val RELEASES = "https://api.github.com/repos/weberq/taracore/releases/latest"
        private const val PREFS = "taracore_update"
        private const val KEY_LAST_CHECK = "last_check"

        /** Once a day is plenty for a project that does not ship hourly. */
        private val INTERVAL_MS = TimeUnit.DAYS.toMillis(1)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Whether to check at all.
     *
     * False when the app was installed from Play: Play does its own updating, and a
     * second mechanism offering a sideload APK on top of it is a good way to get an
     * app taken down.
     */
    fun shouldCheck(enabledInSettings: Boolean): Boolean {
        if (!enabledInSettings) return false
        if (installedFromPlay()) return false

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last >= INTERVAL_MS
    }

    private fun installedFromPlay(): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
        installer == "com.android.vending"
    }.getOrDefault(false)

    /**
     * @return the newer release, or null when up to date, offline, or rate-limited.
     *   Never throws: a failed update check must be invisible.
     */
    suspend fun check(currentVersionName: String, skippedTag: String): AvailableUpdate? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

                val request = Request.Builder()
                    .url(RELEASES)
                    .header("Accept", "application/vnd.github+json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // 403 is the usual one: unauthenticated GitHub API is rate
                        // limited by IP. Not worth surfacing.
                        Log.i(TAG, "update check returned ${response.code}")
                        return@withContext null
                    }
                    val body = response.body?.string() ?: return@withContext null
                    val obj = json.parseToJsonElement(body).jsonObject

                    val tag = obj["tag_name"]?.jsonPrimitive?.content
                        ?: return@withContext null
                    if (obj["draft"]?.jsonPrimitive?.content == "true") return@withContext null
                    if (obj["prerelease"]?.jsonPrimitive?.content == "true") return@withContext null
                    if (tag == skippedTag) return@withContext null
                    if (!isNewer(tag, currentVersionName)) return@withContext null

                    val apk = obj["assets"]?.jsonArray
                        ?.map { it.jsonObject }
                        ?.firstOrNull {
                            it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true
                        }
                        ?.get("browser_download_url")?.jsonPrimitive?.content

                    AvailableUpdate(
                        tag = tag,
                        name = obj["name"]?.jsonPrimitive?.content ?: tag,
                        notes = obj["body"]?.jsonPrimitive?.content.orEmpty(),
                        htmlUrl = obj["html_url"]?.jsonPrimitive?.content
                            ?: "https://github.com/weberq/taracore/releases",
                        apkUrl = apk,
                    )
                }
            }.onFailure { Log.i(TAG, "update check failed: ${it.message}") }.getOrNull()
        }

    /**
     * Semantic-ish comparison of `v1.2.3` against `1.2.3`.
     *
     * Compares numeric components only, so a suffix like `-rc1` is ignored rather
     * than mis-sorted -- prereleases are filtered out before this is reached anyway.
     * Unparseable tags are treated as *not* newer: never nag on a tag we cannot read.
     */
    internal fun isNewer(tag: String, current: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V')
            .takeWhile { it.isDigit() || it == '.' }
            .split('.')
            .mapNotNull { it.toIntOrNull() }

        val a = parts(tag)
        val b = parts(current)
        if (a.isEmpty() || b.isEmpty()) return false

        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
