using System.Text.Json;
using MoonInternet.Core.Models;

namespace MoonInternet.Core.Generation;

/// <summary>
/// Builds an xray-core config.json for a single selected outbound + local SOCKS/HTTP inbounds.
/// Hysteria2 / WireGuard are sing-box protocols and are rejected here (handled by the sing-box builder).
/// </summary>
public static class XrayConfigBuilder
{
    private static readonly JsonSerializerOptions Pretty = new()
    {
        WriteIndented = true,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping
    };

    public static string Build(OutboundProfile p, int socksPort = 10808, int httpPort = 10809)
        => Build(p, null, socksPort, httpPort);

    public static string Build(OutboundProfile p, RoutingProfile? routing, int socksPort = 10808, int httpPort = 10809, int apiPort = 0)
    {
        // Sniffing (below) recovers the domain from each connection, so routing works by domain with
        // AsIs strategy — no per-connection DNS resolution and no FakeDNS. This keeps the server load low.
        var outbounds = new List<object?>
        {
            BuildOutbound(p, "proxy"),
            new Dictionary<string, object?> { ["protocol"] = "freedom", ["tag"] = "direct" },
            new Dictionary<string, object?> { ["protocol"] = "blackhole", ["tag"] = "block" },
        };
        // TLS-hello fragmentation: a freedom outbound the proxy dials through (see sockopt.dialerProxy above).
        if (XrayTuning.Fragment)
            outbounds.Add(new Dictionary<string, object?>
            {
                ["protocol"] = "freedom", ["tag"] = "fragment",
                ["settings"] = new Dictionary<string, object?> { ["fragment"] = new Dictionary<string, object?> { ["packets"] = "tlshello", ["length"] = "100-200", ["interval"] = "10-20" } }
            });
        // A "dns" outbound lets xray ANSWER DNS queries itself (via the DoH resolver in BuildDns) when a UDP :53
        // packet reaches it — needed for the tun2socks TUN engine, which forwards raw DNS instead of hijacking it.
        // Harmless for sing-box TUN / system-proxy (they never send :53 to xray). DoH upstream is :443, so the
        // "port 53 → dns-out" rule below can't loop on the resolver's own query.
        if (routing is not null) outbounds.Add(new Dictionary<string, object?> { ["protocol"] = "dns", ["tag"] = "dns-out" });

        var inbounds = new List<object?>
        {
            Inbound("socks-in", socksPort, "socks", SocksInboundSettings()),
            Inbound("http-in", httpPort, "http", HttpInboundSettings()),
        };
        var levels = new Dictionary<string, object?>
        {
            ["8"] = new Dictionary<string, object?>
            {
                ["handshake"] = 3, ["connIdle"] = 300, ["uplinkOnly"] = 2, ["downlinkOnly"] = 4, ["bufferSize"] = 3
            }
        };
        // Level 0 is what ordinary traffic runs at, so this is where the priority mode bites:
        // a smaller buffer means the core stops a bulk transfer running ahead, and the queue
        // everything else waits behind stays short.
        // Ordinary traffic runs at level 0 and had no timeouts of its own, so a connection the
        // client had finished with was held for xray's default five minutes. On a server carrying
        // a whole subscription those pile up. Same figures the Android build writes.
        var level0 = new Dictionary<string, object?>
        {
            ["handshake"] = 4, ["connIdle"] = 120, ["uplinkOnly"] = 1, ["downlinkOnly"] = 1,
        };
        if (XrayTuning.BufferSizeKb is { } buf) level0["bufferSize"] = buf;
        levels["0"] = level0;

        var policy = new Dictionary<string, object?> { ["levels"] = levels };
        var routingCfg = BuildRouting(routing);

        // The selected server's OWN address must go out DIRECT — never back through the tunnel. Otherwise, in TUN
        // mode, anything you open on that host (its web panel, SSH/console) loops: TUN → proxy → same server → drop.
        // Match by sniffed domain (host + subdomains), or by IP if the address is already an IP. Inserted first so
        // it wins over the proxy rules. (The proxy OUTBOUND's own dial to the server is separate — unaffected.)
        if (!string.IsNullOrWhiteSpace(p.Address))
        {
            var serverRule = System.Net.IPAddress.TryParse(p.Address, out _)
                ? new Dictionary<string, object?> { ["type"] = "field", ["ip"] = new[] { p.Address }, ["outboundTag"] = "direct" }
                : new Dictionary<string, object?> { ["type"] = "field", ["domain"] = new[] { "domain:" + p.Address }, ["outboundTag"] = "direct" };
            ((List<object?>)routingCfg["rules"]!).Insert(0, serverRule);
        }

        // Traffic stats for the live up/down counters. Like INCY, we count the LOCAL INBOUND (payload delivered to /
        // from the app) — the true user-facing traffic — NOT the outbound (which adds TLS/gRPC/protocol wire overhead).
        if (apiPort > 0)
        {
            inbounds.Add(new Dictionary<string, object?>
            {
                ["tag"] = "api", ["listen"] = "127.0.0.1", ["port"] = apiPort,
                ["protocol"] = "dokodemo-door", ["settings"] = new Dictionary<string, object?> { ["address"] = "127.0.0.1" },
            });
            policy["system"] = new Dictionary<string, object?>
            {
                ["statsInboundUplink"] = true, ["statsInboundDownlink"] = true,
                ["statsOutboundUplink"] = true, ["statsOutboundDownlink"] = true,
            };
            ((List<object?>)routingCfg["rules"]!).Insert(0, new Dictionary<string, object?>
            {
                ["type"] = "field", ["inboundTag"] = new[] { "api" }, ["outboundTag"] = "api"
            });
        }

        var config = new Dictionary<string, object?>
        {
            // Logging was pinned to "none", which made the whole Логи page decorative: the level,
            // the retention and the size row had nothing behind them because the core was told to
            // write nothing. It follows the setting now, and writes to a file when one is given.
            ["log"] = BuildLog(),
            ["inbounds"] = inbounds,
            ["outbounds"] = outbounds,
            ["routing"] = routingCfg,
            ["policy"] = policy,
        };
        if (apiPort > 0)
        {
            config["stats"] = new Dictionary<string, object?>();
            config["api"] = new Dictionary<string, object?> { ["tag"] = "api", ["services"] = new[] { "StatsService" } };
        }
        if (routing is not null) config["dns"] = BuildDns(routing);
        return JsonSerializer.Serialize(config, Pretty);
    }

