namespace MoonInternet.Core.Generation;

/// <summary>Global xray tuning knobs from the Tunnel settings (set by the app before a config is built).
/// Single-instance app, so a static is fine — no config-per-call plumbing. ponytail: global, one instance.</summary>
public static class XrayTuning
{
    public static bool Fragment;                 // split the TLS ClientHello (censorship bypass)
    public static bool Mux;                       // multiplex connections over one link
    public static bool Sniffing = true;           // protocol/domain sniffing (needed for domain routing)
    public static string PreferredIp = "auto";    // auto | ipv4 | ipv6  → DNS queryStrategy
    public static string[] Dns = { "1.1.1.1", "8.8.8.8" };   // resolver IPs used for the VPN DNS

    public static string? SocksUser;              // local SOCKS proxy auth (null = no auth)
    public static string? SocksPass;
    public static bool HttpAuth;                  // also require the same creds on the local HTTP proxy
    public static bool BlockUdp;                  // drop UDP on the local inbound (breaks QUIC/DNS-over-UDP/games)

    public static string QueryStrategy => PreferredIp switch { "ipv4" => "UseIPv4", "ipv6" => "UseIPv6", _ => "UseIP" };
}
