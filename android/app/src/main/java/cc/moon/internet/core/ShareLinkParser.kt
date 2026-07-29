package cc.moon.internet.core

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Parses a single share link (vless://, vmess://, trojan://, ss://, hysteria2://, socks://)
 * into a [ServerProfile]. Ported from the desktop ShareLinkParser — same tolerant behaviour:
 * every result is validated before it is returned.
 */
object ShareLinkParser {

    fun parse(link: String): ServerProfile? = try {
        val s = link.trim()
        when (s.substringBefore("://").lowercase()) {
            "vless" -> parseVless(s)
            "vmess" -> parseVmess(s)
            "trojan" -> parseTrojan(s)
            "ss" -> parseShadowsocks(s)
            "hysteria2", "hy2" -> parseHysteria2(s)
            "socks", "socks5" -> parseSocks(s)
            else -> null
        }?.takeIf { it.address.isNotBlank() && it.port in 1..65535 }
    } catch (_: Exception) { null }

    // ---- vless://uuid@host:port?params#name ------------------------------
    private fun parseVless(link: String): ServerProfile {
        val u = Uri.parse(link)
        val q = { k: String -> u.getQueryParameter(k) }
        return ServerProfile(
            protocol = Protocol.VLESS,
            name = nameOf(link, u),
            raw = link,
            address = u.host.orEmpty(),
            port = u.port.takeIf { it > 0 } ?: 443,
            id = u.userInfo?.let { decode(it) },
            encryption = q("encryption") ?: "none",
            flow = q("flow"),
            network = normNetwork(q("type") ?: "tcp"),
            path = q("path"),
            host = q("host"),
            serviceName = q("serviceName"),
            security = q("security") ?: "none",
            sni = q("sni") ?: q("peer"),
            alpn = q("alpn"),
            fingerprint = q("fp"),
            allowInsecure = q("allowInsecure") == "1" || q("insecure") == "1",
            publicKey = q("pbk"),
            shortId = q("sid"),
            spiderX = q("spx"),
            headerType = q("headerType"),
            extra = extrasOf(q),
        )
    }

    // ---- vmess://base64(json) --------------------------------------------
    private fun parseVmess(link: String): ServerProfile {
        val json = JSONObject(String(b64(link.substringAfter("://"))))
        fun str(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> json.optString(k, "").takeIf { it.isNotBlank() } }
        val net = normNetwork(str("net") ?: "tcp")
        val tls = str("tls").orEmpty()
        return ServerProfile(
            protocol = Protocol.VMESS,
            name = str("ps") ?: str("remarks") ?: json.optString("add"),
            raw = link,
            address = json.optString("add"),
            port = json.optString("port").toIntOrNull() ?: 443,
            id = json.optString("id"),
            alterId = json.optString("aid").toIntOrNull() ?: 0,
            encryption = str("scy") ?: "auto",
            network = net,
            path = str("path"),
            host = str("host"),
            serviceName = if (net == "grpc") str("path") else null,
            security = if (tls.isBlank() || tls == "none") "none" else "tls",
            sni = str("sni"),
            alpn = str("alpn"),
            fingerprint = str("fp"),
        )
    }

    // ---- trojan://password@host:port?params#name --------------------------
    private fun parseTrojan(link: String): ServerProfile {
        val u = Uri.parse(link)
        val q = { k: String -> u.getQueryParameter(k) }
        return ServerProfile(
            protocol = Protocol.TROJAN,
            name = nameOf(link, u),
            raw = link,
            address = u.host.orEmpty(),
            port = u.port.takeIf { it > 0 } ?: 443,
            password = u.userInfo?.let { decode(it) },
            network = normNetwork(q("type") ?: "tcp"),
            path = q("path"),
            host = q("host"),
            serviceName = q("serviceName"),
            security = q("security") ?: "tls",
            sni = q("sni") ?: q("peer"),
            alpn = q("alpn"),
            fingerprint = q("fp"),
            allowInsecure = q("allowInsecure") == "1" || q("insecure") == "1",
            headerType = q("headerType"),
            extra = extrasOf(q),
        )
    }

    // ---- ss://base64(method:pass)@host:port#name  (also fully-base64 form) --
    private fun parseShadowsocks(link: String): ServerProfile {
        val body = link.substringAfter("://")
        val name = body.substringAfter('#', "").let { if (it.isEmpty()) "" else decode(it) }
        val core = body.substringBefore('#').substringBefore('?')

        val (userPart, hostPart) = if (core.contains('@')) {
            core.substringBeforeLast('@') to core.substringAfterLast('@')
        } else {
            // whole thing is base64: method:pass@host:port
            val decoded = String(b64(core))
            decoded.substringBeforeLast('@') to decoded.substringAfterLast('@')
        }
        val creds = if (userPart.contains(':')) userPart else String(b64(userPart))
        return ServerProfile(
            protocol = Protocol.SHADOWSOCKS,
            name = name.ifBlank { hostPart },
            raw = link,
            address = hostPart.substringBeforeLast(':'),
            port = hostPart.substringAfterLast(':').toIntOrNull() ?: 443,
            method = creds.substringBefore(':'),
            password = creds.substringAfter(':'),
        )
    }

    // ---- hysteria2://password@host:port?params#name -----------------------
    private fun parseHysteria2(link: String): ServerProfile {
        val u = Uri.parse(link)
        val q = { k: String -> u.getQueryParameter(k) }
        return ServerProfile(
            protocol = Protocol.HYSTERIA2,
            name = nameOf(link, u),
            raw = link,
            address = u.host.orEmpty(),
            port = u.port.takeIf { it > 0 } ?: 443,
            password = u.userInfo?.let { decode(it) },
            security = "tls",
            sni = q("sni") ?: q("peer"),
            alpn = q("alpn"),
            allowInsecure = q("insecure") == "1",
            obfs = q("obfs"),
            obfsPassword = q("obfs-password"),
        )
    }

    private fun parseSocks(link: String): ServerProfile {
        val u = Uri.parse(link)
        val info = u.userInfo?.let { if (it.contains(':')) it else String(b64(it)) }
        return ServerProfile(
            protocol = Protocol.SOCKS,
            name = nameOf(link, u),
            raw = link,
            address = u.host.orEmpty(),
            port = u.port.takeIf { it > 0 } ?: 1080,
            id = info?.substringBefore(':'),
            password = info?.substringAfter(':', ""),
        )
    }

    // ---- helpers ---------------------------------------------------------
    private fun nameOf(link: String, u: Uri): String {
        val frag = link.substringAfter('#', "")
        return if (frag.isNotBlank()) decode(frag) else u.host.orEmpty()
    }

    private fun decode(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    /** Tolerant base64: accepts the url-safe alphabet and missing padding, as share links do. */
    fun b64(s: String): ByteArray {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.DEFAULT)
    }

    private fun normNetwork(n: String) = when (n.lowercase()) {
        "h2", "http" -> "http"
        "httpupgrade" -> "httpupgrade"
        "xhttp", "splithttp" -> "xhttp"  // xray speaks this natively
        else -> n.lowercase()
    }

    /** Transport-specific query params xray reads but our model has no dedicated field for. */
    private fun extrasOf(q: (String) -> String?): Map<String, String> =
        listOf("mode", "extra").mapNotNull { k -> q(k)?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
}
