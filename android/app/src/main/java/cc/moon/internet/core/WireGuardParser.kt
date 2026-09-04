package cc.moon.internet.core

import android.util.Base64

/**
 * Reads AmneziaWG / WireGuard configs — the port of the desktop parser, kept close to it so a
 * server that imports on Windows imports here for the same reasons.
 *
 * Two input shapes, and they are not the same:
 *   • the raw `[Interface]` / `[Peer]` text;
 *   • a `vpn://` link, which is base64url of that text as 3x-ui writes it. The Amnezia client's
 *     own vpn:// is base64url(qCompress(json)) instead — not handled here, and it is not what a
 *     panel subscription contains.
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

    /** The panel writes base64url of the config text. Anything else here is not ours to read. */
    private fun decodeVpnUri(payload: String): String? = runCatching {
        val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        String(bytes).takeIf { it.trimStart().startsWith("[Interface]", true) }
    }.getOrNull()

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
                "persistentkeepalive" -> v.toIntOrNull()?.let { c.copy(persistentKeepalive = it) } ?: c
                else -> c
            } else c
        }
        if (c.allowedIps.isEmpty()) c = c.copy(allowedIps = listOf("0.0.0.0/0", "::/0"))
        if (c.dns.isEmpty()) c = c.copy(dns = listOf("1.1.1.1"))
        return c
    }
}
