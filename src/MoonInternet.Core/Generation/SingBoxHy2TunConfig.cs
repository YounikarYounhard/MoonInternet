using System.Text.Json;
using MoonInternet.Core.Models;

namespace MoonInternet.Core.Generation;

/// <summary>
/// Builds a sing-box config where ONE sing-box is both the TUN and the Hysteria2 outbound (like INCY/HAPP do it).
/// `auto_detect_interface` sends the Hysteria2 QUIC out the physical NIC, so sing-box's own server connection
/// bypasses its own TUN — no loop. Routing (РФ direct etc.) is applied here via sing-box rule-sets (.srs), the
/// sing-box equivalent of xray's geosite/geoip — mapped 1:1 from the profile's <c>geosite:X</c>/<c>geoip:X</c>
/// tags to <c>geosite-x.srs</c>/<c>geoip-x.srs</c>. If no rule-sets are available it falls back to all-proxy.
/// </summary>
public static class SingBoxHy2TunConfig
{
    public static string Build(Hy2Launch h, string interfaceName = "MoonTun",
                               RoutingProfile? routing = null, string? srsDir = null, string tunAddress = "172.19.0.1/30")
    {
        var tls = new Dictionary<string, object?>
        {
            ["enabled"] = true,
            ["server_name"] = string.IsNullOrEmpty(h.Sni) ? h.Address : h.Sni,
            ["insecure"] = h.AllowInsecure,
        };
        if (!string.IsNullOrEmpty(h.Alpn)) tls["alpn"] = h.Alpn.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

        var proxy = new Dictionary<string, object?>
        {
            ["type"] = "hysteria2", ["tag"] = "proxy", ["server"] = h.Address, ["server_port"] = h.Port,
            ["password"] = h.Password ?? "", ["tls"] = tls,
        };
        if (!string.IsNullOrEmpty(h.Obfs) || !string.IsNullOrEmpty(h.ObfsPassword))
            proxy["obfs"] = new Dictionary<string, object?> { ["type"] = "salamander", ["password"] = h.ObfsPassword ?? "" };
        if (h.UpMbps > 0) proxy["up_mbps"] = h.UpMbps;
        if (h.DownMbps > 0) proxy["down_mbps"] = h.DownMbps;

        // --- routing: map the profile's rules to sing-box rule-sets (.srs) + explicit domain/ip rules ---
        var ruleSets = new Dictionary<string, object?>();          // tag -> rule_set entry (deduped by tag)
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
            // block-proxy-direct order (matches the profiles' RouteOrder)
            AddRule(routing.BlockSites, routing.BlockIp, "block");
            AddRule(routing.ProxySites, routing.ProxyIp, "proxy");
            AddRule(routing.DirectSites, routing.DirectIp, "direct");
        }

        var outbounds = new List<object?> { proxy, new Dictionary<string, object?> { ["type"] = "direct", ["tag"] = "direct" } };
        if (rules.Any(x => x is Dictionary<string, object?> d && (d.GetValueOrDefault("outbound") as string) == "block"))
            outbounds.Add(new Dictionary<string, object?> { ["type"] = "block", ["tag"] = "block" });

        var route = new Dictionary<string, object?>
        {
            ["auto_detect_interface"] = true,
            ["final"] = "proxy",
            ["rules"] = rules,
        };
        if (ruleSets.Count > 0) route["rule_set"] = ruleSets.Values.ToList();

        var cfg = new Dictionary<string, object?>
        {
            ["log"] = new Dictionary<string, object?> { ["level"] = "warn" },
            ["dns"] = new Dictionary<string, object?>
            {
                ["servers"] = new object[] { new Dictionary<string, object?> { ["type"] = "https", ["tag"] = "remote", ["server"] = "1.1.1.1", ["detour"] = "proxy" } },
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
            // Clash API so the app can read live traffic here too (Hysteria2 TUN has no xray to query). Loopback only.
            ["experimental"] = new Dictionary<string, object?>
            {
                ["clash_api"] = new Dictionary<string, object?> { ["external_controller"] = "127.0.0.1:19099" },
            },
        };
        return JsonSerializer.Serialize(cfg, new JsonSerializerOptions { WriteIndented = true });
    }

    // Splits a Direct/Proxy/Block list into (rule-set tags that actually have a local .srs) and explicit entries.
    // A "geosite:CATEGORY-RU" tag maps to the file "geosite-category-ru.srs"; anything without the prefix is literal.
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
                string name = (kind + "-" + e[(kind.Length + 1)..]).ToLowerInvariant();   // geosite:CATEGORY-RU -> geosite-category-ru
                string path = System.IO.Path.Combine(srsDir, name + ".srs");
                if (!System.IO.File.Exists(path)) continue;   // only reference rule-sets we actually have
                if (!ruleSets.ContainsKey(name))
                    ruleSets[name] = new Dictionary<string, object?> { ["type"] = "local", ["tag"] = name, ["format"] = "binary", ["path"] = path };
                if (!tags.Contains(name)) tags.Add(name);
            }
            else if (e.Contains(':') && !kind.Equals("geoip")) { /* other geo scheme we don't have — skip */ }
            else if (kind == "geoip") literals.Add(e.Contains('/') ? e : e + "/32");   // bare IP -> /32
            else literals.Add(e.TrimStart('.'));                                        // domain suffix
        }
        return (tags, literals);
    }
}
