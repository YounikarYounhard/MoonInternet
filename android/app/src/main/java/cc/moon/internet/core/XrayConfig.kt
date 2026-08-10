package cc.moon.internet.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the xray-core config — a port of the desktop XrayConfigBuilder, kept deliberately
 * close to it so a server that works on Windows works here for the same reasons.
 *
 * The engine is xray, not sing-box: the phone must speak exactly what the desktop speaks,
 * including VLESS XHTTP (which sing-box has no transport for) and gRPC with an empty
 * serviceName (which the two cores interpret differently).
 *
 * Input is a TUN fd handed over by VpnService; xray's own `tun` inbound reads it, so there is
 * no separate tun2socks process.
 */
/**
 * The nearest port at or above [preferred] that we can actually bind, or [preferred] itself if
 * none of the next few are free either (let xray report it then).
 *
 * 10808/10809 are v2rayNG's defaults, so INCY, HAPP and v2RayTun all sit on them — and if one of
 * them is running, xray refuses to start at all: a busy inbound is a fatal config error, not a
 * warning. That surfaced as a bare "не удалось подключиться" with the real reason
 * (`address already in use`) only in logcat.
 *
 * 0 means the listener is switched off and is passed through untouched.
 *
 * [avoid] holds the ports handed out by earlier calls. Without it SOCKS and HTTP both probe
 * against the OS only, so with 10808 taken they would both be told 10809 is free and land on
 * the same number — a self-collision that replaces one bug with another.
 */
/** Per-connection buffer in kB for a priority mode, or null to leave xray on its own default. */
fun bufferSizeKb(trafficPriority: String): Int? = when (trafficPriority) {
    "balance" -> 256
    "games" -> 64
    else -> null
}

fun freeLocalPort(preferred: Int, listen: String = "127.0.0.1", avoid: Set<Int> = emptySet()): Int {
    if (preferred <= 0) return preferred
    for (port in preferred until preferred + 12) {
        if (port in avoid) continue
        val free = runCatching {
            java.net.ServerSocket().use {
                it.bind(java.net.InetSocketAddress(listen, port))
            }
        }.isSuccess
        if (free) return port
    }
    return preferred
}

object XrayConfig {

