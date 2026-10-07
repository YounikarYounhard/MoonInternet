package cc.moon.internet.core

import android.content.Context
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * The TLS ClientHello the core sends for a given uTLS fingerprint, rewritten for one server —
 * the phone's copy of the desktop's ClientHello.cs, same reasoning, same template file.
 *
 * The box that bans a foreign server lets the TCP handshake through and swallows the TLS one, so a
 * TCP probe reports a healthy number for a server that carries nothing. Sending the bytes xray
 * would send, with this server's SNI, asks the question the box is actually answering. Only the SNI,
 * the client random and the session id are rewritten; see assets/client-hellos.txt.
 */
object ClientHello {
    @Volatile private var templates: Map<String, ByteArray>? = null
    private val random = SecureRandom()

    private fun templates(ctx: Context): Map<String, ByteArray> = templates ?: synchronized(this) {
        templates ?: runCatching {
            ctx.assets.open("client-hellos.txt").bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line ->
                    val (fp, b64) = line.split(' ', limit = 2)
                    fp to android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT)
                }
        }.getOrDefault(emptyMap()).also { templates = it }
    }

    /** The fingerprint the core will use: chrome for Reality without one, Go's own TLS for plain TLS. */
    fun effective(ctx: Context, security: String, fp: String?): String {
        val f = fp?.trim()?.lowercase().orEmpty()
        return when {
            f.isEmpty() -> if (security == "reality") "chrome" else "go"
            f in setOf("random", "randomized", "randomizednoalpn") -> "chrome"
            templates(ctx).containsKey(f) -> f
            else -> "chrome"
        }
    }

    /** A ready-to-send TLS record, or null when there is nothing to send for this SNI. */
    fun forServer(ctx: Context, fingerprint: String, sni: String): ByteArray? {
        if (sni.isBlank() || XrayConfig.isIpLiteral(sni)) return null
        return templates(ctx)[fingerprint]?.let { rewrite(it, sni) }
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    /** Swaps the SNI, refreshes random and session id, and fixes up every length around them. */
    fun rewrite(record: ByteArray, sni: String): ByteArray {
        val body = record.copyOfRange(9, record.size)
        var p = 34
        val sidLen = body[p].toInt() and 0xFF
        ByteArray(32).also(random::nextBytes).copyInto(body, 2)
        ByteArray(sidLen).also(random::nextBytes).copyInto(body, p + 1)
        p += 1 + sidLen
        p += 2 + u16(body, p)                      // cipher suites
        p += 1 + (body[p].toInt() and 0xFF)        // compression methods
        val extEnd = p + 2 + u16(body, p)

        val name = sni.trim().toByteArray(Charsets.US_ASCII)
        val exts = mutableListOf<Pair<Int, ByteArray>>()
        var oldSni = 0
        var q = p + 2
        while (q < extEnd) {
            val type = u16(body, q)
            val len = u16(body, q + 2)
            var data = body.copyOfRange(q + 4, q + 4 + len)
            if (type == 0) {
                oldSni = len
                // server_name_list(2) | name_type(1)=host_name | name length(2) | name
                data = ByteArray(5 + name.size).also {
                    it[0] = ((3 + name.size) shr 8).toByte(); it[1] = (3 + name.size).toByte()
                    it[3] = (name.size shr 8).toByte(); it[4] = name.size.toByte()
                    name.copyInto(it, 5)
                }
            }
            exts += type to data
            q += 4 + len
        }
        // BoringSSL-style padding keeps the hello at a fixed size; keep it there.
        val pad = exts.indexOfFirst { it.first == 21 }
        if (pad >= 0) {
            val newLen = maxOf(0, exts[pad].second.size - ((5 + name.size) - oldSni))
            exts[pad] = 21 to ByteArray(newLen)
        }

        val extBytes = ByteArrayOutputStream().apply {
            exts.forEach { (t, d) -> write(t shr 8); write(t); write(d.size shr 8); write(d.size); write(d) }
        }.toByteArray()
        val newBody = ByteArrayOutputStream().apply {
            write(body, 0, p); write(extBytes.size shr 8); write(extBytes.size); write(extBytes)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(record, 0, 3)
            write((4 + newBody.size) shr 8); write(4 + newBody.size)
            write(1)   // client_hello
            write(newBody.size shr 16); write(newBody.size shr 8); write(newBody.size)
            write(newBody)
        }.toByteArray()
    }
}