    // ---- routing / dns (HAPP / INCY) ------------------------------------

    private static Dictionary<string, object?> BuildRouting(RoutingProfile? r)
    {
        var rules = new List<object?>
        {
            new Dictionary<string, object?> { ["type"] = "field", ["ip"] = new[] { "geoip:private" }, ["outboundTag"] = "direct" },
        };
        if (r is null)
            return new Dictionary<string, object?> { ["domainStrategy"] = "AsIs", ["rules"] = rules };

        // DNS (:53) → the dns outbound, which resolves via DoH. Only reached when a TUN engine forwards raw DNS.
        rules.Add(new Dictionary<string, object?> { ["type"] = "field", ["port"] = "53", ["outboundTag"] = "dns-out" });

        void SiteRule(List<string> sites, string tag)
        {
            if (sites.Count > 0) rules.Add(new Dictionary<string, object?> { ["type"] = "field", ["domain"] = sites, ["outboundTag"] = tag });
        }
        void IpRule(List<string> ips, string tag)
        {
            if (ips.Count > 0) rules.Add(new Dictionary<string, object?> { ["type"] = "field", ["ip"] = ips, ["outboundTag"] = tag });
        }

        // Evaluate buckets in the order the profile asks (e.g. "block-proxy-direct").
        foreach (var bucket in r.RouteOrder.Split('-', StringSplitOptions.RemoveEmptyEntries))
        {
            switch (bucket.Trim().ToLowerInvariant())
            {
                case "block": SiteRule(r.BlockSites, "block"); IpRule(r.BlockIp, "block"); break;
                case "proxy": SiteRule(r.ProxySites, "proxy"); IpRule(r.ProxyIp, "proxy"); break;
                case "direct": SiteRule(r.DirectSites, "direct"); IpRule(r.DirectIp, "direct"); break;
            }
        }

        // Unmatched traffic: proxy (GlobalProxy) or direct.
        if (!r.GlobalProxy)
            rules.Add(new Dictionary<string, object?> { ["type"] = "field", ["network"] = "tcp,udp", ["outboundTag"] = "direct" });

        // AsIs (not IPIfNonMatch): route by the sniffed domain without resolving every connection to an IP.
        // IPIfNonMatch resolves the majority of (non-listed) domains → a DNS/connection storm on the server.
        return new Dictionary<string, object?> { ["domainStrategy"] = "AsIs", ["rules"] = rules };
    }

