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

    /**
     * Name without the leading flag emoji — and never empty. A link with no remark, or one whose
     * remark was nothing but the flag, produced a blank row you could not tell from the next
     * blank one. The endpoint is always there and is what you would look at anyway.
     */
    val label: String
        get() {
            val bare =
                if (countryCode == null) name
                else name.dropWhile { it.code in 0xD800..0xDBFF || it.code in 0xDC00..0xDFFF || it == ' ' }.trim()
            return bare.ifBlank { "$address:$port" }
        }

    val protocolLabel: String
        get() = when (protocol) {
            Protocol.VLESS -> "VLESS"
            Protocol.VMESS -> "VMess"
            Protocol.TROJAN -> "Trojan"
            Protocol.SHADOWSOCKS -> "SS"
            Protocol.HYSTERIA2 -> "Hysteria2"
            // AmneziaWG is built on WireGuard but is not it: different handshake, different
            // engine, and a server that speaks one will not answer the other. Calling both
            // "WireGuard" also lumped them together in the protocol filter, which is exactly
            // where you want them apart. The generation is part of the name because it decides
            // what a server can talk to.
            //
            // "3.x" and not "3.0" on purpose: 3.0 and 3.1 carry the identical set of config
            // fields, so a config cannot tell you which of the two it was written for.
            Protocol.WIREGUARD -> when (wg?.awgGeneration ?: 0) {
                1 -> "AmneziaWG 1.0"
                2 -> "AmneziaWG 2.0"
                3 -> "AmneziaWG 3.x"
                else -> "WireGuard"
            }
            Protocol.SOCKS -> "SOCKS"
        }

    /**
     * Key for the ping map. [raw] is the share link and is normally there, but a server parsed
     * from a format that carries no link has none — and pinging keyed on raw quietly dropped
     * those from the list of things to measure, so they sat in the list forever with no number
     * and no attempt. Address and port always exist.
     */
    val pingKey: String get() = key

    /**
     * What identifies this server across a subscription refresh: the share link without its
     * `#remark`. The remark is only the display name and panels rewrite it — x-ui writes the
     * traffic left and the expiry into it — so a key made of the whole link turned one server
     * into a different one on every refresh. Stars, the current selection and every measured
     * ping were all keyed off it, which is why they kept clearing themselves.
     *
     * ponytail: a vmess:// link keeps its name inside the base64 payload, so renaming one still
     * moves its key. Decode it here if that ever turns up in practice.
     */
    val key: String get() = raw?.takeIf { it.isNotBlank() }?.substringBefore('#') ?: "$address:$port"

    /**
     * What the connection actually rides on, for the chip beside the protocol.
     *
     * [network] only means anything for the protocols that can choose — VLESS, VMess, Trojan.
     * Hysteria2 is QUIC and WireGuard is its own thing, both UDP always; neither reads that field,
     * so it kept its default and the row claimed "Hysteria2 · TCP", which is simply not true.
     */
    val transportLabel: String
        get() = when (protocol) {
            Protocol.HYSTERIA2, Protocol.WIREGUARD -> "UDP"
            else -> when (network) { "kcp" -> "mKCP"; "httpupgrade" -> "HTTPUpgrade"; else -> network.uppercase() }
        }

    /**
     * True when nothing is listening on TCP at [port] — so a TCP handshake there can only ever
     * time out, and the row showed ✕ for a server that was perfectly alive. mKCP and QUIC are UDP
     * transports; Hysteria2 and WireGuard are UDP protocols whatever the transport field says.
     */
    val udpOnly: Boolean
        get() = protocol == Protocol.HYSTERIA2 || protocol == Protocol.WIREGUARD ||
            network == "kcp" || network == "quic"
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
    /** The name, which for a panel-written config lives in a leading `#` comment. */
    val label: String? = null,
    // AmneziaWG 1.x: junk packets and the magic header values. Strings throughout — H1-H4 may be
    // ranges ("1000-2000") and I1-I5 are free-form tag chains.
    val jc: String? = null, val jmin: String? = null, val jmax: String? = null,
    val s1: String? = null, val s2: String? = null, val s3: String? = null, val s4: String? = null,
    val h1: String? = null, val h2: String? = null, val h3: String? = null, val h4: String? = null,
    // 2.0: the signature chain sent ahead of a handshake so the flow opens like something else.
    val i1: String? = null, val i2: String? = null, val i3: String? = null,
    val i4: String? = null, val i5: String? = null,
    // 3.x: header protection proper, plus the timings that used to be constants.
    val headerProtectionKey: String? = null,
    val contentPaddingAddition: String? = null,
    val rekeyAfterTime: String? = null, val rekeyTimeout: String? = null,
    val rejectAfterTime: String? = null, val keepaliveTimeout: String? = null,
    val maxHandshakeAttempts: String? = null,
    val randomTrailers: String? = null, val disableCookies: String? = null,
) {
    val endpointHost: String get() = endpoint.substringBeforeLast(':', endpoint)
    val endpointPort: Int get() = endpoint.substringAfterLast(':', "51820").toIntOrNull() ?: 51820
    val isValid: Boolean get() = privateKey.isNotEmpty() && peerPublicKey.isNotEmpty() && ':' in endpoint

    /**
     * Which AmneziaWG generation this profile was written for: 0 plain WireGuard, 1 the original
     * junk-and-magic-headers set, 2 once I1-I5 appear, 3 once the fields added in 3.x do.
     *
     * Generations are additive — a 3.x engine runs a 1.x or 2.0 config unchanged — which is why
     * there is one code path and not three. J1-J3 and Itime are deliberately absent: they existed
     * in 1.5 only and were dropped in 2.0.
     */
    val awgGeneration: Int
        get() = when {
            !headerProtectionKey.isNullOrEmpty() || !contentPaddingAddition.isNullOrEmpty() ||
                !rekeyAfterTime.isNullOrEmpty() || !rekeyTimeout.isNullOrEmpty() ||
                !rejectAfterTime.isNullOrEmpty() || !keepaliveTimeout.isNullOrEmpty() ||
                !maxHandshakeAttempts.isNullOrEmpty() || !randomTrailers.isNullOrEmpty() ||
                !disableCookies.isNullOrEmpty() -> 3
            !i1.isNullOrEmpty() || !i2.isNullOrEmpty() || !i3.isNullOrEmpty() ||
                !i4.isNullOrEmpty() || !i5.isNullOrEmpty() -> 2
            !jc.isNullOrEmpty() || !jmin.isNullOrEmpty() || !jmax.isNullOrEmpty() ||
                !s1.isNullOrEmpty() || !s2.isNullOrEmpty() || !s3.isNullOrEmpty() ||
                !s4.isNullOrEmpty() || !h1.isNullOrEmpty() || !h2.isNullOrEmpty() ||
                !h3.isNullOrEmpty() || !h4.isNullOrEmpty() -> 1
            else -> 0
        }

    val isAmnezia: Boolean get() = awgGeneration > 0
}

