using System.Text;
using System.Text.Json;
using MoonInternet.Core.Generation;
using MoonInternet.Core.Models;
using MoonInternet.Core.Parsing;
using MoonInternet.Services;

// WG-parse mode: `dotnet run -- wgparse <file>` parses a WG/AmneziaWG config (vpn:// or [Interface]) and prints UAPI.
if (args.Length >= 2 && args[0] == "wgparse")
{
    if (!WireGuardParser.TryParse(File.ReadAllText(args[1]).Trim(), out var w) || w is null) { Console.WriteLine("PARSE FAILED"); return 1; }
    Console.WriteLine($"endpoint={w.Endpoint} address={string.Join(",", w.Address)} dns={string.Join(",", w.Dns)} jc={w.Jc} h1={w.H1}");
    Console.WriteLine("--- UAPI ---");
    Console.WriteLine(WireGuardUapi.Build(w));
    return 0;
}

// Tun-config mode: `dotnet run -- tuncfg [outpath] [socksPort]` writes the sing-box TUN config for `sing-box check`.
if (args.Length >= 1 && args[0] == "tuncfg")
{
    string outp = args.Length >= 2 ? args[1] : Path.Combine(Path.GetTempPath(), "moon_tun_test.json");
    int port = args.Length >= 3 ? int.Parse(args[2]) : 10808;
    var sips = args.Length >= 4 && args[3] != "-" ? args[3].Split(',') : null;
    string appMode = args.Length >= 5 ? args[4] : "off";
    var apps = args.Length >= 6 ? args[5].Split(',') : null;
    File.WriteAllText(outp, MoonInternet.Core.Generation.SingBoxTunConfig.Build(port, "MoonTun1", sips, appMode, apps));
    Console.WriteLine($"wrote tun config -> {outp}");
    return 0;
}

// Emit mode: `dotnet run -- emit <subfile> <index> [outpath]` writes one server's xray config to disk.
if (args.Length >= 3 && args[0] == "emit")
{
    var subRes = SubscriptionParser.ParseFull(File.ReadAllText(args[1]));
    int idx = int.Parse(args[2]);
    string outp = args.Length >= 4 ? args[3] : Path.Combine(Path.GetTempPath(), "moon_xray_test.json");
    int socksPort = args.Length >= 5 ? int.Parse(args[4]) : 10808;
    var routing = subRes.Routing.Count > 0 ? subRes.Routing[0] : null; // INCY priority: first routing profile
    File.WriteAllText(outp, XrayConfigBuilder.Build(subRes.Servers[idx], routing, socksPort, socksPort + 1));
    Console.WriteLine($"wrote [{idx}] {subRes.Servers[idx].Protocol} \"{subRes.Servers[idx].Name}\" routing={(routing?.Name ?? "none")} -> {outp}");
    return 0;
}

// Emit a sing-box Hysteria2 proxy config for `sing-box check`: `dotnet run -- sbhy2 [out]`.
if (args.Length >= 1 && args[0] == "sbhy2")
{
    var hy = ShareLinkParser.Parse("hysteria2://auth123@1.2.3.4:443?sni=example.com&obfs=salamander&obfs-password=xyz&insecure=1#T");
    string outp = args.Length >= 2 ? args[1] : Path.Combine(Path.GetTempPath(), "moon_sbhy2.json");
    File.WriteAllText(outp, SingBoxProxyConfig.Build(hy, 10808));
    Console.WriteLine(outp);
    return 0;
}

// Emit a sing-box Hysteria2-over-TUN config for `sing-box check`: `dotnet run -- sbhy2tun [out] [srsDir]`.
// With srsDir it also builds the РФ routing (rule-sets + explicit rules) so the check validates that shape too.
if (args.Length >= 1 && args[0] == "sbhy2tun")
{
    var h = new MoonInternet.Core.Models.Hy2Launch { Address = "1.2.3.4", Port = 443, Password = "auth123", Sni = "example.com", Obfs = "salamander", ObfsPassword = "xyz", AllowInsecure = true };
    string outp = args.Length >= 2 ? args[1] : Path.Combine(Path.GetTempPath(), "moon_sbhy2tun.json");
    string? srsDir = args.Length >= 3 ? args[2] : null;
    MoonInternet.Core.Models.RoutingProfile? routing = srsDir is null ? null : new()
    {
        Name = "test",
        DirectSites = { "geosite:CATEGORY-RU", "geosite:MTS-RU", "geosite:NOPE-MISSING", "example.ru" },
        DirectIp = { "geoip:RU", "190.115.16.11" },
    };
    File.WriteAllText(outp, SingBoxHy2TunConfig.Build(h, "MoonTun1", routing, srsDir));
    Console.WriteLine(outp);
    return 0;
}

