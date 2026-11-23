package com.soheil.ember

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Reads the shared apps catalogue. Updates are announced only; the user always chooses whether to install. */
internal object ReleaseUpdateChecker {
    const val RELEASE_PAGE = "https://app.agseyl.ir/apps/ember/"
    private const val FEED_URL = "https://app.agseyl.ir/updates.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    fun check(platform: String, currentVersion: String): String? = runCatching {
        val request = Request.Builder()
            .url(FEED_URL)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string()?.takeIf { it.length <= 64 * 1024 } ?: return null
            val feed = JSONObject(text)
            if (feed.optInt("schemaVersion") != 1) return null
            val version = feed.optJSONObject("apps")
                ?.optJSONObject("ember")
                ?.optJSONObject(platform)
                ?.optString("version")
                ?.takeIf(String::isNotBlank)
                ?: return null
            version.takeIf { isNewer(it, currentVersion) }
        }
    }.getOrNull()

    private fun isNewer(candidate: String, current: String): Boolean {
        fun parts(version: String): List<Long>? {
            val values = version.split('.').map { it.toLongOrNull() ?: return null }
            return values.takeIf { it.isNotEmpty() }
        }
        val newerParts = parts(candidate) ?: return false
        val currentParts = parts(current) ?: return false
        for (index in 0 until maxOf(newerParts.size, currentParts.size)) {
            val newer = newerParts.getOrElse(index) { 0L }
            val installed = currentParts.getOrElse(index) { 0L }
            if (newer != installed) return newer > installed
        }
        return false
    }
}
