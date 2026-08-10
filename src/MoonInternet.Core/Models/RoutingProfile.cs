namespace MoonInternet.Core.Models;

/// <summary>
/// A routing profile (INCY/HAPP): Direct/Proxy/Block lists for sites &amp; IPs, DNS, geo sources.
/// Property names match the INCY <c>incy://routing/add/&lt;base64(JSON)&gt;</c> payload for direct deserialization.
/// </summary>
public sealed class RoutingProfile
{
    /// <summary>Stable key: profiles are a list now, so the selection needs something to point at.</summary>
    public string Id { get; set; } = "";
    /// <summary>Ships with the app: can be duplicated and exported, not edited or deleted.</summary>
    public bool Builtin { get; set; }
    /// <summary>
    /// The subscription this profile arrived in, empty for built-ins and for your own copies.
    /// Profiles from a subscription are read-only: the next fetch overwrites them, so an edit
    /// here would quietly disappear.
    /// </summary>
    public string SubUrl { get; set; } = "";
    public string Name { get; set; } = "";
    public bool GlobalProxy { get; set; } = true;

    // DNS
    public string RemoteDNSType { get; set; } = "DoH";      // DoH / DoU / DoT
    public string RemoteDNSDomain { get; set; } = "";
    public string RemoteDNSIP { get; set; } = "";
    public string DomesticDNSType { get; set; } = "DoU";
    public string DomesticDNSDomain { get; set; } = "";
    public string DomesticDNSIP { get; set; } = "";
    public bool FakeDNS { get; set; }
    public Dictionary<string, string> DnsHosts { get; set; } = new();

    // Geo sources (for auto-update + hash verification)
    public string Geoipurl { get; set; } = "";
    public string Geositeurl { get; set; } = "";
    public string GeoipHash { get; set; } = "";
    public string GeositeHash { get; set; } = "";

    // Rules
    public List<string> DirectSites { get; set; } = new();
    public List<string> ProxySites { get; set; } = new();
    public List<string> BlockSites { get; set; } = new();
    public List<string> DirectIp { get; set; } = new();
    public List<string> ProxyIp { get; set; } = new();
    public List<string> BlockIp { get; set; } = new();

    public string DomainStrategy { get; set; } = "IPIfNonMatch";
    public string RouteOrder { get; set; } = "block-proxy-direct";
    public bool isDefault { get; set; }

    /// <summary>Where this profile came from (for the "INCY wins over HAPP" priority).</summary>
    public RoutingSource Source { get; set; } = RoutingSource.Incy;

    [System.Text.Json.Serialization.JsonIgnore]
    public bool IsImported => Source is RoutingSource.Incy or RoutingSource.Happ;
    [System.Text.Json.Serialization.JsonIgnore]
    public string SourceText => Source switch
    {
        RoutingSource.Incy => "INCY",
        RoutingSource.Happ => "HAPP",
        _ => "",
    };
}

public enum RoutingSource { Incy, Happ, Custom }

public static class RoutingProfiles
{
    /// <summary>A subscription carries at most one profile per source, so that pair is the identity.</summary>
    public static string ImportedId(string subUrl, RoutingSource source) => $"sub:{subUrl}:{source}";

    /// <summary>
    /// The two profiles that ship with the app. Fixed ids, so an update recognises the ones it
    /// already installed instead of adding a second copy of each.
    ///
    /// Names are not localised on purpose: they are data the user can duplicate and rename, and a
    /// profile that renamed itself when the language changed would be one you cannot refer to.
    /// </summary>
    public static List<RoutingProfile> Builtins() => new()
    {
        new RoutingProfile
        {
            Id = "builtin-global", Builtin = true, Source = RoutingSource.Custom,
            Name = "Глобальный", GlobalProxy = true, DomainStrategy = "AsIs",
        },
        new RoutingProfile
        {
            Id = "builtin-lan", Builtin = true, Source = RoutingSource.Custom,
            Name = "Обход LAN", GlobalProxy = true, DomainStrategy = "AsIs",
            DirectIp = new List<string> { "geoip:private" },
        },
    };
}
