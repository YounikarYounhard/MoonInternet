package cc.moon.internet.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A sing-box config that is nothing but "Hysteria2 behind a local SOCKS".
 *
 * The phone runs everything on xray, which is the only core here that speaks XHTTP — but its
 * Hysteria2 is its own implementation and it does not survive Russian filtering: XTLS/Xray-core#6717,
 * closed as "not planned", with sing-box working on the very same endpoints. So Hysteria2 alone gets
 * the engine the desktop has always used for it, and xray dials into it as an ordinary SOCKS
 * outbound — exactly what already happens for AmneziaWG through awgproxy.
 *
 * The same shape as the desktop's SingBoxProxyConfig, deliberately: one protocol, two builders, and
 * a difference between them would show up as "it works on the computer" all over again.
 */
object SingBoxHy2Config {

    fun build(s: ServerProfile, port: Int): String {
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", s.sni?.takeIf { it.isNotBlank() } ?: s.address)
            .put("insecure", s.allowInsecure)
        s.alpn?.takeIf { it.isNotBlank() }?.let {
            tls.put("alpn", JSONArray().apply {
                it.split(',').map(String::trim).filter(String::isNotEmpty).forEach(::put)
            })
        }

        val proxy = JSONObject()
            .put("type", "hysteria2")
            .put("tag", "proxy")
            .put("server", s.address)
            .put("server_port", s.port)
            .put("password", s.password.orEmpty())
            .put("tls", tls)

        // Salamander is Hysteria2's only obfuscation; send it only when the link carried one.
        if (!s.obfs.isNullOrBlank() || !s.obfsPassword.isNullOrBlank()) {
            proxy.put("obfs", JSONObject().put("type", "salamander").put("password", s.obfsPassword.orEmpty()))
        }
        // Bandwidth hints drive Hysteria's congestion control, which is most of why it is worth
        // having. xray's implementation has nowhere to put them, so on the phone they were dropped.
        mbps(s, "up", "upmbps")?.let { proxy.put("up_mbps", it) }
        mbps(s, "down", "downmbps")?.let { proxy.put("down_mbps", it) }

        // Android has no /etc/resolv.conf, so sing-box falls back to asking localhost and every
        // lookup comes back "connection refused" — the tunnel comes up and carries nothing. Name
        // a resolver, and keep it out of the proxy: resolving the server's own hostname through
        // the tunnel to that server cannot work.
        val dns = JSONObject().put("servers", JSONArray()
            .put(JSONObject().put("tag", "dns-direct").put("address", "1.1.1.1").put("detour", "direct"))
            .put(JSONObject().put("tag", "dns-backup").put("address", "8.8.8.8").put("detour", "direct")))

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(
                JSONObject()
                    .put("type", "mixed")
                    .put("tag", "in")
                    .put("listen", "127.0.0.1")
                    .put("listen_port", port)
            ))
            .put("outbounds", JSONArray()
                .put(proxy)
                .put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject().put("final", "proxy"))
            .toString(2)
    }

    private fun mbps(s: ServerProfile, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { s.extra[it]?.toIntOrNull() }?.takeIf { it > 0 }
}
