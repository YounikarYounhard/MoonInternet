package cc.moon.internet.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** What the newest GitHub release says, once we have asked it. */
data class ReleaseInfo(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
)

/**
 * Checks GitHub for a newer build — the Android half of the desktop UpdateService, same repo and
 * the same rules.
 *
 * Anonymous: GitHub allows 60 calls an hour per IP and we make one per launch, so no token is
 * needed and none is shipped.
 */
object UpdateService {

    // Not /releases/latest — that endpoint skips pre-releases, and everything we publish is tagged
    // beta, so it answers 404 and the check looks like a network failure.
    private const val API = "https://api.github.com/repos/YounikarYounhard/MoonInternet/releases?per_page=5"

    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL(API).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "MoonInternet")   // GitHub rejects a missing UA
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 12_000
                readTimeout = 12_000
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()

            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (r.optBoolean("draft")) continue

                var apk: String? = null
                val assets = r.optJSONArray("assets")
                if (assets != null) {
                    for (j in 0 until assets.length()) {
                        val a = assets.getJSONObject(j)
                        if (a.optString("name").endsWith(".apk", true)) {
                            apk = a.optString("browser_download_url"); break
                        }
                    }
                }
                return@runCatching ReleaseInfo(
                    version = normalize(r.optString("tag_name")),
                    notes = r.optString("body").trim(),
                    pageUrl = r.optString("html_url"),
                    apkUrl = apk,
                )
            }
            null
        }.getOrNull()
    }

    /**
     * True when [latest] is newer than [current], comparing the numeric parts only.
     *
     * Our own builds carry a fourth number that grows with each build (0.9.1.7) while a release is
     * three (0.9.1), so a local build of the version being prepared must not report an update.
     */
    fun isNewer(latest: String, current: String): Boolean {
        val l = numbers(latest)
        val c = numbers(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** "v0.9.1-beta" → "0.9.1". */
    fun normalize(tag: String): String =
        tag.trim().trimStart('v', 'V').takeWhile { it.isDigit() || it == '.' }.trim('.')

    private fun numbers(v: String) =
        normalize(v).split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
}
