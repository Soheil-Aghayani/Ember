package ember.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateCheck {
    const val RELEASES_URL = "https://github.com/Soheil-Aghayani/Ember/releases"

    suspend fun latestVersion(): String? = withContext(Dispatchers.IO) {
        val current = version(System.getProperty("jpackage.app-version") ?: "") ?: return@withContext null
        runCatching {
            val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
            val request = Request.Builder()
                .url("https://api.github.com/repos/Soheil-Aghayani/Ember/releases/latest")
                .header("Accept", "application/vnd.github+json").header("User-Agent", "Ember").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: return@use null)
                val candidate = version(json.optString("tag_name")) ?: version(json.optString("name"))
                    ?: return@use null
                val newer = (0 until maxOf(current.size, candidate.size)).firstOrNull {
                    candidate.getOrElse(it) { 0 } != current.getOrElse(it) { 0 }
                }?.let { candidate.getOrElse(it) { 0 } > current.getOrElse(it) { 0 } } ?: false
                if (newer) candidate.joinToString(".") else null
            }
        }.getOrNull()
    }

    private fun version(value: String): List<Long>? {
        val match = Regex("(?<![0-9])([0-9]+(?:\\.[0-9]+)+)").find(value) ?: return null
        return match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
    }
}
