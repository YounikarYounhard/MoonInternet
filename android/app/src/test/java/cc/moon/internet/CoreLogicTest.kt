package cc.moon.internet

import cc.moon.internet.core.Protocol
import cc.moon.internet.core.ServerProfile
import cc.moon.internet.core.XrayConfig
import cc.moon.internet.core.freeLocalPort
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks on the pieces that quietly break a connection when they are wrong: the generated xray
 * config and the rules that keep the tunnel from eating itself.
 *
 * Runs on the JVM (no device), so `gradlew testReleaseUnitTest` catches these before an install.
 */
class CoreLogicTest {

    private fun server(
        protocol: Protocol = Protocol.TROJAN,
        address: String = "example.com",
        network: String = "tcp",
        security: String = "tls",
    ) = ServerProfile(
        protocol = protocol, name = "test", address = address, port = 443,
        password = "pw", id = "11111111-2222-3333-4444-555555555555",
        network = network, security = security,
    )

    /** Rule 0 is the DNS hijack; the first rule that decides where traffic goes is the next one. */
    private fun firstTrafficRule(cfg: JSONObject): JSONObject {
        val rules = cfg.getJSONObject("routing").getJSONArray("rules")
        return (0 until rules.length()).map(rules::getJSONObject).first { it.optString("outboundTag") != "dns-out" }
    }

    // ---- DNS: one handshake per lookup is what got the foreign servers banned -----------
    @Test
    fun `the phone's dns is answered by xray, not sent on as traffic`() {
        val cfg = JSONObject(XrayConfig.build(server()))
        val first = cfg.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("dns-out", first.getString("outboundTag"))
        assertEquals("53", first.getString("port"))
        // Only what comes in through the tunnel; xray's own upstream queries must not loop.
        assertEquals("tun-in", first.getJSONArray("inboundTag").getString(0))
        val outbounds = cfg.getJSONArray("outbounds")
        assertTrue((0 until outbounds.length()).any {
            outbounds.getJSONObject(it).let { o -> o.getString("tag") == "dns-out" && o.getString("protocol") == "dns" }
        })
        // and the proxy is still the first outbound, i.e. the default route
        assertEquals("proxy", outbounds.getJSONObject(0).getString("tag"))
    }

    // ---- the loop that killed every connection in the first build ------------
    @Test
    fun `traffic to the proxy server never goes through the proxy`() {
        val cfg = JSONObject(XrayConfig.build(server(address = "1.2.3.4")))
        val first = firstTrafficRule(cfg)
        assertEquals("direct", first.getString("outboundTag"))
        assertEquals("1.2.3.4", first.getJSONArray("ip").getString(0))
    }

    @Test
    fun `a hostname server is bypassed by domain, not by ip`() {
        val cfg = JSONObject(XrayConfig.build(server(address = "vpn.example.com")))
        val first = firstTrafficRule(cfg)
        assertEquals("full:vpn.example.com", first.getJSONArray("domain").getString(0))
    }

    @Test
    fun `ip literals are told apart from hostnames`() {
        assertTrue(XrayConfig.isIpLiteral("8.8.8.8"))
        assertTrue(XrayConfig.isIpLiteral("2001:db8::1"))
        assertFalse(XrayConfig.isIpLiteral("example.com"))
        assertFalse(XrayConfig.isIpLiteral("1.2.3.4.example.com"))
    }

    // ---- geo files: a missing .dat is a fatal config error, not a skipped rule -
    @Test
    fun `no geo reference is emitted while the dat files are missing`() {
        val routing = cc.moon.internet.core.RoutingProfile(
            proxySites = listOf("geosite:CATEGORY-RU", "example.org"),
            directIp = listOf("geoip:ru", "10.1.2.0/24"),
        )
        val json = XrayConfig.build(server(), routing = routing, hasGeoFiles = false)
        assertFalse("geo: rules must be dropped without the .dat files", json.contains("geosite:"))
        assertFalse(json.contains("geoip:"))
        assertTrue("plain rules survive", json.contains("example.org"))

        val withGeo = XrayConfig.build(server(), routing = routing, hasGeoFiles = true)
        assertTrue(withGeo.contains("geosite:CATEGORY-RU"))
    }

    // ---- transports the desktop supports ------------------------------------
    @Test
    fun `grpc keeps an empty serviceName instead of inventing one`() {
        val s = server(network = "grpc").copy(serviceName = "")
        val out = XrayConfig.buildOutbound(s)
        val grpc = out.getJSONObject("streamSettings").getJSONObject("grpcSettings")
        assertEquals("", grpc.getString("serviceName"))
    }

