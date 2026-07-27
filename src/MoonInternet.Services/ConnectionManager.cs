using System.IO;
using System.Text.Json;
using MoonInternet.Core.Generation;
using MoonInternet.Core.Models;

namespace MoonInternet.Services;

public enum ConnectionState { Disconnected, Connecting, Connected }
public enum TunnelMode { SystemProxy, Tun }

/// <summary>
/// Orchestrates a connection: starts the xray core (SOCKS) and either flips the WinINet proxy
/// (SystemProxy mode) or asks the elevated TUN helper to route everything through that SOCKS (TUN mode).
/// If the TUN helper is not installed, TUN mode falls back to system proxy.
/// </summary>
public sealed class ConnectionManager : IDisposable
{
    private readonly XrayRunner _xray;
    private readonly SingBoxProxyRunner _singbox;
    private IProxyRunner _runner;                 // active core: xray, or sing-box for Hysteria2
    private bool _tunActive;
    private bool _userDisconnect;   // distinguishes intentional disconnect from a drop
    private TunnelMode _lastMode;
    private RoutingProfile? _lastRouting;
    private CancellationTokenSource? _cts;   // cancels an in-progress connect ("instant cancel")
    private volatile bool _reconnecting;     // guards against overlapping reconnects (ProcessExited + watchdog)
    private System.Threading.Timer? _health; // polls the SOCKS port while connected — a dead core → reconnect
    private int _healthFails;

    public ConnectionState State { get; private set; } = ConnectionState.Disconnected;
    public OutboundProfile? Current { get; private set; }
    public TunnelMode ActiveMode { get; private set; }
    public string? TunFallbackReason { get; private set; }
    public string? RoutingNote { get; private set; }
    public bool AutoReconnect { get; set; } = true;
    public bool KillSwitch { get; set; }
    public string AppRouteMode { get; set; } = "off";                                  // per-app split routing (TUN)
    public IReadOnlyList<string> AppRouteApps { get; set; } = Array.Empty<string>();
    public int SocksPort => _runner.SocksPort;

    /// <summary>xray traffic totals (recv=download, sent=upload) for the live counters. Null if xray isn't the active
    /// core or stats are unavailable (e.g. Hysteria2 TUN, which runs a lone sing-box → use <see cref="SingBoxTraffic"/>).</summary>
    public (long recv, long sent)? XrayTraffic() => _xray.QueryTraffic() is { } t ? (t.down, t.up) : null;

    // Hysteria2 TUN runs a single sing-box with a Clash API (127.0.0.1:19099). Read its cumulative totals from there.
    private static readonly System.Net.Http.HttpClient _clash = new(new System.Net.Http.HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(2) };
    public (long recv, long sent)? SingBoxTraffic()
    {
        try
        {
            var json = _clash.GetStringAsync("http://127.0.0.1:19099/connections").GetAwaiter().GetResult();
            using var doc = JsonDocument.Parse(json);
            long down = doc.RootElement.TryGetProperty("downloadTotal", out var d) ? d.GetInt64() : 0;
            long up = doc.RootElement.TryGetProperty("uploadTotal", out var u) ? u.GetInt64() : 0;
            return (down, up);
        }
        catch { return null; }
    }
    public int HttpPort => _runner.HttpPort;
    public bool CoreAvailable => _xray.CoreAvailable;
    public event Action<ConnectionState>? StateChanged;
    public event Action<string>? Progress;   // staged status text for the connect button
    private void Report(string s) => Progress?.Invoke(s);

    public ConnectionManager(string coresDir)
    {
        _xray = new XrayRunner(coresDir);
        _singbox = new SingBoxProxyRunner(coresDir);
        _xray.ProcessExited += OnCoreDropped;
        _singbox.ProcessExited += OnCoreDropped;
        _runner = _xray;
        // A tunnel left over from a previous run (crash, re-login, autostart) still routes everything into a port
        // that no longer exists → "connected but no internet". Clear it before we do anything else.
        Task.Run(() => { try { TunClient.StopTun(); } catch { } });
    }

    private void OnCoreDropped() => HandleDrop();

    // xray died — it either exited (ProcessExited) or its SOCKS port went dead while the process lingered
    // (caught by the health watchdog). Either way the tunnel is routing into a dead proxy and passes no traffic,
    // so tear down and reconnect. A real user's log showed the OLD build stuck exactly like this for ~19 HOURS
    // (dead TUN spamming "dial 127.0.0.1:<socks> refused" — no internet while "connected").
    private void HandleDrop()
    {
        if (_userDisconnect || !AutoReconnect || State != ConnectionState.Connected || Current is null || _reconnecting) return;
        _ = ReconnectAsync(Current, _lastMode, _lastRouting);
    }

