package cc.moon.internet

import cc.moon.internet.core.RoutingProfile
import cc.moon.internet.core.ServerProfile
import cc.moon.internet.core.XrayConfig
import org.junit.Test

/**
 * Prints the config we actually hand to the core, for the two shipped routing profiles.
 * Not an assertion suite — a way to read the generated JSON without a phone, after a report
 * that connections broke when routing became a list of profiles.
 */
class ConfigDumpTest {

    private val server = ServerProfile(
        raw = "vless://x@example.org:443",
        name = "Пример",
        protocol = cc.moon.internet.core.Protocol.VLESS,
        address = "example.org",
        port = 443,
        id = "00000000-0000-0000-0000-000000000000",
        security = "tls",
        network = "ws",
    )

    private fun dump(name: String, routing: RoutingProfile?) {
        val cfg = XrayConfig.build(
            server = server,
            routing = routing,
            dns = "1.1.1.1",
            hasGeoFiles = false,
            dnsList = listOf("1.1.1.1", "8.8.8.8"),
        )
        val o = org.json.JSONObject(cfg)
        println("=== $name ===")
        println(o.getJSONObject("dns").toString(2))
        println(o.getJSONObject("routing").toString(2))
    }

    @Test fun globalProfile() = dump(
        "Глобальный",
        RoutingProfile(id = "builtin-global", builtin = true, source = "custom",
                       name = "Глобальный", globalProxy = true, domainStrategy = "AsIs"),
    )

    @Test fun lanProfile() = dump(
        "Обход LAN",
        RoutingProfile(id = "builtin-lan", builtin = true, source = "custom",
                       name = "Обход LAN", globalProxy = true, domainStrategy = "AsIs",
                       directIp = listOf("geoip:private")),
    )

    /** A subscription's profile: only listed sites go through the proxy, and the lists are geo
     *  tags. If the geo files are not on disk yet those rules are dropped — what is left? */
    @Test fun subscriptionProfileWithoutGeoFiles() = dump(
        "подписка, гео-файлов нет",
        RoutingProfile(id = "sub:x:incy", source = "incy", name = "РФ",
                       globalProxy = false, domainStrategy = "IPIfNonMatch",
                       directSites = listOf("geosite:category-ru"),
                       directIp = listOf("geoip:ru", "geoip:private"),
                       proxySites = listOf("geosite:geolocation-!cn")),
    )

    @Test fun noRouting() = dump("без маршрутизации", null)
}
