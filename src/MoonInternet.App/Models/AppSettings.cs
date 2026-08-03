using System.IO;
using System.Text.Json;

namespace MoonInternet.App.Models;

/// <summary>A subscription's last good contents, kept on disk so the app works offline.
/// <see cref="Links"/> are the raw share links (vless://…) — re-parsed on load.</summary>
public sealed class CachedSub
{
    public string Url { get; set; } = "";
    public string Name { get; set; } = "";
    public string Announcement { get; set; } = "";
    public string TrafficText { get; set; } = "—";
    public string ExpiryText { get; set; } = "∞";
    public List<string> Links { get; set; } = new();
    public DateTimeOffset FetchedAt { get; set; }
}

/// <summary>Persisted app state (server choice, subscription, window position). Stored in %AppData%\MoonInternet.</summary>
public sealed class AppSettings
{
    public string? SubscriptionUrl { get; set; }          // legacy single-sub (migrated into SubscriptionUrls)
    public List<string> SubscriptionUrls { get; set; } = new();
    public string? LastServerName { get; set; }
    public bool TunMode { get; set; } = true;   // TUN by default
    public bool StartMinimized { get; set; }
    public bool Autostart { get; set; }
    public bool AutoReconnect { get; set; } = true;
    public bool KillSwitch { get; set; }
    public bool UseRouting { get; set; } = true;
    public string? RoutingChoice { get; set; }             // "Source:Name" of the chosen routing profile (default: INCY)
    public string ServerSort { get; set; } = "default";    // default | ping | name
    public bool AutoConnectOnStart { get; set; }           // off by default
    public string AutoConnectTarget { get; set; } = "first"; // first | last | lowest
    public string AppRouteMode { get; set; } = "off";      // off | bypass (apps go direct) | only (only apps via VPN)
    public List<string> AppRouteApps { get; set; } = new(); // process names, e.g. "chrome.exe"
    public List<string> FavoriteServers { get; set; } = new(); // favourited servers, keyed by their share link (Raw)
    // Ping settings
    public string PingMethod { get; set; } = "moon";           // moon | tcp | httpget | httphead
    public string PingDisplay { get; set; } = "num";           // num | bar | both | dots
    public string PingTestUrl { get; set; } = "https://www.gstatic.com/generate_204";
    public int PingTimeoutMs { get; set; } = 4000;             // per-probe timeout
    public bool ShowServerCount { get; set; } = true;   // badge with the number of servers on a subscription
    public bool PingStagger { get; set; }                      // space the probes out instead of all at once
    public int PingStaggerMs { get; set; } = 150;              // gap between them when staggering
    public int PingEveryMinutes { get; set; }                  // 0 = off; re-ping in the background this often
    // Subscription settings
    public bool AutoUpdateSubs { get; set; } = true;           // periodic auto-refresh (subscriptions ship an interval)
    public int AutoUpdateSubsMinutes { get; set; }             // 0 = use the subscription's own interval; else 30|60|120|360|720|1440
    public bool NotifyOnUpdate { get; set; }                   // toast after an auto-update
    public bool UpdateSubsOnStart { get; set; }                // refresh subscriptions on launch
    public bool PingOnStart { get; set; } = true;              // measure latency on launch
    public bool SendHwid { get; set; } = true;                 // send a device id header with subscription requests
    public bool ShowSubHeader { get; set; } = true;            // show the subscription announcement banner
    public bool NotifyExpiry { get; set; } = true;             // warn before the subscription expires
    public int ExpiryNotifyDays { get; set; } = 3;             // 1 | 3 | 5 | 7
    public string? Hwid { get; set; }                          // stable per-install device id (generated once)
    public string? Language { get; set; }                      // "ru" | "en"; null = follow the system
    // Notifications
    public bool NotificationsEnabled { get; set; } = true;     // master switch for everything below
    public bool TrayBalloons { get; set; } = true;             // pop up over the tray, or stay silent
    public bool NotifyConnection { get; set; }                 // connected/disconnected — off, it fires on every toggle
    public bool NotifyAppUpdate { get; set; } = true;          // a newer release is on GitHub
    // Tunnel tuning
    public bool TlsFragment { get; set; }                      // split the TLS ClientHello
    public bool Mux { get; set; }                              // connection multiplexing
    public string TrafficPriority { get; set; } = "off";       // off | balance | games — BETA, off by default
    public bool Sniffing { get; set; } = true;                 // protocol/domain sniffing
    public string PreferredIp { get; set; } = "auto";          // auto | ipv4 | ipv6
    public string VpnDns { get; set; } = "google";             // cf_google | google | cloudflare | quad9 | custom
    public string VpnDnsCustom { get; set; } = "";             // custom resolver IPs (comma-separated)
    public MoonInternet.Core.Models.RoutingProfile? CustomRouting { get; set; }   // user-built Direct/Proxy/Block profile
    /// <summary>Last successful fetch of every subscription, so servers stay visible with no internet.</summary>
    public List<CachedSub> CachedSubs { get; set; } = new();
    // Connection settings
    public bool AllowLan { get; set; }
    public bool LanThroughProxy { get; set; }
    public bool ShowProxyOnlyButton { get; set; }
    public string ProxyBypassHosts { get; set; } = "";
    public bool Socks5Auth { get; set; }
    public string? ProxyUser { get; set; }
    public string? ProxyPass { get; set; }
    public bool BlockUdp { get; set; }
    public bool HttpProxyAuth { get; set; }
    // Logs
    public bool LogsEnabled { get; set; } = true;
    public string LogLevel { get; set; } = "warn";     // error | warn | info | debug
    public int LogKeepDays { get; set; } = 7;          // 1 | 3 | 7 | 30 | 0 = forever
    public int LogMaxMb { get; set; } = 20;            // trim the file above this size

    private static string Dir => MoonInternet.Core.AppPaths.DataDir;   // portable: next to the exe
    private static string FilePath => Path.Combine(Dir, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings();
        }
        catch { /* corrupt settings -> defaults */ }
        return new AppSettings();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { /* non-fatal */ }
    }
}
