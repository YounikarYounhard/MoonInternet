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

    /**
     * Запрет mode: the tunnel carries everything into byedpi, which is listening on loopback.
     *
     * There is no server here and no routing — byedpi does not send the traffic anywhere, it edits
     * the handshake on its way out. xray is doing one job only, turning the tunnel into SOCKS, and
     * that is why this config is so much smaller than the other one.
     *
     * Sniffing is deliberately OFF, which is the opposite of what this comment used to claim.
     * byedpi cannot resolve host names on Android — it answers a SOCKS request carrying a domain
     * with «not resolved», flat — so xray must hand it the address the app was already dialling.
     * It loses nothing: the desync works on the TLS hello it sees on the wire, not on the name in
     * the SOCKS request.
     */
    fun buildZapret(mtu: Int, logLevel: String, logFile: String?): String {
        val cfg = JSONObject()
        val log = JSONObject().put("loglevel", logLevel)
        if (logLevel != "none" && !logFile.isNullOrBlank()) log.put("error", logFile)
        cfg.put("log", log)

        cfg.put("inbounds", JSONArray().put(
            JSONObject()
                .put("tag", "tun-in").put("port", 0).put("protocol", "tun")
                .put("settings", JSONObject().put("name", "moon0").put("MTU", mtu))
                // No destOverride: that is what replaced the IP with a sniffed domain and left
                // byedpi with a name it cannot resolve.
                .put("sniffing", JSONObject().put("enabled", false))
        ))

        cfg.put("outbounds", JSONArray()
            .put(JSONObject().put("tag", "proxy").put("protocol", "socks")
                .put("settings", JSONObject().put("servers", JSONArray().put(
                    JSONObject().put("address", "127.0.0.1").put("port", ZapretStrategies.PORT)))))
            // byedpi speaks TCP only. UDP has nowhere to go, and letting it out direct is better
            // than a stall: QUIC then fails over to TLS, which is the path byedpi can actually help.
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))

        cfg.put("routing", JSONObject().put("rules", JSONArray()
            .put(JSONObject().put("type", "field").put("network", "udp").put("outboundTag", "direct"))))

        // Counters. Left out at first, and then the speed and traffic readouts sat at zero for the
        // whole session — xray does not count anything unless it is asked to.
        cfg.put("stats", JSONObject())
        cfg.put("policy", JSONObject().put("system", JSONObject()
            .put("statsOutboundUplink", true)
            .put("statsOutboundDownlink", true)))

        return cfg.toString(2)
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
        /**
         * Addresses [server] resolves to, when the caller already looked them up. The direct rule
         * below needs both forms: with domainStrategy IPIfNonMatch xray resolves an unmatched
         * domain and re-checks the IP rules, so a domain-only rule missed anything else hosted on
         * the same machine — the panel on the server you were connected through went out through
         * that server and looped straight back to it.
         */
        serverIps: List<String> = emptyList(),
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
        cfg.put("routing", buildRouting(routing, server, hasGeoFiles, blockUdp, serverIps))
        cfg.put("stats", JSONObject())
        val policy = JSONObject().put("system", JSONObject()
            .put("statsOutboundUplink", true)
            .put("statsOutboundDownlink", true))
        // Level 0 is what ordinary traffic runs at, so this is where the priority mode bites:
        // a smaller buffer stops a bulk transfer running ahead of everything else.
        //
        // The timeouts are spelled out rather than left to the defaults, because they decide how
        // long a session the far side has to keep alive. Without them a connection the phone has
        // finished with lingers for xray's default five minutes, and on a server carrying a whole
        // subscription those add up into real memory. These are the figures v2rayNG and INCY ship.
        // Left to xray's own defaults — see the desktop builder. Forcing connIdle 120 with
        // uplinkOnly and downlinkOnly at one second held fewer connections in theory and caused
        // churn in practice; the transport health checks are the right tool for a dead connection.
        val level0 = JSONObject()
        bufferSizeKb(trafficPriority)?.let { kb -> level0.put("bufferSize", kb) }
        if (level0.length() > 0) policy.put("levels", JSONObject().put("0", level0))
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
        val o = JSONObject().put("tag", tag).put("protocol", protoName(s))
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

            // Hysteria2 no longer reaches xray as itself. Its implementation there does not survive
            // Russian filtering — XTLS/Xray-core#6717, closed as "not planned", with sing-box working
            // on the very same endpoints — so sing-box carries it and this outbound is a SOCKS dial
            // into it on loopback, exactly as AmneziaWG is carried by awgproxy below.
            Protocol.HYSTERIA2 -> servers(JSONObject()
                .put("address", "127.0.0.1")
                .put("port", cc.moon.internet.vpn.SingBoxRunner.PORT))

            // Plain WireGuard is one of xray's own protocols, so it goes out as itself. Only the
            // obfuscated kind needs awgproxy — that is what awgproxy is for — and then this
            // outbound is a SOCKS dial into it on loopback, which hands it the whole routing table
            // without any of it being written a second time.
            Protocol.WIREGUARD -> s.wg?.takeIf { !it.isAmnezia }?.let { wg ->
                JSONObject()
                    .put("secretKey", wg.privateKey)
                    .put("address", JSONArray().apply { wg.address.forEach(::put) })
                    .put("mtu", if (wg.mtu > 0) wg.mtu else 1420)
                    .put("peers", JSONArray().put(JSONObject()
                        .put("publicKey", wg.peerPublicKey)
                        .put("endpoint", wg.endpoint)
                        .put("keepAlive", wg.persistentKeepalive)
                        .put("allowedIPs", JSONArray().apply { wg.allowedIps.forEach(::put) })
                        .apply { wg.presharedKey?.takeIf { it.isNotBlank() }?.let { put("preSharedKey", it) } }))
            } ?: JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", "127.0.0.1")
                    .put("port", cc.moon.internet.vpn.AwgRunner.PORT)))

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
        // Transports the core no longer has. xray 26 dropped the HTTP/2 and QUIC transports and
        // refuses the whole config over the name — so a server on one of them does not merely lose
        // its camouflage, it never dials at all. Both were folded into XHTTP's "stream-one" mode,
        // over h2 and h3 respectively, which is what the same server speaks once its own side is
        // updated. Better a config that loads and may connect than one the core throws out.
        var migratedAlpn: String? = null
        val net = when {
            // Hysteria2 leaves as a plain SOCKS dial into sing-box, so it carries no transport and
            // no TLS of its own — both belong to sing-box now, not to this config.
            s.protocol == Protocol.HYSTERIA2 -> "tcp"
            s.network == "h2" || s.network == "http" -> { migratedAlpn = "h2"; "xhttp" }
            s.network == "quic" -> { migratedAlpn = "h3"; "xhttp" }
            // XHTTP under the name it shipped with — a saved profile can still carry it.
            s.network == "splithttp" -> "xhttp"
            else -> s.network
        }
        st.put("network", net)

        // REALITY rides RAW, XHTTP or gRPC and nothing else — the core says so and refuses the rest
        // outright. A link claiming otherwise is a mislabelled one (panels do emit security=reality
        // for plain TLS), so it goes out as TLS: it may work, where a rejected config cannot.
        val security = when {
            // The link says TLS because Hysteria2 always is one, but this outbound is a loopback
            // SOCKS dial — wrapping that in TLS would be dialling sing-box as if it were the server.
            s.protocol == Protocol.HYSTERIA2 -> "none"
            s.security == "reality" && net !in listOf("tcp", "raw", "xhttp", "grpc") -> "tls"
            else -> s.security
        }

        if (security == "tls" || security == "reality") {
            st.put("security", security)
            val tls = JSONObject()
            s.sni?.takeIf { it.isNotBlank() }?.let { tls.put("serverName", it) }
            s.fingerprint?.takeIf { it.isNotBlank() }?.let { tls.put("fingerprint", it) }
            s.alpn?.takeIf { it.isNotBlank() }?.let {
                tls.put("alpn", JSONArray().apply {
                    it.split(',').map(String::trim).filter(String::isNotEmpty).forEach(::put)
                })
            }
            // ...or the version the dropped transport was, when it was migrated above.
            if (!tls.has("alpn")) migratedAlpn?.let { tls.put("alpn", JSONArray().put(it)) }
            if (security != "reality") {
                tls.put("allowInsecure", s.allowInsecure)
                if (!tls.has("serverName")) tls.put("serverName", s.address)
                st.put("tlsSettings", tls)
            } else {
                // Both spellings of the one key. xray renamed REALITY's publicKey to password —
                // `xray x25519` prints it as "Password (PublicKey)" — and the cores are split over
                // it: the desktop's 26.3 still reads publicKey, the phone's 26.1 reads only
                // password and throws the whole config out with `empty "password"`. That is why
                // every REALITY server here refused to connect while the same link worked on the
                // computer. An unknown key is ignored, so sending both suits either core.
                s.publicKey?.takeIf { it.isNotBlank() }?.let {
                    tls.put("publicKey", it)
                    tls.put("password", it)
                }
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
            // See the desktop builder for why the health check matters: without it a dead gRPC
            // connection is never noticed, the client opens another, and the server keeps the
            // half-open stream until it runs out of memory.
            "grpc" -> st.put("grpcSettings", JSONObject()
                .put("serviceName", s.serviceName.orEmpty())
                .put("multiMode", s.extra["mode"] == "multi")
                .put("idle_timeout", 60)
                .put("health_check_timeout", 20)
                .put("permit_without_stream", false))

            "xhttp" -> st.put("xhttpSettings", JSONObject()
                .put("path", s.path ?: "/")
                .apply {
                    // One host, not a list: the HTTP/2 transport took several and XHTTP takes one,
                    // so a link migrated from it keeps the first.
                    s.host?.takeIf { it.isNotBlank() }?.let { put("host", it.split(',')[0].trim()) }
                    // A transport that used to be h2 or QUIC is a single stream, not a split one.
                    val mode = s.extra["mode"]?.takeIf { it.isNotBlank() }
                        ?: migratedAlpn?.let { "stream-one" }
                    mode?.let { put("mode", it) }
                    s.extra["extra"]?.takeIf { it.isNotBlank() }?.let {
                        runCatching { put("extra", JSONObject(it)) }   // malformed extra is dropped, not fatal
                    }
                })

            // Parsed since forever and never built: the link said type=httpupgrade, xray got a
            // transport name with nothing behind it and fell back to path "/", so a server
            // expecting its own path refused every connection.
            "httpupgrade" -> st.put("httpupgradeSettings", JSONObject()
                .put("path", s.path ?: "/")
                .apply { s.host?.takeIf { it.isNotBlank() }?.let { put("host", it) } })

            // mKCP: UDP carrying its own retransmission, which is why it holds up on a link that
            // makes TCP crawl. The seed is a shared secret — without it the obfuscation header
            // does not match and the server drops everything.
            //
            // Everything below the header is written out even at its default value, on purpose.
            // These are the knobs mKCP IS: uplinkCapacity and downlinkCapacity size the congestion
            // window in MB/s, so leaving them implicit means a fast link is shaped by a number
            // nobody chose and nobody can see. INCY and v2RayTun both send the whole set — that
            // was the gap here, not the ability to connect. Same figures as the desktop builder.
            "kcp" -> st.put("kcpSettings", JSONObject()
                .put("mtu", kcpInt(s, "mtu", 1350, 576, 1460))
                .put("tti", kcpInt(s, "tti", 50, 10, 100))
                .put("uplinkCapacity", kcpInt(s, "uplinkCapacity", 5, 0, 1000))
                .put("downlinkCapacity", kcpInt(s, "downlinkCapacity", 20, 0, 1000))
                // ON, where xray's own default is off. Without congestion control mKCP does not
                // back off when packets start dropping — it retransmits harder, which is what makes
                // it quick on a merely slow link and what makes it stutter on a variable one, i.e.
                // on a phone. A link that sets congestion=0 gets the old behaviour back.
                .put("congestion", s.extra["congestion"] !in listOf("0", "false"))
                .put("readBufferSize", kcpInt(s, "readBufferSize", 2, 1, 128))
                .put("writeBufferSize", kcpInt(s, "writeBufferSize", 2, 1, 128))
                // The obfuscation header moved. Up to xray 25 it was kcpSettings.header.type plus
                // a seed; the cores we ship now refuse that config outright — "the feature mkcp
                // header & seed has been removed and migrated to finalmask/udp header-* &
                // mkcp-original & mkcp-aes128gcm". A link written for the old shape is translated,
                // and the names differ: wechat-video is spelled wechat now.
                .apply { kcpFinalMask(s.headerType)?.let { put("finalmask", it) } })

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
        // The proxy's own hostname has to be resolved before the tunnel can exist. Left to the
        // ordinary resolvers, that query is itself sent through the proxy — which needs the very
        // hostname being looked up. xray gives up on the circle with
        //   "lookup <host>: operation was canceled"
        // and the server never connects. So it gets its own plain resolver, reached directly
        // (see the matching direct rule in buildRouting), and only for that one name.
        if (!isIpLiteral(server.address)) {
            servers.put(
                JSONObject()
                    .put("address", bootstrapDns(routing))
                    .put("domains", JSONArray().put("full:${server.address}"))
                    .put("skipFallback", true)
            )
        }
        dnsList.filter { it.isNotBlank() }.forEach(servers::put)
        if (servers.length() == 0) {
            // The profile's resolver, written the way its type says to reach it — a DoH pick that
            // still went out as plain UDP would be a setting that only looks like it does something.
            val remote = routing?.let { dnsUri(it.remoteDnsType, it.remoteDns) }.orEmpty()
            servers.put(remote.ifBlank { fallback })
        }
        servers.put("localhost")
        val dns = JSONObject().put("servers", servers)
        return dns
    }

    /**
     * The resolver used to look up the proxy's own hostname, before there is a tunnel. It has to
     * be a plain address we can also send straight out, so a DoH/DoT profile setting is no good
     * here — the domestic resolver if it is an IP, otherwise a public one.
     */
    private fun bootstrapDns(routing: RoutingProfile?): String =
        routing?.domesticDns?.trim()?.takeIf { isIpLiteral(it) } ?: "8.8.8.8"

    /**
     * The share link's headerType as current xray spells it, or null for no masking. Vocabulary
     * read out of the shipped binary, not from memory.
     *
     * ponytail: the seed half has no mapping. It became mkcp-original / mkcp-aes128gcm, and xray
     * accepts ANY string in finalmask without checking it — so a guess at that grammar would load
     * happily and carry nothing, which is worse than admitting we do not know it. Left out until
     * it can be confirmed against a real mKCP server.
     */
    private fun kcpFinalMask(headerType: String?): String? = when (headerType) {
        null, "", "none" -> null
        "wechat-video", "wechat" -> "udp header-wechat"
        "srtp", "utp", "dtls", "wireguard", "dns", "custom" -> "udp header-$headerType"
        else -> null
    }

    /** An mKCP knob from the link when a panel sends one, otherwise xray's documented default. */
    private fun kcpInt(s: ServerProfile, key: String, fallback: Int, lo: Int, hi: Int): Int =
        s.extra[key]?.toIntOrNull()?.takeIf { it in lo..hi } ?: fallback

    private fun buildRouting(
        routing: RoutingProfile?, server: ServerProfile, geo: Boolean, blockUdp: Boolean = false,
        serverIps: List<String> = emptyList(),
    ): JSONObject {
        val rules = JSONArray()
        // QUIC and torrents out of the way before anything else looks at the traffic
        if (blockUdp) {
            rules.put(JSONObject().put("type", "field").put("outboundTag", "block")
                .put("network", "udp"))
        }

        // 1. anything aimed at the proxy server goes out directly — never back into the tunnel
        if (!isIpLiteral(server.address)) {
            rules.put(JSONObject().put("type", "field").put("outboundTag", "direct")
                .put("domain", JSONArray().put("full:${server.address}")))
        }
        val directIps = (serverIps + listOfNotNull(server.address.takeIf { isIpLiteral(it) }))
            .filter { it.isNotBlank() }.distinct()
        if (directIps.isNotEmpty()) {
            rules.put(JSONObject().put("type", "field").put("outboundTag", "direct")
                .put("ip", JSONArray().apply { directIps.forEach(::put) }))
        }
        // 1b. and so does the resolver that answers for it, or the lookup goes through the tunnel
        // we are still trying to build
        if (!isIpLiteral(server.address)) {
            rules.put(JSONObject().put("type", "field").put("outboundTag", "direct")
                .put("ip", JSONArray().put(bootstrapDns(routing))))
        }
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

    private fun protoName(s: ServerProfile) = when (s.protocol) {
        Protocol.VLESS -> "vless"
        Protocol.VMESS -> "vmess"
        Protocol.TROJAN -> "trojan"
        Protocol.SHADOWSOCKS -> "shadowsocks"
        Protocol.SOCKS -> "socks"
        // Not "hysteria": sing-box holds that tunnel now and this outbound only dials into it.
        Protocol.HYSTERIA2 -> "socks"
        // Per server, not per protocol: xray speaks WireGuard itself, and only the obfuscated kind
        // goes out as a SOCKS dial into awgproxy.
        Protocol.WIREGUARD -> if (s.wg?.isAmnezia == true) "socks" else "wireguard"
        else -> s.protocol.name.lowercase()
    }

    /** Protocols the xray core can actually dial — the rest are hidden from the picker. */
    // WireGuard is on the list because AmneziaWG now has an engine: awgproxy carries the tunnel
    // and xray dials it as a plain SOCKS outbound, so from here it is just another server.
    fun supports(p: Protocol) = p in setOf(
        Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS,
        Protocol.SOCKS, Protocol.HYSTERIA2, Protocol.WIREGUARD,
    )
}