    private static Dictionary<string, object?> BuildDns(RoutingProfile r)
    {
        // Minimal DNS: one remote resolver + host overrides. Routing is domain-based (AsIs) so we do NOT
        // add split/expectIPs servers that would force per-connection resolution.
        // VPN DNS: the user's chosen resolvers (Tunnel settings) win; fall back to the routing profile's own DNS.
        var servers = XrayTuning.Dns is { Length: > 0 } d
            ? d.Cast<object?>().ToList()
            : new List<object?> { !string.IsNullOrWhiteSpace(r.RemoteDNSDomain) ? r.RemoteDNSDomain
                                  : !string.IsNullOrWhiteSpace(r.RemoteDNSIP) ? r.RemoteDNSIP : "1.1.1.1" };
        var dns = new Dictionary<string, object?>
        {
            ["servers"] = servers,
            ["queryStrategy"] = XrayTuning.QueryStrategy,
        };
        if (r.DnsHosts.Count > 0) dns["hosts"] = r.DnsHosts;
        return dns;
    }

    /// <summary>Pretty single-outbound JSON for the per-server config view (LOCAL display only; shows the server's own credentials).
    /// Hysteria2 / WireGuard aren't xray outbounds → fall back to a readable field dump.</summary>
    public static string OutboundJson(OutboundProfile p)
    {
        try { return JsonSerializer.Serialize(BuildOutbound(p, "proxy"), Pretty); }
        catch (NotSupportedException)
        {
            var d = new Dictionary<string, object?>
            {
                ["protocol"] = p.Protocol.ToString().ToLowerInvariant(), ["tag"] = "proxy",
                ["server"] = p.Address, ["server_port"] = p.Port,
                ["uuid"] = p.Id, ["password"] = p.Password, ["method"] = p.Method,
                ["obfs"] = p.Obfs, ["obfs_password"] = p.ObfsPassword,
                ["network"] = p.Network, ["security"] = p.Security, ["sni"] = p.Sni, ["alpn"] = p.Alpn,
                ["insecure"] = p.AllowInsecure ? true : (bool?)null,
            };
            var pruned = d.Where(kv => kv.Value is not null && (kv.Value as string) != "").ToDictionary(kv => kv.Key, kv => kv.Value);
            return JsonSerializer.Serialize(pruned, Pretty);
        }
    }

    public static Dictionary<string, object?> BuildOutbound(OutboundProfile p, string tag = "proxy")
    {
        var o = new Dictionary<string, object?> { ["tag"] = tag, ["protocol"] = ProtoName(p.Protocol) };
        o["settings"] = p.Protocol switch
        {
            ProtocolType.Vless => Vnext(p, VlessUser(p)),
            ProtocolType.Vmess => Vnext(p, VmessUser(p)),
            ProtocolType.Trojan => Servers(new Dictionary<string, object?> { ["address"] = p.Address, ["port"] = p.Port, ["password"] = p.Password ?? "", ["level"] = 8 }),
            ProtocolType.Shadowsocks => Servers(new Dictionary<string, object?> { ["address"] = p.Address, ["port"] = p.Port, ["method"] = p.Method ?? "", ["password"] = p.Password ?? "", ["level"] = 8 }),
            ProtocolType.Socks => Servers(SocksServer(p)),
            _ => throw new NotSupportedException($"{p.Protocol} is a sing-box protocol, not xray")
        };
        var stream = BuildStream(p);
        if (tag == "proxy" && XrayTuning.Fragment)   // route the real dial through the "fragment" freedom outbound
            stream["sockopt"] = new Dictionary<string, object?> { ["dialerProxy"] = "fragment" };
        if (stream.Count > 0) o["streamSettings"] = stream;
        if (tag == "proxy" && XrayTuning.EffectiveMux)
            o["mux"] = new Dictionary<string, object?> { ["enabled"] = true, ["concurrency"] = 8 };
        return o;
    }

