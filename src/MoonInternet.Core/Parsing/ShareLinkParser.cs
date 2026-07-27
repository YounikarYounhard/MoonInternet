using System.Text.Json;
using MoonInternet.Core.Models;

namespace MoonInternet.Core.Parsing;

/// <summary>
/// Parses a single share link (vless://, vmess://, trojan://, ss://, hysteria2://, socks://)
/// into an <see cref="OutboundProfile"/>. Trust boundary: every result is validated before return.
/// </summary>
public static class ShareLinkParser
{
    public static OutboundProfile Parse(string link)
    {
        if (!TryParse(link, out var p, out var error))
            throw new FormatException(error);
        return p!;
    }

    public static bool TryParse(string link, out OutboundProfile? profile, out string error)
    {
        profile = null;
        error = "";
        try
        {
            link = link.Trim();
            int sep = link.IndexOf("://", StringComparison.Ordinal);
            if (sep <= 0) { error = "no scheme"; return false; }
            string scheme = link[..sep].ToLowerInvariant();

            profile = scheme switch
            {
                "vless" => ParseVless(link),
                "vmess" => ParseVmess(link),
                "trojan" => ParseTrojan(link),
                "ss" => ParseShadowsocks(link),
                "hysteria2" or "hy2" => ParseHysteria2(link),
                "socks" or "socks5" => ParseSocks(link),
                _ => throw new FormatException($"unsupported scheme '{scheme}'")
            };

            Validate(profile);
            profile.Raw = link;                 // keep the original link for Copy URL / QR
            return true;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            profile = null;
            return false;
        }
    }

    // ---- per-protocol ----------------------------------------------------

    private static OutboundProfile ParseVless(string link)
    {
        var (uri, q, name) = SplitUri(link);
        var p = new OutboundProfile
        {
            Protocol = ProtocolType.Vless,
            Name = name,
            Address = HostOf(uri),
            Port = uri.Port,
            Id = Uri.UnescapeDataString(uri.UserInfo),
            Encryption = q.GetValueOrDefault("encryption", "none"),
            Flow = q.GetValueOrDefault("flow"),
        };
        ApplyStream(p, q);
        return p;
    }

    private static OutboundProfile ParseTrojan(string link)
    {
        var (uri, q, name) = SplitUri(link);
        var p = new OutboundProfile
        {
            Protocol = ProtocolType.Trojan,
            Name = name,
            Address = HostOf(uri),
            Port = uri.Port,
            Password = Uri.UnescapeDataString(uri.UserInfo),
        };
        ApplyStream(p, q);
        if (p.Security == "none") p.Security = "tls"; // trojan implies TLS unless overridden
        return p;
    }

    private static OutboundProfile ParseHysteria2(string link)
    {
        var (uri, q, name) = SplitUri(link);
        var hy2 = new OutboundProfile
        {
            Protocol = ProtocolType.Hysteria2,
            Name = name,
            Address = HostOf(uri),
            Port = uri.Port,
            Password = Uri.UnescapeDataString(uri.UserInfo),
            Security = "tls",
            Sni = q.GetValueOrDefault("sni") ?? q.GetValueOrDefault("peer"),
            AllowInsecure = q.GetValueOrDefault("insecure") is "1" or "true",
            Obfs = q.GetValueOrDefault("obfs"),
            ObfsPassword = q.GetValueOrDefault("obfs-password"),
        };
        foreach (var kv in q) hy2.Extra[kv.Key] = kv.Value;
        return hy2;
    }

    private static OutboundProfile ParseSocks(string link)
    {
        var (uri, _, name) = SplitUri(link);
        string user = "", pass = "";
        if (!string.IsNullOrEmpty(uri.UserInfo))
        {
            string info = uri.UserInfo.Contains(':')
                ? Uri.UnescapeDataString(uri.UserInfo)
                : (Base64Ext.TryDecodeUtf8(uri.UserInfo, out var d) && d.Contains(':') ? d : Uri.UnescapeDataString(uri.UserInfo));
            (user, pass) = SplitFirst(info, ':');
        }
        return new OutboundProfile
        {
            Protocol = ProtocolType.Socks,
            Name = name,
            Address = HostOf(uri),
            Port = uri.Port,
            Username = user.Length == 0 ? null : user,
            Password = pass.Length == 0 ? null : pass,
        };
    }

    private static OutboundProfile ParseVmess(string link)
    {
        string json = Base64Ext.DecodeUtf8(link[("vmess://".Length)..]);
        using var doc = JsonDocument.Parse(json);
        var r = doc.RootElement;
        string Str(string k) => r.TryGetProperty(k, out var v) ? (v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : v.ToString()) : "";
        int Int(string k) => int.TryParse(Str(k), out var n) ? n : 0;

        var p = new OutboundProfile
        {
            Protocol = ProtocolType.Vmess,
            Name = Str("ps"),
            Address = Str("add"),
            Port = Int("port"),
            Id = Str("id"),
            AlterId = Int("aid"),
            Encryption = string.IsNullOrEmpty(Str("scy")) ? "auto" : Str("scy"),
            Network = string.IsNullOrEmpty(Str("net")) ? "tcp" : Str("net"),
            Path = Nullify(Str("path")),
            Host = Nullify(Str("host")),
            HeaderType = Nullify(Str("type")),
            Security = Str("tls") is "tls" or "reality" ? Str("tls") : "none",
            Sni = Nullify(Str("sni")),
            Alpn = Nullify(Str("alpn")),
            Fingerprint = Nullify(Str("fp")),
        };
        if (p.Network == "grpc") p.ServiceName = p.Path;
        return p;
    }

