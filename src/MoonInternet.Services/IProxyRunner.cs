using MoonInternet.Core.Models;

namespace MoonInternet.Services;

/// <summary>
/// A local proxy core: launches a process (xray or sing-box) that exposes a SOCKS (and HTTP) port the rest of
/// the pipeline forwards to. Lets ConnectionManager swap xray ↔ sing-box per server protocol.
/// </summary>
public interface IProxyRunner : IDisposable
{
    int SocksPort { get; }
    int HttpPort { get; }
    bool CoreAvailable { get; }
    event Action? ProcessExited;
    void Start(OutboundProfile profile, RoutingProfile? routing, string? geoAssetDir);
    void Stop();
}
