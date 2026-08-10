package cc.moon.internet.core

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses routing deep-links: `incy://routing/add/<b64>` and `happ://routing/add/<b64>`.
 * Ported from the desktop IncyRoutingParser — these payloads ride inside the subscription,
 * so the phone gets the same rule set the desktop imports.
 *
 * HAPP writes booleans as strings and uses slightly different key casing, so every lookup goes
 * through helpers that accept either spelling.
 */
object RoutingParser {

    fun isRoutingLink(link: String) =
        link.startsWith("incy://routing/", true) || link.startsWith("happ://routing/", true)

    fun parse(link: String): RoutingProfile? = runCatching {
        val l = link.trim()
        val (source, b64) = when {
            l.startsWith("incy://routing/add/", true) -> "incy" to l.substring(19)
            l.startsWith("happ://routing/add/", true) -> "happ" to l.substring(19)
            else -> return null
        }
        parseJson(String(decodeB64(b64)), source)
    }.getOrNull()

    fun parseJson(json: String, source: String): RoutingProfile? = runCatching {
        val o = JSONObject(json)

        fun str(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> o.optString(k, "").takeIf { it.isNotBlank() } } ?: ""

        fun bool(vararg keys: String): Boolean {
            for (k in keys) {
                if (!o.has(k)) continue
                return when (val v = o.get(k)) {
                    is Boolean -> v
                    is String -> v.equals("true", true) || v == "1"
                    is Number -> v.toInt() != 0
                    else -> false
                }
            }
            return true
        }

        fun list(vararg keys: String): List<String> {
            val arr = keys.firstNotNullOfOrNull { o.optJSONArray(it) } ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it, null)?.takeIf(String::isNotBlank) }
        }

        RoutingProfile(
            name = str("Name", "name").ifBlank { source.uppercase() },
            directSites = list("DirectSites", "directSites"),
            proxySites = list("ProxySites", "proxySites"),
            blockSites = list("BlockSites", "blockSites"),
            directIp = list("DirectIp", "directIp", "DirectIP"),
            proxyIp = list("ProxyIp", "proxyIp", "ProxyIP"),
            blockIp = list("BlockIp", "blockIp", "BlockIP"),
            globalProxy = bool("GlobalProxy", "globalProxy"),
            remoteDns = str("RemoteDNSIP", "remoteDnsIp", "RemoteDns", "remoteDns").ifBlank { "1.1.1.1" },
            domesticDns = str("DomesticDNSIP", "domesticDnsIp", "DomesticDns", "domesticDns").ifBlank { "8.8.8.8" },
            remoteDnsType = str("RemoteDNSType", "remoteDnsType").ifBlank { "dou" }.lowercase(),
            domesticDnsType = str("DomesticDNSType", "domesticDnsType").ifBlank { "dou" }.lowercase(),
            domainStrategy = str("DomainStrategy", "domainStrategy").ifBlank { "IPIfNonMatch" },
            geoipUrl = str("Geoipurl", "geoIpUrl", "geoipUrl", "GeoipUrl"),
            geositeUrl = str("Geositeurl", "geoSiteUrl", "geositeUrl", "GeositeUrl"),
            source = source,
        )
    }.getOrNull()

    /** Tolerant base64: url-safe alphabet, missing padding, stray whitespace. */
    private fun decodeB64(s: String): ByteArray {
        val clean = s.trim().replace("\n", "").replace("\r", "").replace(' ', '+')
        return Base64.decode(clean, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Serialises back to the INCY payload shape, for export/sharing. */
    fun toJson(p: RoutingProfile): String = JSONObject()
        .put("Name", p.name)
        .put("GlobalProxy", p.globalProxy)
        .put("RemoteDNSIP", p.remoteDns)
        .put("DomesticDNSIP", p.domesticDns)
        .put("RemoteDNSType", p.remoteDnsType)
        .put("DomesticDNSType", p.domesticDnsType)
        .put("DomainStrategy", p.domainStrategy)
        .put("Geoipurl", p.geoipUrl)
        .put("Geositeurl", p.geositeUrl)
        .put("DirectSites", JSONArray(p.directSites))
        .put("ProxySites", JSONArray(p.proxySites))
        .put("BlockSites", JSONArray(p.blockSites))
        .put("DirectIp", JSONArray(p.directIp))
        .put("ProxyIp", JSONArray(p.proxyIp))
        .put("BlockIp", JSONArray(p.blockIp))
        .toString(2)

    /** The shareable form: what "Экспорт в буфер" puts on the clipboard. */
    fun toLink(p: RoutingProfile): String {
        val b64 = Base64.encodeToString(
            toJson(p).toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "incy://routing/add/$b64"
    }
}
