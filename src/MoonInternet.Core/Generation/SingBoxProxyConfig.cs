using System.Text.Json;
using MoonInternet.Core.Models;

namespace MoonInternet.Core.Generation;

/// <summary>
/// Builds a sing-box config that exposes a local mixed (SOCKS+HTTP) proxy backed by a Hysteria2 outbound —
/// xray-core can't speak Hysteria2, so for those servers sing-box plays the role xray plays for everything else
/// (the app's local proxy that the TUN forwards to, or the system proxy points at). One port serves both SOCKS
/// and HTTP. All traffic goes out the Hysteria2 tunnel (routing is applied upstream, like the xray path).
/// </summary>
public static class SingBoxProxyConfig
{
    public static string Build(OutboundProfile p, int port)
    {
        var tls = new Dictionary<string, object?>
        {
            ["enabled"] = true,
            ["server_name"] = string.IsNullOrEmpty(p.Sni) ? p.Address : p.Sni,
            ["insecure"] = p.AllowInsecure,
        };
        if (!string.IsNullOrEmpty(p.Alpn)) tls["alpn"] = p.Alpn.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

        var outbound = new Dictionary<string, object?>
        {
            ["type"] = "hysteria2",
            ["tag"] = "proxy",
            ["server"] = p.Address,
            ["server_port"] = p.Port,
            ["password"] = p.Password ?? "",
            ["tls"] = tls,
        };
        // Salamander is Hysteria2's only obfuscation — include it only when the link carried one.
        if (!string.IsNullOrEmpty(p.Obfs) || !string.IsNullOrEmpty(p.ObfsPassword))
            outbound["obfs"] = new Dictionary<string, object?> { ["type"] = "salamander", ["password"] = p.ObfsPassword ?? "" };
        // Bandwidth hints drive Hysteria's congestion control — set them if the link/sub provided them (better video).
        if (TryMbps(p, "up", "upmbps", out var up)) outbound["up_mbps"] = up;
        if (TryMbps(p, "down", "downmbps", out var down)) outbound["down_mbps"] = down;

        var cfg = new Dictionary<string, object?>
        {
            ["log"] = new Dictionary<string, object?> { ["level"] = "warn" },
            ["inbounds"] = new object[]
            {
                new Dictionary<string, object?>
                {
                    ["type"] = "mixed", ["tag"] = "in", ["listen"] = "127.0.0.1", ["listen_port"] = port,
                }
            },
            ["outbounds"] = new object[] { outbound, new Dictionary<string, object?> { ["type"] = "direct", ["tag"] = "direct" } },
            ["route"] = new Dictionary<string, object?> { ["final"] = "proxy" },
        };
        return JsonSerializer.Serialize(cfg, new JsonSerializerOptions { WriteIndented = true });
    }

    private static bool TryMbps(OutboundProfile p, string k1, string k2, out int mbps)
    {
        mbps = 0;
        var v = p.Extra.GetValueOrDefault(k1) ?? p.Extra.GetValueOrDefault(k2);
        return int.TryParse(v, out mbps) && mbps > 0;
    }
}