    @Test
    fun `xhttp survives as xhttp`() {
        val s = server(protocol = Protocol.VLESS, network = "xhttp")
        val stream = XrayConfig.buildOutbound(s).getJSONObject("streamSettings")
        assertEquals("xhttp", stream.getString("network"))
        assertTrue(stream.has("xhttpSettings"))
    }

    @Test
    fun `hysteria2 leaves xray as a socks dial into sing-box`() {
        // It used to ride xray's own hysteria transport. That implementation does not survive
        // Russian filtering (XTLS/Xray-core#6717, closed as "not planned") while sing-box does, so
        // the engine that carries Hysteria2 on the desktop carries it here too and xray only dials
        // into it — the arrangement AmneziaWG has had through awgproxy all along.
        val s = server(protocol = Protocol.HYSTERIA2)
        val out = XrayConfig.buildOutbound(s)
        assertEquals("socks", out.getString("protocol"))
        val target = out.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("127.0.0.1", target.getString("address"))
        assertEquals(cc.moon.internet.vpn.SingBoxRunner.PORT, target.getInt("port"))
        // No TLS and no transport of its own — both belong to sing-box now. Wrapping a loopback
        // dial in TLS would mean handshaking with sing-box as though it were the server.
        val stream = out.getJSONObject("streamSettings")
        assertEquals("tcp", stream.getString("network"))
        assertTrue(!stream.has("hysteriaSettings") && !stream.has("tlsSettings"))
    }

    @Test
    fun `sing-box gets the hysteria2 details xray had nowhere to put`() {
        val s = server(protocol = Protocol.HYSTERIA2).copy(
            sni = "h.example", obfsPassword = "sala", extra = mapOf("up" to "50", "down" to "200"),
        )
        val cfg = JSONObject(cc.moon.internet.core.SingBoxHy2Config.build(s, 1092))
        val proxy = cfg.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria2", proxy.getString("type"))
        assertEquals("pw", proxy.getString("password"))
        assertEquals("salamander", proxy.getJSONObject("obfs").getString("type"))
        // Bandwidth hints drive Hysteria's congestion control, and xray had no field for them —
        // on the phone they were simply dropped.
        assertEquals(50, proxy.getInt("up_mbps"))
        assertEquals(200, proxy.getInt("down_mbps"))
        assertEquals("h.example", proxy.getJSONObject("tls").getString("server_name"))
        val inbound = cfg.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals(1092, inbound.getInt("listen_port"))
    }

    // ---- local proxy, the way the other Android clients expose it -------------
    @Test
    fun `socks and http inbounds are present and bound to localhost by default`() {
        val cfg = JSONObject(XrayConfig.build(server()))
        val ins = cfg.getJSONArray("inbounds")
        val tags = (0 until ins.length()).map { ins.getJSONObject(it).getString("tag") }
        assertTrue(tags.containsAll(listOf("tun-in", "socks-in", "http-in")))

        val socks = (0 until ins.length()).map { ins.getJSONObject(it) }.first { it.getString("tag") == "socks-in" }
        assertEquals("127.0.0.1", socks.getString("listen"))
        assertEquals(10808, socks.getInt("port"))
    }

    @Test
    fun `a busy local port is stepped over, and zero means off`() {
        java.net.ServerSocket().use { squatter ->
            squatter.bind(java.net.InetSocketAddress("127.0.0.1", 0))
            val taken = squatter.localPort
            // this is the INCY case: another client already listens there, and xray treats a
            // busy inbound as fatal, so we have to move rather than fail
            val socks = freeLocalPort(taken)
            assertEquals(taken + 1, socks)
            // ...and the second listener must not be handed the same number
            assertEquals(taken + 2, freeLocalPort(taken, avoid = setOf(socks)))
        }
        assertEquals(0, freeLocalPort(0))
    }

    @Test
    fun `proxy-only mode drops the tun inbound and keeps the rest`() {
        val full = XrayConfig.build(server())
        val proxyOnly = JSONObject(XrayConfig.buildProxyOnly(full))
        val ins = proxyOnly.getJSONArray("inbounds")
        val protocols = (0 until ins.length()).map { ins.getJSONObject(it).getString("protocol") }
        assertFalse("tun must be gone in proxy mode", protocols.contains("tun"))
        assertTrue(protocols.contains("socks"))
        assertTrue(protocols.contains("http"))
    }

    @Test
    fun `lan access switches the listen address`() {
        val cfg = JSONObject(XrayConfig.build(server(), allowLan = true))
        val ins = cfg.getJSONArray("inbounds")
        val socks = (0 until ins.length()).map { ins.getJSONObject(it) }.first { it.getString("tag") == "socks-in" }
        assertEquals("0.0.0.0", socks.getString("listen"))
    }

