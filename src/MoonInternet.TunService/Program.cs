using System.Diagnostics;
using System.IO.Pipes;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using MoonInternet.Core.Generation;
using MoonInternet.Core.Models;

// Privileged TUN helper. Runs as SYSTEM (via a scheduled task set up by the installer) so it can
// create the TUN adapter + routes without a per-launch UAC prompt. Controlled over loopback TCP.
// Engine: try tun2socks (Amnezia's choice) first; if it can't pass real traffic, fall back to sing-box.
// ponytail: loopback-only, no auth token yet — add a ProgramData token file if a hostile local process is in scope.

const int Port = 35555;
const string TunGatewayIp = "172.19.0.1";
string baseDir = AppContext.BaseDirectory;
string singbox = Path.Combine(baseDir, "cores", "singbox", "sing-box.exe");
string tun2socks = Path.Combine(baseDir, "cores", "tun2socks", "tun2socks.exe");
string dataDir = MoonInternet.Core.AppPaths.DataDir;   // <install>\data, same folder the app uses
Directory.CreateDirectory(dataDir);
string cfgPath = Path.Combine(dataDir, "tun.json");
string logPath = Path.Combine(dataDir, "tunservice.log");

Process? proc = null;                        // the current tunnel engine (sing-box OR tun2socks)
Process? zapret = null;                      // winws.exe — the запрет mode, never runs alongside a tunnel
var cleanupRoutes = new List<string>();      // "<dst> mask <mask>" entries to `route delete` on stop
object gate = new();

void Log(string m) { try { File.AppendAllText(logPath, $"[{DateTime.Now:O}] {m}\n"); } catch { } }

int Run(string exe, string args, int timeoutMs = 8000)
{
    try
    {
        var psi = new ProcessStartInfo { FileName = exe, Arguments = args, UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        using var p = Process.Start(psi)!;
        p.WaitForExit(timeoutMs);
        return p.HasExited ? p.ExitCode : -1;
    }
    catch (Exception e) { Log($"run failed: {exe} {args}: {e.Message}"); return -1; }
}


void StopTun()
{
    lock (gate)
    {
        if (proc is { HasExited: false }) { try { proc.Kill(true); proc.WaitForExit(5000); } catch { } }
        proc = null;
        // Kill orphaned engines — but ONLY the ones running from OUR cores folder. These binary names are shared
        // with other VPN clients (HAPP also ships sing-box), and killing theirs was breaking them while we ran.
        string coresDir = Path.Combine(baseDir, "cores");
        foreach (var name in new[] { "sing-box", "tun2socks" })
            foreach (var p in Process.GetProcessesByName(name))
            {
                string? exe = null;
                try { exe = p.MainModule?.FileName; } catch { /* access denied → not ours (different account) */ }
                if (exe is null || !exe.StartsWith(coresDir, StringComparison.OrdinalIgnoreCase)) continue;
                try { p.Kill(true); p.WaitForExit(5000); } catch { }
            }
        foreach (var r in cleanupRoutes) Run("route", $"delete {r}", 4000);
        cleanupRoutes.Clear();
        Thread.Sleep(500); // let the driver release the adapter + routes settle
    }
}

// Adapter exists AND carries the gateway IP (sing-box path) / adapter is up by name (tun2socks path).
bool TunHasGatewayIp()
{
    try
    {
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            foreach (var a in ni.GetIPProperties().UnicastAddresses)
                if (a.Address.ToString() == TunGatewayIp) return true;
        }
    }
    catch { }
    return false;
}

bool AdapterUp(string ifName)
{
    try
    {
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            if (ni.Name == ifName && ni.OperationalStatus == OperationalStatus.Up) return true;
    }
    catch { }
    return false;
}

