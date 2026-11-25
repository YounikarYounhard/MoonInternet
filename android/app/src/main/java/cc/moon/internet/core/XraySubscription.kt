package cc.moon.internet.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object XraySubscription {
    fun parse(text: String): List<ServerProfile> {
        val root = runCatching { JSONTokener(text).nextValue() }.getOrNull()
        val entries = root as? JSONArray ?: (root as? JSONObject)?.optJSONArray("servers")
        if (entries == null) return listOfNotNull((root as? JSONObject)?.let(::profile))
        return (0 until entries.length()).mapNotNull { index ->
            when (val entry = entries.opt(index)) {
                is JSONObject -> profile(entry)
                is String -> WireGuardParser.parseProfile(entry) ?: ShareLinkParser.parse(entry)
                else -> null
            }
        }
    }

    private fun profile(config: JSONObject): ServerProfile? {
        val outbound = mainOutbound(config) ?: return null
        val protocol = protocol(outbound) ?: return null
        val endpoint = endpoint(outbound) ?: return null
        val address = endpoint.optString("address")
        val port = endpoint.optInt("port")
        if (address.isBlank() || port !in 1..65535) return null
        val user = endpoint.optJSONArray("users")?.optJSONObject(0)
        val stream = outbound.optJSONObject("streamSettings")
        val tls = stream?.optJSONObject("realitySettings") ?: stream?.optJSONObject("tlsSettings")
        return ServerProfile(
            protocol = protocol,
            name = config.optString("remarks").trim().ifBlank { address },
            raw = config.toString(),
            address = address,
            port = port,
            id = user?.optString("id")?.takeIf { it.isNotEmpty() },
            password = user?.optString("pass")?.takeIf { it.isNotEmpty() } ?: endpoint.optString("password"),
            method = endpoint.optString("method"),
            network = stream?.optString("network", "tcp") ?: "tcp",
            security = stream?.optString("security", "none") ?: "none",
            sni = tls?.optString("serverName")?.takeIf { it.isNotEmpty() },
            fingerprint = tls?.optString("fingerprint")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun protocol(outbound: JSONObject): Protocol? = when (outbound.optString("protocol").lowercase()) {
        "vless" -> Protocol.VLESS
        "vmess" -> Protocol.VMESS
        "trojan" -> Protocol.TROJAN
        "shadowsocks" -> Protocol.SHADOWSOCKS
        "socks" -> Protocol.SOCKS
        else -> null
    }

    private fun mainOutbound(config: JSONObject): JSONObject? {
        val outbounds = config.optJSONArray("outbounds").objects().filter { protocol(it) != null }
        return outbounds.firstOrNull { it.optString("tag") == "proxy" } ?: outbounds.firstOrNull()
    }

    private fun endpoint(outbound: JSONObject): JSONObject? = outbound.optJSONObject("settings")?.let {
        it.optJSONArray("vnext")?.optJSONObject(0) ?: it.optJSONArray("servers")?.optJSONObject(0) ?: it
    }

    fun copyOutbound(profile: ServerProfile, tag: String): JSONObject? =
        source(profile)?.let(::mainOutbound)?.put("tag", tag)

    private fun source(profile: ServerProfile): JSONObject? =
        profile.raw?.takeIf { profile.isJson }?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun apply(app: JSONObject, profile: ServerProfile, useAppDns: Boolean) {
        val source = source(profile) ?: return
        val appOutbounds = app.getJSONArray("outbounds").objects().associateByTo(linkedMapOf()) { it.optString("tag") }
        for (outbound in source.optJSONArray("outbounds").objects()) {
            val tag = outbound.optString("tag")
            if (tag.isNotEmpty() && tag !in setOf("proxy", "api", "dns-out")) appOutbounds[tag] = outbound
        }
        app.put("outbounds", JSONArray(appOutbounds.values))

        source.optJSONObject("routing")?.let { provider ->
            val routing = app.getJSONObject("routing")
            val rules = routing.getJSONArray("rules")
            provider.optJSONArray("balancers")?.let { routing.put("balancers", it) }
            val aliases = source.optJSONArray("inbounds").objects().mapNotNull { inbound ->
                val tag = inbound.optString("tag")
                val protocol = inbound.optString("protocol")
                if (tag.isEmpty() || protocol !in setOf("socks", "http")) null
                else tag to listOf("$protocol-in", "tun-in")
            }.toMap()
            for (rule in provider.optJSONArray("rules").objects()) {
                rule.optJSONArray("inboundTag")?.let { tags ->
                    val mapped = (0 until tags.length()).flatMap { i ->
                        val tag = tags.optString(i)
                        aliases[tag] ?: listOf(tag)
                    }
                    rule.put("inboundTag", JSONArray(mapped.distinct()))
                }
                rules.put(rule)
            }
        }

        if (!useAppDns) source.optJSONObject("dns")?.let { app.put("dns", it) }
        for (name in listOf("observatory", "burstObservatory")) {
            source.optJSONObject(name)?.let { app.put(name, it) }
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)
}
