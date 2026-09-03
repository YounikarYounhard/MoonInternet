package cc.moon.internet

import cc.moon.internet.core.RoutingProfile
import cc.moon.internet.core.ServerProfile
import cc.moon.internet.core.XrayConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the core is actually handed, checked rather than eyeballed.
 *
 * Every assertion here stands for a connection that once broke: unmatched traffic falling to the
 * wrong outbound, the proxy's own hostname resolved through the proxy, geo rules referencing files
 * that are not on disk, sessions held open for five minutes. A routing change that breaks any of
 * them fails here instead of on somebody's phone.
 */
class ConfigDumpTest {

    private fun server(address: String = "example.org") = ServerProfile(
        raw = "vless://x@$address:443",
        name = "Пример",
        protocol = cc.moon.internet.core.Protocol.VLESS,
        address = address,
        port = 443,
        id = "00000000-0000-0000-0000-000000000000",
        security = "tls",
        network = "ws",
    )

    private fun build(routing: RoutingProfile?, address: String = "example.org", geo: Boolean = false) =
        JSONObject(
            XrayConfig.build(
                server = server(address),
                routing = routing,
                dns = "1.1.1.1",
                hasGeoFiles = geo,
                dnsList = listOf("1.1.1.1", "8.8.8.8"),
            )
        )