// The physical default gateway (so the VPN server's own IP can bypass the TUN — else xray loops).
string? DefaultGateway()
{
    try
    {
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
            if (ni.Name.StartsWith("MoonTun", StringComparison.Ordinal)) continue;
            var gw = ni.GetIPProperties().GatewayAddresses
                .FirstOrDefault(g => g.Address.AddressFamily == AddressFamily.InterNetwork && g.Address.ToString() != "0.0.0.0");
            if (gw is not null) return gw.Address.ToString();
        }
    }
    catch { }
    return null;
}

// Does traffic actually flow through the tunnel? A raw TCP connect to a well-known IP (1.1.1.1:443) — NO DNS,
// hard-bounded — so a dead tunnel can't hang this for the OS DNS timeout (which would brick the whole box).
bool InternetThroughTun()
{
    try
    {
        using var c = new TcpClient();
        return c.ConnectAsync("1.1.1.1", 443).Wait(3000) && c.Connected;
    }
    catch { return false; }
}

Process? StartEngine(string exe, params string[] args)
{
    var psi = new ProcessStartInfo { FileName = exe, WorkingDirectory = Path.GetDirectoryName(exe)!, UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
    foreach (var a in args) psi.ArgumentList.Add(a);
    var p = Process.Start(psi);
    if (p is not null)
    {
        string tag = Path.GetFileNameWithoutExtension(exe);
        p.ErrorDataReceived += (_, e) => { if (e.Data is { Length: > 0 }) Log($"{tag}: {e.Data}"); };
        p.BeginErrorReadLine();
        // MUST drain stdout too: we redirect it, and an unread pipe fills after ~4 KB, blocking the engine mid-write.
        p.OutputDataReceived += (_, e) => { if (e.Data is { Length: > 0 }) Log($"{tag}: {e.Data}"); };
        p.BeginOutputReadLine();
    }
    return p;
}

string StartTun2Socks(int socksPort, IReadOnlyList<string> serverIps)
{
    if (!File.Exists(tun2socks)) return "ERR tun2socks missing";
    string? gw = DefaultGateway();
    if (gw is null) return "ERR no default gateway";

    string ifName = "MoonTun" + (Environment.TickCount64 % 100000);
    proc = StartEngine(tun2socks, "-device", "tun://" + ifName, "-proxy", $"socks5://127.0.0.1:{socksPort}", "--loglevel", "warning");
    if (proc is null) return "ERR tun2socks start failed";

    bool up = false;
    for (int i = 0; i < 30 && !up; i++) { if (proc.HasExited) return "ERR tun2socks exited"; up = AdapterUp(ifName); if (!up) Thread.Sleep(400); }
    if (!up) return "ERR tun2socks adapter not up";

    // Configure the adapter + routing table (tun2socks does none of this itself on Windows).
    Run("netsh", $"interface ip set address name=\"{ifName}\" static {TunGatewayIp} 255.255.255.0");
    Run("netsh", $"interface ip set dnsservers name=\"{ifName}\" static 1.1.1.1 primary");
    foreach (var ip in serverIps.Where(x => IPAddress.TryParse(x, out _)).Distinct())
    { Run("route", $"add {ip} mask 255.255.255.255 {gw} metric 1"); cleanupRoutes.Add($"{ip} mask 255.255.255.255"); }
    // Two /1 halves override the physical default route without deleting it.
    Run("route", $"add 0.0.0.0 mask 128.0.0.0 {TunGatewayIp} metric 1");
    Run("route", $"add 128.0.0.0 mask 128.0.0.0 {TunGatewayIp} metric 1");
    cleanupRoutes.Add("0.0.0.0 mask 128.0.0.0");
    cleanupRoutes.Add("128.0.0.0 mask 128.0.0.0");

    for (int i = 0; i < 8; i++) { if (InternetThroughTun()) { Log($"tun2socks up as {ifName} — internet OK"); return "OK"; } Thread.Sleep(700); }
    return "ERR tun2socks no traffic";
}

string StartSingBox(int socksPort, IReadOnlyList<string> serverIps, string appMode, IReadOnlyList<string> apps)
{
    if (!File.Exists(singbox)) return "ERR sing-box missing";
    string ifName = "MoonTun" + (Environment.TickCount64 % 100000);
    File.WriteAllText(cfgPath, SingBoxTunConfig.Build(socksPort, ifName, serverIps, appMode, apps)); // UTF-8 no BOM
    for (int attempt = 1; attempt <= 2; attempt++)
    {
        StopTun();
        proc = StartEngine(singbox, "run", "-c", cfgPath);
        if (proc is null) return "ERR sing-box start failed";
        for (int i = 0; i < 40; i++)
        {
            if (proc.HasExited) break;
            if (TunHasGatewayIp()) { Log($"sing-box up as {ifName} (attempt {attempt})"); return "OK"; }
            Thread.Sleep(500);
        }
        Log($"sing-box did not bring up TUN (attempt {attempt}, exited={proc?.HasExited})");
    }
    return "ERR sing-box TUN did not come up";
}

// Hysteria2 over TUN: ONE sing-box is both the TUN and the Hysteria2 outbound (xray can't do Hysteria2, and a
// separate app-side sing-box would loop through the TUN). auto_detect_interface makes its server connection go
// out the physical NIC. Same bring-up wait as StartSingBox.
string StartHy2Tun(Hy2Launch launch)
{
    if (!File.Exists(singbox)) return "ERR sing-box missing";
    string ifName = "MoonTun" + (Environment.TickCount64 % 100000);
    MoonInternet.Core.Models.RoutingProfile? routing = null;
    if (!string.IsNullOrEmpty(launch.RoutingJson))
        try { routing = JsonSerializer.Deserialize<MoonInternet.Core.Models.RoutingProfile>(launch.RoutingJson); } catch { }
    File.WriteAllText(cfgPath, SingBoxHy2TunConfig.Build(launch, ifName, routing, launch.SrsDir));
    for (int attempt = 1; attempt <= 2; attempt++)
    {
        StopTun();
        proc = StartEngine(singbox, "run", "-c", cfgPath);
        if (proc is null) return "ERR sing-box start failed";
        for (int i = 0; i < 40; i++)
        {
            if (proc.HasExited) break;
            if (TunHasGatewayIp()) { Log($"hysteria2 TUN up as {ifName} (attempt {attempt})"); return "OK"; }
            Thread.Sleep(500);
        }
        Log($"hysteria2 sing-box did not bring up TUN (attempt {attempt}, exited={proc?.HasExited})");
    }
    return "ERR hysteria2 TUN did not come up";
}

// Plain WireGuard through sing-box: same shape as the Hysteria2 path (one process = TUN + outbound + router).
string StartWgSingBox(WgSbLaunch launch)
{
    if (!File.Exists(singbox)) return "ERR sing-box missing";
    string ifName = "MoonTun" + (Environment.TickCount64 % 100000);
    MoonInternet.Core.Models.RoutingProfile? routing = null;
    if (!string.IsNullOrEmpty(launch.RoutingJson))
        try { routing = JsonSerializer.Deserialize<MoonInternet.Core.Models.RoutingProfile>(launch.RoutingJson); } catch { }
    File.WriteAllText(cfgPath, SingBoxWgTunConfig.Build(launch.Wg, ifName, routing, launch.SrsDir));
    for (int attempt = 1; attempt <= 2; attempt++)
    {
        StopTun();
        proc = StartEngine(singbox, "run", "-c", cfgPath);
        if (proc is null) return "ERR sing-box start failed";
        for (int i = 0; i < 40; i++)
        {
            if (proc.HasExited) break;
            if (TunHasGatewayIp()) { Log($"wireguard TUN up as {ifName} (attempt {attempt})"); return "OK"; }
            Thread.Sleep(500);
        }
        Log($"wireguard sing-box did not bring up TUN (attempt {attempt}, exited={proc?.HasExited})");
    }
    StopTun();
    return "ERR WireGuard: туннель не поднялся";
}


string StartTun(int socksPort, IReadOnlyList<string> serverIps, string appMode, IReadOnlyList<string> apps)
{
    lock (gate)
    {
        // sing-box is the PROVEN engine (auto-route + DNS hijack in one config) — use it first for reliability
        // across machines. tun2socks (manual routes) stays only as a fallback if sing-box can't come up.
        StopTun();
        var r = StartSingBox(socksPort, serverIps, appMode, apps);
        if (r == "OK") return "OK";
        Log($"sing-box path failed ({r}) → trying tun2socks");
        StopTun();
        return StartTun2Socks(socksPort, serverIps); // tun2socks fallback can't do per-app routing
    }
}

void StopZapret()
{
    lock (gate)
    {
        if (zapret is { HasExited: false }) { try { zapret.Kill(true); zapret.WaitForExit(5000); } catch { } }
        zapret = null;
        // Same rule as the tunnel engines: only kill winws.exe that came out of OUR folder. Other
        // DPI-bypass tools ship the very same binary, and killing theirs would break them.
        string mine = Path.Combine(baseDir, "cores", "zapret");
        foreach (var p in Process.GetProcessesByName("winws"))
        {
            string? exe = null;
            try { exe = p.MainModule?.FileName; } catch { /* access denied → not ours */ }
            if (exe is null || !exe.StartsWith(mine, StringComparison.OrdinalIgnoreCase)) continue;
            try { p.Kill(true); p.WaitForExit(5000); } catch { }
        }
    }
}

/// <summary>
/// Starts winws.exe for one named strategy.
///
/// The caller names a strategy and a filter, never a command line: this process is SYSTEM, and
/// letting the unprivileged app hand it arguments would mean it could run anything it liked with
/// them. The arguments are read here, out of our own folder, from the file that owns that name.
/// </summary>
string StartZapret(string id, string filter)
{
    string dir = Path.Combine(baseDir, "cores", "zapret");
    string exe = Path.Combine(dir, "bin", "winws.exe");
    if (!File.Exists(exe)) return "ERR zapret is not installed";

    var mode = filter switch
    {
        "all" => MoonInternet.Core.Parsing.ZapretGameFilter.All,
        "tcp" => MoonInternet.Core.Parsing.ZapretGameFilter.Tcp,
        "udp" => MoonInternet.Core.Parsing.ZapretGameFilter.Udp,
        _ => MoonInternet.Core.Parsing.ZapretGameFilter.Off,
    };
    var strategy = MoonInternet.Core.Parsing.ZapretStrategyParser.Load(dir, mode).FirstOrDefault(s => s.Id == id);
    if (strategy is null) return "ERR no such strategy: " + id;

    lock (gate)
    {
        StopTun();      // запрет and a tunnel cannot both hold the traffic
        StopZapret();
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = exe, Arguments = strategy.Arguments,
                // WinDivert.dll and the .bin payloads are looked up next to the exe.
                WorkingDirectory = Path.Combine(dir, "bin"),
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true,
            };
            zapret = Process.Start(psi);
            if (zapret is null) return "ERR winws did not start";
            zapret.BeginOutputReadLine();
            zapret.BeginErrorReadLine();
            zapret.OutputDataReceived += (_, e) => { if (e.Data is { } d) Log("winws: " + d); };
            zapret.ErrorDataReceived  += (_, e) => { if (e.Data is { } d) Log("winws! " + d); };

            // A rejected argument or a missing driver kills it in well under a second, and the
            // difference between «running» and «died instantly» is the whole answer here.
            Thread.Sleep(700);
            if (zapret.HasExited) { var code = zapret.ExitCode; zapret = null; return $"ERR winws exited ({code})"; }
            Log($"zapret up: {id} filter={filter}");
            return "OK";
        }
        catch (Exception e) { Log("zapret failed: " + e.Message); return "ERR " + e.Message; }
    }
}