// Proc-exit mode: `dotnet run -- procexit <subfile> <index> [coresDir]` starts xray, kills it externally,
// and confirms XrayRunner raises ProcessExited (the trigger for auto-reconnect). No system proxy touched.
if (args.Length >= 3 && args[0] == "procexit")
{
    var subs = SubscriptionParser.Parse(File.ReadAllText(args[1]));
    string cores = Path.GetFullPath(args.Length >= 4 ? args[3] : Path.Combine(Directory.GetCurrentDirectory(), "cores"));
    using var runner = new XrayRunner(cores);
    if (!runner.CoreAvailable) { Console.WriteLine("xray core not found"); return 1; }
    bool fired = false;
    runner.ProcessExited += () => fired = true;
    runner.Start(subs[int.Parse(args[2])]);
    await Task.Delay(1500);
    foreach (var xpr in System.Diagnostics.Process.GetProcessesByName("xray")) { try { xpr.Kill(); } catch { } }
    await Task.Delay(1500);
    Console.WriteLine($"ProcessExited fired = {fired}");
    runner.Stop();
    return fired ? 0 : 1;
}

// Tunnel mode: `dotnet run -- tunnel <subfile> <index> [coresDir]` starts a real tunnel via
// XrayRunner and reports the exit IP seen through the local SOCKS proxy.
if (args.Length >= 3 && args[0] == "tunnel")
{
    var subs = SubscriptionParser.Parse(File.ReadAllText(args[1]));
    int idx = int.Parse(args[2]);
    string cores = Path.GetFullPath(args.Length >= 4 ? args[3] : Path.Combine(Directory.GetCurrentDirectory(), "cores"));
    using var runner = new XrayRunner(cores);
    if (!runner.CoreAvailable) { Console.WriteLine($"xray core not found under {cores}"); return 1; }
    // Optional arg[4] = connect-by-IP: proves IP-address + domain-SNI handshake still works (the TUN fix).
    var prof = args.Length >= 5 ? subs[idx].CloneForConnectIp(args[4]) : subs[idx];
    Console.WriteLine($"connecting to {prof.Address}:{prof.Port} sni={prof.Sni} host={prof.Host}");
    runner.Start(prof);
    using var http = new HttpClient(new HttpClientHandler { Proxy = new System.Net.WebProxy($"socks5://127.0.0.1:{runner.SocksPort}") })
        { Timeout = TimeSpan.FromSeconds(15) };
    string ip = "";
    for (int t = 0; t < 8 && ip.Length == 0; t++)
    {
        try { ip = (await http.GetStringAsync("https://api.ipify.org")).Trim(); }
        catch { await Task.Delay(700); }
    }
    runner.Stop();
    Console.WriteLine($"tunnel [{idx}] \"{subs[idx].Name}\" via socks {runner.SocksPort} -> IP: {(ip.Length == 0 ? "(failed)" : ip)}");
    return ip.Length == 0 ? 1 : 0;
}

