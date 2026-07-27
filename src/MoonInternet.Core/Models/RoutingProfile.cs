namespace MoonInternet.Core.Models;

/// <summary>
/// A routing profile (INCY/HAPP): Direct/Proxy/Block lists for sites &amp; IPs, DNS, geo sources.
/// Property names match the INCY <c>incy://routing/add/&lt;base64(JSON)&gt;</c> payload for direct deserialization.
/// </summary>
public sealed class RoutingProfile
{
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
}

public enum RoutingSource { Incy, Happ, Custom }
