using System.Text.Json;
using MoonInternet.Core.Models;

namespace MoonInternet.Core.Generation;

/// <summary>
/// One sing-box that is BOTH the TUN and a WireGuard outbound — the same shape as the Hysteria2 path.
/// Used for plain WireGuard (no AmneziaWG obfuscation), because it gets us full routing: sing-box applies the
/// profile's Direct/Proxy/Block rules via .srs rule-sets, which the raw amneziawg-go path can't do.
/// AmneziaWG (Jc/S1/H1…) is NOT supported by sing-box, so those servers keep using amneziawg-go.
/// </summary>
public static class SingBoxWgTunConfig
{
    public static string Build(WireGuardConfig w, string interfaceName = "MoonTun",
                               RoutingProfile? routing = null, string? srsDir = null, string tunAddress = "172.19.0.1/30")
    {
        var (host, portText) = SplitEndpoint(w.Endpoint);
        var peer = new Dictionary<string, object?>
        {
            ["server"] = host,
            ["server_port"] = int.TryParse(portText, out var pp) ? pp : 51820,
            ["public_key"] = w.PeerPublicKey,
            ["allowed_ips"] = w.AllowedIps.Count > 0 ? w.AllowedIps : new List<string> { "0.0.0.0/0" },
        };
        if (!string.IsNullOrWhiteSpace(w.PresharedKey)) peer["pre_shared_key"] = w.PresharedKey;
        if (w.PersistentKeepalive > 0) peer["persistent_keepalive_interval"] = w.PersistentKeepalive;

        var proxy = new Dictionary<string, object?>
        {
            ["type"] = "wireguard", ["tag"] = "proxy",
            ["address"] = w.Address,
            ["private_key"] = w.PrivateKey,
            ["peers"] = new List<object?> { peer },
        };

        var ruleSets = new Dictionary<string, object?>();
        var rules = new List<object?>
        {
            new Dictionary<string, object?> { ["action"] = "sniff" },
            new Dictionary<string, object?> { ["protocol"] = "dns", ["action"] = "hijack-dns" },
            new Dictionary<string, object?> { ["ip_is_private"] = true, ["outbound"] = "direct" },
        };

        void AddRule(IEnumerable<string> sites, IEnumerable<string> ips, string outbound)
        {
            var (srs, domains) = Split(sites, "geosite", srsDir, ruleSets);
            var (ipSrs, cidrs) = Split(ips, "geoip", srsDir, ruleSets);
            srs.AddRange(ipSrs);
            if (srs.Count == 0 && domains.Count == 0 && cidrs.Count == 0) return;
            var r = new Dictionary<string, object?> { ["outbound"] = outbound };
            if (srs.Count > 0) r["rule_set"] = srs;
            if (domains.Count > 0) r["domain_suffix"] = domains;
            if (cidrs.Count > 0) r["ip_cidr"] = cidrs;
            rules.Add(r);
        }

        if (routing is not null && !string.IsNullOrEmpty(srsDir))
        {
            AddRule(routing.BlockSites, routing.BlockIp, "block");
            AddRule(routing.ProxySites, routing.ProxyIp, "proxy");
            AddRule(routing.DirectSites, routing.DirectIp, "direct");
        }

        var outbounds = new List<object?> { proxy, new Dictionary<string, object?> { ["type"] = "direct", ["tag"] = "direct" } };
        if (rules.Any(x => x is Dictionary<string, object?> d && (d.GetValueOrDefault("outbound") as string) == "block"))
            outbounds.Add(new Dictionary<string, object?> { ["type"] = "block", ["tag"] = "block" });

        var route = new Dictionary<string, object?>
        {
            ["auto_detect_interface"] = true,   // the WG handshake leaves via the physical NIC → no loop through our own TUN
            ["final"] = "proxy",
            ["rules"] = rules,
        };
        if (ruleSets.Count > 0) route["rule_set"] = ruleSets.Values.ToList();

        var dnsServer = w.Dns.Count > 0 ? w.Dns[0] : "1.1.1.1";
        var cfg = new Dictionary<string, object?>
        {
            ["log"] = new Dictionary<string, object?> { ["level"] = "warn" },
            ["dns"] = new Dictionary<string, object?>
            {
                ["servers"] = new object[] { new Dictionary<string, object?> { ["type"] = "udp", ["tag"] = "remote", ["server"] = dnsServer, ["detour"] = "proxy" } },
                ["strategy"] = "ipv4_only",
            },
            ["inbounds"] = new object[]
            {
                new Dictionary<string, object?>
                {
                    ["type"] = "tun", ["tag"] = "tun-in", ["interface_name"] = interfaceName,
                    ["address"] = new[] { tunAddress }, ["auto_route"] = true, ["strict_route"] = true, ["stack"] = "gvisor",
                }
            },
            ["outbounds"] = outbounds,
            ["route"] = route,
            ["experimental"] = new Dictionary<string, object?>
            {
                ["clash_api"] = new Dictionary<string, object?> { ["external_controller"] = "127.0.0.1:19099" },
            },
        };
        return JsonSerializer.Serialize(cfg, new JsonSerializerOptions { WriteIndented = true });
    }

    private static (string host, string port) SplitEndpoint(string endpoint)
    {
        int i = endpoint.LastIndexOf(':');
        return i > 0 ? (endpoint[..i], endpoint[(i + 1)..]) : (endpoint, "51820");
    }

    // Same mapping as the Hysteria2 builder: "geosite:CATEGORY-RU" → geosite-category-ru.srs (only if present).
    private static (List<object?> srsTags, List<string> literals) Split(IEnumerable<string> entries, string kind, string? srsDir, Dictionary<string, object?> ruleSets)
    {
        var tags = new List<object?>();
        var literals = new List<string>();
        foreach (var raw in entries)
        {
            var e = raw.Trim();
            if (e.Length == 0) continue;
            if (e.StartsWith(kind + ":", StringComparison.OrdinalIgnoreCase))
            {
                if (string.IsNullOrEmpty(srsDir)) continue;
                string name = (kind + "-" + e[(kind.Length + 1)..]).ToLowerInvariant();
                string path = System.IO.Path.Combine(srsDir, name + ".srs");
                if (!System.IO.File.Exists(path)) continue;
                if (!ruleSets.ContainsKey(name))
                    ruleSets[name] = new Dictionary<string, object?> { ["type"] = "local", ["tag"] = name, ["format"] = "binary", ["path"] = path };
                if (!tags.Contains(name)) tags.Add(name);
            }
            else if (e.Contains(':') && !kind.Equals("geoip")) { /* unknown geo scheme */ }
            else if (kind == "geoip") literals.Add(e.Contains('/') ? e : e + "/32");
            else literals.Add(e.TrimStart('.'));
        }
        return (tags, literals);
    }
}
