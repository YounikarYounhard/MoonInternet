using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace MoonInternet.Services;

/// <summary>TCP-connect latency to a server endpoint (ms), or -1 on timeout/failure.</summary>
public static class Pinger
{
    // IP_UNICAST_IF (Winsock): forces the socket's outgoing interface.
    private const SocketOptionName IP_UNICAST_IF = (SocketOptionName)31;

    public static async Task<int> TcpLatencyAsync(string host, int port, int timeoutMs = 3000, int? outIfIndex = null)
    {
        var sw = Stopwatch.StartNew();
        try
        {
            using var client = new TcpClient(AddressFamily.InterNetwork);
            if (outIfIndex is int idx)
            {
                // Bypass the VPN TUN: force egress out the physical NIC so we measure the REAL round-trip to the
                // server. Without this, sing-box's gvisor stack completes the TCP handshake locally (~0-1 ms) and
                // every server reads a fake "1". IPv4 wants the index in network byte order.
                client.Client.SetSocketOption(SocketOptionLevel.IP, IP_UNICAST_IF, IPAddress.HostToNetworkOrder(idx));
            }
            var connect = client.ConnectAsync(host, port);
            if (await Task.WhenAny(connect, Task.Delay(timeoutMs)) != connect || !client.Connected)
                return -1;
            await connect; // surface connect exceptions
            return (int)sw.ElapsedMilliseconds;
        }
        catch
        {
            return -1;
        }
    }

    /// <summary>HTTP GET/HEAD latency to a host:port (ms), time-to-first-byte. -1 on failure; connect time if the
    /// server accepts TCP but never replies HTTP (e.g. a VLESS port).</summary>
    public static async Task<int> HttpLatencyAsync(string host, int port, bool head, int timeoutMs = 4000, int? outIfIndex = null)
    {
        var sw = Stopwatch.StartNew();
        try
        {
            using var client = new TcpClient(AddressFamily.InterNetwork);
            if (outIfIndex is int idx)
                client.Client.SetSocketOption(SocketOptionLevel.IP, IP_UNICAST_IF, IPAddress.HostToNetworkOrder(idx));
            var connect = client.ConnectAsync(host, port);
            if (await Task.WhenAny(connect, Task.Delay(timeoutMs)) != connect || !client.Connected) return -1;
            await connect;
            var stream = client.GetStream();
            var req = System.Text.Encoding.ASCII.GetBytes($"{(head ? "HEAD" : "GET")} / HTTP/1.1\r\nHost: {host}\r\nUser-Agent: MoonInternet\r\nConnection: close\r\n\r\n");
            await stream.WriteAsync(req);
            var buf = new byte[1];
            var read = stream.ReadAsync(buf, 0, 1);
            if (await Task.WhenAny(read, Task.Delay(timeoutMs)) != read) return (int)sw.ElapsedMilliseconds;
            await read;
            return (int)sw.ElapsedMilliseconds;
        }
        catch { return -1; }
    }

    /// <summary>ICMP echo latency (ms), -1 on failure. Used for WireGuard/AmneziaWG: their endpoint is UDP-only,
    /// so a TCP connect always "times out" and would show ✕ for a perfectly healthy server.</summary>
    public static async Task<int> IcmpLatencyAsync(string host, int timeoutMs = 3000)
    {
        try
        {
            using var ping = new Ping();
            var reply = await ping.SendPingAsync(host, timeoutMs);
            return reply.Status == IPStatus.Success ? (int)reply.RoundtripTime : -1;
        }
        catch { return -1; }
    }

    /// <summary>RTT measured from a TCP RST ("connection refused"). A closed port still answers — and that answer
    /// travels the same path as real traffic, so the round-trip time is a valid latency even when ICMP is filtered.
    /// Used for WireGuard/AmneziaWG, whose UDP endpoint never replies to probes. -1 if the packet is dropped.</summary>
    public static async Task<int> RstLatencyAsync(string host, int port, int timeoutMs = 3000)
    {
        var sw = Stopwatch.StartNew();
        try
        {
            using var client = new TcpClient(AddressFamily.InterNetwork);
            var connect = client.ConnectAsync(host, port);
            if (await Task.WhenAny(connect, Task.Delay(timeoutMs)) != connect) return -1;   // dropped, no answer
            await connect;                                    // unlikely: port actually open
            return (int)sw.ElapsedMilliseconds;
        }
        catch (SocketException e) when (e.SocketErrorCode is SocketError.ConnectionRefused or SocketError.ConnectionReset)
        {
            return (int)sw.ElapsedMilliseconds;               // RST came back → that's our round-trip
        }
        catch { return -1; }
    }

    /// <summary>Dispatch a latency probe by method. "moon" = TCP handshake out the physical NIC (our default, accurate
    /// channel latency to the VPN server); "tcp" = plain TCP on the current route; "httpget"/"httphead" = HTTP TTFB.</summary>
    public static Task<int> MeasureAsync(string method, string host, int port, int timeoutMs, int? outIfIndex) => method switch
    {
        "httpget" => HttpLatencyAsync(host, port, head: false, timeoutMs, outIfIndex),
        "httphead" => HttpLatencyAsync(host, port, head: true, timeoutMs, outIfIndex),
        "tcp" => TcpLatencyAsync(host, port, timeoutMs, null),
        _ => TcpLatencyAsync(host, port, timeoutMs, outIfIndex),
    };

    /// <summary>
    /// IPv4 interface index of the primary physical adapter (Up, has a default gateway, not loopback/TUN/VPN),
    /// or null if none found. Used to route latency probes around an active VPN tunnel.
    /// </summary>
    public static int? PhysicalIfIndex()
    {
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
            var tag = (ni.Description + " " + ni.Name).ToLowerInvariant();
            if (tag.Contains("wintun") || tag.Contains("moontun") || tag.Contains("wireguard")
                || tag.Contains("sing-box") || tag.Contains("tailscale") || tag.Contains("tap-windows")) continue;
            var props = ni.GetIPProperties();
            if (props.GatewayAddresses.Count == 0) continue; // a real egress has a default gateway
            try { return props.GetIPv4Properties()?.Index; } catch { }
        }
        return null;
    }
}