    private fun rules(cfg: JSONObject) = cfg.getJSONObject("routing").getJSONArray("rules")
        .let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }

    private val global = RoutingProfile(
        id = "builtin-global", builtin = true, source = "custom",
        name = "Глобальный", globalProxy = true, domainStrategy = "AsIs",
    )
    private val fromSub = RoutingProfile(
        id = "sub:x:incy", source = "incy", name = "РФ", subUrl = "https://panel/sub",
        globalProxy = false, domainStrategy = "IPIfNonMatch",
        directSites = listOf("geosite:category-ru"),
        directIp = listOf("geoip:ru", "geoip:private"),
        proxySites = listOf("geosite:geolocation-!cn"),
    )

    /** Nothing matches a rule → xray uses the first outbound. That has to be the proxy. */
    @Test fun unmatchedTrafficGoesToTheProxy() {
        for (r in listOf(null, global, fromSub)) {
            val outbounds = build(r).getJSONArray("outbounds")
            assertEquals("first outbound must be the proxy", "proxy", outbounds.getJSONObject(0).getString("tag"))
        }
    }

    /** Traffic aimed at the server must never re-enter the tunnel it is building. */
    @Test fun theServerItselfIsReachedDirectly() {
        val direct = rules(build(fromSub)).filter { it.optString("outboundTag") == "direct" }
        assertTrue(
            "no direct rule for the server's own address",
            direct.any { it.optJSONArray("domain")?.toString()?.contains("example.org") == true },
        )
    }

    /**
     * The lookup for that hostname must not go through the proxy either — that circle is what
     * produced "lookup …: operation was canceled" and a server that never connected.
     */
    @Test fun theServersNameIsResolvedOutsideTheTunnel() {
        val servers = build(fromSub).getJSONObject("dns").getJSONArray("servers")
        val first = servers.getJSONObject(0)
        assertTrue("bootstrap resolver missing", first.getJSONArray("domains").toString().contains("example.org"))

        val bootstrapIp = first.getString("address")
        assertTrue(
            "the bootstrap resolver itself must be reached directly",
            rules(build(fromSub)).any {
                it.optString("outboundTag") == "direct" &&
                    it.optJSONArray("ip")?.toString()?.contains(bootstrapIp) == true
            },
        )
    }

    /** A server given by IP needs no bootstrap at all — and must not get a rule for one. */
    @Test fun anIpServerNeedsNoBootstrap() {
        val servers = build(fromSub, address = "203.0.113.7").getJSONObject("dns").getJSONArray("servers")
        assertTrue("no lookup is needed for a literal address", servers.get(0) is String)
    }

    /** geo: rules reference files on disk; a missing file is a fatal config error, not a skip. */
    @Test fun geoRulesAreDroppedUntilTheFilesExist() {
        assertTrue(
            "geo rules must not be written before the .dat files are downloaded",
            !rules(build(fromSub, geo = false)).toString().contains("geosite:"),
        )
        assertTrue(
            "with the files present the profile's rules must be there",
            rules(build(fromSub, geo = true)).toString().contains("geosite:"),
        )
    }

    /**
     * Connection timeouts are xray's, not ours.
     *
     * We used to force connIdle 120 with uplinkOnly and downlinkOnly at a second, which reads like
     * tidiness and behaves like churn: the connection goes a second after one direction finishes,
     * the client opens another, and the server ends up holding the pile. 0.9.1 shipped without
     * them and did not have the problem.
     */
    @Test fun connectionTimeoutsAreLeftAlone() {
        val levels = build(global).getJSONObject("policy").optJSONObject("levels")
        val level0 = levels?.optJSONObject("0")
        assertTrue("уровень 0 не должен задавать свои таймауты",
                   level0 == null || !level0.has("connIdle"))
    }

    /** A gRPC connection that dies quietly is what fills a server up; it has to be noticed. */
    @Test fun grpcIsCheckedForLife() {
        val grpc = ServerProfile(
            raw = "vless://x@h.example:443?type=grpc",
            name = "gRPC",
            protocol = cc.moon.internet.core.Protocol.VLESS,
            address = "h.example", port = 443,
            id = "00000000-0000-0000-0000-000000000000",
            security = "tls", network = "grpc",
        )
        val cfg = JSONObject(XrayConfig.build(server = grpc, routing = global, dns = "1.1.1.1"))
        val st = cfg.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("grpcSettings")
        assertEquals(60, st.getInt("idle_timeout"))
        assertEquals(20, st.getInt("health_check_timeout"))
    }

    /** Neither core writes a fakedns section; one would be silently ignored. */
    @Test fun noFakeDns() {
        assertTrue(!build(fromSub).toString().contains("fakedns"))
    }

    /**
     * mKCP's masking moved between xray 25 and 26: kcpSettings.header and seed are REJECTED by the
     * cores we ship — the config does not load at all. A share link written the old way has to be
     * translated to finalmask, and the names differ: the link says wechat-video, the core wants
     * wechat. Verified against cores/xray/xray.exe run -test on the desktop side.
     */
    @Test fun mkcpUsesFinalmaskNotTheRemovedHeader() {
        fun kcp(header: String?): JSONObject {
            val p = ServerProfile(
                raw = "x://h.example:2096?type=kcp", name = "K",
                protocol = cc.moon.internet.core.Protocol.VLESS, address = "h.example", port = 2096,
                id = "00000000-0000-0000-0000-000000000000",
                security = "none", network = "kcp", headerType = header,
                extra = mapOf("seed" to "hunter2"),
            )
            return JSONObject(XrayConfig.build(server = p, routing = null, dns = "1.1.1.1"))
                .getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("streamSettings").getJSONObject("kcpSettings")
        }
        val wechat = kcp("wechat-video")
        assertEquals("udp header-wechat", wechat.getString("finalmask"))
        assertTrue("header removed in xray 26", !wechat.has("header"))
        assertTrue("seed removed in xray 26", !wechat.has("seed"))
        assertEquals("udp header-srtp", kcp("srtp").getString("finalmask"))
        assertTrue("none means no mask at all", !kcp("none").has("finalmask"))
        // the knobs mKCP is actually for
        assertEquals(1350, wechat.getInt("mtu"))
        assertEquals(20, wechat.getInt("downlinkCapacity"))
        // ON, unlike xray's own default: without it mKCP answers packet loss by retransmitting
        // harder rather than backing off, which is exactly what stutters on a phone.
        assertTrue("congestion control on", wechat.getBoolean("congestion"))
    }

    /**
     * Every transport against every security, for the protocols that can choose. "We do not
     * support X" turned out to mean "the builder had no case for it": xray was handed the
     * transport name with nothing behind it and quietly used its defaults, so a server expecting
     * its own path or an mKCP seed refused every connection and said nothing about why.
     */
    @Test fun everyTransportGetsItsSettingsBlock() {
        val blocks = mapOf(
            "tcp" to null, "ws" to "wsSettings", "grpc" to "grpcSettings", "h2" to "httpSettings",
            "xhttp" to "xhttpSettings", "httpupgrade" to "httpupgradeSettings", "kcp" to "kcpSettings",
        )
        for (proto in listOf(cc.moon.internet.core.Protocol.VLESS, cc.moon.internet.core.Protocol.TROJAN))
            for ((net, block) in blocks)
                for (sec in listOf("none", "tls", "reality")) {
                    if (sec == "reality" && net == "kcp") continue   // xray has no reality over kcp
                    val p = ServerProfile(
                        raw = "x://h.example:443?type=$net", name = "M",
                        protocol = proto, address = "h.example", port = 443,
                        id = "00000000-0000-0000-0000-000000000000", password = "hunter2",
                        security = sec, network = net,
                        path = "/p", host = "cdn.example", serviceName = "svc",
                        headerType = "none", publicKey = "k", shortId = "00",
                        extra = mapOf("seed" to "sd"),
                    )
                    val st = JSONObject(XrayConfig.build(server = p, routing = null, dns = "1.1.1.1"))
                        .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
                    val what = "$proto/$net/$sec"
                    assertEquals(what, if (net == "h2") "http" else net, st.getString("network"))
                    if (block != null) assertTrue(what, st.has(block))
                    if (sec != "none") assertEquals(what, sec, st.getString("security"))
                }
    }
}
