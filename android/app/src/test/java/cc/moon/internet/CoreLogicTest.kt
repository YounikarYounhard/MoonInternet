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

    // ---- the loop that killed every connection in the first build ------------
    @Test
    fun `traffic to the proxy server never goes through the proxy`() {
        val cfg = JSONObject(XrayConfig.build(server(address = "1.2.3.4")))
        val first = cfg.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("direct", first.getString("outboundTag"))
        assertEquals("1.2.3.4", first.getJSONArray("ip").getString(0))
    }

    @Test
    fun `a hostname server is bypassed by domain, not by ip`() {
        val cfg = JSONObject(XrayConfig.build(server(address = "vpn.example.com")))
        val first = cfg.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
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
    fun `hysteria2 carries its password in the transport, not the outbound`() {
        val s = server(protocol = Protocol.HYSTERIA2)
        val out = XrayConfig.buildOutbound(s)
        assertEquals("hysteria", out.getString("protocol"))
        val hy = out.getJSONObject("streamSettings").getJSONObject("hysteriaSettings")
        assertEquals(2, hy.getInt("version"))
        assertEquals("pw", hy.getString("auth"))
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
        val first = cfg.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals("block", first.getString("outboundTag"))
        assertEquals("udp", first.getString("network"))
    }

    @Test
    fun `unsupported protocols are refused instead of producing a broken config`() {
        assertFalse(XrayConfig.supports(Protocol.WIREGUARD))
        assertTrue(XrayConfig.supports(Protocol.HYSTERIA2))
        assertTrue(XrayConfig.supports(Protocol.VLESS))
    }
}
