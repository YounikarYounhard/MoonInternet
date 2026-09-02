namespace MoonInternet.Services;

/// <summary>
/// "Is there any internet at all" — asked before blaming the servers.
///
/// A ping pass where nothing answered has two very different causes, and the app treated them as
/// one: every server is down, or the machine is not on a network. The second one is far more
/// common and it was reported as the first, so the tray went red and the auto-reconnect burned
/// through its attempts against a cable that was simply unplugged.
///
/// Two well-known addresses, by IP and never by name: a DNS lookup on a dead link takes the OS
/// timeout, which is the very hang this is meant to avoid. Bound to the physical card for the same
/// reason every probe is — a TUN stack answers a handshake itself and would call a dead link alive.
/// </summary>
public static class Internet
{
    private static readonly (string Host, int Port)[] Anchors =
    {
        ("1.1.1.1", 443),
        ("8.8.8.8", 53),
    };

    public static async Task<bool> IsUpAsync(int timeoutMs = 1500)
    {
        // No link at all — no need to open a socket to find that out.
        if (!System.Net.NetworkInformation.NetworkInterface.GetIsNetworkAvailable()) return false;

        int? outIf = Pinger.PhysicalIfIndex();
        var probes = Anchors
            .Select(a => Pinger.TcpLatencyAsync(a.Host, a.Port, timeoutMs, outIf))
            .ToList();
        while (probes.Count > 0)
        {
            var done = await Task.WhenAny(probes).ConfigureAwait(false);
            probes.Remove(done);
            if (await done.ConfigureAwait(false) >= 0) return true;
        }
        return false;
    }
}