// Diagnostic mode: `dotnet run -- <file>` parses a real subscription file and prints
// a credential-redacted summary + parsed/total coverage. Otherwise runs the self-check.
if (args.Length > 0)
{
    string content = File.ReadAllText(args[0]);
    string text = content.Trim();
    if (!text.Contains("://") && Base64Ext.TryDecodeUtf8(text, out var dec) && dec.Contains("://")) text = dec;
    var links = text.Split('\n').Select(l => l.Trim()).Where(l => l.Contains("://")).ToList();

    var okSchemes = new SortedDictionary<string, int>();
    var failSchemes = new SortedDictionary<string, int>();
    var profiles = new List<OutboundProfile>();
    foreach (var link in links)
    {
        string scheme = link[..link.IndexOf("://", StringComparison.Ordinal)].ToLowerInvariant();
        if (ShareLinkParser.TryParse(link, out var p, out var err))
        {
            okSchemes[scheme] = okSchemes.GetValueOrDefault(scheme) + 1;
            profiles.Add(p!);
        }
        else
        {
            failSchemes[scheme] = failSchemes.GetValueOrDefault(scheme) + 1;
            Console.WriteLine($"  SKIP [{scheme}]: {err}");
        }
    }

    string Red(string? s) => string.IsNullOrEmpty(s) ? "-" : (s.Length <= 4 ? "****" : s[..4] + "****");
    Console.WriteLine($"\nParsed {profiles.Count}/{links.Count} links");
    Console.WriteLine($"OK by scheme:   {string.Join(", ", okSchemes.Select(k => $"{k.Key}={k.Value}"))}");
    if (failSchemes.Count > 0)
        Console.WriteLine($"FAIL by scheme: {string.Join(", ", failSchemes.Select(k => $"{k.Key}={k.Value}"))}");
    Console.WriteLine();
    int i = 0;
    foreach (var p in profiles)
        Console.WriteLine($"[{i++,2}] {p.Protocol,-11} net={p.Network,-5} sec={p.Security,-7} cred={Red(p.Id ?? p.Password)}  {p.Address}:{p.Port}  \"{p.Name}\"");

    // Xray config generation coverage over the real servers
    int genOk = 0, genSingbox = 0, genErr = 0;
    foreach (var p in profiles)
    {
        try { _ = MoonInternet.Core.Generation.XrayConfigBuilder.Build(p); genOk++; }
        catch (NotSupportedException) { genSingbox++; }              // hysteria2/amnezia -> sing-box builder (Phase 4)
        catch (Exception e) { genErr++; Console.WriteLine($"  GENFAIL {p.Protocol} {p.Name}: {e.Message}"); }
    }
    Console.WriteLine($"\nXray config: {genOk} built OK, {genSingbox} deferred to sing-box, {genErr} errors");
    return genErr == 0 ? 0 : 1;
}

// Minimal assert-based self-check for the parsers (no test framework by design).
int passed = 0, failed = 0;
void Check(string name, bool ok)
{
    if (ok) { passed++; }
    else { failed++; Console.WriteLine($"  FAIL: {name}"); }
}
string B64(string s) => Convert.ToBase64String(Encoding.UTF8.GetBytes(s));

// --- VLESS Reality ---
var vless = ShareLinkParser.Parse(
    "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443" +
    "?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome&pbk=PUBKEY&sid=abcd&flow=xtls-rprx-vision&type=tcp#My%20Reality");
Check("vless protocol", vless.Protocol == ProtocolType.Vless);
Check("vless id", vless.Id == "b831381d-6324-4d53-ad4f-8cda48b30811");
Check("vless host/port", vless is { Address: "example.com", Port: 443 });
Check("vless reality pbk/sid", vless is { Security: "reality", PublicKey: "PUBKEY", ShortId: "abcd" });
Check("vless flow", vless.Flow == "xtls-rprx-vision");
Check("vless name decoded", vless.Name == "My Reality");

// --- VMess (base64 JSON) ---
var vmessJson = """{"v":"2","ps":"VM Node","add":"1.2.3.4","port":"8443","id":"11111111-2222-3333-4444-555555555555","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/ray","tls":"tls","sni":"cdn.example.com"}""";
var vmess = ShareLinkParser.Parse("vmess://" + B64(vmessJson));
Check("vmess protocol", vmess.Protocol == ProtocolType.Vmess);
Check("vmess name", vmess.Name == "VM Node");
Check("vmess endpoint", vmess is { Address: "1.2.3.4", Port: 8443 });
Check("vmess ws/path/host", vmess is { Network: "ws", Path: "/ray", Host: "cdn.example.com" });
Check("vmess tls", vmess.Security == "tls");

// --- Trojan over WS ---
var trojan = ShareLinkParser.Parse("trojan://pass123@example.com:443?security=tls&sni=example.com&type=ws&path=%2Fpath&host=cdn.com#Tro");
Check("trojan protocol", trojan.Protocol == ProtocolType.Trojan);
Check("trojan password", trojan.Password == "pass123");
Check("trojan ws path decoded", trojan.Path == "/path");
Check("trojan tls", trojan.Security == "tls");

// --- Shadowsocks SIP002 (base64 userinfo) ---
var ss = ShareLinkParser.Parse("ss://" + B64("aes-256-gcm:sspassword") + "@example.com:8388#SS");
Check("ss protocol", ss.Protocol == ProtocolType.Shadowsocks);
Check("ss method", ss.Method == "aes-256-gcm");
Check("ss password", ss.Password == "sspassword");
Check("ss endpoint", ss is { Address: "example.com", Port: 8388 });

