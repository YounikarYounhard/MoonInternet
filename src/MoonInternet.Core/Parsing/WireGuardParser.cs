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
        // vpn:// links carry no clean name here; label by endpoint host. Keep any #fragment on a wireguard:// link.
        int hash = input.IndexOf('#');
        if (hash >= 0 && hash < input.Length - 1) return Uri.UnescapeDataString(input[(hash + 1)..].Trim());
        return $"AmneziaWG {wg.EndpointHost}";
    }

    public static bool TryParse(string input, out WireGuardConfig? config)
    {
        config = null;
        try
        {
            input = input.Trim();
            string? ini =
                input.StartsWith("vpn://", StringComparison.OrdinalIgnoreCase) ? ExtractIniFromVpnUri(input[6..]) :
                input.StartsWith("[Interface]", StringComparison.OrdinalIgnoreCase) ? input :
                null;
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

    // Amnezia's vpn://: base64url( qCompress(json) ). qCompress = 4-byte big-endian length + zlib stream.
    private static string? ExtractIniFromVpnUri(string b64)
    {
        byte[] packed = Base64Ext.DecodeBytes(b64);
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
            if (line.Length == 0 || line.StartsWith('#')) continue;
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
                    case "jc": wg.Jc = val; break; case "jmin": wg.Jmin = val; break; case "jmax": wg.Jmax = val; break;
                    case "s1": wg.S1 = val; break; case "s2": wg.S2 = val; break; case "s3": wg.S3 = val; break; case "s4": wg.S4 = val; break;
                    case "h1": wg.H1 = val; break; case "h2": wg.H2 = val; break; case "h3": wg.H3 = val; break; case "h4": wg.H4 = val; break;
                    case "i1": wg.I1 = val; break; case "i2": wg.I2 = val; break; case "i3": wg.I3 = val; break; case "i4": wg.I4 = val; break; case "i5": wg.I5 = val; break;
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
