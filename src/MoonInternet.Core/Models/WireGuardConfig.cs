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
    /// <summary>Tunnel MTU from the config, or null to let the engine choose. The panel always sets one.</summary>
    public int? Mtu;
    /// <summary>The name, which for a panel-written config lives in a leading <c>#</c> comment.</summary>
    public string? Label;
    // AmneziaWG obfuscation (strings — H1-H4 may be ranges "a-b", I1-I5 are packet specs)
    public string? Jc, Jmin, Jmax, S1, S2, S3, S4, H1, H2, H3, H4, I1, I2, I3, I4, I5;

    // AmneziaWG 3.x. 3x-ui emits these into the client config now, and dropping them silently
    // downgrades a 3.x profile to a 2.0 one — it still connects, which is exactly why nobody
    // would notice the protection had gone. Kept as strings: every one of them may be a range.
    /// <summary>32 bytes of hex. Encrypts the WireGuard header itself (ChaCha20) — the 3.0 headline.</summary>
    public string? HeaderProtectionKey;
    /// <summary>Extra padding on content, e.g. "50-100".</summary>
    public string? ContentPaddingAddition;
    public string? RekeyAfterTime, RekeyTimeout, RejectAfterTime, KeepaliveTimeout, MaxHandshakeAttempts;
    public string? RandomTrailers, DisableCookies;

    public string EndpointHost => Endpoint.Contains(':') ? Endpoint[..Endpoint.LastIndexOf(':')] : Endpoint;
    public int EndpointPort => int.TryParse(Endpoint[(Endpoint.LastIndexOf(':') + 1)..], out var p) ? p : 51820;
    public bool IsValid => PrivateKey.Length > 0 && PeerPublicKey.Length > 0 && Endpoint.Contains(':');

    /// <summary>True when the profile carries AmneziaWG obfuscation knobs. Those need an AmneziaWG
    /// engine; plain WireGuard can go through sing-box instead, which is what gives it full routing.</summary>
    public bool IsAmnezia => AwgGeneration > 0;

    /// <summary>
    /// What to call this in the interface. AmneziaWG is built on WireGuard but is not it —
    /// different handshake, different engine, and a server speaking one will not answer the other —
    /// so the two are named apart, which is also what keeps them apart in the protocol filter.
    ///
    /// "3.x" and not "3.0" on purpose: the two carry the identical set of config fields, so a
    /// config cannot tell you which of them it was written for.
    ///
    /// It lives here rather than in the view model because the Android build has to say the same
    /// words, and because here it can be checked.
    /// </summary>
    public string ProtocolLabel => AwgGeneration switch
    {
        1 => "AmneziaWG 1.0",
        2 => "AmneziaWG 2.0",
        3 => "AmneziaWG 3.x",
        _ => "WireGuard",
    };

    /// <summary>
    /// Which AmneziaWG generation this profile was written for: 0 plain WireGuard, 1 the original
    /// junk-and-magic-headers set, 2 once I1-I5 signature packets appear, 3 once the fields added
    /// in 3.0 do.
    ///
    /// Generations are additive — the 3.x module runs a 1.x or 2.0 config unchanged, which is why
    /// "support 3.0 and the rest is 3.0 with the protection switched off" is the right way round.
    /// J1-J3 and Itime are deliberately absent: they existed in 1.5 and were dropped in 2.0.
    /// </summary>
    public int AwgGeneration =>
        !string.IsNullOrEmpty(HeaderProtectionKey) || !string.IsNullOrEmpty(ContentPaddingAddition)
        || !string.IsNullOrEmpty(RekeyAfterTime) || !string.IsNullOrEmpty(RekeyTimeout)
        || !string.IsNullOrEmpty(RejectAfterTime) || !string.IsNullOrEmpty(KeepaliveTimeout)
        || !string.IsNullOrEmpty(MaxHandshakeAttempts) || !string.IsNullOrEmpty(RandomTrailers)
        || !string.IsNullOrEmpty(DisableCookies) ? 3
        : !string.IsNullOrEmpty(I1) || !string.IsNullOrEmpty(I2) || !string.IsNullOrEmpty(I3)
        || !string.IsNullOrEmpty(I4) || !string.IsNullOrEmpty(I5) ? 2
        : !string.IsNullOrEmpty(Jc) || !string.IsNullOrEmpty(Jmin) || !string.IsNullOrEmpty(Jmax)
        || !string.IsNullOrEmpty(S1) || !string.IsNullOrEmpty(S2) || !string.IsNullOrEmpty(S3)
        || !string.IsNullOrEmpty(S4) || !string.IsNullOrEmpty(H1) || !string.IsNullOrEmpty(H2)
        || !string.IsNullOrEmpty(H3) || !string.IsNullOrEmpty(H4) ? 1
        : 0;
}
