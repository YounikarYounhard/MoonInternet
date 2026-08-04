package cc.moon.internet.data

import android.util.Base64
import cc.moon.internet.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a subscription. Panels put the interesting bits in HTTP headers, exactly like the
 * desktop client reads them: profile-title, subscription-userinfo, announce, profile-update-interval.
 */
object SubscriptionService {

    data class Fetched(
        val servers: List<ServerProfile>,
        val title: String?,
        val announcement: String,
        val trafficText: String,
        val expiryText: String,
        val trafficFraction: Double,
        val expiryFraction: Double,
        val updateMinutes: Int,
        val routingLinks: List<String>,
    )

    suspend fun fetch(url: String, hwid: String? = null): Fetched = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            // Panels hand out every protocol when they think it's Happ asking
            setRequestProperty("User-Agent", "Happ/1.0")
            hwid?.let { setRequestProperty("X-HWID", it) }
        }
        try {
            val body = conn.inputStream.bufferedReader().readText()
            val parsed = SubscriptionParser.parse(body)

            fun header(name: String) = conn.headerFields.entries
                .firstOrNull { it.key?.equals(name, true) == true }?.value?.firstOrNull()

            val info = parseUserInfo(header("subscription-userinfo"))
            Fetched(
                servers = parsed.servers,
                title = decodeHeader(header("profile-title")),
                announcement = decodeHeader(header("announce")) ?: parsed.announcement,
                trafficText = info.traffic,
                expiryText = info.expiry,
                trafficFraction = info.trafficFraction,
                expiryFraction = info.expiryFraction,
                updateMinutes = header("profile-update-interval")?.toIntOrNull()?.times(60) ?: 0,
                routingLinks = SubscriptionParser.routingLinks(body) +
                    listOfNotNull(header("routing")),
            )
        } finally { conn.disconnect() }
    }

    /** Panels encode text headers as "base64:<b64>" (sometimes plain). */
    private fun decodeHeader(raw: String?): String? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        val prefix = "base64:"
        if (!v.startsWith(prefix, true)) return v
        return runCatching {
            String(Base64.decode(v.substring(prefix.length).trim(), Base64.DEFAULT)).trim()
        }.getOrNull()
    }

    data class UserInfo(
        val traffic: String, val expiry: String,
        val trafficFraction: Double, val expiryFraction: Double,
    )

    /** "upload=…; download=…; total=…; expire=…" → text plus the two fractions the meters need. */
    private fun parseUserInfo(raw: String?): UserInfo {
        if (raw.isNullOrBlank()) return UserInfo("—", "∞", -1.0, -1.0)
        val map = raw.split(';').mapNotNull {
            val p = it.trim().split('=', limit = 2)
            if (p.size == 2) p[0].trim().lowercase() to (p[1].trim().toLongOrNull() ?: 0L) else null
        }.toMap()

        val used = (map["upload"] ?: 0L) + (map["download"] ?: 0L)
        val total = map["total"] ?: 0L
        val traffic = if (total > 0) "${size(used)} / ${size(total)}" else "${size(used)} / ∞"

        val expire = map["expire"] ?: 0L
        val expiry = if (expire > 0) {
            val d = java.util.Date(expire * 1000)
            java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.getDefault()).format(d)
        } else "∞"

        val trafficFraction = if (total > 0) (used.toDouble() / total).coerceIn(0.0, 1.0) else -1.0
        // A month is the plan length nearly every panel sells, so it is the scale a bar is read
        // against; anything longer just shows full until it is inside the last thirty days.
        val expiryFraction = if (expire > 0) {
            val daysLeft = (expire * 1000 - System.currentTimeMillis()) / 86_400_000.0
            (1 - daysLeft / 30.0).coerceIn(0.0, 1.0)
        } else -1.0
        return UserInfo(traffic, expiry, trafficFraction, expiryFraction)
    }

    fun size(b: Long): String = when {
        b < 1024 -> "$b Б"
        b < 1024L * 1024 -> String.format("%.1f КБ", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format("%.1f МБ", b / 1048576.0)
        b < 1024L * 1024 * 1024 * 1024 -> String.format("%.2f ГБ", b / 1073741824.0)
        else -> String.format("%.2f ТБ", b / 1099511627776.0)
    }

    /** Speed in bits, like a speed test shows it. */
    fun speed(bytesPerSec: Long): String {
        val bits = bytesPerSec * 8.0
        return when {
            bits < 1000 -> String.format("%.0f бит/с", bits)
            bits < 1_000_000 -> String.format("%.1f Кбит/с", bits / 1000)
            bits < 1_000_000_000 -> String.format("%.1f Мбит/с", bits / 1_000_000)
            else -> String.format("%.2f Гбит/с", bits / 1_000_000_000)
        }
    }
}
