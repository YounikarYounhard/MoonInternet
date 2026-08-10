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
    /**
     * Every category in a .dat file as "geosite:CATEGORY-RU" / "geoip:RU".
     *
     * The files are protobuf and we have no schema on the phone, so this walks the wire format
     * directly: the list is a repeated field 1, and each entry's own field 1 is the code. Same
     * reader as the desktop GeoDat.Tags — anything else in the entry is skipped by wire type.
     */
    fun tags(file: File, prefix: String): List<String> {
        val d = runCatching { file.readBytes() }.getOrNull() ?: return emptyList()
        val out = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        var i = 0
        while (i < d.size) {
            val key = readVarint(d, i) ?: break
            i = key.second
            val tag = (key.first ushr 3).toInt()
            val wire = (key.first and 7L).toInt()
            if (tag == 1 && wire == 2) {
                val len = readVarint(d, i) ?: break
                i = len.second
                val end = i + len.first.toInt()
                if (end > d.size || end < i) break
                var j = i
                while (j < end) {
                    val k2 = readVarint(d, j) ?: break
                    j = k2.second
                    val t2 = (k2.first ushr 3).toInt()
                    val w2 = (k2.first and 7L).toInt()
                    if (t2 == 1 && w2 == 2) {
                        val l2 = readVarint(d, j) ?: break
                        j = l2.second
                        if (j + l2.first.toInt() <= end) {
                            out.add(prefix + ":" + String(d, j, l2.first.toInt()).uppercase())
                        }
                        break
                    }
                    j = skip(d, j, w2) ?: break
                }
                i = end
            } else {
                i = skip(d, i, wire) ?: break
            }
        }
        return out.toList()
    }

    /** Returns the value and the index just past it, or null when the buffer runs out. */
    private fun readVarint(d: ByteArray, at: Int): Pair<Long, Int>? {
        var v = 0L; var shift = 0; var i = at
        while (i < d.size) {
            val b = d[i].toInt() and 0xFF
            v = v or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return v to i
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    private fun skip(d: ByteArray, at: Int, wire: Int): Int? = when (wire) {
        0 -> readVarint(d, at)?.second
        1 -> (at + 8).takeIf { it <= d.size }
        2 -> readVarint(d, at)?.let { (len, next) -> (next + len.toInt()).takeIf { it <= d.size } }
        5 -> (at + 4).takeIf { it <= d.size }
        else -> null
    }

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