// --- Shadowsocks legacy (whole body base64) ---
var ssLegacy = ShareLinkParser.Parse("ss://" + B64("chacha20-ietf-poly1305:pw@10.0.0.1:8080") + "#Legacy");
Check("ss legacy method", ssLegacy.Method == "chacha20-ietf-poly1305");
Check("ss legacy endpoint", ssLegacy is { Address: "10.0.0.1", Port: 8080 });

// --- Hysteria2 ---
var hy2 = ShareLinkParser.Parse("hysteria2://auth123@example.com:443?sni=example.com&obfs=salamander&obfs-password=xyz&insecure=1#Hy2");
Check("hy2 protocol", hy2.Protocol == ProtocolType.Hysteria2);
Check("hy2 auth", hy2.Password == "auth123");
Check("hy2 obfs", hy2 is { Obfs: "salamander", ObfsPassword: "xyz" });
Check("hy2 insecure", hy2.AllowInsecure);
// sing-box proxy config for Hysteria2 (xray can't do it → sing-box provides the local mixed proxy)
string sbHy2 = SingBoxProxyConfig.Build(hy2, 10808);
Check("sb-hy2 outbound", sbHy2.Contains("\"hysteria2\"") && sbHy2.Contains("\"password\": \"auth123\""));
Check("sb-hy2 obfs salamander", sbHy2.Contains("\"salamander\"") && sbHy2.Contains("\"xyz\""));
Check("sb-hy2 mixed inbound", sbHy2.Contains("\"mixed\"") && sbHy2.Contains("\"listen_port\": 10808"));
Check("sb-hy2 tls", sbHy2.Contains("\"insecure\": true") && sbHy2.Contains("\"server_name\": \"example.com\""));
// Hysteria2-over-TUN (single sing-box does TUN + hy2 outbound)
var hy2Launch = new MoonInternet.Core.Models.Hy2Launch { Address = "1.2.3.4", Port = 443, Password = "auth123", Sni = "example.com", Obfs = "salamander", ObfsPassword = "xyz", AllowInsecure = true };
string sbHy2Tun = SingBoxHy2TunConfig.Build(hy2Launch, "MoonTun1");
Check("sb-hy2-tun has tun + hy2", sbHy2Tun.Contains("\"tun\"") && sbHy2Tun.Contains("\"hysteria2\"") && sbHy2Tun.Contains("\"auto_detect_interface\": true"));
Check("sb-hy2-tun dns hijack", sbHy2Tun.Contains("\"hijack-dns\"") && sbHy2Tun.Contains("\"detour\": \"proxy\""));

// --- SOCKS5 ---
var socks = ShareLinkParser.Parse("socks5://user:pass@127.0.0.1:1080#Sock");
Check("socks endpoint", socks is { Address: "127.0.0.1", Port: 1080 });
Check("socks creds", socks is { Username: "user", Password: "pass" });

// --- Validation rejects garbage ---
Check("reject no-scheme", !ShareLinkParser.TryParse("notalink", out _, out _));
Check("reject bad port", !ShareLinkParser.TryParse("vless://id@host:0#x", out _, out _));

// --- Subscription: base64 of two links ---
var sub = SubscriptionParser.Parse(B64(
    "vless://b831381d-6324-4d53-ad4f-8cda48b30811@a.com:443?security=tls#A\n" +
    "trojan://pw@b.com:443#B"));
Check("subscription count", sub.Count == 2);
Check("subscription order", sub[0].Name == "A" && sub[1].Name == "B");

// --- Xray config generation ---
JsonElement Root(string json) => JsonDocument.Parse(json).RootElement;
JsonElement Proxy(JsonElement root) => root.GetProperty("outbounds")[0];

// VLESS over XHTTP (packet-up) — the tricky new transport from the real subscription
var xhttp = ShareLinkParser.Parse(
    "vless://15f55b17-6353-48ad-9c16-3941dcc4dcaa@host.example:26376" +
    "?encryption=none&security=tls&fp=firefox&host=host.example&mode=packet-up&path=%2Fapi%2Fv2%2Fuploads" +
    "&type=xhttp&extra=%7B%22mode%22%3A%22packet-up%22%2C%22xPaddingBytes%22%3A%22100-300%22%7D#XH");
