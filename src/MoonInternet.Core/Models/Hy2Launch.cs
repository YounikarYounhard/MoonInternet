namespace MoonInternet.Core.Models;

/// <summary>
/// Minimal Hysteria2 endpoint the app hands to the TUN helper (base64 JSON over the control channel) so the
/// helper's own sing-box can be BOTH the TUN and the Hysteria2 outbound — one process, no xray/second-sing-box
/// chain, so sing-box's own server connection bypasses its TUN natively (no loop). <see cref="Address"/> is a
/// pre-resolved IP; <see cref="Sni"/> keeps the domain for TLS.
/// </summary>
public sealed class Hy2Launch
{
    public string Address { get; set; } = "";
    public int Port { get; set; }
    public string? Password { get; set; }
    public string? Sni { get; set; }
    public string? Obfs { get; set; }
    public string? ObfsPassword { get; set; }
    public bool AllowInsecure { get; set; }
    public string? Alpn { get; set; }
    public int UpMbps { get; set; }
    public int DownMbps { get; set; }

    /// <summary>Serialized <see cref="RoutingProfile"/> so the helper's sing-box applies the SAME РФ routing as
    /// every other protocol (via sing-box .srs rule-sets). Null → all traffic through the tunnel (no routing).</summary>
    public string? RoutingJson { get; set; }
    /// <summary>Folder holding the sing-box rule-sets (.srs) the routing references. Null → no routing.</summary>
    public string? SrsDir { get; set; }
}
