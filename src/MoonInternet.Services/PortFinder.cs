using System.Net;
using System.Net.Sockets;

namespace MoonInternet.Services;

/// <summary>Finds a free loopback TCP port (needed because 10808/10809 are often taken by other clients, e.g. INCY).</summary>
public static class PortFinder
{
    // ponytail: tiny TOCTOU window between finding and the core binding; fine for localhost, retry on bind failure if it ever bites.
    public static int Free()
    {
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        int port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }

    /// <summary>
    /// The local proxy's two ports, the same on every connect when they can be: 10808 for SOCKS5 and
    /// 10809 for HTTP, what most clients and the phone use. They are what people type into Telegram,
    /// a browser extension or a phone on the same Wi-Fi — and a fresh random pair on every connect
    /// left all of those pointing at nothing after a reconnect, which is most of why proxy mode felt
    /// broken. Taken by somebody else (INCY sits on exactly these) → any free port instead.
    /// </summary>
    public static (int socks, int http) Stable(int socks = 10808, int http = 10809)
    {
        int s = IsFree(socks) ? socks : Free();
        int h = http != s && IsFree(http) ? http : Free();
        while (h == s) h = Free();
        return (s, h);
    }

    /// <summary>Nobody listens on it, on the loopback or on every address (LAN sharing binds that).</summary>
    public static bool IsFree(int port)
    {
        foreach (var addr in new[] { IPAddress.Loopback, IPAddress.Any })
        {
            try
            {
                var l = new TcpListener(addr, port);
                l.ExclusiveAddressUse = true;
                l.Start();
                l.Stop();
            }
            catch (SocketException) { return false; }
        }
        return true;
    }

    /// <summary>Two distinct free ports (socks + http).</summary>
    public static (int socks, int http) Pair()
    {
        int socks = Free();
        int http;
        do { http = Free(); } while (http == socks);
        return (socks, http);
    }
}
