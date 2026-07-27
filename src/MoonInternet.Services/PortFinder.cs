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

    /// <summary>Two distinct free ports (socks + http).</summary>
    public static (int socks, int http) Pair()
    {
        int socks = Free();
        int http;
        do { http = Free(); } while (http == socks);
        return (socks, http);
    }
}
