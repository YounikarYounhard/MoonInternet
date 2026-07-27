namespace MoonInternet.Core.Generation;

/// <summary>
/// Builds a sing-box config that creates a TUN device and forwards everything to a local SOCKS proxy
/// (the app's xray). This tun2socks split lets xray handle every protocol (incl. XHTTP) while sing-box
/// only owns the privileged TUN + routing. The xray.exe process is routed direct to break the loop.
/// </summary>
public static class SingBoxTunConfig
{
    public static string Build(int socksPort, string interfaceName = "MoonTun", IEnumerable<string>? serverIps = null,
                               string appRouteMode = "off", IEnumerable<string>? appRouteApps = null, string tunAddress = "172.19.0.1/30")
    {
        // The VPN server's own IPs MUST go direct, or xray's connection to the server is captured by the TUN
        // and loops back into xray → every proxied connection (incl. DNS) dies with EOF. process_name:["xray.exe"]
        // is a backup for this but is unreliable on Windows, so we pin the server IPs explicitly.
        var ips = (serverIps ?? Enumerable.Empty<string>())
            .Where(s => System.Net.IPAddress.TryParse(s, out _)).Distinct().ToList();
        string serverRule = ips.Count > 0
            ? "{ \"ip_cidr\": [" + string.Join(", ", ips.Select(ip => $"\"{ip}/32\"")) + "], \"outbound\": \"direct\" },\n              "
            : "";

        // Per-app routing (split tunnel, TUN only): "bypass" = listed apps go direct; "only" = ONLY listed apps
        // go through the VPN (everything else direct). Matched by process_name — best-effort on Windows.
        var apps = (appRouteApps ?? Enumerable.Empty<string>())
            .Select(a => a.Trim()).Where(a => a.Length > 0).Distinct().ToList();
        string appList = string.Join(", ", apps.Select(a => $"\"{a}\""));
        string finalOut = "proxy";
        string appRule = "";
        if (apps.Count > 0 && appRouteMode == "bypass")
            appRule = $"{{ \"process_name\": [{appList}], \"outbound\": \"direct\" }},\n              ";
        else if (apps.Count > 0 && appRouteMode == "only")
        {
            finalOut = "direct"; // everything direct EXCEPT the listed apps
            appRule = $"{{ \"process_name\": [{appList}], \"outbound\": \"proxy\" }},\n              ";
        }

        // Hand-written to keep the exact key names/shape sing-box 1.13 expects.
        // DNS MUST be hijacked: without it Windows sends queries to the TUN gateway (172.19.0.2:53),
        // the private-IP rule routes them "direct", and they time out → no name resolution → no internet.
        // The `{ "action": "sniff" }` rule is REQUIRED before `protocol:"dns"` can match at runtime (1.11+);
        // without it hijack-dns silently never fires. The new (1.12+) DNS server format is required in 1.13.
        // interface_name is made unique per start so a leaked wintun adapter can't block the next connect.
        return $$"""
        {
          "log": { "level": "warn" },
          "dns": {
            "servers": [
              { "type": "https", "tag": "remote", "server": "1.1.1.1", "detour": "proxy" }
            ],
            "strategy": "ipv4_only"
          },
          "inbounds": [
            {
              "type": "tun",
              "tag": "tun-in",
              "interface_name": "{{interfaceName}}",
              "address": ["{{tunAddress}}"],
              "auto_route": true,
              "strict_route": true,
              "stack": "gvisor"
            }
          ],
          "outbounds": [
            { "type": "socks", "tag": "proxy", "server": "127.0.0.1", "server_port": {{socksPort}}, "version": "5" },
            { "type": "direct", "tag": "direct" }
          ],
          "route": {
            "auto_detect_interface": true,
            "final": "{{finalOut}}",
            "rules": [
              { "action": "sniff" },
              {{serverRule}}{ "process_name": ["xray.exe", "sing-box.exe"], "outbound": "direct" },
              { "protocol": "dns", "action": "hijack-dns" },
              {{appRule}}{ "ip_is_private": true, "outbound": "direct" }
            ]
          }
        }
        """;
    }
}
