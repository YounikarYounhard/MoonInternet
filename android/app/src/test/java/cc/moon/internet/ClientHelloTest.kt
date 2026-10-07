package cc.moon.internet

import cc.moon.internet.core.ClientHello
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** Moon Ping's ClientHello: xray's own bytes, with this server's SNI and every length still right. */
class ClientHelloTest {

    private val templates = File("src/main/assets/client-hellos.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { it.split(' ', limit = 2).let { (fp, b64) -> fp to Base64.getDecoder().decode(b64.trim()) } }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun sniOf(r: ByteArray): String? {
        var p = 9 + 34
        p += 1 + (r[p].toInt() and 0xFF)
        p += 2 + u16(r, p)
        p += 1 + (r[p].toInt() and 0xFF)
        val end = p + 2 + u16(r, p)
        var q = p + 2
        while (q < end) {
            val type = u16(r, q); val len = u16(r, q + 2)
            if (type == 0) return String(r, q + 9, len - 5, Charsets.US_ASCII)
            q += 4 + len
        }
        return null
    }

    @Test
    fun `every fingerprint gets the new sni with consistent lengths`() {
        assertTrue(templates.size >= 9)
        for ((fp, t) in templates) for (sni in listOf("a.io", "cdn.stillwaterworks.example.org")) {
            val h = ClientHello.rewrite(t, sni)
            assertEquals("$fp sni", sni, sniOf(h))
            assertEquals("$fp record length", h.size, 5 + u16(h, 3))
            val hs = ((h[6].toInt() and 0xFF) shl 16) or u16(h, 7)
            assertEquals("$fp handshake length", u16(h, 3), 4 + hs)
        }
    }

    @Test
    fun `nothing repeats from one probe to the next`() {
        val t = templates.getValue("firefox")
        val a = ClientHello.rewrite(t, "x.example.com")
        val b = ClientHello.rewrite(t, "x.example.com")
        assertFalse(a.copyOfRange(11, 43).contentEquals(b.copyOfRange(11, 43)))
    }
}