var xr = Root(XrayConfigBuilder.Build(xhttp));
var xp = Proxy(xr);
Check("xray vless protocol", xp.GetProperty("protocol").GetString() == "vless");
Check("xray vnext addr", xp.GetProperty("settings").GetProperty("vnext")[0].GetProperty("address").GetString() == "host.example");
Check("xray xhttp network", xp.GetProperty("streamSettings").GetProperty("network").GetString() == "xhttp");
Check("xray xhttp security tls", xp.GetProperty("streamSettings").GetProperty("security").GetString() == "tls");
var xs = xp.GetProperty("streamSettings").GetProperty("xhttpSettings");
Check("xray xhttp path", xs.GetProperty("path").GetString() == "/api/v2/uploads");
Check("xray xhttp mode", xs.GetProperty("mode").GetString() == "packet-up");
Check("xray xhttp extra preserved", xs.GetProperty("extra").GetProperty("xPaddingBytes").GetString() == "100-300");
Check("xray has socks+http inbounds", xr.GetProperty("inbounds").GetArrayLength() == 2);

// Trojan over WS
var trs = Proxy(Root(XrayConfigBuilder.Build(trojan)));
Check("xray trojan protocol", trs.GetProperty("protocol").GetString() == "trojan");
Check("xray trojan password", trs.GetProperty("settings").GetProperty("servers")[0].GetProperty("password").GetString() == "pass123");
Check("xray trojan ws path", trs.GetProperty("streamSettings").GetProperty("wsSettings").GetProperty("path").GetString() == "/path");

// VLESS Reality → realitySettings.publicKey
var rs = Proxy(Root(XrayConfigBuilder.Build(vless)));
Check("xray reality security", rs.GetProperty("streamSettings").GetProperty("security").GetString() == "reality");
Check("xray reality pbk", rs.GetProperty("streamSettings").GetProperty("realitySettings").GetProperty("publicKey").GetString() == "PUBKEY");
Check("xray reality flow on user", rs.GetProperty("settings").GetProperty("vnext")[0].GetProperty("users")[0].GetProperty("flow").GetString() == "xtls-rprx-vision");

// Hysteria2 must be rejected by the xray builder (belongs to sing-box)
bool rejected = false;
try { XrayConfigBuilder.Build(hy2); } catch (NotSupportedException) { rejected = true; }
Check("xray rejects hysteria2", rejected);

// --- INCY routing parse + config generation ---
var rjson = """{"Name":"РФ","GlobalProxy":true,"RemoteDNSType":"DoH","RemoteDNSDomain":"https://cloudflare-dns.com/dns-query","DomesticDNSType":"DoU","DomesticDNSIP":"77.88.8.8","FakeDNS":true,"DnsHosts":{"dns.google":"8.8.8.8"},"DirectSites":["geosite:CATEGORY-RU","geosite:TLD-RU"],"DirectIp":["geoip:RU","190.115.16.11"],"BlockSites":["geosite:category-ads-all"],"ProxySites":[],"DomainStrategy":"IPIfNonMatch","RouteOrder":"block-proxy-direct"}""";
var incyLink = "incy://routing/add/" + B64(rjson);
Check("incy is routing link", MoonInternet.Core.Parsing.IncyRoutingParser.IsRoutingLink(incyLink));
Check("incy parses", MoonInternet.Core.Parsing.IncyRoutingParser.TryParse(incyLink, out var rp) && rp is not null);
Check("incy name", rp!.Name == "РФ");
Check("incy globalproxy", rp.GlobalProxy);
Check("incy direct sites", rp.DirectSites.Contains("geosite:CATEGORY-RU"));
Check("incy direct ip", rp.DirectIp.Contains("geoip:RU"));
Check("incy fakedns", rp.FakeDNS);
Check("incy route order", rp.RouteOrder == "block-proxy-direct");

// --- HAPP routing: happ:// scheme + string booleans ("true") delivered via response header ---
var happJson = """{"BlockIp":[],"BlockSites":[],"DirectIp":["geoip:RU"],"DirectSites":["geosite:CATEGORY-RU","geosite:TLD-RU"],"DnsHosts":{"dns.google":"8.8.8.8"},"DomainStrategy":"IPIfNonMatch","FakeDNS":"true","GlobalProxy":"true","LastUpdated":1781121882,"Name":"РФ","ProxyIp":[],"ProxySites":[],"RemoteDNSDomain":"https://cloudflare-dns.com/dns-query","RouteOrder":"block-proxy-direct"}""";
var happLink = "happ://routing/add/" + B64(happJson);
Check("happ is routing link", IncyRoutingParser.IsRoutingLink(happLink));
Check("happ parses (string bools)", IncyRoutingParser.TryParse(happLink, out var hp) && hp is not null);
Check("happ source", hp!.Source == MoonInternet.Core.Models.RoutingSource.Happ);
Check("happ globalproxy string->bool", hp.GlobalProxy);
Check("happ fakedns string->bool", hp.FakeDNS);
Check("happ direct sites", hp.DirectSites.Contains("geosite:TLD-RU"));
Check("happ ignores LastUpdated", hp.RouteOrder == "block-proxy-direct");

