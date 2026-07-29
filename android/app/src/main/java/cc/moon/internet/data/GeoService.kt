package cc.moon.internet.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * geoip.dat / geosite.dat for xray's routing rules.
 *
 * The URLs come from the routing profile itself (INCY ships them in its deep-link), so the phone
 * pulls exactly the same rule data the desktop does. Without these files any `geosite:`/`geoip:`
 * rule is a hard config error, not a skipped rule — [ready] is what decides whether those rules
 * are allowed into the generated config at all.
 */
object GeoService {

    /** Fallback sources, used when the profile carries no URLs of its own. */
    private const val DEFAULT_GEOIP =
        "https://raw.githubusercontent.com/runetfreedom/russia-blocked-geoip/release/geoip.dat"
    private const val DEFAULT_GEOSITE =
        "https://raw.githubusercontent.com/runetfreedom/russia-blocked-geosite/release/geosite.dat"

    fun dir(ctx: Context): File = File(ctx.filesDir, "assets").apply { mkdirs() }
    fun geoip(ctx: Context) = File(dir(ctx), "geoip.dat")
    fun geosite(ctx: Context) = File(dir(ctx), "geosite.dat")

    fun ready(ctx: Context) = geoip(ctx).length() > 0 && geosite(ctx).length() > 0

    fun info(f: File): String =
        if (f.length() <= 0) "не загружен"
        else "%.1f МБ".format(f.length() / 1048576.0)

    /**
     * Downloads whatever is missing. [force] re-downloads even if the file is already there.
     * Returns a human-readable result for the status line.
     */
    suspend fun refresh(
        ctx: Context,
        geoipUrl: String = "",
        geositeUrl: String = "",
        force: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val jobs = listOf(
            Triple(geoip(ctx), geoipUrl.ifBlank { DEFAULT_GEOIP }, "geoip.dat"),
            Triple(geosite(ctx), geositeUrl.ifBlank { DEFAULT_GEOSITE }, "geosite.dat"),
        )
        val done = mutableListOf<String>()
        for ((file, url, name) in jobs) {
            if (!force && file.length() > 0) continue
            runCatching { download(url, file) }
                .onSuccess { done += name }
                .onFailure { return@withContext "Не удалось скачать $name: ${it.message}" }
        }
        when {
            done.isEmpty() -> "Гео-файлы уже загружены"
            else -> "Загружено: ${done.joinToString(", ")}"
        }
    }

    /** Writes to a temp file first — a half-downloaded .dat would break every later connect. */
    private fun download(url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".part")
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 60000; instanceFollowRedirects = true
        }.use { conn ->
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use(input::copyTo) }
        }
        if (tmp.length() <= 0) { tmp.delete(); error("пустой файл") }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) { tmp.delete(); error("не удалось сохранить") }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }
}
