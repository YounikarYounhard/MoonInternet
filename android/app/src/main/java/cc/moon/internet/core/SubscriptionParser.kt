package cc.moon.internet.core

/**
 * Turns the body of a subscription into servers. Panels ship either a plain list of share links
 * or that same list base64-encoded — both are handled here, same as on desktop.
 *
 * Fake "announcement" nodes (placeholder addresses) are filtered out and returned separately,
 * because panels use them to push a welcome message into the server list.
 */
object SubscriptionParser {

    data class Result(val servers: List<ServerProfile>, val announcement: String, val isJson: Boolean = false)

    private val placeholderHosts = setOf(
        "", "127.0.0.1", "localhost", "0.0.0.0", "::1", "example.com", "example.org"
    )

    fun parse(body: String): Result {
        val text = decodeIfBase64(body).trimStart('\uFEFF').trimStart()
        if (text.startsWith('[') || text.startsWith('{'))
            return Result(XraySubscription.parse(text).distinctBy { it.key }, "", isJson = true)
        val servers = mutableListOf<ServerProfile>()
        val notes = mutableListOf<String>()

        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                // routing links (happ://routing/add/… , incy://routing/add/…) are handled elsewhere
                if (line.startsWith("happ://", true) || line.startsWith("incy://", true)) return@forEach
                // AmneziaWG arrives as vpn://, which ShareLinkParser knows nothing about — so a
                // subscription carrying it used to deliver only the other protocols, silently.
                if (WireGuardParser.isWireGuardLink(line)) {
                    WireGuardParser.parseProfile(line)?.let { servers += it }
                    return@forEach
                }
                val p = ShareLinkParser.parse(line) ?: return@forEach
                if (p.address.lowercase() in placeholderHosts) {
                    if (p.name.isNotBlank()) notes += p.name.trim()
                } else servers += p
            }

        return Result(servers.distinctBy { it.key }, notes.joinToString("\n"))
    }

    /** Extracts happ:// or incy:// routing links found in the body. */
    fun routingLinks(body: String): List<String> =
        decodeIfBase64(body).lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("happ://routing/", true) || it.startsWith("incy://routing/", true) }
            .toList()

    private fun decodeIfBase64(body: String): String {
        val t = body.trim().trimStart('\uFEFF').trimStart()
        // A links list always contains "://" in the clear; otherwise assume base64.
        if (t.contains("://")) return t
        return try {
            val decoded = String(ShareLinkParser.b64(t), Charsets.UTF_8).trimStart('\uFEFF').trimStart()
            if (decoded.contains("://") || decoded.startsWith('[') || decoded.startsWith('{')) decoded else t
        } catch (_: Exception) { t }
    }
}