// dns-out + port-53 rule present (xray answers DNS via DoH for the tun2socks TUN engine; DoH upstream=443, no loop)
var dnscfg = Root(XrayConfigBuilder.Build(vless, hp));
Check("has dns-out outbound", dnscfg.GetProperty("outbounds").EnumerateArray().Any(o => o.TryGetProperty("tag", out var t) && t.GetString() == "dns-out"));
Check("has port-53 dns rule", dnscfg.GetProperty("routing").GetProperty("rules").EnumerateArray().Any(r => r.TryGetProperty("port", out var pp) && pp.GetString() == "53"));
Check("no dns-out without routing", !Root(XrayConfigBuilder.Build(vless)).GetProperty("outbounds").EnumerateArray().Any(o => o.TryGetProperty("tag", out var t) && t.GetString() == "dns-out"));

// --- INCY on-disk routing JSON (real bools + extra fields id/type/description) ---
var incyDiskJson = """{"id":"d132d575","Name":"РФ","description":"","type":"GLOBAL","GlobalProxy":true,"FakeDNS":true,"DirectSites":["geosite:CATEGORY-RU"],"DirectIp":["geoip:RU"],"RouteOrder":"block-proxy-direct","DomainStrategy":"IPIfNonMatch"}""";
Check("incy-disk parses (real bools + extra fields)", IncyRoutingParser.TryParseJson(incyDiskJson, MoonInternet.Core.Models.RoutingSource.Incy, out var dp) && dp is not null);
Check("incy-disk source", dp!.Source == MoonInternet.Core.Models.RoutingSource.Incy);
Check("incy-disk name/globalproxy", dp.Name == "РФ" && dp.GlobalProxy && dp.FakeDNS);

var rc = Root(XrayConfigBuilder.Build(vless, rp));
var rt = rc.GetProperty("routing");
Check("routing domainStrategy AsIs", rt.GetProperty("domainStrategy").GetString() == "AsIs");
var ruleTags = rt.GetProperty("rules").EnumerateArray()
    .Select(x => x.TryGetProperty("outboundTag", out var t) ? t.GetString() : null).ToList();
Check("routing has block rule", ruleTags.Contains("block"));
Check("routing has direct rule", ruleTags.Contains("direct"));
Check("routing has dns", rc.TryGetProperty("dns", out _));
Check("routing no fakedns (light config)", !rc.TryGetProperty("fakedns", out _));
Check("routing dns hosts", rc.GetProperty("dns").TryGetProperty("hosts", out _));

// block rule must come before direct rule (RouteOrder=block-proxy-direct)
int blockIdx = ruleTags.IndexOf("block"), directIdx = ruleTags.LastIndexOf("direct");
Check("routing block before direct", blockIdx >= 0 && blockIdx < directIdx);

// subscription with a routing link yields both servers and routing
var full = SubscriptionParser.ParseFull(B64(
    "vless://11111111-2222-3333-4444-555555555555@a.com:443?security=tls#A\n" + incyLink));
Check("parsefull servers", full.Servers.Count == 1);
Check("parsefull routing", full.Routing.Count == 1 && full.Routing[0].Source == MoonInternet.Core.Models.RoutingSource.Incy);

// --- subscription-userinfo header ---
var si = MoonInternet.Core.Models.SubscriptionInfo.Parse("upload=1073741824; download=2147483648; total=107374182400; expire=1800000000");
Check("subinfo used", si!.Used == 1073741824L + 2147483648L);
Check("subinfo total", si.Total == 107374182400L);
Check("subinfo traffic text", si.TrafficText.Contains("/"));
Check("subinfo expire", si.Expire is not null);
Check("subinfo null on empty", MoonInternet.Core.Models.SubscriptionInfo.Parse("") is null);

