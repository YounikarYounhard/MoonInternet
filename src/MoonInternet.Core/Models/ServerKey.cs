namespace MoonInternet.Core.Models;

/// <summary>
/// What identifies a server across a subscription refresh: who you are, and where you connect.
///
/// It used to be the share link with its <c>#remark</c> cut off, which held only as long as the
/// panel wrote the same link twice. It does not. Fetching one real subscription twice, two seconds
/// apart, returned 36 links of which 14 had changed — x-ui picks a different <c>sni</c> every time
/// it renders them. So on every refresh a third of the servers became servers the app had never
/// seen: the star fell off, the measured ping was thrown away, and the selection moved. The remark
/// does the same more slowly — x-ui writes the traffic left and the expiry date into it.
///
/// Credentials plus endpoint survive all of that. Transport dressing — sni, fingerprint, alpn,
/// short id, path — is how you reach the machine, not which machine it is.
///
/// AmneziaWG is keyed by its peer for the same reason: its whole link is one base64 blob with the
/// config inside it, name and all, so the least cosmetic edit produces a brand-new link.
/// </summary>
public static class ServerKey
{
    /// <summary>
    /// The old link-shaped key. Kept because saved stars are still written in it — see the
    /// migration in MainViewModel — and because it is what strips a remark off a raw link.
    /// </summary>
    public static string? Of(string? raw) =>
        raw is null ? null : raw.IndexOf('#') is var i and >= 0 ? raw[..i] : raw;

    /// <summary>Key for a parsed profile, falling back to its endpoint when it carries no
    /// credentials (some formats do not, and those servers must still be identifiable).</summary>
    public static string Of(OutboundProfile p)
    {
        if (p.Wireguard is { PeerPublicKey.Length: > 0 } wg)
            return $"awg:{wg.PeerPublicKey}@{wg.Endpoint}";
        // Not lower-cased: a UUID is hex either way, but a trojan password is a password.
        string cred = p.Id is { Length: > 0 } id ? id : p.Password ?? "";
        string host = p.Address.ToLowerInvariant();
        return cred.Length > 0 ? $"{p.Protocol}:{cred}@{host}:{p.Port}" : $"{p.Protocol}:{host}:{p.Port}";
    }

    /// <summary>
    /// The <c>host:port</c> a key of either shape points at, or null when it names none. Both key
    /// shapes put the endpoint after the last <c>@</c>, which is what lets an old saved star find
    /// its server again.
    /// </summary>
    public static string? EndpointOf(string? key)
    {
        if (key is null) return null;
        int at = key.LastIndexOf('@');
        if (at < 0) return null;
        string tail = key[(at + 1)..];
        foreach (char c in "?/#") { int i = tail.IndexOf(c); if (i >= 0) tail = tail[..i]; }
        return tail.Contains(':') ? tail.ToLowerInvariant() : null;
    }
}
