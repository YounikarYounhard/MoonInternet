package cc.moon.internet

import cc.moon.internet.core.Protocol
import cc.moon.internet.core.ServerProfile
import cc.moon.internet.data.hostPortOf
import cc.moon.internet.data.matchSelected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the saved selection resolves to after a subscription refresh.
 *
 * The complaint was "the app switches servers by itself, to the first one in the list". It did:
 * the selection is stored as the share link, a panel rewrites its own links, the key stopped
 * matching, and healSelection() wrote the top of the list down as the new choice.
 */
class SelectionTest {

    private fun server(name: String, address: String, port: Int, query: String = "") = ServerProfile(
        raw = "vless://uuid@$address:$port$query",
        name = name,
        protocol = Protocol.VLESS,
        address = address,
        port = port,
        id = "00000000-0000-0000-0000-000000000000",
    )

    private val first = server("Финляндия", "fi.example", 443, "?type=grpc&security=tls")
    private val chosen = server("Нидерланды", "nl.example", 8443, "?type=grpc&security=tls")
    private val all = listOf(first, chosen)

    @Test
    fun anExactKeyWins() {
        assertEquals(chosen.name, matchSelected(all, chosen.key)?.name)
    }

    @Test
    fun aRewrittenLinkIsStillTheSameServer() {
        // Same machine, same port; the panel added a fingerprint and reordered the parameters.
        val rewritten = "vless://uuid@nl.example:8443?security=tls&type=grpc&fp=chrome"
        assertEquals(chosen.name, matchSelected(all, rewritten)?.name)
    }

    @Test
    fun aServerThatIsGoneFallsBackToTheFirst() {
        assertEquals(first.name, matchSelected(all, "vless://uuid@retired.example:443")?.name)
    }

    @Test
    fun nothingSavedMeansTheFirst() {
        assertEquals(first.name, matchSelected(all, null)?.name)
    }

    @Test
    fun anEmptyListResolvesToNothing() {
        assertNull(matchSelected(emptyList(), chosen.key))
    }

    @Test
    fun hostPortComesOutOfALinkAndOnlyWhenItIsThere() {
        assertEquals("nl.example:8443", hostPortOf("vless://uuid@nl.example:8443?type=grpc"))
        assertEquals("1.2.3.4:443", hostPortOf("awg:pubkey@1.2.3.4:443"))
        assertEquals("h.example:2096", hostPortOf("trojan://pass@h.example:2096/?sni=x"))
        // vmess hides the address inside its base64 payload, so there is nothing to read here.
        assertNull(hostPortOf("vmess://eyJhZGQiOiJoLmV4YW1wbGUifQ=="))
    }
}