    // ---- stream / transport / security ----------------------------------

    private static Dictionary<string, object?> BuildStream(OutboundProfile p)
    {
        var s = new Dictionary<string, object?>();
        string net = p.Network == "h2" ? "http" : p.Network;
        s["network"] = net;

        if (p.Security is "tls" or "reality")
        {
            s["security"] = p.Security;
            var tls = new Dictionary<string, object?>();
            if (!string.IsNullOrEmpty(p.Sni)) tls["serverName"] = p.Sni;
            if (!string.IsNullOrEmpty(p.Fingerprint)) tls["fingerprint"] = p.Fingerprint;
            if (!string.IsNullOrEmpty(p.Alpn))
                tls["alpn"] = p.Alpn.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            if (p.Security == "tls")
            {
                tls["allowInsecure"] = p.AllowInsecure;
                s["tlsSettings"] = tls;
            }
            else
            {
                if (!string.IsNullOrEmpty(p.PublicKey)) tls["publicKey"] = p.PublicKey;
                if (p.ShortId != null) tls["shortId"] = p.ShortId;
                if (!string.IsNullOrEmpty(p.SpiderX)) tls["spiderX"] = p.SpiderX;
                s["realitySettings"] = tls;
            }
        }

        switch (net)
        {
            case "ws":
                var ws = new Dictionary<string, object?> { ["path"] = p.Path ?? "/" };
                if (!string.IsNullOrEmpty(p.Host)) ws["host"] = p.Host;
                s["wsSettings"] = ws;
                break;
            case "grpc":
                // gRPC keeps one long-lived HTTP/2 connection, and without a health check neither
                // end notices when it dies: the client quietly opens another and the server is left
                // holding a half-open stream. On a server carrying a whole subscription those pile up
                // until it runs out of memory — which looks exactly like «this protocol died but the
                // others on the same server are fine», because the others are separate inbounds.

                // 60s idle before a ping, 20s to answer it. Lower values make servers answer GOAWAY
                // with too_many_pings, which trades a slow leak for instant disconnects.
                s["grpcSettings"] = new Dictionary<string, object?>
                {
                    ["serviceName"] = p.ServiceName ?? "",
                    ["multiMode"] = p.Extra.GetValueOrDefault("mode") == "multi",
                    ["idle_timeout"] = 60,
                    ["health_check_timeout"] = 20,
                    ["permit_without_stream"] = false,
                };
                break;
            case "http":
                var h2 = new Dictionary<string, object?> { ["path"] = p.Path ?? "/" };
                if (!string.IsNullOrEmpty(p.Host)) h2["host"] = p.Host.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
                s["httpSettings"] = h2;
                break;
            case "xhttp":
                var xh = new Dictionary<string, object?> { ["path"] = p.Path ?? "/" };
                if (!string.IsNullOrEmpty(p.Host)) xh["host"] = p.Host;
                var mode = p.Extra.GetValueOrDefault("mode");
                if (!string.IsNullOrEmpty(mode)) xh["mode"] = mode;
                var extra = p.Extra.GetValueOrDefault("extra");
                if (!string.IsNullOrEmpty(extra))
                {
                    try { xh["extra"] = JsonSerializer.Deserialize<JsonElement>(extra); } catch { /* leave raw off if malformed */ }
                }
                s["xhttpSettings"] = xh;
                break;
            case "tcp":
                if (p.HeaderType == "http")
                    s["tcpSettings"] = new Dictionary<string, object?> { ["header"] = new Dictionary<string, object?> { ["type"] = "http" } };
                break;
        }
        return s;
    }

    // ---- small builders -------------------------------------------------

