using System.IO.Compression;
using System.Text;
using System.Text.Json;
using MoonInternet.Core.Models;

namespace MoonInternet.Core.Parsing;

/// <summary>
/// Parses AmneziaWG/WireGuard configs: the raw <c>[Interface]/[Peer]</c> text, or Amnezia's
/// <c>vpn://</c> link (base64url of qCompress(JSON) whose <c>containers[].awg.last_config.config</c>
/// holds the very same <c>[Interface]/[Peer]</c> text).
/// </summary>
public static class WireGuardParser
{
    public static bool IsWireGuardLink(string s)
    {
        s = s.TrimStart();
        return s.StartsWith("vpn://", StringComparison.OrdinalIgnoreCase)
            || s.StartsWith("wireguard://", StringComparison.OrdinalIgnoreCase)
            || s.StartsWith("[Interface]", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>Parse into an <see cref="OutboundProfile"/> (Protocol=Wireguard) for the import/subscription flow.</summary>
    public static bool TryParseProfile(string input, out OutboundProfile? profile)
    {
        profile = null;
        if (!TryParse(input, out var wg) || wg is null) return false;
        profile = new OutboundProfile
        {
            Protocol = ProtocolType.Wireguard,
            Name = NameFromInput(input, wg),
            Address = wg.EndpointHost,
            Port = wg.EndpointPort,
            Wireguard = wg,
            Raw = input.Trim(),      // keep the original config so the server survives an app restart (offline cache)
        };
        return true;
    }

    private static string NameFromInput(string input, WireGuardConfig wg)
    {
        // A wireguard:// link may carry a #fragment. A vpn:// one does not — but the config it
        // wraps is commented, and that comment is where 3x-ui puts the label ("# AWG 3.0"). Without
        // reading it every AmneziaWG row was called "AmneziaWG <ip>" and they were indistinguishable.
        int hash = input.IndexOf('#');
        if (input.StartsWith("wireguard://", StringComparison.OrdinalIgnoreCase)
            && hash >= 0 && hash < input.Length - 1)
            return Uri.UnescapeDataString(input[(hash + 1)..].Trim());
        if (!string.IsNullOrWhiteSpace(wg.Label)) return wg.Label!;
        return $"AmneziaWG {wg.EndpointHost}";
    }

    /// <summary>
    /// The config as plain text — the <c>[Interface]</c> / <c>[Peer]</c> document itself.
    ///
    /// For showing people what they actually have. An AmneziaWG server has no xray outbound worth
    /// printing: ours is a SOCKS dial into the tunnel on loopback, which says nothing about their
    /// server and reads as gibberish. This is what the official client shows instead.
    /// </summary>
    public static string? ConfigText(string input)
    {
        input = input.Trim();
        try
        {
            return input.StartsWith("vpn://", StringComparison.OrdinalIgnoreCase) ? ExtractIniFromVpnUri(input[6..])
                : input.StartsWith("[Interface]", StringComparison.OrdinalIgnoreCase) ? input
                : null;
        }
        catch { return null; }
    }

    public static bool TryParse(string input, out WireGuardConfig? config)
    {
        config = null;
        try
        {
            input = input.Trim();
            string? ini = ConfigText(input);
            if (ini is null) return false;

            var wg = ParseIni(ini);
            if (!wg.IsValid) return false;
            config = wg;
            return true;
        }
        catch
        {
            config = null;
            return false;
        }
    }

    /// <summary>
    /// The payload of a <c>vpn://</c> link, which comes in two shapes.
    ///
    /// The Amnezia client writes base64url( qCompress(json) ) and buries the config inside.
    /// 3x-ui writes base64url of the <c>[Interface]/[Peer]</c> text itself — no compression, no
    /// JSON — and that is what a subscription from the panel actually contains. Only the first
    /// shape was handled, so every AmneziaWG entry in such a subscription failed to parse.
    /// </summary>
    private static string? ExtractIniFromVpnUri(string b64)
    {
        byte[] packed = Base64Ext.DecodeBytes(b64);
        if (packed.Length == 0) return null;

        // The plain shape announces itself: the text starts with the section header.
        string asText = Encoding.UTF8.GetString(packed);
        if (asText.TrimStart().StartsWith("[Interface]", StringComparison.OrdinalIgnoreCase)) return asText;

        if (packed.Length <= 4) return null;
        using var ms = new MemoryStream(packed, 4, packed.Length - 4);
        using var zs = new ZLibStream(ms, CompressionMode.Decompress);
        using var outMs = new MemoryStream();
        zs.CopyTo(outMs);
        using var doc = JsonDocument.Parse(Encoding.UTF8.GetString(outMs.ToArray()));

        // containers[].awg.last_config is a JSON *string*; its .config field is the [Interface]/[Peer] text.
        if (!doc.RootElement.TryGetProperty("containers", out var containers)) return null;
        foreach (var c in containers.EnumerateArray())
        {
            if (!c.TryGetProperty("awg", out var awg)) continue;
            if (!awg.TryGetProperty("last_config", out var lc)) continue;
            string lastCfg = lc.ValueKind == JsonValueKind.String ? lc.GetString()! : lc.GetRawText();
            using var inner = JsonDocument.Parse(lastCfg);
            if (inner.RootElement.TryGetProperty("config", out var cfg) && cfg.GetString() is { } text)
                return text;
        }
        return null;
    }

    private static WireGuardConfig ParseIni(string ini)
    {
        var wg = new WireGuardConfig();
        string section = "";
        foreach (var raw in ini.Replace("\r", "").Split('\n'))
        {
            string line = raw.Trim();
            if (line.Length == 0) continue;
            if (line.StartsWith('#'))
            {
                // The panel labels a config with a comment and nothing else, so this is the only
                // place its name exists. First one wins; later comments are ordinary remarks.
                string label = line.TrimStart('#').Trim();
                if (wg.Label is null && label.Length > 0) wg.Label = label;
                continue;
            }
            if (line.StartsWith('[') && line.EndsWith(']')) { section = line[1..^1].Trim().ToLowerInvariant(); continue; }

            int eq = line.IndexOf('=');
            if (eq < 0) continue;
            string key = line[..eq].Trim().ToLowerInvariant();
            string val = line[(eq + 1)..].Trim();
            if (val.Length == 0) continue;

            List<string> Csv() => val.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();

            if (section == "interface")
                switch (key)
                {
                    case "privatekey": wg.PrivateKey = val; break;
                    case "address": wg.Address = Csv(); break;
                    case "dns": wg.Dns = Csv().Where(d => !d.StartsWith('$')).ToList(); break; // skip $PRIMARY_DNS placeholders
                    // Every config the panel writes sets one, and dropping it left the tunnel on a
                    // default that is larger than the link — the packets that then need fragmenting
                    // are the ones a censor's box is most interested in.
                    case "mtu": if (int.TryParse(val, out var mtu) && mtu is >= 576 and <= 1500) wg.Mtu = mtu; break;
                    case "jc": wg.Jc = val; break; case "jmin": wg.Jmin = val; break; case "jmax": wg.Jmax = val; break;
                    case "s1": wg.S1 = val; break; case "s2": wg.S2 = val; break; case "s3": wg.S3 = val; break; case "s4": wg.S4 = val; break;
                    case "h1": wg.H1 = val; break; case "h2": wg.H2 = val; break; case "h3": wg.H3 = val; break; case "h4": wg.H4 = val; break;
                    case "i1": wg.I1 = val; break; case "i2": wg.I2 = val; break; case "i3": wg.I3 = val; break; case "i4": wg.I4 = val; break; case "i5": wg.I5 = val; break;
                    // AmneziaWG 3.x. 3x-ui writes these into the client config now; reading the
                    // file and dropping them produced a 2.0 profile that still connects, so the
                    // protection would have gone quietly.
                    case "headerprotectionkey": wg.HeaderProtectionKey = val; break;
                    case "contentpaddingaddition": wg.ContentPaddingAddition = val; break;
                    case "rekeyaftertime": wg.RekeyAfterTime = val; break;
                    case "rekeytimeout": wg.RekeyTimeout = val; break;
                    case "rejectaftertime": wg.RejectAfterTime = val; break;
                    case "keepalivetimeout": wg.KeepaliveTimeout = val; break;
                    case "maxhandshakeattempts": wg.MaxHandshakeAttempts = val; break;
                    case "randomtrailers": wg.RandomTrailers = val; break;
                    case "disablecookies": wg.DisableCookies = val; break;
                }
            else if (section == "peer")
                switch (key)
                {
                    case "publickey": wg.PeerPublicKey = val; break;
                    case "presharedkey": wg.PresharedKey = val; break;
                    case "allowedips": wg.AllowedIps = Csv(); break;
                    case "endpoint": wg.Endpoint = val; break;
                    case "persistentkeepalive": if (int.TryParse(val, out var k)) wg.PersistentKeepalive = k; break;
                }
        }
        if (wg.AllowedIps.Count == 0) wg.AllowedIps = new() { "0.0.0.0/0", "::/0" };
        if (wg.Dns.Count == 0) wg.Dns = new() { "1.1.1.1" };
        return wg;
    }
}
