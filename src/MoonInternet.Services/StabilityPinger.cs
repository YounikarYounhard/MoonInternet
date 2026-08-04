using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using MoonInternet.Core.Generation;
using MoonInternet.Core.Models;

namespace MoonInternet.Services;

/// <summary>One server's result: median round-trip in ms, or -1 if it never answered.</summary>
public sealed record StabilityResult(string Key, int Ms, int Succeeded, int Attempts)
{
    public bool Reachable => Ms >= 0;
    /// <summary>-2 = this core cannot carry the protocol, so nothing was measured.</summary>
    public bool NotMeasured => Ms == -2;
    /// <summary>How many probes came back, 0..1 — a server that answers 2 times out of 5 is not "up".</summary>
    public double Reliability => Attempts == 0 ? 0 : (double)Succeeded / Attempts;
}

/// <summary>
/// Measures servers the way traffic will actually travel: through the protocol.
///
/// The ordinary ping opens a TCP connection to the server's port and reports how long the
/// handshake took. That answers "is something listening", which is not the same question — a
/// CDN, a transparent middlebox or the server with a stale key will all complete a handshake
/// happily, and the row then shows a healthy 12 ms for a server that cannot carry a byte. That
/// is the "Финляндия доступна, а на самом деле нет" case.
///
/// So this one starts the real core with a real outbound per server, and fetches a URL through
/// each. Anything that answers has genuinely proxied traffic.
///
/// One core process holds every server at once — a local SOCKS inbound each, routed to its own
/// outbound — rather than a process per server. Thirty processes would be thirty times the
/// memory and startup cost for the same answer.
/// </summary>
public sealed class StabilityPinger
{
    private readonly string _exePath;
    private readonly string _assetDir;

    /// <summary>First local port of the block handed to the probe inbounds.</summary>
    private const int BasePort = 24800;

    public StabilityPinger(string coresDir)
    {
        _assetDir = Path.Combine(coresDir, "xray");
        _exePath = Path.Combine(_assetDir, "xray.exe");
    }

    public bool CoreAvailable => File.Exists(_exePath);

    /// <summary>Why the last run produced nothing, for diagnostics.</summary>
    public string? LastError { get; private set; }

    /// <summary>
    /// Probes every profile <paramref name="attempts"/> times and reports the median of the
    /// successful ones.
    ///
    /// Median, not average: one stalled probe out of five would drag an average up and make a
    /// good server look bad, while the median says what a typical request actually costs.
    /// </summary>
    public async Task<IReadOnlyList<StabilityResult>> MeasureAsync(
        IReadOnlyList<(string Key, OutboundProfile Profile)> servers,
        string probeUrl = "https://www.gstatic.com/generate_204",
        int attempts = 3,
        int timeoutMs = 5000,
        IProgress<string>? progress = null,
        CancellationToken ct = default)
    {
        // Hysteria2 and WireGuard are sing-box's job on the desktop; asking xray to build an
        // outbound for them throws. They are reported as unmeasured rather than as failures —
        // "we did not check" is not the same claim as "it is down".
        var (measurable, skipped) = (
            servers.Where(s => XrayConfigBuilder.Supports(s.Profile.Protocol)).ToList(),
            servers.Where(s => !XrayConfigBuilder.Supports(s.Profile.Protocol))
                   .Select(s => new StabilityResult(s.Key, -2, 0, 0)).ToList());

        if (measurable.Count == 0 || !CoreAvailable) return skipped;
        servers = measurable;

        var ports = new int[servers.Count];
        for (int i = 0; i < servers.Count; i++) ports[i] = BasePort + i;

        var configPath = Path.Combine(Path.GetTempPath(), $"moon_probe_{Guid.NewGuid():N}.json");
        Process? core = null;
        try
        {
            await File.WriteAllTextAsync(configPath, BuildProbeConfig(servers, ports), ct).ConfigureAwait(false);

            core = Process.Start(new ProcessStartInfo
            {
                FileName = _exePath,
                WorkingDirectory = _assetDir,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            }.WithArgs("run", "-c", configPath));

            if (core is null) return Array.Empty<StabilityResult>();

            // The core binds its listeners a moment after the process appears; probing before that
            // gives every server a false "refused".
            await Task.Delay(700, ct).ConfigureAwait(false);
            if (core.HasExited) { LastError = "ядро вышло сразу: " + await core.StandardError.ReadToEndAsync(ct) + await core.StandardOutput.ReadToEndAsync(ct); return Array.Empty<StabilityResult>(); }

            var results = new List<StabilityResult>(servers.Count);
            for (int i = 0; i < servers.Count; i++)
            {
                ct.ThrowIfCancellationRequested();
                progress?.Report($"Проверяю {i + 1} из {servers.Count}…");
                results.Add(await ProbeOne(servers[i].Key, ports[i], probeUrl, attempts, timeoutMs, ct).ConfigureAwait(false));
            }
            results.AddRange(skipped);
            return results;
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception ex) { LastError = ex.ToString(); return Array.Empty<StabilityResult>(); }
        finally
        {
            try { if (core is { HasExited: false }) core.Kill(entireProcessTree: true); } catch { }
            core?.Dispose();
            try { File.Delete(configPath); } catch { }
        }
    }

    private static async Task<StabilityResult> ProbeOne(
        string key, int port, string url, int attempts, int timeoutMs, CancellationToken ct)
    {
        var samples = new List<int>(attempts);
        using var handler = new HttpClientHandler
        {
            Proxy = new WebProxy($"socks5://127.0.0.1:{port}"),
            UseProxy = true,
        };
        using var http = new HttpClient(handler) { Timeout = TimeSpan.FromMilliseconds(timeoutMs) };

        for (int a = 0; a < attempts; a++)
        {
            ct.ThrowIfCancellationRequested();
            var sw = Stopwatch.StartNew();
            try
            {
                using var res = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
                if ((int)res.StatusCode < 500) samples.Add((int)sw.ElapsedMilliseconds);
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested) { /* timeout — a failed attempt */ }
            catch { /* refused, reset, protocol error — a failed attempt */ }
        }

        if (samples.Count == 0) return new StabilityResult(key, -1, 0, attempts);
        samples.Sort();
        return new StabilityResult(key, samples[samples.Count / 2], samples.Count, attempts);
    }

    /// <summary>
    /// One inbound and one outbound per server, wired together by a routing rule. Sniffing is off
    /// and no DNS block is set: we are measuring the path, not exercising the routing rules.
    /// </summary>
    private static string BuildProbeConfig(
        IReadOnlyList<(string Key, OutboundProfile Profile)> servers, int[] ports)
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
                ["port"] = ports[i],
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
