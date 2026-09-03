using System.Text;
using MoonInternet.Core.Models;

namespace MoonInternet.Core.Generation;

/// <summary>
/// Turns a parsed <see cref="WireGuardConfig"/> into the UAPI text amneziawg-go's
/// <c>IpcSet</c> takes.
///
/// UAPI is not the .conf file: keys are lower_snake_case, the base64 keys of a config file are
/// <b>hex</b> here, a peer begins at the first <c>public_key=</c> and the whole thing ends with a
/// blank line. The key names below are the ones the core actually compares against — taken from
/// device/uapi.go, not from the config-file spelling, because the two differ (the file says
/// HeaderProtectionKey, the core wants header_protection_key).
///
/// Generations are additive: every AmneziaWG knob is optional, so a 1.x or 2.0 profile produces a
/// shorter document and the same core runs it. That is why there is one builder and not three.
/// </summary>
public static class WireGuardUapi
{
    public static string Build(WireGuardConfig wg)
    {
        var b = new StringBuilder();

        void Set(string key, string? value)
        {
            if (!string.IsNullOrWhiteSpace(value)) b.Append(key).Append('=').Append(value!.Trim()).Append('\n');
        }

        // ---- device ------------------------------------------------------
        Set("private_key", Hex(wg.PrivateKey));
        b.Append("replace_peers=true\n");

        // AmneziaWG 1.x: junk packets and the magic header values.
        Set("jc", wg.Jc); Set("jmin", wg.Jmin); Set("jmax", wg.Jmax);
        Set("s1", wg.S1); Set("s2", wg.S2); Set("s3", wg.S3); Set("s4", wg.S4);
        Set("h1", wg.H1); Set("h2", wg.H2); Set("h3", wg.H3); Set("h4", wg.H4);

        // 2.0: the signature chain — up to five packets sent ahead of a handshake so the flow
        // opens like something else (a QUIC initial, a DNS query). Free-form tag strings.
        Set("i1", wg.I1); Set("i2", wg.I2); Set("i3", wg.I3); Set("i4", wg.I4); Set("i5", wg.I5);

        // 3.x: header protection proper, plus the timings that used to be constants.
        // The key is 32 bytes and the panel writes it base64, exactly like the WireGuard keys —
        // and exactly like them, UAPI wants hex. Sending it through raw fails the whole config
        // with "invalid byte: U+004A 'J'", which is the engine choking on base64's alphabet.
        Set("header_protection_key", HexKey(wg.HeaderProtectionKey));
        Set("content_padding_addition", wg.ContentPaddingAddition);
        Set("rekey_after_time", wg.RekeyAfterTime);
        Set("rekey_timeout", wg.RekeyTimeout);
        Set("reject_after_time", wg.RejectAfterTime);
        Set("keepalive_timeout", wg.KeepaliveTimeout);
        Set("max_handshake_attempts", wg.MaxHandshakeAttempts);
        // Both arrived in 3.1. They do not exist in the 3.0 engine, where an unknown key is not
        // ignored but fails the whole IpcSet — so if this is ever built against an older commit,
        // these two are the first things to drop.
        // ...and both are booleans the core parses with Go's ParseBool, while the panel writes
        // them the ini way — "on". Passing that through fails the whole config with
        // «parsing "on": invalid syntax».
        Set("random_trailers", Bool(wg.RandomTrailers));
        Set("disable_cookies", Bool(wg.DisableCookies));

        // ---- peer --------------------------------------------------------
        Set("public_key", Hex(wg.PeerPublicKey));       // starts the peer section
        Set("preshared_key", Hex(wg.PresharedKey));
        Set("endpoint", wg.Endpoint);
        if (wg.PersistentKeepalive > 0)
            Set("persistent_keepalive_interval", wg.PersistentKeepalive.ToString());
        b.Append("replace_allowed_ips=true\n");
        foreach (var ip in wg.AllowedIps) Set("allowed_ip", ip);

        b.Append('\n');   // UAPI ends on a blank line; without it the core waits for more
        return b.ToString();
    }

    /// <summary>A config-file flag as the "true"/"false" Go's ParseBool accepts.</summary>
    private static string? Bool(string? value) => value?.Trim().ToLowerInvariant() switch
    {
        null or "" => null,
        "on" or "yes" or "true" or "1" or "enabled" => "true",
        _ => "false",
    };

    /// <summary>
    /// Hex for a key that may already be hex. Panels are not consistent about this: 3x-ui writes
    /// the header-protection key base64, the same way it writes the WireGuard keys, but the field
    /// is documented as hex and another panel may well write it that way.
    /// </summary>
    private static string? HexKey(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        string v = value.Trim();
        bool alreadyHex = v.Length % 2 == 0 && v.All(Uri.IsHexDigit);
        return alreadyHex ? v.ToLowerInvariant() : Hex(v);
    }

    /// <summary>A base64 key from the config file as the 64 hex characters UAPI expects, or null.</summary>
    private static string? Hex(string? base64)
    {
        if (string.IsNullOrWhiteSpace(base64)) return null;
        try { return Convert.ToHexString(Convert.FromBase64String(base64.Trim())).ToLowerInvariant(); }
        catch { return null; }   // not base64 — leave it out rather than send the core nonsense
    }
}
