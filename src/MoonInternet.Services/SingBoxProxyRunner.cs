using System.Diagnostics;
using System.IO;
using MoonInternet.Core.Generation;
using MoonInternet.Core.Models;

namespace MoonInternet.Services;

/// <summary>
/// Runs sing-box as the local proxy for Hysteria2 servers (xray can't do Hysteria2). Exposes a single mixed
/// SOCKS+HTTP port — SocksPort == HttpPort — so the TUN forwards to it and system-proxy points at it, exactly
/// like <see cref="XrayRunner"/>. Mirrors XrayRunner's lifecycle so ConnectionManager can use either.
/// </summary>
public sealed class SingBoxProxyRunner : IProxyRunner
{
    private readonly string _exePath;
    private readonly string _configPath;
    private Process? _proc;
    private bool _stopping;

    public int SocksPort { get; private set; }
    public int HttpPort => SocksPort;   // one mixed inbound serves both
    public bool CoreAvailable => File.Exists(_exePath);
    public event Action? ProcessExited;

    /// <param name="configName">A file of its own for a second engine (the stability probe).</param>
    public SingBoxProxyRunner(string coresDir, string configName = "moon_sbproxy.json")
    {
        _exePath = Path.Combine(coresDir, "singbox", "sing-box.exe");
        _configPath = Path.Combine(Path.GetTempPath(), configName);
    }

    public void Start(OutboundProfile profile, RoutingProfile? routing, string? geoAssetDir)
    {
        if (!CoreAvailable) throw new FileNotFoundException("sing-box core not found", _exePath);
        Stop();

        SocksPort = PortFinder.Free();
        File.WriteAllText(_configPath, SingBoxProxyConfig.Build(profile, SocksPort));

        var psi = new ProcessStartInfo
        {
            FileName = _exePath,
            Arguments = $"run -c \"{_configPath}\"",
            WorkingDirectory = Path.GetDirectoryName(_exePath)!,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = false,
            RedirectStandardError = false,
        };

        _stopping = false;
        _proc = Process.Start(psi) ?? throw new InvalidOperationException("failed to start sing-box proxy");
        _proc.EnableRaisingEvents = true;
        _proc.Exited += (_, _) => { if (!_stopping) ProcessExited?.Invoke(); };
    }

    public void Stop()
    {
        _stopping = true;
        if (_proc is { HasExited: false })
        {
            try { _proc.Kill(entireProcessTree: true); _proc.WaitForExit(3000); } catch { }
        }
        _proc?.Dispose();
        _proc = null;
        // The config carries the server's password; it has been read at startup.
        if (Path.GetFileName(_configPath).StartsWith("moon_probe_")) try { File.Delete(_configPath); } catch { }
    }

    public void Dispose() => Stop();
}