    /**
     * Private ranges spelled out instead of `geoip:private`. Every geo: reference makes xray open
     * geoip.dat / geosite.dat, and a missing file is a hard config error, not a skipped rule —
     * so the base config must not depend on files we may not have downloaded yet.
     */
    private val PRIVATE_CIDRS = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
        "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32",
    )

    /**
     * Proxy mode: no TUN inbound at all, only the local SOCKS/HTTP listeners. Nothing is captured
     * system-wide — apps that can be pointed at 127.0.0.1 use it, everything else stays direct.
     */
    fun buildProxyOnly(config: String): String {
        val o = JSONObject(config)
        val kept = JSONArray()
        val ins = o.getJSONArray("inbounds")
        for (i in 0 until ins.length()) {
            val one = ins.getJSONObject(i)
            if (one.optString("protocol") != "tun") kept.put(one)
        }
        return o.put("inbounds", kept).toString(2)
    }

    fun build(
        server: ServerProfile,
        routing: RoutingProfile? = null,
        dns: String = "1.1.1.1",
        mtu: Int = 1500,
        /** True once geoip.dat/geosite.dat are on disk; until then geo: rules are dropped. */
        hasGeoFiles: Boolean = false,
        sniffing: Boolean = true,
        blockUdp: Boolean = false,
        tlsFragment: Boolean = false,
        mux: Boolean = false,
        /**
         * off | balance | games — BETA, off by default. Same three modes as the desktop build.
         * See [bufferSizeKb]: what ruins a call while something downloads is the queue, not the
         * speed, so this shortens the buffer and forces mux off.
         */
        trafficPriority: String = "off",
        preferredIp: String = "auto",
        logLevel: String = "warning",
        /** Where the core should write its log, or null to leave it in logcat only. */
        logFile: String? = null,
        /** Local SOCKS/HTTP listeners, the way v2rayNG and HAPP expose them to other apps. */
        socksPort: Int = 10808,
        httpPort: Int = 10809,
        proxyUser: String = "",
        proxyPass: String = "",
        socksAuth: Boolean = false,
        httpAuth: Boolean = false,
        allowLan: Boolean = false,
        dnsList: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    ): String {
        val cfg = JSONObject()
        // The core writes to a file when we give it one; without a path it only reaches logcat,
        // which the Логи page cannot show a size for or clear. That is why that page used to be
        // decorative — the size was a hardcoded dash.
        val log = JSONObject().put("loglevel", logLevel)
        if (logLevel != "none" && !logFile.isNullOrBlank()) log.put("error", logFile)
        cfg.put("log", log)

        val sniff = JSONObject()
            .put("enabled", sniffing)
            .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
        val listen = if (allowLan) "0.0.0.0" else "127.0.0.1"

        val accounts = JSONArray()
        if (proxyUser.isNotBlank() && proxyPass.isNotBlank()) {
            accounts.put(JSONObject().put("user", proxyUser).put("pass", proxyPass))
        }

        // TUN inbound: no port, no listen — xray picks the fd up from the env var we set.
        // The SOCKS/HTTP inbounds are what "Прокси" mode hands to apps that can be pointed at
        // a local proxy; they are always up, the mode switch only decides whether TUN joins them.
        val inbounds = JSONArray().put(
            JSONObject()
                .put("tag", "tun-in")
                .put("port", 0)
                .put("protocol", "tun")
                .put("settings", JSONObject().put("name", "moon0").put("MTU", mtu))
                .put("sniffing", sniff)
        )
        if (socksPort > 0) {
            inbounds.put(JSONObject()
                .put("tag", "socks-in").put("protocol", "socks")
                .put("listen", listen).put("port", socksPort)
                .put("settings", JSONObject()
                    .put("auth", if (socksAuth && accounts.length() > 0) "password" else "noauth")
                    .put("udp", true)
                    .apply { if (socksAuth && accounts.length() > 0) put("accounts", accounts) })
                .put("sniffing", sniff))
        }
        if (httpPort > 0) {
            inbounds.put(JSONObject()
                .put("tag", "http-in").put("protocol", "http")
                .put("listen", listen).put("port", httpPort)
                .put("settings", JSONObject()
                    .apply { if (httpAuth && accounts.length() > 0) put("accounts", accounts) })
                .put("sniffing", sniff))
        }
        cfg.put("inbounds", inbounds)

        val domainStrategy = when (preferredIp) {
            "ipv4" -> "UseIPv4"
            "ipv6" -> "UseIPv6"
            else -> "UseIP"
        }
        val outbounds = JSONArray()
            // mux is forced off by the priority modes: with it on, every connection shares one
            // stream and a download blocks the game's packets outright
            .put(buildOutbound(server, mux = mux && trafficPriority == "off", fragment = tlsFragment))
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom")
                .put("settings", JSONObject().put("domainStrategy", domainStrategy)))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        if (tlsFragment) {
            // the proxy dials through this one, which chops the TLS ClientHello into pieces
            outbounds.put(JSONObject().put("tag", "fragment").put("protocol", "freedom")
                .put("settings", JSONObject().put("domainStrategy", domainStrategy)
                    .put("fragment", JSONObject()
                        .put("packets", "tlshello").put("length", "10-20").put("interval", "10-20"))))
        }
        cfg.put("outbounds", outbounds)

        cfg.put("dns", buildDns(routing, dns, server, dnsList))
        cfg.put("routing", buildRouting(routing, server, hasGeoFiles, blockUdp))
        cfg.put("stats", JSONObject())
        val policy = JSONObject().put("system", JSONObject()
            .put("statsOutboundUplink", true)
            .put("statsOutboundDownlink", true))
        // Level 0 is what ordinary traffic runs at, so this is where the priority mode bites:
        // a smaller buffer stops a bulk transfer running ahead of everything else.
        bufferSizeKb(trafficPriority)?.let { kb ->
            policy.put("levels", JSONObject().put("0", JSONObject().put("bufferSize", kb)))
        }
        cfg.put("policy", policy)
        return cfg.toString(2)
    }

    // ---- outbound --------------------------------------------------------
    fun buildOutbound(
        s: ServerProfile,
        tag: String = "proxy",
        mux: Boolean = false,
        fragment: Boolean = false,
    ): JSONObject {
        val o = JSONObject().put("tag", tag).put("protocol", protoName(s.protocol))
        o.put("settings", when (s.protocol) {
            Protocol.VLESS -> vnext(s, JSONObject()
                .put("id", s.id.orEmpty())
                .put("encryption", s.encryption?.takeIf { it.isNotBlank() } ?: "none")
                .apply { s.flow?.takeIf { it.isNotBlank() }?.let { put("flow", it) } }
                .put("level", 8))

            Protocol.VMESS -> vnext(s, JSONObject()
                .put("id", s.id.orEmpty())
                .put("alterId", s.alterId)
                .put("security", s.encryption?.takeIf { it.isNotBlank() } ?: "auto")
                .put("level", 8))

            Protocol.TROJAN -> servers(JSONObject()
                .put("address", s.address).put("port", s.port)
                .put("password", s.password.orEmpty()).put("level", 8))

            Protocol.SHADOWSOCKS -> servers(JSONObject()
                .put("address", s.address).put("port", s.port)
                .put("method", s.method.orEmpty()).put("password", s.password.orEmpty()).put("level", 8))

            Protocol.SOCKS -> servers(JSONObject()
                .put("address", s.address).put("port", s.port)
                .apply {
                    if (!s.id.isNullOrBlank() || !s.password.isNullOrBlank()) {
                        put("users", JSONArray().put(JSONObject()
                            .put("user", s.id.orEmpty()).put("pass", s.password.orEmpty()).put("level", 8)))
                    }
                })

            // Hysteria2 is a transport in xray, not a protocol: the outbound only carries the
            // endpoint, and the password lives in streamSettings.hysteriaSettings.auth below.
            Protocol.HYSTERIA2 -> JSONObject()
                .put("version", 2)
                .put("address", s.address)
                .put("port", s.port)

            else -> throw IllegalArgumentException("${s.protocolLabel} не поддерживается ядром xray")
        })

        val stream = buildStream(s)
        // route the real dial through the "fragment" freedom outbound
        if (fragment && tag == "proxy") {
            stream.put("sockopt", JSONObject().put("dialerProxy", "fragment"))
        }
        if (stream.length() > 0) o.put("streamSettings", stream)
        if (mux && tag == "proxy") {
            o.put("mux", JSONObject().put("enabled", true).put("concurrency", 8))
        }
        return o
    }

    private fun vnext(s: ServerProfile, user: JSONObject) = JSONObject().put("vnext",
        JSONArray().put(JSONObject()
            .put("address", s.address).put("port", s.port)
            .put("users", JSONArray().put(user))))

    private fun servers(server: JSONObject) = JSONObject().put("servers", JSONArray().put(server))

    // ---- stream / transport / security -----------------------------------
    private fun buildStream(s: ServerProfile): JSONObject {
        val st = JSONObject()
        // Hysteria2 always rides its own QUIC transport with TLS; the share link's "type" is
        // meaningless for it.
        val net = when {
            s.protocol == Protocol.HYSTERIA2 -> "hysteria"
            s.network == "h2" -> "http"
            else -> s.network
        }
        st.put("network", net)

        if (net == "hysteria") {
            st.put("hysteriaSettings", JSONObject()
                .put("version", 2)
                .put("auth", s.password.orEmpty())
                .apply {
                    if (!s.obfsPassword.isNullOrBlank()) {
                        put("obfs", JSONObject().put("type", "salamander").put("password", s.obfsPassword))
                    }
                })
        }

        if (s.security == "tls" || s.security == "reality" || net == "hysteria") {
            st.put("security", if (net == "hysteria") "tls" else s.security)
            val tls = JSONObject()
            s.sni?.takeIf { it.isNotBlank() }?.let { tls.put("serverName", it) }
            s.fingerprint?.takeIf { it.isNotBlank() }?.let { tls.put("fingerprint", it) }
            s.alpn?.takeIf { it.isNotBlank() }?.let {
                tls.put("alpn", JSONArray().apply {
                    it.split(',').map(String::trim).filter(String::isNotEmpty).forEach(::put)
                })
            }
            if (s.security != "reality") {
                tls.put("allowInsecure", s.allowInsecure)
                if (!tls.has("serverName")) tls.put("serverName", s.address)
                st.put("tlsSettings", tls)
            } else {
                s.publicKey?.takeIf { it.isNotBlank() }?.let { tls.put("publicKey", it) }
                s.shortId?.let { tls.put("shortId", it) }
                s.spiderX?.takeIf { it.isNotBlank() }?.let { tls.put("spiderX", it) }
                st.put("realitySettings", tls)
            }
        }

        when (net) {
            "ws" -> st.put("wsSettings", JSONObject()
                .put("path", s.path ?: "/")
                .apply { s.host?.takeIf { it.isNotBlank() }?.let { put("host", it) } })

            // serviceName stays as-is, empty included — that is what the desktop sends and what
            // these servers expect; "fixing" it to a default breaks them.
            "grpc" -> st.put("grpcSettings", JSONObject()
                .put("serviceName", s.serviceName.orEmpty())
                .put("multiMode", s.extra["mode"] == "multi"))

            "http" -> st.put("httpSettings", JSONObject()
                .put("path", s.path ?: "/")
                .apply {
                    s.host?.takeIf { it.isNotBlank() }?.let {
                        put("host", JSONArray().apply {
                            it.split(',').map(String::trim).filter(String::isNotEmpty).forEach(::put)
                        })
                    }
                })

            "xhttp" -> st.put("xhttpSettings", JSONObject()
                .put("path", s.path ?: "/")
                .apply {
                    s.host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
                    s.extra["mode"]?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
                    s.extra["extra"]?.takeIf { it.isNotBlank() }?.let {
                        runCatching { put("extra", JSONObject(it)) }   // malformed extra is dropped, not fatal
                    }
                })

            "tcp" -> if (s.headerType == "http") {
                st.put("tcpSettings", JSONObject()
                    .put("header", JSONObject().put("type", "http")))
            }
        }
        return st
    }

    // ---- dns / routing ----------------------------------------------------
    private fun buildDns(
        routing: RoutingProfile?, fallback: String, server: ServerProfile, dnsList: List<String>,
    ): JSONObject {
        val servers = JSONArray()
        dnsList.filter { it.isNotBlank() }.forEach(servers::put)
        if (servers.length() == 0) {
            // The profile's resolver, written the way its type says to reach it — a DoH pick that
            // still went out as plain UDP would be a setting that only looks like it does something.
            val remote = routing?.let { dnsUri(it.remoteDnsType, it.remoteDns) }.orEmpty()
            servers.put(remote.ifBlank { fallback })
        }
        servers.put("localhost")
        val dns = JSONObject().put("servers", servers)
        // The proxy's own hostname must resolve through the system, or connecting needs DNS
        // and DNS needs the connection.
        if (!isIpLiteral(server.address)) {
            dns.put("hosts", JSONObject())   // placeholder, real bypass is the routing rule below
        }
        return dns
    }

    private fun buildRouting(
        routing: RoutingProfile?, server: ServerProfile, geo: Boolean, blockUdp: Boolean = false,
    ): JSONObject {
        val rules = JSONArray()
        // QUIC and torrents out of the way before anything else looks at the traffic
        if (blockUdp) {
            rules.put(JSONObject().put("type", "field").put("outboundTag", "block")
                .put("network", "udp"))
        }

        // 1. anything aimed at the proxy server goes out directly — never back into the tunnel
        rules.put(JSONObject().put("type", "field").put("outboundTag", "direct").apply {
            if (isIpLiteral(server.address)) put("ip", JSONArray().put(server.address))
            else put("domain", JSONArray().put("full:${server.address}"))
        })
        // 2. private ranges stay local
        rules.put(JSONObject().put("type", "field").put("outboundTag", "direct")
            .put("ip", JSONArray().apply { PRIVATE_CIDRS.forEach(::put) }))

        routing?.let { r ->
            bucket(r.blockSites, r.blockIp, "block", geo)?.let(rules::put)
            bucket(r.proxySites, r.proxyIp, "proxy", geo)?.let(rules::put)
            bucket(r.directSites, r.directIp, "direct", geo)?.let(rules::put)
        }

        return JSONObject()
            .put("domainStrategy", routing?.domainStrategy?.takeIf { it.isNotBlank() } ?: "IPIfNonMatch")
            .put("rules", rules)
    }

    private fun bucket(sites: List<String>, ips: List<String>, outbound: String, geo: Boolean): JSONObject? {
        // A geo: entry with no .dat file on disk aborts the whole config, so drop those rather
        // than let one unavailable rule take the connection down with it.
        val keep = { v: String -> geo || !v.startsWith("geosite:", true) && !v.startsWith("geoip:", true) }
        val d = JSONArray().apply { sites.map(String::trim).filter { it.isNotEmpty() && keep(it) }.forEach(::put) }
        val i = JSONArray().apply { ips.map(String::trim).filter { it.isNotEmpty() && keep(it) }.forEach(::put) }
        if (d.length() == 0 && i.length() == 0) return null
        return JSONObject().put("type", "field").put("outboundTag", outbound).apply {
            if (d.length() > 0) put("domain", d)
            if (i.length() > 0) put("ip", i)
        }
    }

    internal fun isIpLiteral(host: String): Boolean =
        host.contains(':') || (host.isNotEmpty() && host.all { it.isDigit() || it == '.' })

    private fun protoName(p: Protocol) = when (p) {
        Protocol.VLESS -> "vless"
        Protocol.VMESS -> "vmess"
        Protocol.TROJAN -> "trojan"
        Protocol.SHADOWSOCKS -> "shadowsocks"
        Protocol.SOCKS -> "socks"
        Protocol.HYSTERIA2 -> "hysteria"   // xray names it without the version
        else -> p.name.lowercase()
    }

    /** Protocols the xray core can actually dial — the rest are hidden from the picker. */
    fun supports(p: Protocol) = p in setOf(
        Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS,
        Protocol.SOCKS, Protocol.HYSTERIA2,
    )
}
