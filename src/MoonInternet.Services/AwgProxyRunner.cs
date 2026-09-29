using System.Diagnostics;
using System.IO;
using MoonInternet.Core.Generation;
using MoonInternet.Core.Models;

namespace MoonInternet.Services;

/// <summary>
/// Runs awgproxy — AmneziaWG behind a local SOCKS5 listener (see <c>build/awgproxy</c>).
///
/// It sits exactly where the Hysteria2 sing-box sits: it carries the tunnel, xray dials it as an
/// ordinary proxy outbound, and so every routing rule the app has applies to AmneziaWG without any
/// of it being written twice. That is the whole reason for the SOCKS shape — a TUN of its own would
/// have needed the routing again and admin rights besides.
/// </summary>
public sealed class AwgProxyRunner : IDisposable
{
    private readonly string _coresDir;
    private Process? _proc;
    private bool _stopping;
    private string? _uapiPath;

    public int SocksPort { get; private set; }
    public bool CoreAvailable => File.Exists(_exePath);
    public event Action? ProcessExited;

    public AwgProxyRunner(string coresDir) => _coresDir = coresDir;

    /// <summary>
    /// Two places, in this order.
    ///
    /// AmneziaWG is not in the installer any more: it is downloaded afterwards into the writable
    /// data folder, which is also the folder an app update leaves alone. The path beside the exe
    /// stays as the fallback, for a build that still carries it. Resolved on every use rather than
    /// once in the constructor — the engine can be installed while the app is running, and a path
    /// decided at startup would go on saying it is missing.
    /// </summary>
    private string _exePath
    {
        get
        {
            string downloaded = Path.Combine(MoonInternet.Core.AppPaths.In("cores"), "awg", "awgproxy.exe");
            return File.Exists(downloaded) ? downloaded : Path.Combine(_coresDir, "awg", "awgproxy.exe");
        }
    }

    /// <param name="keepPort">
    /// Come back up on the port we were already using. For a restart we decide on rather than one
    /// the user asked for: xray is still dialling that number and knows nothing about the change.
    /// </param>
    public void Start(WireGuardConfig wg, bool keepPort = false)
    {
        if (!CoreAvailable) throw new FileNotFoundException("AmneziaWG core not found", _exePath);
        Stop();

        if (!keepPort || SocksPort == 0) SocksPort = PortFinder.Free();

        // The UAPI document holds the private key, so it is written where the app's own data lives
        // rather than the shared temp folder, and deleted the moment the core has read it.
        _uapiPath = Path.Combine(MoonInternet.Core.AppPaths.DataDir, "awg.uapi");
        File.WriteAllText(_uapiPath, WireGuardUapi.Build(wg));

        var psi = new ProcessStartInfo
        {
            FileName = _exePath,
            WorkingDirectory = Path.GetDirectoryName(_exePath)!,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = false,
            RedirectStandardError = false,
        };
        psi.ArgumentList.Add("-uapi"); psi.ArgumentList.Add(_uapiPath);
        psi.ArgumentList.Add("-listen"); psi.ArgumentList.Add($"127.0.0.1:{SocksPort}");
        psi.ArgumentList.Add("-addr"); psi.ArgumentList.Add(string.Join(",", wg.Address));
        if (wg.Dns.Count > 0) { psi.ArgumentList.Add("-dns"); psi.ArgumentList.Add(string.Join(",", wg.Dns)); }
        // The panel sets an MTU in every config it writes and it is smaller than the usual default;
        // ignoring it leaves the tunnel fragmenting, which is what a censor's box looks hardest at.
        if (wg.Mtu is { } mtu) { psi.ArgumentList.Add("-mtu"); psi.ArgumentList.Add(mtu.ToString()); }

        _stopping = false;
        _proc = Process.Start(psi) ?? throw new InvalidOperationException("failed to start awgproxy");
        _proc.EnableRaisingEvents = true;
        _proc.Exited += (_, _) => { if (!_stopping) ProcessExited?.Invoke(); };
    }

    /// <summary>Deletes the UAPI file. Call once the port is up — the core reads it only at startup,
    /// so there is no reason for a file with the private key in it to outlive the handshake.</summary>
    public void DropSecrets()
    {
        if (_uapiPath is null) return;
        try { File.Delete(_uapiPath); } catch { /* it will be overwritten next start anyway */ }
        _uapiPath = null;
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
        DropSecrets();
    }

    public void Dispose() => Stop();
}
