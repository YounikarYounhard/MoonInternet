package cc.moon.internet.core

import android.util.Base64

/**
 * Turns a [WireGuardConfig] into the UAPI text amneziawg-go's IpcSet takes. Port of the desktop
 * builder — the two must agree, because a config that connects on Windows has to connect here.
 *
 * UAPI is not the .conf file. Keys are lower_snake_case, the base64 keys of a config file are
 * **hex** here, a peer begins at the first `public_key=`, and the document ends with a blank line.
 * The names come from the engine's device/uapi.go rather than the file spelling: the file says
 * HeaderProtectionKey, the core wants header_protection_key.
 *
 * Every knob is optional, so a plain WireGuard profile yields the same document with none of them
 * and the same core runs it — which is what "3.x also runs 1.x and 2.0" means in practice.
 */
object WireGuardUapi {

    fun build(wg: WireGuardConfig): String = buildString {
        fun set(key: String, value: String?) {
            if (!value.isNullOrBlank()) append(key).append('=').append(value.trim()).append('\n')
        }

        // ---- device ----
        set("private_key", hex(wg.privateKey))
        append("replace_peers=true\n")

        set("jc", wg.jc); set("jmin", wg.jmin); set("jmax", wg.jmax)
        set("s1", wg.s1); set("s2", wg.s2); set("s3", wg.s3); set("s4", wg.s4)
        set("h1", wg.h1); set("h2", wg.h2); set("h3", wg.h3); set("h4", wg.h4)
        set("i1", wg.i1); set("i2", wg.i2); set("i3", wg.i3); set("i4", wg.i4); set("i5", wg.i5)

        // The header key is 32 bytes and the panel writes it base64, exactly like the WireGuard
        // keys — and exactly like them UAPI wants hex. Raw, it fails the whole config with
        // "invalid byte: U+004A", the engine choking on base64's alphabet.
        set("header_protection_key", hexKey(wg.headerProtectionKey))
        set("content_padding_addition", wg.contentPaddingAddition)
        set("rekey_after_time", wg.rekeyAfterTime)
        set("rekey_timeout", wg.rekeyTimeout)
        set("reject_after_time", wg.rejectAfterTime)
        set("keepalive_timeout", wg.keepaliveTimeout)
        set("max_handshake_attempts", wg.maxHandshakeAttempts)
        // Both arrived in 3.1 and are read with Go's ParseBool, while the panel writes them the ini
        // way — "on". Passing that through fails with «parsing "on": invalid syntax».
        set("random_trailers", bool(wg.randomTrailers))
        set("disable_cookies", bool(wg.disableCookies))

        // ---- peer ----
        set("public_key", hex(wg.peerPublicKey))       // starts the peer section
        set("preshared_key", hex(wg.presharedKey))
        set("endpoint", wg.endpoint)
        if (wg.persistentKeepalive > 0) set("persistent_keepalive_interval", wg.persistentKeepalive.toString())
        append("replace_allowed_ips=true\n")
        wg.allowedIps.forEach { set("allowed_ip", it) }

        append('\n')   // UAPI ends on a blank line; without it the core waits for more
    }

    /** A config-file flag as the "true"/"false" Go's ParseBool accepts. */
    private fun bool(v: String?): String? = when (v?.trim()?.lowercase()) {
        null, "" -> null
        "on", "yes", "true", "1", "enabled" -> "true"
        else -> "false"
    }

    /** Hex for a key that may already be hex — panels are not consistent about this one. */
    private fun hexKey(v: String?): String? {
        val s = v?.trim().orEmpty()
        if (s.isEmpty()) return null
        val alreadyHex = s.length % 2 == 0 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        return if (alreadyHex) s.lowercase() else hex(s)
    }

    /** A base64 key as the 64 hex characters UAPI expects, or null if it is not base64. */
    private fun hex(base64: String?): String? {
        val s = base64?.trim().orEmpty()
        if (s.isEmpty()) return null
        return runCatching {
            Base64.decode(s, Base64.DEFAULT).joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
