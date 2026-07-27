using System.Linq;
using System.Net.Sockets;
using System.Text;

namespace MoonInternet.Services;

/// <summary>Talks to the privileged TUN helper over loopback TCP (see MoonInternet.TunService).</summary>
public static class TunClient
{
    public const int Port = 35555;

    public static string Send(string cmd, int timeoutMs = 4000)
    {
        try
        {
            using var c = new TcpClient();
            if (!c.ConnectAsync("127.0.0.1", Port).Wait(timeoutMs)) return "ERR timeout";
            using var ns = c.GetStream();
            var b = Encoding.UTF8.GetBytes(cmd);
            ns.Write(b, 0, b.Length);
            var buf = new byte[256];
            int n = ns.Read(buf, 0, buf.Length);
            return Encoding.UTF8.GetString(buf, 0, n).Trim();
        }
        catch { return "ERR unavailable"; }
    }

    public static bool IsAvailable() => Send("PING", 1500) == "OK";
    // The helper now blocks until the TUN adapter is actually up (~10-20 s) before replying, so allow for it.
    // serverIps go direct in the TUN so xray's connection to the VPN server can't loop back into the tunnel.
    // appRouteMode/apps = per-app split routing (base64 so exe names with spaces survive the space-delimited protocol).
    public static string StartTun(int socksPort, IEnumerable<string>? serverIps = null, string appRouteMode = "off", IEnumerable<string>? appRouteApps = null)
    {
        var ipList = serverIps?.ToList() ?? new System.Collections.Generic.List<string>();
        string ips = ipList.Count > 0 ? string.Join(",", ipList) : "-";
        var apps = appRouteApps?.Where(a => !string.IsNullOrWhiteSpace(a)).ToList() ?? new System.Collections.Generic.List<string>();
        string mode = string.IsNullOrWhiteSpace(appRouteMode) ? "off" : appRouteMode;
        string appsB64 = apps.Count > 0 && mode != "off"
            ? Convert.ToBase64String(Encoding.UTF8.GetBytes(string.Join("\n", apps))) : "-";
        return Send($"START {socksPort} {ips} {mode} {appsB64}", 30000);
    }
    public static string StopTun() => Send("STOP", 10000);
    // Hysteria2 over TUN: the helper's own sing-box does the TUN AND the Hysteria2 outbound (no xray, no loop).
    public static string StartHy2(MoonInternet.Core.Models.Hy2Launch launch)
    {
        string json = System.Text.Json.JsonSerializer.Serialize(launch);
        string b64 = Convert.ToBase64String(Encoding.UTF8.GetBytes(json));
        return Send($"STARTHY2 {b64}", 30000);
    }
    // Plain WireGuard through sing-box — same one-process shape, and it gives us real routing.
    public static string StartWgSingBox(MoonInternet.Core.Models.WgSbLaunch launch)
    {
        string json = System.Text.Json.JsonSerializer.Serialize(launch);
        string b64 = Convert.ToBase64String(Encoding.UTF8.GetBytes(json));
        return Send($"STARTWGSB {b64}", 30000);
    }
}
