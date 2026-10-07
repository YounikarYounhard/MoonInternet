using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text.Json;
using MoonInternet.Core;
using MoonInternet.Core.Generation;
using MoonInternet.Core.Models;

namespace MoonInternet.Services;

/// <summary>
/// Measures servers the way traffic will actually travel: through the protocol.
///
/// The ordinary ping opens a TCP connection to the server's port and reports how long the
/// handshake took. That answers "is something listening", which is not the same question — a
/// CDN, a transparent middlebox or the server with a stale key will all complete a handshake
/// happily, and the row then shows a healthy 12 ms for a server that cannot carry a byte.
///
/// So this brings up the engine that really carries each server — xray for most, sing-box for
/// Hysteria2, awgproxy for AmneziaWG — behind a local SOCKS port, and fetches a URL through it.
/// Anything that answers has genuinely proxied traffic. Hysteria2 and AmneziaWG used to be skipped
/// ("—") because only xray was ever started, which is the "не может их пропинговать" complaint.
///
/// What the number is: one request to open the path (TCP, TLS and the protocol's own handshake —
/// retried once, a single lost packet is not a dead server), then two more over the connection it
/// left open; the best of those. That is the round trip through the server, without the handshake
/// noise that made the old single cold request read 400 one time and 1200 the next.
///
/// Speed without a burst: different machines are measured side by side, the inbounds of one
/// machine strictly one after another. A dozen handshakes at one address in a second is the
/// "many TLS to one server" pattern the censor's box punishes with minutes of blackout.
/// </summary>
public sealed class StabilityPinger
{
    private readonly string _coresDir;
    private readonly string _xrayDir;
    private string XrayExe => Path.Combine(_xrayDir, "xray.exe");

    /// <summary>Machines measured at once.</summary>
    private const int Parallel = 3;

    /// <summary>One run at a time, app-wide: two would double the handshakes and fight over the engines.</summary>
    private static readonly SemaphoreSlim Gate = new(1, 1);

    public StabilityPinger(string coresDir)
    {
        _coresDir = coresDir;
        _xrayDir = Path.Combine(coresDir, "xray");
    }

    public bool CoreAvailable => File.Exists(XrayExe);

    /// <summary>Why the last run produced nothing, for diagnostics.</summary>
    public string? LastError { get; private set; }

    /// <summary>
    /// Measures every profile; the result is in the same order — ms, -1 for no answer, -2 when it
    /// could not be measured at all (its engine is missing). <paramref name="onResult"/> hears each
    /// one as it lands, so rows fill in one by one instead of all at the very end.
    /// </summary>
    public async Task<int[]> MeasureAsync(
        IReadOnlyList<OutboundProfile> servers,
        string probeUrl = "https://www.gstatic.com/generate_204",
        int timeoutMs = 5000,
        Action<int, int>? onResult = null,
        IProgress<string>? progress = null,
        CancellationToken ct = default)
    {
        var results = Enumerable.Repeat(-2, servers.Count).ToArray();
        if (servers.Count == 0) return results;
        timeoutMs = Math.Max(timeoutMs, 5000);

        await Gate.WaitAsync(ct).ConfigureAwait(false);
        Process? xray = null;
        var configPath = Path.Combine(Path.GetTempPath(), $"moon_probe_{Guid.NewGuid():N}.json");
        try
        {
            // One xray holds every server it can carry: a SOCKS inbound each, routed to its own
            // outbound. Thirty processes would be thirty times the startup for the same answer.
            var viaXray = Enumerable.Range(0, servers.Count)
                .Where(i => XrayConfigBuilder.Supports(servers[i].Protocol)).ToList();
            var xrayPort = new Dictionary<int, int>();
            if (viaXray.Count > 0 && CoreAvailable)
            {
                foreach (var i in viaXray) xrayPort[i] = PortFinder.Free();
                await File.WriteAllTextAsync(configPath, BuildProbeConfig(viaXray.Select(i => (servers[i], xrayPort[i])).ToList()), ct).ConfigureAwait(false);
                xray = Process.Start(new ProcessStartInfo
                {
                    FileName = XrayExe,
                    WorkingDirectory = _xrayDir,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                }.WithArgs("run", "-c", configPath));
                // By the port, not a fixed pause: on a slow machine 700 ms was not always enough
                // and the first servers read as dead.
                if (xray is null || !await PortUp(xrayPort[viaXray[^1]], 5000, ct).ConfigureAwait(false))
                {
                    LastError = xray is { HasExited: true } ? "ядро вышло сразу: " + await xray.StandardError.ReadToEndAsync(ct) : "ядро не открыло порты";
                    xrayPort.Clear();
                }
                else
                {
                    // Nobody reads the core's output; drain it so a chatty one never blocks on a full pipe.
                    _ = xray.StandardOutput.ReadToEndAsync(ct);
                    _ = xray.StandardError.ReadToEndAsync(ct);
                }
            }

            int done = 0;
            using var lanes = new SemaphoreSlim(Parallel);
            var machines = Enumerable.Range(0, servers.Count)
                .GroupBy(i => servers[i].Address, StringComparer.OrdinalIgnoreCase)
                .Select(async g =>
                {
                    await lanes.WaitAsync(ct).ConfigureAwait(false);
                    try
                    {
                        foreach (var i in g)
                        {
                            ct.ThrowIfCancellationRequested();
                            results[i] = await MeasureOne(servers[i], xrayPort.TryGetValue(i, out var p) ? p : null, probeUrl, timeoutMs, ct).ConfigureAwait(false);
                            onResult?.Invoke(i, results[i]);
                            progress?.Report($"Проверяю {Interlocked.Increment(ref done)} из {servers.Count}…");
                        }
                    }
                    finally { lanes.Release(); }
                });
            await Task.WhenAll(machines).ConfigureAwait(false);
            return results;
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception ex) { LastError = ex.ToString(); return results; }
        finally
        {
            try { if (xray is { HasExited: false }) xray.Kill(entireProcessTree: true); } catch { }
            xray?.Dispose();
            try { File.Delete(configPath); } catch { }
            Gate.Release();
        }
    }