    private static List<object?>? ProxyAccounts() =>
        !string.IsNullOrEmpty(XrayTuning.SocksUser) && !string.IsNullOrEmpty(XrayTuning.SocksPass)
            ? new List<object?> { new Dictionary<string, object?> { ["user"] = XrayTuning.SocksUser, ["pass"] = XrayTuning.SocksPass } }
            : null;
    private static Dictionary<string, object?> SocksInboundSettings()
    {
        var s = new Dictionary<string, object?> { ["udp"] = !XrayTuning.BlockUdp };
        if (ProxyAccounts() is { } acc) { s["auth"] = "password"; s["accounts"] = acc; } else s["auth"] = "noauth";
        return s;
    }
    private static Dictionary<string, object?> HttpInboundSettings()
    {
        var s = new Dictionary<string, object?>();
        if (XrayTuning.HttpAuth && ProxyAccounts() is { } acc) s["accounts"] = acc;
        return s;
    }

    private static Dictionary<string, object?> Inbound(string tag, int port, string protocol, Dictionary<string, object?> settings, bool fakeDns = false) => new()
    {
        ["tag"] = tag,
        ["listen"] = "127.0.0.1",
        ["port"] = port,
        ["protocol"] = protocol,
        ["settings"] = settings,
        ["sniffing"] = new Dictionary<string, object?>
        {
            ["enabled"] = XrayTuning.Sniffing,
            ["destOverride"] = new[] { "http", "tls", "quic" },
            ["routeOnly"] = true // route by sniffed domain but keep the original IP → server doesn't re-resolve
        }
    };

    private static Dictionary<string, object?> Vnext(OutboundProfile p, Dictionary<string, object?> user) => new()
    {
        ["vnext"] = new List<object?>
        {
            new Dictionary<string, object?> { ["address"] = p.Address, ["port"] = p.Port, ["users"] = new List<object?> { user } }
        }
    };

    private static Dictionary<string, object?> Servers(Dictionary<string, object?> server) => new()
    {
        ["servers"] = new List<object?> { server }
    };

    private static Dictionary<string, object?> VlessUser(OutboundProfile p)
    {
        var u = new Dictionary<string, object?> { ["id"] = p.Id, ["encryption"] = string.IsNullOrEmpty(p.Encryption) ? "none" : p.Encryption, ["level"] = 8 };
        if (!string.IsNullOrEmpty(p.Flow)) u["flow"] = p.Flow;
        return u;
    }

    private static Dictionary<string, object?> VmessUser(OutboundProfile p) => new()
    {
        ["id"] = p.Id,
        ["alterId"] = p.AlterId,
        ["security"] = string.IsNullOrEmpty(p.Encryption) ? "auto" : p.Encryption,
        ["level"] = 8
    };

    private static Dictionary<string, object?> SocksServer(OutboundProfile p)
    {
        var srv = new Dictionary<string, object?> { ["address"] = p.Address, ["port"] = p.Port };
        if (!string.IsNullOrEmpty(p.Username))
            srv["users"] = new List<object?> { new Dictionary<string, object?> { ["user"] = p.Username, ["pass"] = p.Password ?? "" } };
        return srv;
    }

    /// <summary>
    /// The log block. A file path only goes in when logging is actually on — handing xray a path
    /// at level "none" would create an empty file and make the size row lie the other way.
    /// </summary>
    private static Dictionary<string, object?> BuildLog()
    {
        var level = string.IsNullOrWhiteSpace(XrayTuning.LogLevel) ? "none" : XrayTuning.LogLevel;
        var log = new Dictionary<string, object?> { ["loglevel"] = level };
        if (level != "none" && !string.IsNullOrWhiteSpace(XrayTuning.LogFile))
            log["error"] = XrayTuning.LogFile;
        return log;
    }

    /// <summary>
    /// True when xray can carry this protocol. Hysteria2 and WireGuard go through sing-box on the
    /// desktop, so anything building an xray-only config has to ask first rather than throw.
    /// </summary>
    public static bool Supports(ProtocolType t) =>
        t is ProtocolType.Vless or ProtocolType.Vmess or ProtocolType.Trojan
          or ProtocolType.Shadowsocks or ProtocolType.Socks;

    private static string ProtoName(ProtocolType t) => t switch
    {
        ProtocolType.Vless => "vless",
        ProtocolType.Vmess => "vmess",
        ProtocolType.Trojan => "trojan",
        ProtocolType.Shadowsocks => "shadowsocks",
        ProtocolType.Socks => "socks",
        _ => throw new NotSupportedException($"{t} not an xray protocol")
    };
}