// --- sing-box TUN config: must SNIFF then hijack DNS (else 172.19.0.2:53 times out → no internet) ---
var tun = Root(MoonInternet.Core.Generation.SingBoxTunConfig.Build(10808, "MoonTun42"));
var tunRules = tun.GetProperty("route").GetProperty("rules").EnumerateArray().ToList();
Check("tun has dns servers", tun.GetProperty("dns").GetProperty("servers").GetArrayLength() > 0);
string? Act(JsonElement r) => r.TryGetProperty("action", out var a) ? a.GetString() : null;
int sniffIdx = tunRules.FindIndex(r => Act(r) == "sniff");
int hijackIdx = tunRules.FindIndex(r => Act(r) == "hijack-dns");
int xrayIdx = tunRules.FindIndex(r => r.TryGetProperty("process_name", out var pn) && pn.EnumerateArray().Any(x => x.GetString() == "xray.exe"));
Check("tun sniffs first", sniffIdx == 0);
// xray.exe must be excluded BEFORE the DNS hijack, or xray's own server lookup gets hijacked and deadlocks
Check("tun excludes xray before hijack", xrayIdx >= 0 && hijackIdx > xrayIdx);
Check("tun unique interface name", tun.GetProperty("inbounds")[0].GetProperty("interface_name").GetString() == "MoonTun42");
Check("tun excludes xray.exe", tunRules
    .Any(r => r.TryGetProperty("process_name", out var pn) && pn.EnumerateArray().Any(x => x.GetString() == "xray.exe")));

// per-app split routing
var tunBypass = Root(MoonInternet.Core.Generation.SingBoxTunConfig.Build(10808, "MoonTun1", null, "bypass", new[] { "chrome.exe", "discord.exe" }));
Check("app bypass → direct rule", tunBypass.GetProperty("route").GetProperty("rules").EnumerateArray()
    .Any(r => r.TryGetProperty("process_name", out var pn) && pn.EnumerateArray().Any(x => x.GetString() == "chrome.exe") && r.GetProperty("outbound").GetString() == "direct"));
Check("app bypass keeps final=proxy", tunBypass.GetProperty("route").GetProperty("final").GetString() == "proxy");
var tunOnly = Root(MoonInternet.Core.Generation.SingBoxTunConfig.Build(10808, "MoonTun1", null, "only", new[] { "chrome.exe" }));
Check("app only → final=direct", tunOnly.GetProperty("route").GetProperty("final").GetString() == "direct");
Check("app only → proxy rule", tunOnly.GetProperty("route").GetProperty("rules").EnumerateArray()
    .Any(r => r.TryGetProperty("process_name", out var pn) && pn.EnumerateArray().Any(x => x.GetString() == "chrome.exe") && r.GetProperty("outbound").GetString() == "proxy"));
Check("app off → no extra process rule", Root(MoonInternet.Core.Generation.SingBoxTunConfig.Build(10808, "MoonTun1", null, "off", new[] { "chrome.exe" }))
    .GetProperty("route").GetProperty("final").GetString() == "proxy");

// server IPs must be pinned direct so xray's connection to the VPN server can't loop back into the TUN
var tun2 = Root(MoonInternet.Core.Generation.SingBoxTunConfig.Build(10808, "MoonTun9", new[] { "31.76.114.75", "bad-ip", "1.2.3.4" }));
Check("tun pins server ips direct", tun2.GetProperty("route").GetProperty("rules").EnumerateArray()
    .Any(r => r.TryGetProperty("ip_cidr", out var c) && c.EnumerateArray().Select(x => x.GetString()).Contains("31.76.114.75/32")
              && r.GetProperty("outbound").GetString() == "direct"));
Check("tun drops invalid server ip", !MoonInternet.Core.Generation.SingBoxTunConfig.Build(10808, "x", new[] { "bad-ip" }).Contains("bad-ip"));

// --- AmneziaWG parser + UAPI generation ---
var wgIni = "[Interface]\n"
    + "Address = 10.8.1.3/32\n"
    + "DNS = 1.1.1.1, 1.0.0.1\n"
    + "PrivateKey = FM74omFKGhwJNpB9x5RElOjSg/PQBaxSst1cgXe1ER0=\n"
    + "Jc = 5\nJmin = 10\nJmax = 50\nS1 = 15\nS2 = 128\nS3 = 36\nS4 = 10\n"
    + "H1 = 1400777498-1491390525\nH2 = 1581803587-1597604001\nH3 = 1723269807-1737647865\nH4 = 1770997698-1866088827\n"
    + "I1 = <r 2><b 0x858000010001>\n\n"
    + "[Peer]\n"
    + "PublicKey = DDIa88OFzw8xF2AaWIZYI4LxjaQqVA0egJt9Jg5prCA=\n"
    + "PresharedKey = GimHNGgP7XRAtHMNnBtHlCMWzsXV+4uUzzsP/t3mOWo=\n"
    + "AllowedIPs = 0.0.0.0/0, ::/0\n"
    + "Endpoint = 192.124.181.110:41166\n"
    + "PersistentKeepalive = 25\n";