    // While connected, poll xray's local SOCKS port. Two consecutive misses (~20 s) mean the core is gone even
    // if it never fired ProcessExited (a silent hang) → trigger the same reconnect. Safety net for a dead tunnel.
    private void StartHealthWatchdog()
    {
        StopHealthWatchdog();
        _healthFails = 0;
        _health = new System.Threading.Timer(_ => HealthTick(), null, 10000, 10000);
    }

    private void StopHealthWatchdog()
    {
        _health?.Dispose();
        _health = null;
        _healthFails = 0;
    }

    private async void HealthTick()
    {
        if (State != ConnectionState.Connected || _userDisconnect || _reconnecting) return;
        try
        {
            bool alive = await Pinger.TcpLatencyAsync("127.0.0.1", _runner.SocksPort, 1500) >= 0;
            if (alive) { _healthFails = 0; return; }
            if (++_healthFails >= 2) { _healthFails = 0; HandleDrop(); }
        }
        catch { /* transient — try again next tick */ }
    }

    private async Task ReconnectAsync(OutboundProfile server, TunnelMode mode, RoutingProfile? routing)
    {
        _reconnecting = true;
        StopHealthWatchdog();
        Set(ConnectionState.Connecting);
        try
        {
            for (int attempt = 0; attempt < 5; attempt++)
            {
                await Task.Delay(2000);
                if (_userDisconnect) return;
                try { await ConnectAsync(server, mode, routing); return; } catch { /* retry */ }
            }
            // Gave up. Kill-switch: leave the system proxy pointing at the dead port so proxy-aware
            // traffic stays blocked (fail-closed) instead of leaking; the user must disconnect manually.
            if (KillSwitch)
            {
                _userDisconnect = true;
                if (_tunActive) { try { TunClient.StopTun(); } catch { } _tunActive = false; }
                _runner.Stop();
                Current = null;
                RoutingNote = "kill-switch: связь потеряна, трафик заблокирован";
                Set(ConnectionState.Disconnected);
            }
            else Disconnect();
        }
        finally { _reconnecting = false; }
    }

    public static bool TunServiceAvailable() => TunClient.IsAvailable();

