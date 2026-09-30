package cc.moon.internet.core

import android.util.Base64

/**
 * Reads AmneziaWG / WireGuard configs — the port of the desktop parser, kept close to it so a
 * server that imports on Windows imports here for the same reasons.
 *
 * Three input shapes:
 *   • the raw `[Interface]` / `[Peer]` text;
 *   • a `vpn://` link that is base64url of that text — what 3x-ui writes;
 *   • a `vpn://` link that is base64url of Qt's qCompress(json) — what the Amnezia client itself
 *     hands out. Same `vpn://` scheme, entirely different payload, so which one it is has to be
 *     read off the bytes rather than off the prefix.
 */
object WireGuardParser {

    fun isWireGuardLink(s: String): Boolean {
        val t = s.trimStart()
        return t.startsWith("vpn://", true) || t.startsWith("wireguard://", true) ||
            t.startsWith("[Interface]", true)
    }

    /** A config or link as a [ServerProfile] the rest of the app can hold. */
    fun parseProfile(input: String): ServerProfile? {
        val wg = parse(input) ?: return null
        return ServerProfile(
            raw = input.trim(),
            name = wg.label ?: "AmneziaWG ${wg.endpointHost}",
            protocol = Protocol.WIREGUARD,
            address = wg.endpointHost,
            port = wg.endpointPort,
            wg = wg,
        )
    }

    /**
     * The config as plain text — the `[Interface]` / `[Peer]` document itself, for showing people
     * what they actually have. An AmneziaWG server has no xray outbound worth printing.
     */
    fun configText(input: String): String? {
        val text = input.trim()
        return when {
            text.startsWith("vpn://", true) -> decodeVpnUri(text.substring(6))
            text.startsWith("[Interface]", true) -> text
            else -> null
        }
    }

    fun parse(input: String): WireGuardConfig? {
        val ini = configText(input) ?: return null
        return parseIni(ini).takeIf { it.isValid }
    }

    /**
     * A `vpn://` payload, whichever of the two things it is.
     *
     * 3x-ui base64s the config text itself. The Amnezia client base64s a Qt blob: four bytes of
     * big-endian uncompressed length, then a zlib stream, then JSON describing the whole profile.
     * Telling them apart is a matter of looking — the zlib header `78 da` is not `[`.
     */
    private fun decodeVpnUri(payload: String): String? = runCatching {
        val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val text = String(bytes)
        if (text.trimStart().startsWith("[Interface]", true)) text else fromAmneziaBlob(bytes)
    }.getOrNull()

    /**
     * The Amnezia client's own export: qCompress(json) with the finished config inside it.
     *
     * The config it carries writes its resolvers as `$PRIMARY_DNS` / `$SECONDARY_DNS` — the client
     * fills those in from fields that sit beside it in the JSON, so they are substituted here
     * rather than dropped, which would leave the tunnel with no DNS at all.
     */
    private fun fromAmneziaBlob(bytes: ByteArray): String? = runCatching {
        // Four bytes of length, then raw zlib. Inflater(false) reads the zlib header itself.
        val inflater = java.util.zip.Inflater()
        inflater.setInput(bytes, 4, bytes.size - 4)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        inflater.end()

        val root = org.json.JSONObject(String(out.toByteArray()))
        val wanted = root.optString("defaultContainer")
        val containers = root.optJSONArray("containers") ?: return null
        var awg: org.json.JSONObject? = null
        for (i in 0 until containers.length()) {
            val c = containers.getJSONObject(i)
            val block = c.optJSONObject("awg") ?: continue
            if (awg == null || c.optString("container") == wanted) awg = block
        }
        val config = awg?.optJSONObject("last_config")?.optString("config")
            ?: awg?.optString("last_config")?.let { org.json.JSONObject(it).optString("config") }
            ?: return null

        config
            .replace("\$PRIMARY_DNS", root.optString("dns1", "1.1.1.1"))
            .replace("\$SECONDARY_DNS", root.optString("dns2", "1.0.0.1"))
            .takeIf { it.trimStart().startsWith("[Interface]", true) }
    }.getOrNull()