string Handle(string cmd)
{
    Log("cmd: " + cmd);
    if (cmd == "PING") return "OK";
    if (cmd == "STOP") { StopTun(); StopZapret(); return "OK"; }
    if (cmd == "ZAPRETSTOP") { StopZapret(); return "OK"; }
    if (cmd.StartsWith("ZAPRET ", StringComparison.Ordinal))
    {
        // "ZAPRET <strategy id> <off|all|tcp|udp>"
        var parts = cmd[7..].Trim().Split('\t', 2, StringSplitOptions.TrimEntries);
        if (parts.Length == 0 || parts[0].Length == 0) return "ERR bad strategy";
        return StartZapret(parts[0], parts.Length > 1 ? parts[1] : "off");
    }
    if (cmd.StartsWith("STARTHY2 ", StringComparison.Ordinal))
    {
        // "STARTHY2 <base64(JSON Hy2Launch)>" — sing-box does the TUN AND the Hysteria2 outbound in one process.
        try
        {
            var launch = JsonSerializer.Deserialize<Hy2Launch>(Encoding.UTF8.GetString(Convert.FromBase64String(cmd[9..].Trim())));
            if (launch is null || string.IsNullOrEmpty(launch.Address)) return "ERR bad hy2 launch";
            lock (gate) { StopTun(); return StartHy2Tun(launch); }
        }
        catch (Exception e) { return "ERR hy2: " + e.Message; }
    }
    if (cmd.StartsWith("STARTWGSB ", StringComparison.Ordinal))
    {
        // "STARTWGSB <base64(JSON WgSbLaunch)>" — plain WireGuard via sing-box, so routing rules apply.
        try
        {
            var launch = JsonSerializer.Deserialize<WgSbLaunch>(Encoding.UTF8.GetString(Convert.FromBase64String(cmd[10..].Trim())));
            if (launch is null || !launch.Wg.IsValid) return "ERR bad wg launch";
            lock (gate) { StopTun(); return StartWgSingBox(launch); }
        }
        catch (Exception e) { return "ERR wg-sb: " + e.Message; }
    }
    if (cmd.StartsWith("START ", StringComparison.Ordinal))
    {
        // "START <port> <ips|-> <appMode> <base64(apps\n…)|->" — server IPs go direct (no loop); per-app split routing optional.
        var parts = cmd[6..].Trim().Split(' ', 4, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (parts.Length == 0 || !int.TryParse(parts[0], out var p) || p is <= 0 or > 65535) return "ERR bad port";
        var ips = parts.Length > 1 && parts[1] != "-"
            ? parts[1].Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            : Array.Empty<string>();
        string appMode = parts.Length > 2 ? parts[2] : "off";
        string[] apps = Array.Empty<string>();
        if (parts.Length > 3 && parts[3] != "-")
            try { apps = Encoding.UTF8.GetString(Convert.FromBase64String(parts[3])).Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries); } catch { }
        return StartTun(p, ips, appMode, apps);
    }
    return "ERR unknown";
}

Log($"TunService up on 127.0.0.1:{Port} (baseDir={baseDir})");
var listener = new TcpListener(IPAddress.Loopback, Port);
listener.Start();
AppDomain.CurrentDomain.ProcessExit += (_, _) => { StopTun(); StopZapret(); };

while (true)
{
    try
    {
        using var client = listener.AcceptTcpClient();
        using var ns = client.GetStream();
        var buf = new byte[8192];   // room for the START command's base64 app-route list
        int n = ns.Read(buf, 0, buf.Length);
        string cmd = Encoding.UTF8.GetString(buf, 0, n).Trim();
        var rb = Encoding.UTF8.GetBytes(Handle(cmd) + "\n");
        ns.Write(rb, 0, rb.Length);
    }
    catch (Exception ex) { Log("client error: " + ex.Message); }
}