    /// <summary>
    /// How long the first request may take through an engine started a moment ago for this one
    /// server. AmneziaWG carried nothing for about three seconds after its port opened — the
    /// handshake — and a request sent into that gap lost its first packets and only recovered on the
    /// next retry, past the ordinary five seconds: every AmneziaWG server read ✕ while it worked. Fifteen,
    /// because a missed handshake is retried only five seconds later.
    /// </summary>
    private const int FreshEngineMs = 15000;

    /// <summary>The first request through an engine that is already up.</summary>
    private const int ColdMs = 8000;

    /// <summary>One server through whichever engine carries it; -2 if that engine is not here.</summary>
    private async Task<int> MeasureOne(OutboundProfile p, int? xrayPort, string url, int timeoutMs, CancellationToken ct)
    {
        string name = p.Name ?? p.Address;
        if (xrayPort is { } xp) return await Through(xp, url, timeoutMs, ct, name).ConfigureAwait(false);

        if (p.Protocol == MoonInternet.Core.Models.ProtocolType.Hysteria2)
        {
            using var sb = new SingBoxProxyRunner(_coresDir, $"moon_probe_sb_{Guid.NewGuid():N}.json");
            if (!sb.CoreAvailable) return -2;
            try
            {
                sb.Start(p, null, null);
                if (!await PortUp(sb.SocksPort, 5000, ct).ConfigureAwait(false)) { DevLog.Log("стабильность", $"{name}: sing-box не открыл порт"); return -1; }
                return await Through(sb.SocksPort, url, timeoutMs, ct, name, FreshEngineMs).ConfigureAwait(false);
            }
            finally { sb.Stop(); }
        }

        if (p.Wireguard is { } wg)
        {
            // Never the profile we are connected through — the caller measures that one through the
            // live tunnel. A second session on the same keys moves the peer on the server and knocks
            // the first one off.
            using var awg = new AwgProxyRunner(_coresDir, $"awg-probe-{Guid.NewGuid():N}");
            if (!awg.CoreAvailable) return -2;
            try
            {
                awg.Start(wg);
                bool up = await PortUp(awg.SocksPort, 5000, ct).ConfigureAwait(false);
                awg.DropSecrets();
                if (!up) { DevLog.Log("стабильность", $"{name}: awgproxy не открыл порт"); return -1; }
                return await Through(awg.SocksPort, url, timeoutMs, ct, name, FreshEngineMs).ConfigureAwait(false);
            }
            finally { awg.Stop(); }
        }

        return -2;
    }

    /// <summary>
    /// The measurement itself, through a local SOCKS port: open the path (twice if the first try
    /// is refused quickly), then the best of two requests over the connection it left open. -1 if
    /// it never opened.
    /// </summary>
    internal static async Task<int> Through(int socksPort, string url, int timeoutMs, CancellationToken ct, string name = "", int coldMs = 0)
    {
        using var handler = new SocketsHttpHandler
        {
            Proxy = new WebProxy($"socks5://127.0.0.1:{socksPort}"),
            UseProxy = true,
            PooledConnectionLifetime = TimeSpan.FromMinutes(1),
        };
        using var http = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };   // per request, below

