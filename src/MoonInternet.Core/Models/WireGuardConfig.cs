namespace MoonInternet.Core.Models;

/// <summary>
/// A WireGuard / AmneziaWG connection. Standard WG fields plus the AmneziaWG obfuscation knobs
/// (Jc/Jmin/Jmax junk packets, S1-S4 junk sizes, H1-H4 header magic ranges, I1-I5 junk-packet specs).
/// Consumed by <c>WireGuardUapi</c> to drive amneziawg-go over its UAPI pipe.
/// </summary>
public sealed class WireGuardConfig
{
    // Interface
    public string PrivateKey { get; set; } = "";           // base64
    public List<string> Address { get; set; } = new();     // local addrs, e.g. "10.8.1.3/32"
    public List<string> Dns { get; set; } = new();
    // Peer
    public string PeerPublicKey { get; set; } = "";        // base64
    public string? PresharedKey { get; set; }              // base64
    public List<string> AllowedIps { get; set; } = new();  // "0.0.0.0/0", "::/0"
    public string Endpoint { get; set; } = "";             // host:port
    public int PersistentKeepalive { get; set; } = 25;
    // AmneziaWG obfuscation (strings — H1-H4 may be ranges "a-b", I1-I5 are packet specs)
    public string? Jc, Jmin, Jmax, S1, S2, S3, S4, H1, H2, H3, H4, I1, I2, I3, I4, I5;

    public string EndpointHost => Endpoint.Contains(':') ? Endpoint[..Endpoint.LastIndexOf(':')] : Endpoint;
    public int EndpointPort => int.TryParse(Endpoint[(Endpoint.LastIndexOf(':') + 1)..], out var p) ? p : 51820;
    public bool IsValid => PrivateKey.Length > 0 && PeerPublicKey.Length > 0 && Endpoint.Contains(':');

    /// <summary>True when the profile carries AmneziaWG obfuscation knobs. Those need amneziawg-go (sing-box can't
    /// speak them); plain WireGuard can go through sing-box instead, which is what gives it full routing.</summary>
    public bool IsAmnezia => !string.IsNullOrEmpty(Jc) || !string.IsNullOrEmpty(Jmin) || !string.IsNullOrEmpty(S1)
                          || !string.IsNullOrEmpty(H1) || !string.IsNullOrEmpty(I1);
}