    public async Task ConnectAsync(OutboundProfile profile, TunnelMode mode, RoutingProfile? routing = null)
    {
        Disconnect();
        _userDisconnect = false;      // an intentional connect — drops should auto-reconnect
        _lastMode = mode;
        _lastRouting = routing;
        _cts = new CancellationTokenSource();
        var ct = _cts.Token;
        Set(ConnectionState.Connecting);
        try
        {
            // WireGuard family, always TUN, never xray:
            //  • plain WG  → sing-box (TUN + wireguard outbound + router) so Direct/Proxy/Block rules apply;
            //  • AmneziaWG → amneziawg-go, the only engine that speaks its obfuscation (no router → tunnel all).
            if (profile.Wireguard is { } wgc)
            {
                if (wgc.IsAmnezia) throw new InvalidOperationException("AmneziaWG не поддерживается — используйте обычный WireGuard или другой протокол");
                await ConnectWgSingBoxAsync(profile, wgc, routing, ct);
                return;
            }

            // Hysteria2 over TUN: the helper's OWN sing-box is both the TUN and the Hysteria2 outbound. This is
            // the only reliable shape — the xray-router→sing-box chain loops on Hysteria2's QUIC (the app-side
            // sing-box's UDP to the server gets caught by the TUN even with the server-IP pin; auto_detect_interface
            // in the single sing-box avoids it). Routing is applied INSIDE this config (see SingBoxHy2TunConfig).
            if (profile.Protocol == ProtocolType.Hysteria2 && mode == TunnelMode.Tun && TunClient.IsAvailable())
            {
                await ConnectHy2TunAsync(profile, routing, ct);
                return;
            }

            string? geoDir = null;
            RoutingNote = null;
            if (routing is not null)
            {
                Report("Загрузка гео-файлов…");   // first connect downloads ~90 MB — must finish before the core starts
                geoDir = await GeoService.EnsureAsync(routing, ct);
                if (geoDir is null) { RoutingNote = "geo-файлы не загружены — routing отключён"; routing = null; }
                else RoutingNote = $"routing: {routing.Name}";
            }
            ct.ThrowIfCancellationRequested();

            // In TUN mode, resolve the server domain up front and connect xray to the IP directly. Otherwise
            // xray would resolve the domain itself, that DNS query gets hijacked by the TUN, and it deadlocks.
            // Hysteria2: xray can't speak it, so sing-box holds the Hysteria2 tunnel and xray's "proxy" outbound
            // is a SOCKS into it. That way xray still does ALL the routing → one routing for every protocol, and
            // it works in system-proxy mode too. Non-Hysteria2 servers connect straight through xray as before.
            bool isHy2 = profile.Protocol == ProtocolType.Hysteria2;
            IReadOnlyList<string> serverIps = (mode == TunnelMode.Tun || isHy2)
                ? await ResolveServerIpsAsync(profile.Address) : Array.Empty<string>();
            ct.ThrowIfCancellationRequested();
            var connectProfile = serverIps.Count > 0 ? profile.CloneForConnectIp(serverIps[0]) : profile;

            Report("Запуск ядра…");
            var xrayProfile = connectProfile;
            if (isHy2)
            {
                _singbox.Start(connectProfile, null, null);
                if (!await WaitPortAsync(_singbox.SocksPort, ct)) throw new InvalidOperationException("hysteria2 core did not start");
                xrayProfile = new OutboundProfile { Protocol = ProtocolType.Socks, Address = "127.0.0.1", Port = _singbox.SocksPort, Name = profile.Name };
            }
            _xray.Start(xrayProfile, routing, geoDir);
            if (!await WaitPortAsync(_xray.SocksPort, ct)) throw new InvalidOperationException("core did not open the local proxy port");

            _tunActive = false;
            TunFallbackReason = null;
            if (mode == TunnelMode.Tun)
            {
                // The TUN helper is a SEPARATE SYSTEM process that outlives us: after an app restart (autostart,
                // re-login) or a mode switch it can still be routing everything into the PREVIOUS xray port, which
                // is now dead → "tunnel but no internet" until the user kills sing-box/xray by hand. Our in-process
                // _tunActive flag knows nothing about that, so always tear the old tunnel down first.
                try { TunClient.StopTun(); } catch { }
                // The helper blocks until the TUN adapter is actually up, so "OK" already means traffic can flow.
                Report("Поднятие туннеля…");
                var r = await Task.Run(() => TunClient.StartTun(_runner.SocksPort, serverIps, AppRouteMode, AppRouteApps), ct);
                if (r == "OK") { _tunActive = true; ActiveMode = TunnelMode.Tun; }
                else { SystemProxy.Enable("127.0.0.1", _runner.HttpPort); ActiveMode = TunnelMode.SystemProxy; TunFallbackReason = r; }
            }
            else
            {
                SystemProxy.Enable("127.0.0.1", _runner.HttpPort);
                ActiveMode = TunnelMode.SystemProxy;
            }

            ct.ThrowIfCancellationRequested();
            Current = profile;
            Set(ConnectionState.Connected);
            StartHealthWatchdog();
        }
        catch (OperationCanceledException)
        {
            // user cancelled — keep "Отмена…" on screen a beat (some teardown is instant), then tear down
            await Task.Delay(700);
            try { if (_tunActive) { TunClient.StopTun(); _tunActive = false; } } catch { }
            try { SystemProxy.Disable(); } catch { }
            _runner.Stop();
            Current = null;
            if (State != ConnectionState.Disconnected) Set(ConnectionState.Disconnected);
        }
        catch
        {
            Disconnect();
            throw;
        }
    }

    /// <summary>
    /// Instant cancel of an in-progress connect: flip the UI to Disconnected NOW and cancel the token. The
    /// ConnectAsync cancel path then tears everything down in the right order (TUN before xray) so we never
    /// leave the TUN routed at a dead core. If cancel lands mid-StartTun (a long helper call that can't be
    /// aborted), teardown lands a few seconds later — the UI is already free.
    /// </summary>
    public void CancelConnect()
    {
        if (State != ConnectionState.Connecting) return;
        _userDisconnect = true;
        _cts?.Cancel();
        // Don't flip to Disconnected here — ConnectAsync's cancel path tears TUN/xray down first and flips
        // once teardown actually completes, so the UI can show "Отмена…" for the real duration.
    }

    public void Disconnect()
    {
        _userDisconnect = true;   // suppress auto-reconnect for an intentional stop
        StopHealthWatchdog();
        // Unconditional: the helper may still hold a tunnel we didn't start (previous app run / crashed session).
        try { TunClient.StopTun(); } catch { }
        _tunActive = false;
        try { SystemProxy.Disable(); } catch { /* best effort */ }
        _xray.Stop();
        _singbox.Stop();
        Current = null;
        Set(ConnectionState.Disconnected);
    }

