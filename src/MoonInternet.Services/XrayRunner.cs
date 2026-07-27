using System.Diagnostics;
using MoonInternet.Core.Generation;
using MoonInternet.Core.Models;

namespace MoonInternet.Services;

/// <summary>
/// Manages the xray-core process lifecycle for a single selected server:
/// picks free ports, writes the generated config, launches xray, exposes the local SOCKS/HTTP proxy.
/// </summary>
public sealed class XrayRunner : IProxyRunner
{
    private readonly string _exePath;
    private readonly string _assetDir;
    private readonly string _configPath;
    private Process? _proc;
    private bool _stopping;

    public int SocksPort { get; private set; }
    public int HttpPort { get; private set; }
    public int ApiPort { get; private set; }

    /// <summary>Query xray's StatsService for the "proxy" outbound totals (bytes). Null if unavailable.
    /// Used for the live traffic counters in system-proxy mode (no TUN adapter to read there).</summary>
    public (long up, long down)? QueryTraffic()
    {
        if (ApiPort <= 0 || _proc is null || _proc.HasExited) return null;
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = _exePath, WorkingDirectory = _assetDir,
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
            };
            psi.ArgumentList.Add("api"); psi.ArgumentList.Add("statsquery");
            psi.ArgumentList.Add($"--server=127.0.0.1:{ApiPort}");
            using var p = Process.Start(psi);
            if (p is null) return null;
            string outp = p.StandardOutput.ReadToEnd();
            if (!p.WaitForExit(4000)) { try { p.Kill(); } catch { } return null; }   // generous: xray.exe spawn is slow under load

            long up = 0, down = 0;
            using var doc = System.Text.Json.JsonDocument.Parse(outp);
            if (doc.RootElement.TryGetProperty("stat", out var stat) && stat.ValueKind == System.Text.Json.JsonValueKind.Array)
                foreach (var e in stat.EnumerateArray())
                {
                    var name = e.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
                    long val = 0;
                    if (e.TryGetProperty("value", out var v))
                        val = v.ValueKind == System.Text.Json.JsonValueKind.Number ? v.GetInt64() : (long.TryParse(v.GetString(), out var lv) ? lv : 0);
                    // count the LOCAL inbound(s) = actual user payload (like INCY). Skip the "api" inbound.
                    if (!name.StartsWith("inbound>>>") || name.Contains(">>>api>>>")) continue;
                    if (name.EndsWith(">>>uplink")) up += val;
                    else if (name.EndsWith(">>>downlink")) down += val;
                }
            return (up, down);
        }
        catch { return null; }
    }
    public bool IsRunning => _proc is { HasExited: false };

    /// <summary>Raised when xray exits on its own (crash / killed externally), NOT via <see cref="Stop"/>.</summary>
    public event Action? ProcessExited;

    /// <param name="coresDir">Folder containing the <c>xray/</c> subfolder (xray.exe + geoip.dat/geosite.dat).</param>
    public XrayRunner(string coresDir, string? configPath = null)
    {
        _assetDir = Path.Combine(coresDir, "xray");
        _exePath = Path.Combine(_assetDir, "xray.exe");
        _configPath = configPath ?? Path.Combine(Path.GetTempPath(), "moon_xray.json");
    }

    public bool CoreAvailable => File.Exists(_exePath);

    public void Start(OutboundProfile profile) => Start(profile, null, null);

    /// <param name="routing">Optional HAPP/INCY routing to bake into the config.</param>
    /// <param name="geoAssetDir">Optional geo dir (geoip.dat/geosite.dat) to use instead of the bundled one — needed when routing references custom geosite tags (e.g. runetfreedom).</param>
    public void Start(OutboundProfile profile, RoutingProfile? routing, string? geoAssetDir)
    {
        if (!CoreAvailable) throw new FileNotFoundException("xray core not found", _exePath);
        Stop();

        (SocksPort, HttpPort) = PortFinder.Pair();
        do { ApiPort = PortFinder.Free(); } while (ApiPort == SocksPort || ApiPort == HttpPort);
        File.WriteAllText(_configPath, XrayConfigBuilder.Build(profile, routing, SocksPort, HttpPort, ApiPort));

        string assetDir = geoAssetDir is not null && File.Exists(Path.Combine(geoAssetDir, "geosite.dat")) ? geoAssetDir : _assetDir;
        var psi = new ProcessStartInfo
        {
            FileName = _exePath,
            WorkingDirectory = _assetDir,               // so xray finds wintun.dll etc.
            UseShellExecute = false,
            CreateNoWindow = true,
            // Do NOT redirect stdout/stderr: we never drain them, and a full OS pipe would block xray
            // mid-write and degrade the tunnel over time (the "server dies gradually" symptom).
            RedirectStandardOutput = false,
            RedirectStandardError = false,
        };
        psi.ArgumentList.Add("-config");
        psi.ArgumentList.Add(_configPath);
        psi.EnvironmentVariables["XRAY_LOCATION_ASSET"] = assetDir; // geoip.dat/geosite.dat resolution

        _stopping = false;
        _proc = Process.Start(psi) ?? throw new InvalidOperationException("failed to start xray");
        _proc.EnableRaisingEvents = true;
        _proc.Exited += (_, _) => { if (!_stopping) ProcessExited?.Invoke(); };
    }

    public void Stop()
    {
        _stopping = true;
        if (_proc is { HasExited: false })
        {
            try { _proc.Kill(entireProcessTree: true); _proc.WaitForExit(3000); } catch { /* already gone */ }
        }
        _proc?.Dispose();
        _proc = null;
    }

    public void Dispose() => Stop();
}
