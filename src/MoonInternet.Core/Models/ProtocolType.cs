namespace MoonInternet.Core.Models;

/// <summary>Supported outbound protocols. Wire-level handling is delegated to the bundled xray/sing-box core.</summary>
public enum ProtocolType
{
    Vless,
    Vmess,
    Trojan,
    Shadowsocks,
    Hysteria2,
    Socks,
    Wireguard // AmneziaWG / WireGuard (sing-box)
}