    /**
     * A number, or one picked out of a range like `25-35`.
     *
     * AmneziaWG 3.x writes several timings as ranges and expects the client to choose — the
     * choosing is the point, since a value that differs between clients is one fewer thing to
     * fingerprint. `"25-35".toIntOrNull()` is null, so the whole setting used to be dropped and
     * the tunnel went out with no keepalive at all.
     */
    internal fun pickInRange(v: String): Int? {
        v.toIntOrNull()?.let { return it }
        val (lo, hi) = v.split('-', limit = 2).map { it.trim().toIntOrNull() ?: return null }
        return if (hi >= lo) (lo..hi).random() else null
    }

    private fun parseIni(ini: String): WireGuardConfig {
        var c = WireGuardConfig()
        var section = ""
        for (rawLine in ini.replace("\r", "").split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith('#')) {
                // The panel labels a config with a comment and nothing else, so this is the only
                // place its name exists. First one wins; later comments are ordinary remarks.
                val label = line.trimStart('#').trim()
                if (c.label == null && label.isNotEmpty()) c = c.copy(label = label)
                continue
            }
            if (line.startsWith('[') && line.endsWith(']')) {
                section = line.substring(1, line.length - 1).trim().lowercase(); continue
            }
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val v = line.substring(eq + 1).trim()
            if (v.isEmpty()) continue
            fun csv() = v.split(',').map(String::trim).filter(String::isNotEmpty)

            c = if (section == "interface") when (key) {
                "privatekey" -> c.copy(privateKey = v)
                "address" -> c.copy(address = csv())
                "dns" -> c.copy(dns = csv().filterNot { it.startsWith('$') })  // skip $PRIMARY_DNS
                // Every config the panel writes sets one, and it is smaller than the usual default;
                // dropping it leaves the tunnel fragmenting, which is what DPI looks hardest at.
                "mtu" -> v.toIntOrNull()?.takeIf { it in 576..1500 }?.let { c.copy(mtu = it) } ?: c
                "jc" -> c.copy(jc = v); "jmin" -> c.copy(jmin = v); "jmax" -> c.copy(jmax = v)
                "s1" -> c.copy(s1 = v); "s2" -> c.copy(s2 = v); "s3" -> c.copy(s3 = v); "s4" -> c.copy(s4 = v)
                "h1" -> c.copy(h1 = v); "h2" -> c.copy(h2 = v); "h3" -> c.copy(h3 = v); "h4" -> c.copy(h4 = v)
                "i1" -> c.copy(i1 = v); "i2" -> c.copy(i2 = v); "i3" -> c.copy(i3 = v)
                "i4" -> c.copy(i4 = v); "i5" -> c.copy(i5 = v)
                "headerprotectionkey" -> c.copy(headerProtectionKey = v)
                "contentpaddingaddition" -> c.copy(contentPaddingAddition = v)
                "rekeyaftertime" -> c.copy(rekeyAfterTime = v)
                "rekeytimeout" -> c.copy(rekeyTimeout = v)
                "rejectaftertime" -> c.copy(rejectAfterTime = v)
                "keepalivetimeout" -> c.copy(keepaliveTimeout = v)
                "maxhandshakeattempts" -> c.copy(maxHandshakeAttempts = v)
                "randomtrailers" -> c.copy(randomTrailers = v)
                "disablecookies" -> c.copy(disableCookies = v)
                else -> c
            } else if (section == "peer") when (key) {
                "publickey" -> c.copy(peerPublicKey = v)
                "presharedkey" -> c.copy(presharedKey = v)
                "allowedips" -> c.copy(allowedIps = csv())
                "endpoint" -> c.copy(endpoint = v)
                "persistentkeepalive" -> pickInRange(v)?.let { c.copy(persistentKeepalive = it) } ?: c
                else -> c
            } else c
        }
        if (c.allowedIps.isEmpty()) c = c.copy(allowedIps = listOf("0.0.0.0/0", "::/0"))
        if (c.dns.isEmpty()) c = c.copy(dns = listOf("1.1.1.1"))
        return c
    }
}
