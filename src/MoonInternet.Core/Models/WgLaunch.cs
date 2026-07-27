namespace MoonInternet.Core.Models;

/// <summary>Plain WireGuard routed through sing-box (TUN + wireguard outbound in one process), so the profile's
/// Direct/Proxy/Block rules actually apply — the raw amneziawg-go path has no router in it.</summary>
public sealed class WgSbLaunch
{
    public WireGuardConfig Wg { get; set; } = new();
    /// <summary>JSON of the RoutingProfile to apply (null = tunnel everything).</summary>
    public string? RoutingJson { get; set; }
    /// <summary>Folder with sing-box .srs rule-sets the app downloaded.</summary>
    public string? SrsDir { get; set; }
}