    /// <summary>Plain WireGuard via the helper's sing-box — one process doing TUN + wireguard outbound + routing.
    /// This is what makes Direct/Proxy/Block rules work for WG (the amneziawg-go path has no router at all).</summary>
    private async Task ConnectWgSingBoxAsync(OutboundProfile profile, WireGuardConfig wg, RoutingProfile? routing, CancellationToken ct)
    {
        if (!TunClient.IsAvailable()) throw new InvalidOperationException("TUN-служба не запущена (нужна установка/перезагрузка)");
        string? srsDir = null;
        RoutingNote = "WireGuard";
        if (routing is not null)
        {
            Report("Загрузка правил маршрутизации…");
            srsDir = await GeoService.EnsureSingBoxRulesAsync(ct);
            RoutingNote = srsDir is null ? "WireGuard (правила не загружены — routing выкл.)" : $"WireGuard · routing: {routing.Name}";
        }
        ct.ThrowIfCancellationRequested();
        Report("Поднятие туннеля…");
        var launch = new WgSbLaunch
        {
            Wg = wg,
            RoutingJson = routing is null || srsDir is null ? null : JsonSerializer.Serialize(routing),
            SrsDir = srsDir,
        };
        var r = await Task.Run(() => TunClient.StartWgSingBox(launch), ct);
        if (r != "OK") throw new InvalidOperationException(r.StartsWith("ERR") ? r[4..] : r);
        _tunActive = true;
        ActiveMode = TunnelMode.Tun;
        Current = profile;
        Set(ConnectionState.Connected);
    }


    // Hysteria2 over TUN: hand the endpoint to the helper's sing-box (which is both the TUN and the Hysteria2
    // outbound). No xray, no app-side sing-box, no health watchdog — the single sing-box owns the whole path.
    private async Task ConnectHy2TunAsync(OutboundProfile profile, RoutingProfile? routing, CancellationToken ct)
    {
        // Hysteria2 runs on sing-box, which can't read xray's geo .dat — so fetch the sing-box .srs rule-sets and
        // let the helper's sing-box apply the SAME РФ routing (see SingBoxHy2TunConfig). No rules → tunnel everything.
        string? srsDir = null;
        RoutingNote = "Hysteria2";
        if (routing is not null)
        {
            Report("Загрузка правил маршрутизации…");
            srsDir = await GeoService.EnsureSingBoxRulesAsync(ct);
            RoutingNote = srsDir is null ? "Hysteria2 (правила не загружены — routing выкл.)" : $"Hysteria2 · routing: {routing.Name}";
        }
        ct.ThrowIfCancellationRequested();

        Report("Поднятие туннеля…");
        var ips = await ResolveServerIpsAsync(profile.Address);
        ct.ThrowIfCancellationRequested();
        var launch = new Hy2Launch
        {
            Address = ips.Count > 0 ? ips[0] : profile.Address,        // connect by IP; keep the domain as SNI
            Port = profile.Port,
            Password = profile.Password,
            Sni = string.IsNullOrEmpty(profile.Sni) ? profile.Address : profile.Sni,
            Obfs = profile.Obfs,
            ObfsPassword = profile.ObfsPassword,
            AllowInsecure = profile.AllowInsecure,
            Alpn = profile.Alpn,
            UpMbps = int.TryParse(profile.Extra.GetValueOrDefault("up") ?? profile.Extra.GetValueOrDefault("upmbps"), out var up) ? up : 0,
            DownMbps = int.TryParse(profile.Extra.GetValueOrDefault("down") ?? profile.Extra.GetValueOrDefault("downmbps"), out var dn) ? dn : 0,
            RoutingJson = (routing is not null && srsDir is not null) ? System.Text.Json.JsonSerializer.Serialize(routing) : null,
            SrsDir = srsDir,
        };
        var r = await Task.Run(() => TunClient.StartHy2(launch), ct);
        ct.ThrowIfCancellationRequested();
        if (r != "OK") throw new InvalidOperationException("Hysteria2 TUN: " + r);
        _tunActive = true;
        ActiveMode = TunnelMode.Tun;
        Current = profile;
        Set(ConnectionState.Connected);
    }

    // Resolve the VPN server's address to IP(s) so the TUN can route them direct (break the loop). If it's
    // already an IP, use it as-is; DNS failures just yield an empty list (process_name stays as the backup).
    private static async Task<IReadOnlyList<string>> ResolveServerIpsAsync(string address)
    {
        if (System.Net.IPAddress.TryParse(address, out var literal)) return new[] { literal.ToString() };
        try
        {
            var ips = await System.Net.Dns.GetHostAddressesAsync(address);
            return ips.Where(i => i.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
                      .Select(i => i.ToString()).Distinct().ToArray();
        }
        catch { return Array.Empty<string>(); }
    }

    // Wait (up to ~5 s) for a local core's proxy port to start accepting connections.
    private static async Task<bool> WaitPortAsync(int port, CancellationToken ct)
    {
        for (int i = 0; i < 25; i++)
        {
            ct.ThrowIfCancellationRequested();
            if (await Pinger.TcpLatencyAsync("127.0.0.1", port, 400) >= 0) return true;
            await Task.Delay(200, ct);
        }
        return false;
    }

    private void Set(ConnectionState s)
    {
        State = s;
        StateChanged?.Invoke(s);
    }

    public void Dispose() => Disconnect();
}
