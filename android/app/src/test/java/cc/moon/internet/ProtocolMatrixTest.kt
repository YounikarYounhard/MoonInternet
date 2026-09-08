package cc.moon.internet

import cc.moon.internet.core.Protocol
import cc.moon.internet.core.ServerProfile
import cc.moon.internet.core.XrayConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every transport a share link can name, taken all the way to a finished config.
 *
 * The phone builds its own config — the same JSON as the desktop, written twice — and only one of
 * the two was ever put in front of the core. A transport the core has since renamed or dropped
 * therefore failed here and nowhere else, which is how "protocol not supported" reached a user for
 * a combination that works on the computer.
 *
 * Profiles are built by hand rather than parsed: the parser leans on android.net.Uri, which is not
 * there in a plain unit test. The link half is covered by the desktop suite, which parses the same
 * shapes with the same rules.
 *
 * The configs are written to build/xraycfg as well, so `xray run -test` can be pointed at them:
 * this test proves the shape, the core proves it is a shape it still accepts.
 */
class ProtocolMatrixTest {

    private val dump = File("build/xraycfg").apply { mkdirs() }

    private fun server(net: String, security: String, protocol: Protocol = Protocol.VLESS) = ServerProfile(
        protocol = protocol,
        name = "$protocol-$net-$security",
        raw = "x://x@1.2.3.4:443",
        address = "1.2.3.4",
        port = 443,
        id = "11111111-2222-3333-4444-555555555555",
        password = "pass",
        network = net,
        security = security,
        sni = "a.com",
        path = "/p",
        host = "a.com",
        serviceName = "sv",
        fingerprint = "chrome",
        publicKey = "hiRx48PmqmtW7xvwlRxoey7CvDpDpID3NEujEvs1cSA",
        shortId = "ab",
        spiderX = "/",
    )

    private fun stream(s: ServerProfile): JSONObject {
        val json = XrayConfig.build(server = s, routing = null, hasGeoFiles = false)
        File(dump, "${s.name}.json").writeText(json)
        return JSONObject(json).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
    }

    @Test
    fun everyTransportBuildsAConfigTheCoreCanRead() {
        val nets = listOf("tcp", "raw", "ws", "grpc", "http", "h2", "httpupgrade", "xhttp", "splithttp", "kcp", "quic")
        // Collected rather than asserted one at a time: every combination is written out even when
        // an earlier one is wrong, so the core can be pointed at the whole set in one go.
        val problems = mutableListOf<String>()
        for (net in nets) for (sec in listOf("tls", "reality", "none"))
            for (p in listOf(Protocol.VLESS, Protocol.TROJAN)) {
                val st = stream(server(net, sec, p))
                val out = st.getString("network")

                // Transports the core dropped have to be translated on the way out, not passed
                // through: it refuses the whole config over one of these names, so such a server
                // does not merely lose its camouflage — it never dials at all.
                if (out in listOf("http", "h2", "quic", "splithttp"))
                    problems += "$p/$net/$sec: '$out' is a transport xray no longer has"

                // ...and whatever it ends up as brings its own settings block with it.
                if (out != "tcp" && out != "raw" && !st.has("${out}Settings"))
                    problems += "$p/$net/$sec: network=$out with no ${out}Settings"
            }
        assertTrue(problems.joinToString(" | "), problems.isEmpty())
    }

    /** REALITY on a transport the core will not pair it with is a config it rejects outright. */
    @Test
    fun realityOnlyRidesTheTransportsItSupports() {
        for (net in listOf("ws", "httpupgrade", "kcp")) {
            val st = stream(server(net, "reality"))
            assertEquals("$net + reality is a pairing the core refuses", "tls", st.getString("security"))
        }
    }

    /**
     * The same query string means the same transport whichever protocol carries it.
     *
     * It did not: trojan's copy of this list was missing pbk, sid and spx, so a Trojan REALITY
     * link arrived at the core with no public key and the core threw the config out. Testable
     * without a parser because the filler takes a lookup, not a Uri.
     */
    @Test
    fun everyProtocolReadsTheSameStreamFields() {
        val q = mapOf(
            "type" to "xhttp", "security" to "reality", "pbk" to "KEY", "sid" to "ab", "spx" to "/",
            "sni" to "a.com", "fp" to "chrome", "path" to "/p", "host" to "a.com", "alpn" to "h2",
        )
        val bare = ServerProfile(protocol = Protocol.TROJAN, address = "1.2.3.4", port = 443)
        with(cc.moon.internet.core.ShareLinkParser) {
            val filled = bare.withStream({ k -> q[k] }, defaultSecurity = "tls")
            assertEquals("KEY", filled.publicKey)
            assertEquals("ab", filled.shortId)
            assertEquals("/", filled.spiderX)
            assertEquals("reality", filled.security)
            assertEquals("xhttp", filled.network)
        }
    }
}