Check("wg is wireguard link", WireGuardParser.IsWireGuardLink(wgIni));
Check("wg parses", WireGuardParser.TryParse(wgIni, out var wg) && wg is not null);
Check("wg keys", wg!.PrivateKey.StartsWith("FM74") && wg.PeerPublicKey.StartsWith("DDIa") && wg.PresharedKey!.StartsWith("GimH"));
Check("wg endpoint split", wg.Endpoint == "192.124.181.110:41166" && wg.EndpointHost == "192.124.181.110" && wg.EndpointPort == 41166);
Check("wg awg params", wg.Jc == "5" && wg.S2 == "128" && wg.H1 == "1400777498-1491390525" && wg.I1!.StartsWith("<r 2>"));
Check("wg address/dns", wg.Address.Contains("10.8.1.3/32") && wg.Dns.Contains("1.1.1.1"));
Check("wg valid", wg.IsValid);
var uapi = WireGuardUapi.Build(wg);
Check("uapi hex private_key (64 hex)", System.Text.RegularExpressions.Regex.IsMatch(uapi, "private_key=[0-9a-f]{64}\n"));
Check("uapi amnezia params", uapi.Contains("\njc=5\n") && uapi.Contains("\nh1=1400777498-1491390525\n") && uapi.Contains("\ni1=<r 2>"));
Check("uapi peer + endpoint", uapi.Contains("endpoint=192.124.181.110:41166\n") && System.Text.RegularExpressions.Regex.IsMatch(uapi, "public_key=[0-9a-f]{64}\n") && uapi.Contains("allowed_ip=0.0.0.0/0\n"));
Check("uapi terminates with blank line", uapi.EndsWith("\n\n"));
Check("wg rejects garbage", !WireGuardParser.TryParse("vless://x@y:1", out _));

// --- PortFinder ---
int p1 = PortFinder.Free();
Check("portfinder in range", p1 is > 0 and <= 65535);
var (ps, ph) = PortFinder.Pair();
Check("portfinder pair distinct", ps != ph);
using (var l = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, ps)) { l.Start(); l.Stop(); }
Check("portfinder port bindable", true); // no exception above

// --- Health-watchdog signal: TcpLatencyAsync must say "dead" on a closed port, "alive" on a live one ---
int deadPort = PortFinder.Free();   // free = nothing listening → connect refused
Check("pinger dead port -> -1", Pinger.TcpLatencyAsync("127.0.0.1", deadPort, 500).GetAwaiter().GetResult() < 0);
var hl = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0); hl.Start();
int livePort = ((System.Net.IPEndPoint)hl.LocalEndpoint).Port;
Check("pinger live port -> >=0", Pinger.TcpLatencyAsync("127.0.0.1", livePort, 500).GetAwaiter().GetResult() >= 0);
hl.Stop();

// --- HAPP routing parse: camelCase keys + string bool must map onto the RoutingProfile shape ---
string happCiJson = "{\"name\":\"РФ\",\"globalProxy\":true,\"directIp\":[\"geoip:RU\"],\"directSites\":[\"geosite:CATEGORY-RU\"],\"fakeDns\":\"true\",\"geoIpUrl\":\"http://x/geoip.dat\",\"remoteDnsIp\":\"1.1.1.1\"}";
bool happOk = MoonInternet.Core.Parsing.IncyRoutingParser.TryParseJson(happCiJson, RoutingSource.Happ, out var happP) && happP is not null;
Check("happ routing parses", happOk);
Check("happ routing fields", happOk && happP!.Name == "РФ" && happP.GlobalProxy && happP.FakeDNS
    && happP.DirectIp.Contains("geoip:RU") && happP.Geoipurl == "http://x/geoip.dat" && happP.RemoteDNSIP == "1.1.1.1"
    && happP.Source == RoutingSource.Happ);

// --- AppPaths: portable data dir must resolve, exist and be writable ---
string dd = MoonInternet.Core.AppPaths.DataDir;
Check("apppaths dir exists", System.IO.Directory.Exists(dd));
try { var t = System.IO.Path.Combine(dd, ".t"); System.IO.File.WriteAllText(t, "x"); System.IO.File.Delete(t); Check("apppaths writable", true); }
catch { Check("apppaths writable", false); }

Console.WriteLine($"\n{passed} passed, {failed} failed");
return failed == 0 ? 0 : 1;
