package cc.moon.internet.core

import kotlinx.serialization.Serializable

/** Protocols we can hand to sing-box. Ported from the desktop app's ProtocolType. */
enum class Protocol { VLESS, VMESS, TROJAN, SHADOWSOCKS, HYSTERIA2, WIREGUARD, SOCKS }

/** One server. Mirrors the desktop OutboundProfile, minus the xray-only bits. */
@Serializable
data class ServerProfile(
    val protocol: Protocol,
    val name: String = "",
    /** Original share link — needed for copy/QR and to survive a restart. */
    val raw: String? = null,

    val address: String = "",
    val port: Int = 0,

    val id: String? = null,          // vless/vmess uuid
    val password: String? = null,    // trojan / ss / hysteria2
    val method: String? = null,      // ss cipher
    val alterId: Int = 0,
    val encryption: String? = null,
    val flow: String? = null,

    val network: String = "tcp",     // tcp | ws | grpc | http | httpupgrade | xhttp
    val path: String? = null,
    val host: String? = null,
    val serviceName: String? = null,
    val headerType: String? = null,  // tcp with "http" obfuscation
    /** Anything transport-specific the link carried, e.g. xhttp "mode" and "extra". */
    val extra: Map<String, String> = emptyMap(),

    val security: String = "none",   // none | tls | reality
    val sni: String? = null,
    val alpn: String? = null,
    val fingerprint: String? = null,
    val allowInsecure: Boolean = false,
    val publicKey: String? = null,   // reality
    val shortId: String? = null,
    val spiderX: String? = null,     // reality

    val obfs: String? = null,        // hysteria2 salamander
    val obfsPassword: String? = null,

    val wg: WireGuardConfig? = null,
) {
    /** Two-letter country code parsed from a flag emoji in the name, e.g. 🇫🇮 → "fi". */
    val countryCode: String?
        get() {
            if (name.length < 4) return null
            val a = name.codePointAt(0)
            val b = name.codePointAt(Character.charCount(a))
            if (a !in 0x1F1E6..0x1F1FF || b !in 0x1F1E6..0x1F1FF) return null
            return "${'a' + (a - 0x1F1E6)}${'a' + (b - 0x1F1E6)}"
        }

    /** Name without the leading flag emoji. */
    val label: String
        get() {
            if (countryCode == null) return name
            return name.dropWhile { it.code in 0xD800..0xDBFF || it.code in 0xDC00..0xDFFF || it == ' ' }.trim()
        }

    val protocolLabel: String
        get() = when (protocol) {
            Protocol.VLESS -> "VLESS"
            Protocol.VMESS -> "VMess"
            Protocol.TROJAN -> "Trojan"
            Protocol.SHADOWSOCKS -> "SS"
            Protocol.HYSTERIA2 -> "Hysteria2"
            Protocol.WIREGUARD -> "WireGuard"
            Protocol.SOCKS -> "SOCKS"
        }
}

@Serializable
data class WireGuardConfig(
    val privateKey: String = "",
    val address: List<String> = emptyList(),
    val dns: List<String> = emptyList(),
    val peerPublicKey: String = "",
    val presharedKey: String? = null,
    val allowedIps: List<String> = listOf("0.0.0.0/0"),
    val endpoint: String = "",
    val persistentKeepalive: Int = 25,
    val mtu: Int = 0,
) {
    val endpointHost: String get() = endpoint.substringBeforeLast(':', endpoint)
    val endpointPort: Int get() = endpoint.substringAfterLast(':', "51820").toIntOrNull() ?: 51820
}

/** Direct / Proxy / Block rules — the same shape the desktop app imports from HAPP/INCY. */
@Serializable
data class RoutingProfile(
    val name: String = "",
    val directSites: List<String> = emptyList(),
    val proxySites: List<String> = emptyList(),
    val blockSites: List<String> = emptyList(),
    val directIp: List<String> = emptyList(),
    val proxyIp: List<String> = emptyList(),
    val blockIp: List<String> = emptyList(),
    val globalProxy: Boolean = true,
    val remoteDns: String = "1.1.1.1",
    val domesticDns: String = "8.8.8.8",
    val domainStrategy: String = "IPIfNonMatch",
    val geoipUrl: String = "",
    val geositeUrl: String = "",
    val source: String = "incy",     // incy | happ | custom
) {
    val ruleCount: Int
        get() = directSites.size + proxySites.size + blockSites.size +
                directIp.size + proxyIp.size + blockIp.size
}

/** A subscription plus everything we cached from its last fetch. */
@Serializable
data class Subscription(
    val url: String,
    val name: String,
    val servers: List<ServerProfile> = emptyList(),
    val announcement: String = "",
    val trafficText: String = "—",
    val expiryText: String = "∞",
    val updateIntervalMinutes: Int = 0,
    val fetchedAt: Long = 0,
)