/** Direct / Proxy / Block rules — the same shape the desktop app imports from HAPP/INCY. */
@Serializable
data class RoutingProfile(
    /** Stable key. Profiles are a list now, so "which one is selected" needs something to point at. */
    val id: String = "",
    /** Shipped with the app: can be duplicated and exported, not edited or deleted. */
    val builtin: Boolean = false,
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
    /** doh | dot | dou | tcp — how to reach the resolver above. */
    val remoteDnsType: String = "dou",
    val domesticDnsType: String = "dou",
    val domainStrategy: String = "IPIfNonMatch",
    val geoipUrl: String = "",
    val geositeUrl: String = "",
    val source: String = "incy",     // incy | happ | custom
    /**
     * The subscription this profile arrived in, empty for built-ins and for your own copies.
     * Profiles from a subscription are grouped under it and are read-only: the next fetch
     * overwrites them, so an edit here would quietly disappear.
     */
    val subUrl: String = "",
) {
    val ruleCount: Int
        get() = directSites.size + proxySites.size + blockSites.size +
                directIp.size + proxyIp.size + blockIp.size

    val siteCount: Int get() = directSites.size + proxySites.size + blockSites.size
    val ipCount: Int get() = directIp.size + proxyIp.size + blockIp.size
}

/**
 * What a profile you create yourself starts with. Not a default on the model: an imported profile
 * passes its own (often empty) URLs explicitly, and a built-in needs no download at all —
 * "geoip:private" lives in xray's own bundled file.
 */
const val GEOIP_URL_DEFAULT = "https://raw.githubusercontent.com/runetfreedom/russia-blocked-geoip/release/geoip.dat"
const val GEOSITE_URL_DEFAULT = "https://raw.githubusercontent.com/runetfreedom/russia-blocked-geosite/release/geosite.dat"

/** The four ways xray can be told to reach a resolver, as it writes them. */
fun dnsUri(type: String, address: String): String {
    val a = address.trim()
    if (a.isEmpty()) return ""
    // Already a URI (INCY exports "https://..." for DoH) — leave it be.
    if (a.contains("://")) return a
    return when (type.lowercase()) {
        "doh" -> "https://$a/dns-query"
        "dot" -> "tls://$a"
        "tcp" -> "tcp://$a"
        else -> a          // plain UDP, xray's default form
    }
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
    /** Share of the plan used / elapsed, <0 when unlimited or unknown — for the bar and dots. */
    val trafficFraction: Double = -1.0,
    val expiryFraction: Double = -1.0,
    val updateIntervalMinutes: Int = 0,
    val fetchedAt: Long = 0,
)
