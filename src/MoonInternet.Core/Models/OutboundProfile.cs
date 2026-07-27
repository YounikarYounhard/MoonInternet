namespace MoonInternet.Core.Models;

/// <summary>
/// Protocol-agnostic superset of everything a share link / subscription entry can carry.
/// Maps 1:1 onto an xray/sing-box outbound during config generation (Phase 2).
/// </summary>
public sealed class OutboundProfile
{
    public ProtocolType Protocol { get; set; }
    public string Name { get; set; } = "";
    public string? Raw { get; set; }        // original share link (vless://…) — for Copy URL / QR

    // Endpoint
    public string Address { get; set; } = "";
    public int Port { get; set; }

    // Credentials (only the ones relevant to the protocol are set)
    public string? Id { get; set; }        // vless/vmess UUID
    public string? Password { get; set; }   // trojan / ss / hysteria2 auth / socks pass
    public string? Username { get; set; }   // socks user
    public string? Method { get; set; }     // ss cipher
    public int AlterId { get; set; }        // vmess aid
    public string? Encryption { get; set; } // vless "none" / vmess security (scy)
    public string? Flow { get; set; }       // vless xtls-rprx-vision

    // Transport (streamSettings.network)
    public string Network { get; set; } = "tcp"; // tcp / ws / grpc / http(h2) / quic / kcp
    public string? Path { get; set; }
    public string? Host { get; set; }        // ws/http Host header
    public string? ServiceName { get; set; } // grpc
    public string? HeaderType { get; set; }  // tcp header type (none/http)

    // Security (TLS / Reality)
    public string Security { get; set; } = "none"; // none / tls / reality
    public string? Sni { get; set; }
    public string? Alpn { get; set; }
    public string? Fingerprint { get; set; } // uTLS fp
    public bool AllowInsecure { get; set; }
    public string? PublicKey { get; set; }   // reality pbk
    public string? ShortId { get; set; }     // reality sid
    public string? SpiderX { get; set; }     // reality spx

    // Hysteria2 obfs
    public string? Obfs { get; set; }
    public string? ObfsPassword { get; set; }

    /// <summary>Set for <see cref="ProtocolType.Wireguard"/> (AmneziaWG) — drives the amneziawg-go engine, not xray.</summary>
    public WireGuardConfig? Wireguard { get; set; }

    /// <summary>Protocol-specific leftovers not modelled above (kept verbatim for the config generator).</summary>
    public Dictionary<string, string> Extra { get; } = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>
    /// Copy that connects to a pre-resolved server IP instead of the domain, keeping the domain as the TLS SNI
    /// and Host header. In TUN mode this stops xray from doing its own DNS lookup for the server — that lookup
    /// gets caught by sing-box's DNS hijack and deadlocks (to resolve the server it would need the server).
    /// </summary>
    public OutboundProfile CloneForConnectIp(string ip)
    {
        var c = (OutboundProfile)MemberwiseClone();   // Extra dict is shared (read-only during generation)
        if (string.IsNullOrEmpty(c.Sni)) c.Sni = c.Address;   // preserve the domain for TLS SNI
        if (string.IsNullOrEmpty(c.Host)) c.Host = c.Address; // preserve the domain for ws/http/xhttp Host
        c.Address = ip;
        return c;
    }

    public override string ToString() => $"{Protocol} {Name} [{Address}:{Port}]";
}