    // ---- tuning switches actually reach the config ---------------------------
    @Test
    fun `fragment adds the dialer outbound and points the proxy at it`() {
        val cfg = JSONObject(XrayConfig.build(server(), tlsFragment = true))
        val outs = cfg.getJSONArray("outbounds")
        val tags = (0 until outs.length()).map { outs.getJSONObject(it).getString("tag") }
        assertTrue(tags.contains("fragment"))

        val proxy = outs.getJSONObject(0)
        assertEquals("fragment",
            proxy.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
    }

    @Test
    fun `block udp inserts a reject rule before everything else`() {
        val cfg = JSONObject(XrayConfig.build(server(), blockUdp = true))
        val first = firstTrafficRule(cfg)
        assertEquals("block", first.getString("outboundTag"))
        assertEquals("udp", first.getString("network"))
    }

    @Test
    fun `the picker offers what the core can actually dial`() {
        assertTrue(XrayConfig.supports(Protocol.HYSTERIA2))
        assertTrue(XrayConfig.supports(Protocol.VLESS))
        // WireGuard used to be refused here because nothing could carry it. awgproxy does now, and
        // xray reaches it as an ordinary SOCKS outbound — which is what lends AmneziaWG the same
        // routing as everything else rather than a second implementation of it.
        assertTrue(XrayConfig.supports(Protocol.WIREGUARD))
    }

    @Test
    fun `AmneziaWG is named apart from WireGuard, generation and all`() {
        fun profile(vararg knobs: Pair<String, String>): ServerProfile {
            var wg = cc.moon.internet.core.WireGuardConfig(
                privateKey = "aGVsbG8=", peerPublicKey = "d29ybGQ=", endpoint = "1.2.3.4:51820",
            )
            for ((k, v) in knobs) wg = when (k) {
                "jc" -> wg.copy(jc = v)
                "i1" -> wg.copy(i1 = v)
                "hpk" -> wg.copy(headerProtectionKey = v)
                else -> wg
            }
            return ServerProfile(
                raw = "vpn://x", name = "n", protocol = Protocol.WIREGUARD,
                address = "1.2.3.4", port = 51820, wg = wg,
            )
        }
        // Built on WireGuard, but not it: different handshake, different engine, and a server
        // speaking one will not answer the other. The generation is part of the name because it
        // decides what a server can talk to.
        assertEquals("WireGuard", profile().protocolLabel)
        assertEquals("AmneziaWG 1.0", profile("jc" to "5").protocolLabel)
        assertEquals("AmneziaWG 2.0", profile("jc" to "5", "i1" to "<b 0x01>").protocolLabel)
        assertEquals("AmneziaWG 3.1", profile("jc" to "5", "hpk" to "00ff").protocolLabel)

        // ...and the protocol filter keeps them apart, because it filters on that same label.
        val list = listOf(profile(), profile("jc" to "5"), profile("jc" to "5", "hpk" to "00ff"))
        val onlyPlain = cc.moon.internet.data.orderServers(
            list, emptyMap(), emptyList(), "default", "WireGuard", null)
        assertEquals(1, onlyPlain.size)
        val only3 = cc.moon.internet.data.orderServers(
            list, emptyMap(), emptyList(), "default", "AmneziaWG 3.1", null)
        assertEquals(1, only3.size)

        // A subscription that does not carry the chosen protocol shows NOTHING, so its card can be
        // left out of the page entirely. This used to hand back the whole list: the guard against a
        // protocol that has vanished was asked of one subscription at a time, so "this lot has none
        // of it" and "nobody has any of it" gave the same answer and the filter did the opposite of
        // filtering. The real guard lives in Store.order(), which asks about every server there is.
        val none = cc.moon.internet.data.orderServers(
            list, emptyMap(), emptyList(), "default", "Hysteria2", null)
        assertEquals(0, none.size)
    }

    @Test
    fun `an AmneziaWG server becomes a socks dial into awgproxy`() {
        val wg = ServerProfile(
            raw = "vpn://x", name = "AWG", protocol = Protocol.WIREGUARD,
            address = "1.2.3.4", port = 51820,
            wg = cc.moon.internet.core.WireGuardConfig(
                privateKey = "aGVsbG8=", peerPublicKey = "d29ybGQ=", endpoint = "1.2.3.4:51820",
                address = listOf("10.8.1.2/32"), jc = "5", h1 = "1",
            ),
        )
        val out = JSONObject(XrayConfig.build(server = wg, routing = null, dns = "1.1.1.1"))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("socks", out.getString("protocol"))
        val target = out.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("127.0.0.1", target.getString("address"))
        assertEquals(cc.moon.internet.vpn.AwgRunner.PORT, target.getInt("port"))
    }
}