    private static OutboundProfile ParseShadowsocks(string link)
    {
        string rest = link["ss://".Length..];
        string name = "";
        int h = rest.IndexOf('#');
        if (h >= 0) { name = Uri.UnescapeDataString(rest[(h + 1)..]); rest = rest[..h]; }
        int qm = rest.IndexOf('?');
        if (qm >= 0) rest = rest[..qm]; // plugin params: not modelled yet

        string method, password, host; int port;
        int at = rest.LastIndexOf('@');
        if (at >= 0)
        {
            string userinfo = rest[..at];
            string mp = Base64Ext.TryDecodeUtf8(userinfo, out var dec) && dec.Contains(':')
                ? dec : Uri.UnescapeDataString(userinfo);
            (method, password) = SplitFirst(mp, ':');
            (host, port) = SplitHostPort(rest[(at + 1)..]);
        }
        else
        {
            string dec = Base64Ext.DecodeUtf8(rest); // method:password@host:port
            int at2 = dec.LastIndexOf('@');
            if (at2 < 0) throw new FormatException("malformed ss link");
            (method, password) = SplitFirst(dec[..at2], ':');
            (host, port) = SplitHostPort(dec[(at2 + 1)..]);
        }
        return new OutboundProfile
        {
            Protocol = ProtocolType.Shadowsocks,
            Name = name,
            Address = host,
            Port = port,
            Method = method,
            Password = password,
        };
    }

    // ---- shared helpers --------------------------------------------------

    /// <summary>Fill transport + security fields shared by vless/trojan from the query string.</summary>
    private static void ApplyStream(OutboundProfile p, Dictionary<string, string> q)
    {
        foreach (var kv in q) p.Extra[kv.Key] = kv.Value; // keep everything (xhttp mode/extra/xPaddingBytes, etc.)
        p.Network = q.GetValueOrDefault("type", "tcp");
        p.Security = q.GetValueOrDefault("security", "none");
        p.Sni = q.GetValueOrDefault("sni");
        p.Alpn = q.GetValueOrDefault("alpn");
        p.Fingerprint = q.GetValueOrDefault("fp");
        p.PublicKey = q.GetValueOrDefault("pbk");
        p.ShortId = q.GetValueOrDefault("sid");
        p.SpiderX = q.GetValueOrDefault("spx");
        p.HeaderType = q.GetValueOrDefault("headerType");
        p.Path = q.GetValueOrDefault("path");
        p.Host = q.GetValueOrDefault("host");
        p.ServiceName = q.GetValueOrDefault("serviceName");
        if (p.Network == "grpc" && p.ServiceName is null) p.ServiceName = p.Path;
        p.AllowInsecure = q.GetValueOrDefault("allowInsecure") is "1" or "true";
    }

    private static (Uri uri, Dictionary<string, string> query, string name) SplitUri(string link)
    {
        var uri = new Uri(link);
        string name = string.IsNullOrEmpty(uri.Fragment) ? "" : Uri.UnescapeDataString(uri.Fragment[1..]);
        return (uri, ParseQuery(uri.Query), name);
    }

    private static string HostOf(Uri uri) => uri.HostNameType == UriHostNameType.IPv6 ? uri.Host.Trim('[', ']') : uri.Host;

    private static Dictionary<string, string> ParseQuery(string query)
    {
        var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var part in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            int i = part.IndexOf('=');
            if (i < 0) d[Uri.UnescapeDataString(part)] = "";
            else d[Uri.UnescapeDataString(part[..i])] = Uri.UnescapeDataString(part[(i + 1)..]);
        }
        return d;
    }

    private static (string, int) SplitHostPort(string hostPort)
    {
        if (hostPort.StartsWith('['))
        {
            int end = hostPort.IndexOf(']');
            string h = hostPort[1..end];
            int p6 = hostPort.IndexOf(':', end);
            return (h, p6 < 0 ? 0 : int.Parse(hostPort[(p6 + 1)..]));
        }
        int c = hostPort.LastIndexOf(':');
        if (c < 0) return (hostPort, 0);
        return (hostPort[..c], int.Parse(hostPort[(c + 1)..]));
    }

    private static (string, string) SplitFirst(string s, char sep)
    {
        int i = s.IndexOf(sep);
        return i < 0 ? (s, "") : (s[..i], s[(i + 1)..]);
    }

    private static string? Nullify(string s) => string.IsNullOrEmpty(s) ? null : s;

    private static void Validate(OutboundProfile p)
    {
        if (string.IsNullOrWhiteSpace(p.Address)) throw new FormatException("missing address");
        if (p.Port is <= 0 or > 65535) throw new FormatException($"invalid port {p.Port}");
        bool needsId = p.Protocol is ProtocolType.Vless or ProtocolType.Vmess;
        if (needsId && string.IsNullOrWhiteSpace(p.Id)) throw new FormatException("missing id/uuid");
        if (string.IsNullOrWhiteSpace(p.Name)) p.Name = $"{p.Address}:{p.Port}";
    }
}