        string why = "";
        async Task<int> Once(int ms)
        {
            var sw = Stopwatch.StartNew();
            using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
            limit.CancelAfter(ms);
            try
            {
                using var res = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, limit.Token).ConfigureAwait(false);
                why = $"HTTP {(int)res.StatusCode}";
                return (int)res.StatusCode < 500 ? (int)sw.ElapsedMilliseconds : -1;
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested) { why = $"нет ответа за {ms} мс"; return -1; }
            catch (HttpRequestException e) { why = (e.InnerException ?? e).Message; return -1; }   // refused, reset, protocol error
        }

        // The first request opens the whole path — the server's handshake, and for a cascade the
        // next hop's too — then the test site's TLS on top. Five seconds turned slow-but-working
        // Russian entry points (the exit is another machine) into ✕ several times a pass.
        int coldLimit = Math.Max(Math.Max(timeoutMs, ColdMs), coldMs);
        var first = Stopwatch.StartNew();
        int cold = await Once(coldLimit).ConfigureAwait(false);
        if (coldMs > 0)
        {
            // An engine started a moment ago: knock until its budget runs out. Until its handshake
            // is through it refuses or stalls, and which of the two differs by platform — a fixed
            // count of tries was enough on the desktop and not on the phone.
            while (cold < 0 && first.ElapsedMilliseconds < coldLimit - 1000)
            {
                await Task.Delay(1000, ct).ConfigureAwait(false);
                cold = await Once((int)Math.Max(1000, coldLimit - first.ElapsedMilliseconds)).ConfigureAwait(false);
            }
        }
        // Otherwise again only after a quick refusal — a reset, a dropped packet the protocol gave
        // up on. A full timeout means nothing came back at all, and waiting it out twice made every
        // dead server cost eleven seconds.
        else if (cold < 0 && first.ElapsedMilliseconds < coldLimit / 2)
        {
            await Task.Delay(1000, ct).ConfigureAwait(false);
            cold = await Once(coldLimit).ConfigureAwait(false);
        }
        // Why, in the journal: «✕» alone could not tell a dead server from a probe that broke.
        if (cold < 0) { DevLog.Log("стабильность", $"{name}: {why}"); return -1; }

        int best = -1;
        for (int i = 0; i < 2; i++)
        {
            int warm = await Once(timeoutMs).ConfigureAwait(false);
            if (warm >= 0 && (best < 0 || warm < best)) best = warm;
        }
        return best >= 0 ? best : cold;
    }

    private static async Task<bool> PortUp(int port, int waitMs, CancellationToken ct)
    {
        var until = Environment.TickCount64 + waitMs;
        while (Environment.TickCount64 < until)
        {
            try
            {
                using var c = new TcpClient();
                await c.ConnectAsync(IPAddress.Loopback, port, ct).ConfigureAwait(false);
                return true;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch { await Task.Delay(100, ct).ConfigureAwait(false); }
        }
        return false;
    }

    /// <summary>
    /// One inbound and one outbound per server, wired together by a routing rule. Sniffing is off
    /// and no DNS block is set: we are measuring the path, not exercising the routing rules.
    /// </summary>
    private static string BuildProbeConfig(IReadOnlyList<(OutboundProfile Profile, int Port)> servers)
    {
        var inbounds = new List<object?>();
        var outbounds = new List<object?>();
        var rules = new List<object?>();

        for (int i = 0; i < servers.Count; i++)
        {
            inbounds.Add(new Dictionary<string, object?>
            {
                ["tag"] = $"in{i}",
                ["listen"] = "127.0.0.1",
                ["port"] = servers[i].Port,
                ["protocol"] = "socks",
                ["settings"] = new Dictionary<string, object?> { ["auth"] = "noauth", ["udp"] = false },
            });
            outbounds.Add(XrayConfigBuilder.BuildOutbound(servers[i].Profile, $"out{i}"));
            rules.Add(new Dictionary<string, object?>
            {
                ["type"] = "field",
                ["inboundTag"] = new[] { $"in{i}" },
                ["outboundTag"] = $"out{i}",
            });
        }

        var cfg = new Dictionary<string, object?>
        {
            ["log"] = new Dictionary<string, object?> { ["loglevel"] = "none" },
            ["inbounds"] = inbounds,
            ["outbounds"] = outbounds,
            ["routing"] = new Dictionary<string, object?> { ["rules"] = rules },
        };
        return JsonSerializer.Serialize(cfg, new JsonSerializerOptions { WriteIndented = false });
    }
}

internal static class PsiExtensions
{
    public static ProcessStartInfo WithArgs(this ProcessStartInfo psi, params string[] args)
    {
        foreach (var a in args) psi.ArgumentList.Add(a);
        return psi;
    }
}
