using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.Win32;
using MoonInternet.App.Models;
using MoonInternet.Core.Models;
using MoonInternet.Core.Parsing;
using MoonInternet.Services;

namespace MoonInternet.App.ViewModels;

public enum AppPage { Home, Servers, Settings, Add, Language, Connection, Tunnel, Routing, Stats }

public partial class ServerItem : ObservableObject
{
    public OutboundProfile Profile { get; }
    public string Flag { get; }
    public string Label { get; }
    public ImageSource? FlagImage { get; }   // real flag picture (Windows renders flag emoji as "FI" text, useless)
    public string? SubscriptionName { get; set; }

    public ServerItem(OutboundProfile p)
    {
        Profile = p;
        (Flag, Label, var code) = SplitFlag(p.Name);
        FlagImage = LoadFlag(code);
    }

    private static ImageSource? LoadFlag(string? code)
    {
        if (code is null || Application.Current is null) return null;
        try
        {
            var uri = new Uri($"pack://application:,,,/Assets/flags/{code}.png");
            if (Application.GetResourceStream(uri) is null) return null; // country not in the bundled set
            var bmp = new BitmapImage();
            bmp.BeginInit(); bmp.CacheOption = BitmapCacheOption.OnLoad; bmp.UriSource = uri; bmp.EndInit();
            bmp.Freeze();
            return bmp;
        }
        catch { return null; }
    }

    public string Protocol => Profile.Protocol switch
    {
        ProtocolType.Vless => "VLESS", ProtocolType.Vmess => "VMess", ProtocolType.Trojan => "Trojan",
        ProtocolType.Shadowsocks => "SS", ProtocolType.Hysteria2 => "Hysteria2", ProtocolType.Socks => "SOCKS",
        // AmneziaWG = WireGuard + obfuscation params (junk packets Jc/Jmin/Jmax, magic headers S1/H1…). No params = plain WG.
        ProtocolType.Wireguard => Profile.Wireguard is { } w
            && (!string.IsNullOrEmpty(w.Jc) || !string.IsNullOrEmpty(w.S1) || !string.IsNullOrEmpty(w.H1)) ? "AmneziaWG" : "WireGuard",
        _ => Profile.Protocol.ToString()
    };
    public string Network => Profile.Network.ToUpperInvariant();
    public string Security => Profile.Security.ToUpperInvariant();
    public bool HasSecurity => Profile.Security is not ("none" or "");
    // Pretty JSON of this server's outbound — shown in the per-server "⋯" config dialog.
    public string ConfigJson => MoonInternet.Core.Generation.XrayConfigBuilder.OutboundJson(Profile);

    [ObservableProperty] private int ping = -2; // -2 unknown, -1 timeout, >=0 ms

    /// <summary>True while this row is being measured — the spinner in place of the number.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ShowsPing))]
    private bool pinging;

    [ObservableProperty] private bool isSelected;  // highlights the row on Home's picker list
    [ObservableProperty] private bool isFavorite;  // starred by the user (persisted, keyed by ShareUrl)
    public string? ShareUrl => Profile.Raw;        // original share link (Copy URL / QR)
    public int Order { get; set; }                 // position in the subscription (for "Обычная" order)

    /// <summary>The reading is only worth showing when it is not mid-measurement.</summary>
    public bool ShowsPing => !Pinging;

    public string PingText => Ping switch { -2 => "—", -1 => "✕", _ => $"{Ping} ms" };
    public int PingSignal => Ping switch { < 0 => 0, < 60 => 4, < 120 => 3, < 220 => 2, _ => 1 };  // 0..4 for dots/bar
    public string FavoriteText => IsFavorite ? Localization.Loc.T("S_VM_001") : Localization.Loc.T("S_VM_002");
    partial void OnIsFavoriteChanged(bool value) => OnPropertyChanged(nameof(FavoriteText));
    /// <summary>
    /// Five shared, frozen brushes instead of a new one per read.
    ///
    /// This property is read about fourteen times per row — the four ping display styles all
    /// live in the template at once and only differ by visibility — so a fresh unfrozen
    /// SolidColorBrush each time meant roughly four hundred allocations per ping pass across
    /// thirty servers, none of them cacheable by the renderer. That was the stutter while pinging.
    /// Frozen brushes are shareable and thread-safe, so one each is enough for the whole app.
    /// </summary>
    private static readonly Brush PingUnknownBrush = Frozen(0x56, 0x5B, 0x70);
    private static readonly Brush PingDeadBrush = Frozen(0xFF, 0x6B, 0x8A);
    private static readonly Brush PingGoodBrush = Frozen(0x34, 0xD3, 0x99);
    private static readonly Brush PingOkBrush = Frozen(0xF5, 0xC0, 0x42);
    private static readonly Brush PingSlowBrush = Frozen(0xFF, 0x8A, 0x5B);

    internal static Brush Frozen(byte r, byte g, byte b)
    {
        var brush = new SolidColorBrush(Color.FromRgb(r, g, b));
        brush.Freeze();
        return brush;
    }

    public Brush PingBrush => Ping switch
    {
        -2 => PingUnknownBrush,
        -1 => PingDeadBrush,
        < 100 => PingGoodBrush,
        < 250 => PingOkBrush,
        _ => PingSlowBrush,
    };
    partial void OnPingChanged(int value) { OnPropertyChanged(nameof(PingText)); OnPropertyChanged(nameof(PingBrush)); OnPropertyChanged(nameof(PingSignal)); }

    // Returns the leading flag emoji, the name without it, and the ISO-3166 alpha-2 code (for the flag picture).
    private static (string flag, string label, string? code) SplitFlag(string name)
    {
        if (name.Length >= 4 && char.IsHighSurrogate(name[0]) && char.IsHighSurrogate(name[2]))
        {
            int cp1 = char.ConvertToUtf32(name[0], name[1]);
            int cp2 = char.ConvertToUtf32(name[2], name[3]);
            if (cp1 is >= 0x1F1E6 and <= 0x1F1FF && cp2 is >= 0x1F1E6 and <= 0x1F1FF)
            {
                string code = $"{(char)('a' + cp1 - 0x1F1E6)}{(char)('a' + cp2 - 0x1F1E6)}";
                return (name[..4], name[4..].Trim(), code);
            }
        }
        return ("\U0001F310", name, null); // globe, unknown country
    }
}

public partial class MainViewModel : ObservableObject
{
    private readonly ConnectionManager _conn;
    private readonly AppSettings _settings;
    private readonly DispatcherTimer _timer;
    private readonly List<RoutingProfile> _installedRoutings; // INCY's on-disk profiles → always available as "INCY"
    private DateTime _connectedAt;

    public ObservableCollection<SubscriptionVM> Subscriptions { get; } = new();
    public int TotalServers => Subscriptions.Sum(s => s.Count);
    public bool HasServers => TotalServers > 0;
    public bool HasNoServers => TotalServers == 0;
    public IEnumerable<ServerItem> AllServers => Subscriptions.SelectMany(s => s.Servers);
    /// <summary>Servers for the tray menu, honouring the chosen sort (favourites always first, like the list).</summary>
    public IEnumerable<ServerItem> TrayServers
    {
        get
        {
            var all = AllServers;
            return ServerSort switch
            {
                "ping" => all.OrderByDescending(s => s.IsFavorite).ThenBy(s => s.Ping < 0 ? int.MaxValue : s.Ping).ThenBy(s => s.Label),
                "name" => all.OrderByDescending(s => s.IsFavorite).ThenBy(s => s.Label, StringComparer.OrdinalIgnoreCase),
                "favorite" => all.Where(s => s.IsFavorite || !AllServers.Any(x => x.IsFavorite))
                                 .OrderByDescending(s => s.IsFavorite).ThenBy(s => s.Label, StringComparer.OrdinalIgnoreCase),
                _ => all.OrderByDescending(s => s.IsFavorite).ThenBy(s => s.Order),
            };
        }
    }
    // Distinct protocols across all subscriptions — shown as chips on the Home page.
    public IEnumerable<string> Protocols => AllServers.Select(s => s.Protocol).Distinct().OrderBy(x => x);
    /// <summary>"Все" + the protocols actually present, as ONE list so chips wrap item-by-item
    /// (a nested ItemsControl inside a WrapPanel wraps as a single block — that's why everything jumped to line 2).</summary>
    public IEnumerable<string> FilterChips => new[] { Localization.Loc.T("S_VM_030") }.Concat(Protocols);
    public bool IsFilterAll => Filter == "Все";

    private void NotifyServerListChanged()
    {
        OnPropertyChanged(nameof(TotalServers)); OnPropertyChanged(nameof(TotalServersText));
        OnPropertyChanged(nameof(HasServers));
        OnPropertyChanged(nameof(HasNoServers));
        OnPropertyChanged(nameof(AllServers));
        OnPropertyChanged(nameof(TrayServers));
        OnPropertyChanged(nameof(Protocols));
        OnPropertyChanged(nameof(FilterChips));
    }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsSettingsSection))]
    private AppPage currentPage = AppPage.Home;
    public bool IsSettingsSection => CurrentPage is AppPage.Settings or AppPage.Routing
                                     or AppPage.Connection or AppPage.Tunnel or AppPage.Stats or AppPage.Language;

    [ObservableProperty] private bool editLayout;

    // Movable/resizable Home blocks (positions persisted to layout.json)
    public BlockLayout ModeBlock { get; }
    public BlockLayout StatsBlock { get; }
    public BlockLayout RingBlock { get; }
    public BlockLayout SubBlock { get; }
    private static readonly Dictionary<string, double[]> LayoutDefaults = new()
    {
        ["mode"] = new[] { 345.0, 58, 280, 48 },
        ["stats"] = new[] { 40.0, 128, 880, 40 },
        ["ring"] = new[] { 330.0, 184, 300, 330 },
        ["sub"] = new[] { 30.0, 544, 900, 176 },
    };
    [ObservableProperty] private string subscriptionUrl = "";
    [ObservableProperty] private string subscriptionName = "";   // set in the constructor, see ApplyLanguageTexts
    [ObservableProperty] private bool useRouting = true;
    [ObservableProperty] private string subscriptionTraffic = "—";
    [ObservableProperty] private string subscriptionExpiry = "∞";
    [ObservableProperty] private string geoStatus = "";          // ditto

    public ObservableCollection<RoutingProfile> AvailableRoutings { get; } = new();

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(RoutingName), nameof(RoutingSubtitle), nameof(DirectSites), nameof(ProxySites), nameof(BlockSites),
        nameof(DirectIps), nameof(ProxyIps), nameof(BlockIps), nameof(HasMultipleRoutings),
        nameof(IsRoutingIncy), nameof(IsRoutingHapp), nameof(IsRoutingCustom), nameof(GeoSources))]
    private RoutingProfile? selectedRouting;
    partial void OnSelectedRoutingChanged(RoutingProfile? value) => RebuildRuleChips();

    public string RoutingName => SelectedRouting?.Name ?? Localization.Loc.T("S_None");
    /// <summary>StringFormat cannot take a DynamicResource, so the whole line is built here.</summary>
    public string TotalServersText => string.Format(Localization.Loc.T("S_VM_120"), TotalServers);
    public string RoutingSubtitle => string.Format(Localization.Loc.T("S_Routing_Sub_Fmt"), RoutingName);
    public bool HasMultipleRoutings => AvailableRoutings.Count > 1;
    public bool HasRoutings => AvailableRoutings.Count > 0;

    // Two-button routing source toggle (INCY | HAPP), default INCY — like the TUN/Proxy switch. Picks the first
    // available profile of that source. Shown only when both an INCY and a HAPP routing exist.
    private bool _syncingRoutingToggle;
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(RoutingIsIncy), nameof(RoutingIsHapp))]
    private bool routingUseHapp;
    public bool RoutingIsIncy => !RoutingUseHapp;
    public bool RoutingIsHapp => RoutingUseHapp;
    public bool HasHappRouting => AvailableRoutings.Any(r => r.Source == RoutingSource.Happ);
    public bool HasIncyRouting => AvailableRoutings.Any(r => r.Source == RoutingSource.Incy);
    public bool ShowRoutingToggle => HasHappRouting && HasIncyRouting;

    partial void OnRoutingUseHappChanged(bool value)
    {
        if (_syncingRoutingToggle) return;
        var src = value ? RoutingSource.Happ : RoutingSource.Incy;
        var p = AvailableRoutings.FirstOrDefault(r => r.Source == src);
        if (p is null) return;
        SelectedRouting = p;
        _settings.RoutingChoice = $"{p.Source}:{p.Name}";
        _settings.Save();
        ReconnectIfConnected();
    }
    public IEnumerable<string> DirectSites => SelectedRouting?.DirectSites ?? Enumerable.Empty<string>();
    public IEnumerable<string> ProxySites => SelectedRouting?.ProxySites ?? Enumerable.Empty<string>();
    public IEnumerable<string> BlockSites => SelectedRouting?.BlockSites ?? Enumerable.Empty<string>();
    public IEnumerable<string> DirectIps => SelectedRouting?.DirectIp ?? Enumerable.Empty<string>();
    public IEnumerable<string> ProxyIps => SelectedRouting?.ProxyIp ?? Enumerable.Empty<string>();
    public IEnumerable<string> BlockIps => SelectedRouting?.BlockIp ?? Enumerable.Empty<string>();

    // ===== Custom routing profile + rule editing + geo-tag browser =====
    private RoutingProfile? _customRouting;
    public RoutingProfile CustomRouting => _customRouting ??= _settings.CustomRouting ??= new RoutingProfile { Name = "Свой", Source = RoutingSource.Custom };
    public bool IsRoutingIncy => SelectedRouting?.Source == RoutingSource.Incy;
    public bool IsRoutingHapp => SelectedRouting?.Source == RoutingSource.Happ;
    public bool IsRoutingCustom => SelectedRouting?.Source == RoutingSource.Custom;

    [RelayCommand]
    private void SelectRoutingSource(string src)
    {
        RoutingProfile? target = src switch
        {
            "happ" => AvailableRoutings.FirstOrDefault(r => r.Source == RoutingSource.Happ),
            "custom" => CustomRouting,
            _ => AvailableRoutings.FirstOrDefault(r => r.Source == RoutingSource.Incy),
        };
        if (target is null) return;
        SelectedRouting = target;
        _settings.RoutingChoice = $"{target.Source}:{target.Name}"; _settings.Save();
        ReconnectIfConnected();
    }

    // per-bucket chips (shown for any profile; editable only when the custom profile is selected)
    public ObservableCollection<RuleChip> DirectRules { get; } = new();
    public ObservableCollection<RuleChip> ProxyRules { get; } = new();
    public ObservableCollection<RuleChip> BlockRules { get; } = new();
    // counters + search, so a 200-rule profile stays readable
    public string DirectCount => $"{DirectRules.Count}";
    public string ProxyCount => $"{ProxyRules.Count}";
    public string BlockCount => $"{BlockRules.Count}";
    [ObservableProperty] private string ruleSearch = "";
    partial void OnRuleSearchChanged(string value) => RebuildRuleChips();
    [ObservableProperty] private bool directOpen = true;
    [ObservableProperty] private bool proxyOpen = true;
    [ObservableProperty] private bool blockOpen = true;
    [RelayCommand] private void ToggleBucket(string b)
    {
        if (b == "proxy") ProxyOpen = !ProxyOpen;
        else if (b == "block") BlockOpen = !BlockOpen;
        else DirectOpen = !DirectOpen;
    }
    private void RebuildRuleChips()
    {
        string q = RuleSearch?.Trim() ?? "";
        void Fill(ObservableCollection<RuleChip> c, string bucket, List<string>? sites, List<string>? ips)
        {
            c.Clear();
            foreach (var s in (sites ?? new()).Concat(ips ?? new()))
                if (q.Length == 0 || s.Contains(q, StringComparison.OrdinalIgnoreCase)) c.Add(new RuleChip(bucket, s));
        }
        var r = SelectedRouting;
        Fill(DirectRules, "direct", r?.DirectSites, r?.DirectIp);
        Fill(ProxyRules, "proxy", r?.ProxySites, r?.ProxyIp);
        Fill(BlockRules, "block", r?.BlockSites, r?.BlockIp);
        OnPropertyChanged(nameof(DirectCount)); OnPropertyChanged(nameof(ProxyCount)); OnPropertyChanged(nameof(BlockCount));
    }

    private static bool IsIpLike(string v) => v.StartsWith("geoip:", StringComparison.OrdinalIgnoreCase)
        || System.Net.IPAddress.TryParse(v.Split('/')[0], out _);

    private void AddToBucket(string bucket, string raw)
    {
        var v = raw.Trim();
        if (v.Length == 0 || SelectedRouting is not { Source: RoutingSource.Custom } r) return;
        var (sites, ips) = bucket switch
        {
            "proxy" => (r.ProxySites, r.ProxyIp),
            "block" => (r.BlockSites, r.BlockIp),
            _ => (r.DirectSites, r.DirectIp),
        };
        var list = IsIpLike(v) ? ips : sites;
        if (!list.Any(x => x.Equals(v, StringComparison.OrdinalIgnoreCase))) list.Add(v);
        _settings.CustomRouting = r; _settings.Save();
        RebuildRuleChips(); ReconnectIfConnected();
    }
    [RelayCommand]
    private void RemoveRule(RuleChip? chip)
    {
        if (chip is null || SelectedRouting is not { Source: RoutingSource.Custom } r) return;
        var lists = chip.Bucket switch { "proxy" => new[] { r.ProxySites, r.ProxyIp }, "block" => new[] { r.BlockSites, r.BlockIp }, _ => new[] { r.DirectSites, r.DirectIp } };
        foreach (var l in lists) l.RemoveAll(x => x.Equals(chip.Value, StringComparison.OrdinalIgnoreCase));
        _settings.CustomRouting = r; _settings.Save();
        RebuildRuleChips(); ReconnectIfConnected();
    }

    // add-rule mini dialog
    [ObservableProperty] private bool showRuleDialog;
    [ObservableProperty][NotifyPropertyChangedFor(nameof(RuleBucketTitle))] private string ruleBucket = "direct";
    [ObservableProperty] private string ruleInput = "";
    public string RuleBucketTitle => RuleBucket switch { "proxy" => Localization.Loc.T("S_VM_040"), "block" => Localization.Loc.T("S_VM_041"), _ => Localization.Loc.T("S_VM_042") };
    [RelayCommand] private void OpenAddRule(string bucket) { RuleBucket = bucket; RuleInput = ""; ShowRuleDialog = true; }
    [RelayCommand] private void CloseRuleDialog() => ShowRuleDialog = false;
    [RelayCommand] private void ConfirmAddRule() { if (!string.IsNullOrWhiteSpace(RuleInput)) AddToBucket(RuleBucket, RuleInput); ShowRuleDialog = false; }

    // geo-tag browser (parsed from geosite.dat / geoip.dat)
    [ObservableProperty] private bool showGeoBrowser;
    [ObservableProperty] private string geoQuery = "";
    public ObservableCollection<string> GeoTags { get; } = new();
    private List<string> _allGeoTags = new();
    private bool _geoLoaded;
    [RelayCommand] private void OpenGeoBrowser() { LoadGeoTags(); GeoQuery = ""; ShowGeoBrowser = true; }
    [RelayCommand] private void CloseGeoBrowser() => ShowGeoBrowser = false;
    [RelayCommand] private void PickGeoTag(string? tag) { if (!string.IsNullOrEmpty(tag)) AddToBucket(RuleBucket, tag!); ShowGeoBrowser = false; ShowRuleDialog = false; }
    partial void OnGeoQueryChanged(string value) => FilterGeoTags();
    private void LoadGeoTags()
    {
        if (_geoLoaded) return;
        _geoLoaded = true;
        Task.Run(() =>
        {
            var tags = MoonInternet.Core.Generation.GeoDat.Tags(MoonInternet.Services.GeoService.GeositeFile, "geosite");
            tags.AddRange(MoonInternet.Core.Generation.GeoDat.Tags(MoonInternet.Services.GeoService.GeoipFile, "geoip"));
            Application.Current?.Dispatcher.Invoke(() => { _allGeoTags = tags; FilterGeoTags(); });
        });
    }
    private void FilterGeoTags()
    {
        GeoTags.Clear();
        var q = GeoQuery?.Trim() ?? "";
        foreach (var t in _allGeoTags.Where(t => q.Length == 0 || t.Contains(q, StringComparison.OrdinalIgnoreCase)).Take(400))
            GeoTags.Add(t);
    }
    [ObservableProperty] private ServerItem? selectedServer;
    [ObservableProperty] private string status = "";             // ditto
    [ObservableProperty] private bool tunMode;
    [ObservableProperty] private bool launchMinimized;
    [ObservableProperty] private bool autostart;
    [ObservableProperty] private bool autoReconnect = true;
    [ObservableProperty] private bool killSwitch;

    partial void OnAutoReconnectChanged(bool value) { _conn.AutoReconnect = value; _settings.AutoReconnect = value; _settings.Save(); }
    partial void OnKillSwitchChanged(bool value) { _conn.KillSwitch = value; _settings.KillSwitch = value; _settings.Save(); }
    partial void OnUseRoutingChanged(bool value) { _settings.UseRouting = value; _settings.Save(); }
    [ObservableProperty] private string elapsed = "00:00";
    public string ElapsedDisplay => IsConnected ? Elapsed : "—";   // time shows a dash until connected
    partial void OnElapsedChanged(string value) => OnPropertyChanged(nameof(ElapsedDisplay));

    // ===== Live session traffic (TUN mode: read the MoonTun adapter's byte counters each tick) =====
    [ObservableProperty] private string uploadSpeed = "—";     // Отдача
    [ObservableProperty] private string downloadSpeed = "—";   // Приём
    [ObservableProperty] private string sessionTraffic = "—";  // Трафик за сессию (↑+↓)
    private long _baseRecv, _baseSent, _lastRecv, _lastSent;

    private bool _sampling;
    private void ResetTraffic()
    {
        // Count via xray's own INBOUND stats = exact user payload (like INCY); Hysteria2 TUN → sing-box Clash API.
        // The TUN/gvisor adapter's Windows counters over-report badly under load, so we don't use them for the numbers.
        var t = _conn.XrayTraffic() ?? _conn.SingBoxTraffic();
        _baseRecv = _lastRecv = t?.recv ?? 0;
        _baseSent = _lastSent = t?.sent ?? 0;
        _lastSampleAt = DateTime.UtcNow;
        UploadSpeed = DownloadSpeed = SessionTraffic = "—";
    }
    private void SampleTraffic()
    {
        if (_sampling) return;                     // xray query is a subprocess → keep off the UI thread, one at a time
        _sampling = true;
        Task.Run(() =>
        {
            var b = _conn.XrayTraffic() ?? _conn.SingBoxTraffic();
            Application.Current?.Dispatcher.Invoke(() =>
            {
                if (b is { } v) ApplyBytes(v.recv, v.sent);   // null (WG, or a transient miss) → keep the last shown value
                _sampling = false;
            });
        });
    }
    private DateTime _lastSampleAt = DateTime.UtcNow;
    private void ApplyBytes(long recv, long sent)
    {
        var now = DateTime.UtcNow;
        double secs = (now - _lastSampleAt).TotalSeconds;   // real interval — the xray stats subprocess isn't exactly 1s
        _lastSampleAt = now;
        long dr = Math.Max(0, recv - _lastRecv), ds = Math.Max(0, sent - _lastSent);
        _lastRecv = recv; _lastSent = sent;
        if (secs is >= 0.25 and <= 20)   // ignore the first/garbage interval
        {
            long down = (long)(dr / secs);
            DownloadSpeed = FmtSpeed(down);
            UploadSpeed = FmtSpeed((long)(ds / secs));

            // Feed the learner: it decides what this link really carries by pairing each speed
            // sample with the latency at that moment. CheckPing is whatever the last connection
            // check measured; below zero means "no reading", which it handles.
            _netQuality.Observe(down, _lastCheckPingMs);
            OnPropertyChanged(nameof(LearnedCapacity));
            OnPropertyChanged(nameof(LearnedBaseline));
        }
        SessionTraffic = FmtSize(Math.Max(0, recv - _baseRecv) + Math.Max(0, sent - _baseSent));
    }
    // Read ONLY our own TUN adapter ("MoonTun…"). Matching any wintun/wireguard would grab another client's
    // adapter (INCY/HAPP) and report its traffic. Null → not our TUN (proxy mode) → caller falls back to xray stats.
    private static (long recv, long sent)? TunBytes()
    {
        try
        {
            foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                if (!ni.Name.StartsWith("MoonTun", StringComparison.OrdinalIgnoreCase)) continue;
                var s = ni.GetIPv4Statistics();
                return (s.BytesReceived, s.BytesSent);
            }
        }
        catch { }
        return null;
    }
    // Speed in BITS like a speedtest (Mbps). Decimal units (÷1000), so 6.5 MB/s → 52.0 Мбит/с.
    private static string FmtSpeed(long bytesPerSec)
    {
        double bits = bytesPerSec * 8.0;
        return bits < 1000 ? string.Format(Localization.Loc.T("S_VM_050"), bits)
            : bits < 1_000_000 ? string.Format(Localization.Loc.T("S_VM_051"), bits / 1000.0)
            : bits < 1_000_000_000 ? string.Format(Localization.Loc.T("S_VM_052"), bits / 1_000_000.0) : string.Format(Localization.Loc.T("S_VM_053"), bits / 1_000_000_000.0);
    }
    private static string FmtSize(long b) => b < 1024 ? string.Format(Localization.Loc.T("S_VM_054"), b)
        : b < 1024 * 1024 ? string.Format(Localization.Loc.T("S_VM_055"), b / 1024.0)
        : b < 1024L * 1024 * 1024 ? string.Format(Localization.Loc.T("S_VM_056"), b / 1048576.0)
        : b < 1024L * 1024 * 1024 * 1024 ? string.Format(Localization.Loc.T("S_VM_057"), b / 1073741824.0) : string.Format(Localization.Loc.T("S_VM_058"), b / 1099511627776.0);

    // ===== server sorting (default / ping / name) =====
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsSortDefault), nameof(IsSortPing), nameof(IsSortName), nameof(IsSortFavorite))]
    private string serverSort = "default";
    public bool IsSortDefault => ServerSort == "default";
    public bool IsSortPing => ServerSort == "ping";
    public bool IsSortName => ServerSort == "name";
    public bool IsSortFavorite => ServerSort == "favorite";
    private void ApplySortAll() { foreach (var s in Subscriptions) s.ApplySort(ServerSort); }

    [RelayCommand]
    private void SetSort(string mode)
    {
        if (ServerSort == mode) return;
        ServerSort = mode; _settings.ServerSort = mode; _settings.Save();
        ApplySortAll();
        RefreshFilters();   // re-run the filter so "★ Избранное" hides/shows non-favourites
        OnPropertyChanged(nameof(TrayServers));
    }

    // ===== auto-connect on startup (off by default) =====
    [ObservableProperty] private bool autoConnectOnStart;
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsAutoFirst), nameof(IsAutoLast), nameof(IsAutoLowest), nameof(IsAutoFav))]
    private string autoConnectTarget = "first";
    public bool IsAutoFirst => AutoConnectTarget == "first";
    public bool IsAutoLast => AutoConnectTarget == "last";
    public bool IsAutoLowest => AutoConnectTarget == "lowest";
    // "favorite*" = pick among favourites; the sub-mode says WHICH favourite (first / last used / lowest ping)
    public bool IsAutoFav => AutoConnectTarget.StartsWith("favorite");
    public bool IsAutoFavFirst => AutoConnectTarget is "favorite" or "favorite-first";
    public bool IsAutoFavLast => AutoConnectTarget == "favorite-last";
    public bool IsAutoFavLowest => AutoConnectTarget == "favorite-lowest";
    private bool _autoConnectTried;

    partial void OnAutoConnectOnStartChanged(bool value) { _settings.AutoConnectOnStart = value; _settings.Save(); }
    partial void OnAutoConnectTargetChanged(string value)
    { OnPropertyChanged(nameof(IsAutoFavFirst)); OnPropertyChanged(nameof(IsAutoFavLast)); OnPropertyChanged(nameof(IsAutoFavLowest)); }
    [RelayCommand]
    private void SetAutoTarget(string mode) { AutoConnectTarget = mode; _settings.AutoConnectTarget = mode; _settings.Save(); }

    /// <summary>True once at least one server has actually been measured this session.</summary>
    private bool AnyMeasured => AllServers.Any(s => s.Ping != -2);

    /// <summary>
    /// Everything was measured and nothing answered. Drives the red tray icon: an idle icon in
    /// that situation looks like "not connected yet" and the user waits for a tunnel that is
    /// never coming.
    /// </summary>
    public bool AllServersDown =>
        !IsConnected && AllServers.Any() && AnyMeasured && !AllServers.Any(Reachable);

    /// <summary>Re-evaluated after every ping pass; the tray listens for this.</summary>
    private void RefreshReachability() => OnPropertyChanged(nameof(AllServersDown));

    /// <summary>Reachable = it answered. Unknown (-2) does not count as reachable, nor as dead.</summary>
    private static bool Reachable(ServerItem s) => s.Ping >= 0;

    /// <summary>Whatever the user's setting points at, dead or alive.</summary>
    private ServerItem? PreferredAutoServer()
    {
        // Favourites first when the user asked for them; fall back to all servers if nothing is starred.
        if (AutoConnectTarget.StartsWith("favorite"))
        {
            var favs = AllServers.Where(s => s.IsFavorite).ToList();
            if (favs.Count > 0)
                return AutoConnectTarget switch
                {
                    "favorite-last" => favs.FirstOrDefault(s => s.Label == _settings.LastServerName) ?? favs[0],
                    "favorite-lowest" => favs.Where(Reachable).OrderBy(s => s.Ping).FirstOrDefault() ?? favs[0],
                    _ => favs[0],
                };
        }
        return AutoConnectTarget switch
        {
            "last" => AllServers.FirstOrDefault(s => s.Label == _settings.LastServerName) ?? AllServers.FirstOrDefault(),
            "lowest" => AllServers.Where(Reachable).OrderBy(s => s.Ping).FirstOrDefault() ?? AllServers.FirstOrDefault(),
            _ => AllServers.FirstOrDefault(),
        };
    }

    /// <summary>
    /// What auto-connect should actually dial.
    ///
    /// The preference wins whenever it answers. When it does not, we fall over to the fastest
    /// server that did — without touching the stored preference, so the next launch tries the
    /// preferred one again and only falls over if it is still down. That is the behaviour asked
    /// for: the fallback is for this session, not a new default.
    ///
    /// Null means "connect to nothing": every server was measured and none answered. Dialling a
    /// server we already know is dead only produces a spinner and a failure.
    /// </summary>
    private ServerItem? PickAutoServer()
    {
        var preferred = PreferredAutoServer();
        if (preferred is null) return null;

        // Nothing measured yet — we have no grounds to overrule the preference.
        if (!AnyMeasured) return preferred;
        if (Reachable(preferred)) return preferred;
        // The user can ask us to respect the preference even when it looks dead — some servers
        // simply do not answer a probe while carrying traffic perfectly well.
        if (!AutoFailover) return preferred;

        var pool = AutoConnectTarget.StartsWith("favorite")
            ? AllServers.Where(s => s.IsFavorite).ToList()
            : AllServers.ToList();
        if (pool.Count == 0) pool = AllServers.ToList();

        var alive = pool.Where(Reachable).OrderBy(s => s.Ping).FirstOrDefault()
                    ?? AllServers.Where(Reachable).OrderBy(s => s.Ping).FirstOrDefault();
        return alive;   // null when nothing answered at all
    }

    // ===== per-app routing (split tunnel, TUN mode) =====
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsAppRouteOff), nameof(IsAppRouteBypass), nameof(IsAppRouteOnly))]
    private string appRouteMode = "off";
    public bool IsAppRouteOff => AppRouteMode == "off";
    public bool IsAppRouteBypass => AppRouteMode == "bypass";
    public bool IsAppRouteOnly => AppRouteMode == "only";
    public ObservableCollection<AppRouteItem> AppRouteApps { get; } = new();

    partial void OnAppRouteModeChanged(string value)
    {
        _settings.AppRouteMode = value; _settings.Save();
        _conn.AppRouteMode = value;
        ReconnectIfConnected();
    }

    [RelayCommand] private void SetAppRouteMode(string mode) => AppRouteMode = mode;

    [RelayCommand]
    private void AddAppRoute()
    {
        // Accept .exe AND .lnk. DereferenceLinks=false so the shell doesn't auto-resolve (it throws "Разрушительный
        // сбой" on some broken/store shortcuts) — we resolve the target ourselves, guarded.
        var dlg = new OpenFileDialog
        {
            Filter = "Программы и ярлыки|*.exe;*.lnk;*.url|Все файлы|*.*",
            Title = Localization.Loc.T("S_VM_238"), DereferenceLinks = false,
        };
        if (dlg.ShowDialog() != true) return;
        // Always resolve to a real .exe — never store the shortcut file itself (.lnk / .url / Steam link).
        var exe = ShortcutResolver.ToExe(dlg.FileName);
        if (exe is null) { Status = Localization.Loc.T("S_VM_237"); return; }
        if (AppRouteApps.Any(a => a.Name.Equals(Path.GetFileName(exe), StringComparison.OrdinalIgnoreCase))) return;
        AppRouteApps.Add(new AppRouteItem(exe));
        PersistAppRouteApps();
        ReconnectIfConnected();
    }

    [RelayCommand]
    private void RemoveAppRoute(AppRouteItem app)
    {
        AppRouteApps.Remove(app);
        PersistAppRouteApps();
        ReconnectIfConnected();
    }

    private void PersistAppRouteApps()
    {
        _settings.AppRouteApps = AppRouteApps.Select(a => a.Path).ToList();   // full paths (for icons next launch)
        _settings.Save();
        _conn.AppRouteApps = AppRouteApps.Select(a => a.Name).ToList();       // process names (what the helper matches)
    }

    // ===== Tunnel tuning: fragment / mux / sniffing / preferred IP / VPN DNS =====
    [ObservableProperty] private bool tlsFragment;
    partial void OnTlsFragmentChanged(bool value) { _settings.TlsFragment = value; _settings.Save(); ApplyTuning(); ReconnectIfConnected(); }
    [ObservableProperty] private bool mux;
    partial void OnMuxChanged(bool value) { _settings.Mux = value; _settings.Save(); ApplyTuning(); ReconnectIfConnected(); }
    [ObservableProperty] private bool sniffing = true;
    partial void OnSniffingChanged(bool value) { _settings.Sniffing = value; _settings.Save(); ApplyTuning(); ReconnectIfConnected(); }

    // ---- Приоритет трафика (бета) ----------------------------------------
    private readonly NetworkQuality _netQuality = new();

    /// <summary>Last measured round-trip, or -1 when there is none. Paired with the speed samples.</summary>
    private int _lastCheckPingMs = -1;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsPriorityOff), nameof(IsPriorityBalance), nameof(IsPriorityGames))]
    private string trafficPriority = "off";

    public bool IsPriorityOff => TrafficPriority == "off";
    public bool IsPriorityBalance => TrafficPriority == "balance";
    public bool IsPriorityGames => TrafficPriority == "games";

    partial void OnTrafficPriorityChanged(string value)
    {
        _settings.TrafficPriority = value; _settings.Save(); ApplyTuning(); ReconnectIfConnected();
    }

    [RelayCommand] private void SetTrafficPriority(string mode) => TrafficPriority = mode;

    /// <summary>What the learner has worked out so far, for the settings page.</summary>
    public string LearnedCapacity => NetworkQuality.FormatMbit(_netQuality.CapacityBytesPerSecond);
    public string LearnedBaseline => _netQuality.BaselineRttMs < 0 ? "—" : $"{_netQuality.BaselineRttMs} ms";

    [RelayCommand]
    private void ResetLearnedCapacity()
    {
        _netQuality.Reset();
        OnPropertyChanged(nameof(LearnedCapacity));
        OnPropertyChanged(nameof(LearnedBaseline));
        Status = Localization.Loc.T("S_VM_200");
    }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsIpAuto), nameof(IsIpV4), nameof(IsIpV6), nameof(PreferredIpLabel))]
    private string preferredIp = "auto";
    public bool IsIpAuto => PreferredIp == "auto";
    public bool IsIpV4 => PreferredIp == "ipv4";
    public bool IsIpV6 => PreferredIp == "ipv6";
    public string PreferredIpLabel => PreferredIp switch { "ipv4" => "IPv4", "ipv6" => "IPv6", _ => "AUTO" };
    [RelayCommand] private void SetPreferredIp(string v) { PreferredIp = v; _settings.PreferredIp = v; _settings.Save(); ApplyTuning(); ReconnectIfConnected(); }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsDnsCfGoogle), nameof(IsDnsGoogle), nameof(IsDnsCloudflare), nameof(IsDnsQuad9), nameof(IsDnsCustom), nameof(VpnDnsLabel))]
    private string vpnDns = "google";
    public bool IsDnsCfGoogle => VpnDns == "cf_google";
    public bool IsDnsGoogle => VpnDns == "google";
    public bool IsDnsCloudflare => VpnDns == "cloudflare";
    public bool IsDnsQuad9 => VpnDns == "quad9";
    public bool IsDnsCustom => VpnDns == "custom";
    public string VpnDnsLabel => VpnDns switch { "cf_google" => "Cloudflare + Google", "cloudflare" => "Cloudflare", "quad9" => "Quad9", "custom" => Localization.Loc.T("S_VM_043"), _ => "Google DNS" };
    [RelayCommand] private void SetVpnDns(string v) { VpnDns = v; _settings.VpnDns = v; _settings.Save(); ApplyTuning(); ReconnectIfConnected(); }
    [ObservableProperty] private string vpnDnsCustom = "";
    partial void OnVpnDnsCustomChanged(string value) { _settings.VpnDnsCustom = value ?? ""; _settings.Save(); if (VpnDns == "custom") { ApplyTuning(); ReconnectIfConnected(); } }

    private void ApplyTuning()
    {
        MoonInternet.Core.Generation.XrayTuning.Fragment = TlsFragment;
        MoonInternet.Core.Generation.XrayTuning.Mux = Mux;
        MoonInternet.Core.Generation.XrayTuning.TrafficPriority = TrafficPriority;
        // xray spells it "warning"; our chip says "warn".
        MoonInternet.Core.Generation.XrayTuning.LogLevel =
            !LogsEnabled ? "none" : LogLevel == "warn" ? "warning" : LogLevel;
        MoonInternet.Core.Generation.XrayTuning.LogFile =
            LogsEnabled ? MoonInternet.Core.AppPaths.In("xray.log") : null;
        MoonInternet.Core.Generation.XrayTuning.Sniffing = Sniffing;
        MoonInternet.Core.Generation.XrayTuning.PreferredIp = PreferredIp;
        MoonInternet.Core.Generation.XrayTuning.Dns = DnsIps(VpnDns, VpnDnsCustom);
        MoonInternet.Core.Generation.XrayTuning.BlockUdp = BlockUdp;
        MoonInternet.Core.Generation.XrayTuning.HttpAuth = HttpProxyAuth && Socks5Auth;
        if (Socks5Auth)
        {
            if (string.IsNullOrEmpty(_settings.ProxyUser)) GenProxyCreds();
            MoonInternet.Core.Generation.XrayTuning.SocksUser = _settings.ProxyUser;
            MoonInternet.Core.Generation.XrayTuning.SocksPass = _settings.ProxyPass;
        }
        else { MoonInternet.Core.Generation.XrayTuning.SocksUser = null; MoonInternet.Core.Generation.XrayTuning.SocksPass = null; }
    }
    private static string[] DnsIps(string preset, string custom) => preset switch
    {
        "cf_google" => new[] { "1.1.1.1", "8.8.8.8" },
        "cloudflare" => new[] { "1.1.1.1", "1.0.0.1" },
        "quad9" => new[] { "9.9.9.9", "149.112.112.112" },
        "custom" => custom.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries) is { Length: > 0 } a ? a : new[] { "8.8.8.8" },
        _ => new[] { "8.8.8.8", "8.8.4.4" },
    };

    // ===== Connection settings (LAN / proxy-only / SOCKS5 auth / block UDP / HTTP auth) =====
    [ObservableProperty] private bool allowLan;
    partial void OnAllowLanChanged(bool value) { _settings.AllowLan = value; _settings.Save(); }
    [ObservableProperty] private bool lanThroughProxy;
    partial void OnLanThroughProxyChanged(bool value) { _settings.LanThroughProxy = value; _settings.Save(); }
    [ObservableProperty] private bool showProxyOnlyButton;
    partial void OnShowProxyOnlyButtonChanged(bool value) { _settings.ShowProxyOnlyButton = value; _settings.Save(); }
    [ObservableProperty] private string proxyBypassHosts = "";
    partial void OnProxyBypassHostsChanged(string value) { _settings.ProxyBypassHosts = value ?? ""; _settings.Save(); }

    [ObservableProperty] private bool socks5Auth;
    partial void OnSocks5AuthChanged(bool value) { _settings.Socks5Auth = value; _settings.Save(); ApplyTuning(); OnPropertyChanged(nameof(ProxyUser)); OnPropertyChanged(nameof(ProxyPassMasked)); ReconnectIfConnected(); }
    [ObservableProperty] private bool blockUdp;
    partial void OnBlockUdpChanged(bool value) { _settings.BlockUdp = value; _settings.Save(); ApplyTuning(); ReconnectIfConnected(); }
    [ObservableProperty] private bool httpProxyAuth;
    partial void OnHttpProxyAuthChanged(bool value) { _settings.HttpProxyAuth = value; _settings.Save(); ApplyTuning(); ReconnectIfConnected(); }

    public string ProxyUser => string.IsNullOrEmpty(_settings.ProxyUser) ? "—" : _settings.ProxyUser!;
    public string ProxyPassMasked => string.IsNullOrEmpty(_settings.ProxyPass) ? "—" : new string('•', Math.Min(12, _settings.ProxyPass!.Length));
    public string LocalProxyInfo => _conn.SocksPort > 0 ? $"Mixed (SOCKS5 + HTTP): {_conn.SocksPort}" : "Mixed (SOCKS5 + HTTP)";
    [RelayCommand] private void CopyProxyUser() { try { System.Windows.Clipboard.SetText(_settings.ProxyUser ?? ""); Status = Localization.Loc.T("S_VM_201"); } catch { } }
    [RelayCommand] private void CopyProxyPass() { try { System.Windows.Clipboard.SetText(_settings.ProxyPass ?? ""); Status = Localization.Loc.T("S_VM_202"); } catch { } }
    [RelayCommand] private void ResetProxyCreds() { GenProxyCreds(); ApplyTuning(); OnPropertyChanged(nameof(ProxyUser)); OnPropertyChanged(nameof(ProxyPassMasked)); ReconnectIfConnected(); Status = Localization.Loc.T("S_VM_203"); }
    [RelayCommand] private void ResetKillSwitch() { Status = Localization.Loc.T("S_VM_204"); }
    private void GenProxyCreds() { _settings.ProxyUser = "moon_" + Guid.NewGuid().ToString("N")[..8]; _settings.ProxyPass = Guid.NewGuid().ToString("N")[..12]; _settings.Save(); }

    partial void OnTunModeChanged(bool value) { _settings.TunMode = value; _settings.Save(); ReconnectIfConnected(); }
    partial void OnLaunchMinimizedChanged(bool value) { _settings.StartMinimized = value; _settings.Save(); }
    partial void OnAutostartChanged(bool value)
    {
        _settings.Autostart = value; _settings.Save();
        try { Services.Autostart.Apply(value, Environment.ProcessPath ?? ""); } catch { /* best effort */ }
    }

    [ObservableProperty][NotifyPropertyChangedFor(nameof(Search))] private string filter = "Все";
    [ObservableProperty] private string search = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsConnected), nameof(IsConnecting), nameof(ConnectButtonText),
        nameof(CanClick), nameof(RingBrush), nameof(StateText))]
    private ConnectionState connectionState = ConnectionState.Disconnected;

    public bool IsConnected => ConnectionState == ConnectionState.Connected;
    public bool IsConnecting => ConnectionState == ConnectionState.Connecting;
    public bool CanClick => true; // clickable in every state — during "Connecting" a click cancels
    public string ConnectButtonText => ConnectionState switch
    {
        ConnectionState.Connected => Localization.Loc.T("S_VM_010"),
        ConnectionState.Connecting => Localization.Loc.T("S_VM_011"),
        _ => Localization.Loc.T("S_VM_012")
    };
    public string TrayToggleText => ConnectionState switch   // tray menu (mixed case)
    {
        ConnectionState.Connected => Localization.Loc.T("S_VM_013"),
        ConnectionState.Connecting => Localization.Loc.T("S_VM_014"),
        _ => Localization.Loc.T("S_VM_015")
    };
    // Staged status shown under the ring while connecting ("Загрузка гео-файлов…", "Запуск ядра…", "Проверка соединения…").
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(StateText))]
    private string connectingStatus = "";

    // While true, the ring shows Localization.Loc.T("S_VM_228") during the (brief) teardown after the user hits ОТКЛЮЧИТЬ.
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(StateText))]
    private bool disconnecting;

    // Graceful pause shown before an AUTO-reconnect: the moon "falls asleep" (crescent) for a beat instead of a
    // harsh instant re-cycle.
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(StateText), nameof(MoonAwake))]
    private bool reSleeping;
    public bool MoonAwake => IsConnected && !ReSleeping;   // full moon only when truly connected

    public string StateText => ReSleeping ? Localization.Loc.T("S_VM_016") : ConnectionState switch
    {
        ConnectionState.Connected => Disconnecting ? Localization.Loc.T("S_VM_017") : Localization.Loc.T("S_VM_018"),
        ConnectionState.Connecting => string.IsNullOrEmpty(ConnectingStatus) ? Localization.Loc.T("S_VM_019") : ConnectingStatus,
        _ => SelectedServer is null ? Localization.Loc.T("S_VM_020") : Localization.Loc.T("S_VM_021")
    };
    partial void OnConnectionStateChanged(ConnectionState value)
    {
        if (value != ConnectionState.Connecting) ConnectingStatus = "";
        OnPropertyChanged(nameof(CheckPingText)); OnPropertyChanged(nameof(HasCheckPing));
        if (value != ConnectionState.Connected) { UploadSpeed = DownloadSpeed = SessionTraffic = "—"; }
        else _ = CheckConnection();                                   // auto-measure ping the moment we connect
        OnPropertyChanged(nameof(ElapsedDisplay));
        OnPropertyChanged(nameof(MoonAwake));
        OnPropertyChanged(nameof(TrayToggleText));
        // Only the two settled states are worth a popup; Connecting is on the way to both.
        if (value == ConnectionState.Connected)
            Notifier.Connection("Moon Internet", string.Format(Localization.Loc.T("S_VM_023"), SelectedServer?.Label));
        else if (value == ConnectionState.Disconnected)
            Notifier.Connection("Moon Internet", Localization.Loc.T("S_VM_024"));
    }
    // Frozen and shared, same reason as the ping brushes.
    private static readonly Brush RingConnected = ServerItem.Frozen(0x34, 0xD3, 0x99);
    private static readonly Brush RingConnecting = ServerItem.Frozen(0x9D, 0x7B, 0xFF);
    private static readonly Brush RingIdle = ServerItem.Frozen(0x3A, 0x30, 0x52);

    public Brush RingBrush => ConnectionState switch
    {
        ConnectionState.Connected => RingConnected,
        ConnectionState.Connecting => RingConnecting,
        _ => RingIdle,
    };

    public bool HasSelectedServer => SelectedServer is not null;
    public string SelectedServerLabel => SelectedServer?.Label ?? Localization.Loc.T("S_VM_022");
    public ImageSource? SelectedServerFlag => SelectedServer?.FlagImage;
    private bool _restoringServer;
    partial void OnSelectedServerChanged(ServerItem? value)
    {
        OnPropertyChanged(nameof(SelectedServerLabel));
        OnPropertyChanged(nameof(SelectedServerFlag));
        OnPropertyChanged(nameof(HasSelectedServer));
        OnPropertyChanged(nameof(StateText));   // "Нет активного соединения" ⇄ "Не подключено"
        foreach (var s in AllServers) s.IsSelected = ReferenceEquals(s, value);   // highlight the picked row on Home
        if (_restoringServer) return;   // programmatic re-select — don't save or reconnect
        if (value is null)
        {
            // The list was rebuilt (sub reload/sort) and WPF cleared the ListBox selection → don't show
            // "Сервер не выбран" while we're actually connected. Re-pick the same server from the new list, quietly.
            var restore = AllServers.FirstOrDefault(s => s.Label == _settings.LastServerName) ?? AllServers.FirstOrDefault();
            if (restore is not null) { _restoringServer = true; SelectedServer = restore; _restoringServer = false; }
            return;
        }
        _settings.LastServerName = value.Label; _settings.Save();   // remember the choice even without connecting
        ReconnectIfConnected();                                     // switching server while connected → reconnect to it
    }
    partial void OnFilterChanged(string value) => RefreshFilters();
    partial void OnSearchChanged(string value) => RefreshFilters();
    private void RefreshFilters() { foreach (var s in Subscriptions) s.Refresh(); }

    /// <summary>
    /// Text that is a default rather than a reaction to something. It cannot live in a field
    /// initializer (those run before Loc has a dictionary) and it has to be re-read when the
    /// language changes, so both paths call this.
    /// </summary>
    private void ApplyLanguageTexts()
    {
        Status = Localization.Loc.T("S_VM_236");
        GeoStatus = Localization.Loc.T("S_VM_064");
        SubscriptionName = Localization.Loc.T("S_VM_031");
        LogViewerTitle = Localization.Loc.T("S_VM_080");
        DialogTitle = Localization.Loc.T("S_VM_100");
    }

    public MainViewModel()
    {
        ApplyLanguageTexts();
        _settings = AppSettings.Load();
        if (_settings.SubscriptionUrls.Count == 0 && !string.IsNullOrWhiteSpace(_settings.SubscriptionUrl))
            _settings.SubscriptionUrls.Add(_settings.SubscriptionUrl!); // migrate legacy single URL
        SubscriptionUrl = _settings.SubscriptionUrls.FirstOrDefault() ?? "";
        TunMode = _settings.TunMode;
        LaunchMinimized = _settings.StartMinimized;
        Autostart = _settings.Autostart;
        var saved = LayoutStore.Load();
        BlockLayout Make(string id) { var b = new BlockLayout(); b.Set(saved.TryGetValue(id, out var v) && v.Length == 4 ? v : LayoutDefaults[id]); return b; }
        ModeBlock = Make("mode"); StatsBlock = Make("stats"); RingBlock = Make("ring"); SubBlock = Make("sub");

        _installedRoutings = RoutingStore.LoadInstalled().ToList();   // both INCY and HAPP, loaded up front
        ServerSort = _settings.ServerSort;
        AutoConnectOnStart = _settings.AutoConnectOnStart;
        AutoConnectTarget = _settings.AutoConnectTarget;

        _conn = new ConnectionManager(CoreLocator.CoresDir());
        _conn.StateChanged += s => Application.Current?.Dispatcher.Invoke(() => ConnectionState = s);
        _conn.Progress += m => Application.Current?.Dispatcher.Invoke(() => ConnectingStatus = m);
        GeoService.Status += msg => Application.Current?.Dispatcher.Invoke(() => GeoStatus = msg);
        AutoReconnect = _settings.AutoReconnect;
        KillSwitch = _settings.KillSwitch;
        UseRouting = _settings.UseRouting;
        PingMethod = _settings.PingMethod; PingDisplay = _settings.PingDisplay;
        PingTestUrl = _settings.PingTestUrl; PingTimeoutMs = _settings.PingTimeoutMs;
        PingStagger = _settings.PingStagger; PingStaggerMs = _settings.PingStaggerMs;
        ShowServerCount = _settings.ShowServerCount;
        Language = Localization.Loc.Language;   // already applied at startup; mirror it here
        PingEveryMinutes = _settings.PingEveryMinutes;
        AutoUpdateSubs = _settings.AutoUpdateSubs; AutoUpdateSubsMinutes = _settings.AutoUpdateSubsMinutes;
        NotifyOnUpdate = _settings.NotifyOnUpdate; UpdateSubsOnStart = _settings.UpdateSubsOnStart;
        PingOnStart = _settings.PingOnStart; SendHwid = _settings.SendHwid;
        ShowSubHeader = _settings.ShowSubHeader; SubMeter = _settings.SubMeter; NotifyExpiry = _settings.NotifyExpiry; ExpiryNotifyDays = _settings.ExpiryNotifyDays;
        NotifyTrafficLow = _settings.NotifyTrafficLow;
        ShowWelcome = !_settings.WelcomeShown;
        AutoFailover = _settings.AutoFailover; ReconnectDelaySec = _settings.ReconnectDelaySec;
        NotificationsEnabled = _settings.NotificationsEnabled; TrayBalloons = _settings.TrayBalloons;
        NotifyConnection = _settings.NotifyConnection; NotifyAppUpdate = _settings.NotifyAppUpdate;
        ApplyHwid();
        TlsFragment = _settings.TlsFragment; Mux = _settings.Mux; Sniffing = _settings.Sniffing;
        TrafficPriority = _settings.TrafficPriority;
        PreferredIp = _settings.PreferredIp; VpnDns = _settings.VpnDns; VpnDnsCustom = _settings.VpnDnsCustom;
        AllowLan = _settings.AllowLan; LanThroughProxy = _settings.LanThroughProxy;
        ShowProxyOnlyButton = _settings.ShowProxyOnlyButton; ProxyBypassHosts = _settings.ProxyBypassHosts;
        Socks5Auth = _settings.Socks5Auth; BlockUdp = _settings.BlockUdp; HttpProxyAuth = _settings.HttpProxyAuth;
        LogsEnabled = _settings.LogsEnabled; LogLevel = _settings.LogLevel; LogKeepDays = _settings.LogKeepDays;
        Task.Run(PruneLogs);            // enforce the retention window on every launch
        ApplyTuning();
        RebuildRouting(); // installed INCY profiles available even before a subscription loads
        _conn.AutoReconnect = AutoReconnect;
        _conn.KillSwitch = KillSwitch;
        foreach (var a in _settings.AppRouteApps)
            if (a.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) AppRouteApps.Add(new AppRouteItem(a));   // skip stale non-exe entries
        _conn.AppRouteApps = AppRouteApps.Select(a => a.Name).ToList();
        if (AppRouteApps.Count != _settings.AppRouteApps.Count) PersistAppRouteApps();   // rewrite cleaned list
        AppRouteMode = _settings.AppRouteMode; // triggers OnAppRouteModeChanged → sets _conn.AppRouteMode

        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _timer.Tick += (_, _) => { if (IsConnected) { Elapsed = (DateTime.Now - _connectedAt).ToString(@"hh\:mm\:ss"); SampleTraffic(); } };
        _timer.Start();

        if (!_conn.CoreAvailable) Status = Localization.Loc.T("S_VM_205");
        LoadCachedSubs();                                                     // offline-first: show last known servers immediately
        foreach (var u in _settings.SubscriptionUrls.ToList()) _ = ImportUrl(u);   // then refresh from the network
        _themeReady = true; // now theme edits (via UI) may apply+save
        _ = CheckUpdate();  // quiet: only lights the badge, never interrupts the launch
    }

    private bool _themeReady;

    public bool StartMinimized => _settings.StartMinimized;

    private bool FilterServer(object o)
    {
        if (o is not ServerItem s) return false;
        if (Filter != "Все" && !s.Protocol.Equals(Filter, StringComparison.OrdinalIgnoreCase)) return false;
        if (!string.IsNullOrWhiteSpace(Search) && !s.Label.Contains(Search, StringComparison.OrdinalIgnoreCase)) return false;
        // "★ Избранное" = show ONLY favourites — but if the user has none, fall back to showing everything.
        if (ServerSort == "favorite" && _settings.FavoriteServers.Count > 0 && !s.IsFavorite) return false;
        return true;
    }

    // Settings is a hub: one page with sub-pages (appearance/connection/routing/subs/ping/auto/logs), toggled here.
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(SettingsPageTitle), nameof(IsSettingsSubPage))]
    private string settingsPage = "hub";
    private readonly Stack<string> _settingsBack = new();   // so a sub-page returns to wherever it was opened from
    [RelayCommand] private void OpenSettingsPage(string page)
    {
        if (page == SettingsPage) return;
        _settingsBack.Push(SettingsPage);
        SettingsPage = page;
        if (page == "about") LoadCoreVersions();
        if (page == "logs") RefreshLogsInfo();
    }
    [RelayCommand] private void SettingsBack() => SettingsPage = _settingsBack.Count > 0 ? _settingsBack.Pop() : "hub";

    /// <summary>Back to the Home screen (connect button), clearing any settings sub-page — used when the window
    /// is restored from the tray so it never reopens deep inside settings.</summary>
    public void ResetToHome()
    {
        CurrentPage = AppPage.Home;
        SettingsPage = "hub";
        _settingsBack.Clear();
        ShowServerSheet = ShowSubSheet = ShowAddDialog = ShowConfigDialog = ShowQrDialog = false;
    }
    // Routing rules page (its own AppPage) returns to the "Маршрутизация" settings sub-page, not the hub/home.
    [RelayCommand] private void BackToRoutingSettings() { CurrentPage = AppPage.Settings; SettingsPage = "routing"; }

    public string GeoipInfo => FileInfoText(MoonInternet.Services.GeoService.GeoipFile);
    public string GeositeInfo => FileInfoText(MoonInternet.Services.GeoService.GeositeFile);

    /// <summary>One row of the geo-files card: who it belongs to and where its two lists come from.</summary>
    public sealed record GeoSourceRow(string Owner, string GeoipText, string GeositeText, bool ShowOwner);

    /// <summary>
    /// INCY and HAPP usually point at the same geoip/geosite release, and then there is nothing to
    /// choose between — one plate, exactly as before. When their URLs differ that is worth seeing,
    /// so each set gets its own plate with the owners named.
    /// </summary>
    public IReadOnlyList<GeoSourceRow> GeoSources
    {
        get
        {
            var imported = AvailableRoutings
                .Where(r => r.Source is RoutingSource.Incy or RoutingSource.Happ)
                .ToList();
            if (imported.Count == 0)
                return new[] { new GeoSourceRow("", GeoipInfo, GeositeInfo, false) };

            var groups = imported
                .GroupBy(r => (r.Geoipurl, r.Geositeurl))
                .ToList();
            bool split = groups.Count > 1;
            return groups.Select(g =>
            {
                // Only the selected profile's lists are the ones actually on disk; for the other
                // set we can honestly show where it would come from, not a size we never fetched.
                bool onDisk = !split || g.Any(r => ReferenceEquals(r, SelectedRouting));
                return new GeoSourceRow(
                    string.Join(" · ", g.Select(r => SourceLabel(r.Source)).Distinct()),
                    onDisk ? GeoipInfo : UrlHost(g.Key.Geoipurl),
                    onDisk ? GeositeInfo : UrlHost(g.Key.Geositeurl),
                    split);
            }).ToList();
        }
    }

    private static string SourceLabel(RoutingSource s) => s switch
    {
        RoutingSource.Incy => "INCY",
        RoutingSource.Happ => "HAPP",
        _ => "Свой",
    };

    private static string UrlHost(string url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var u) ? u.Host : Localization.Loc.T("S_VM_063");

    private static string FileInfoText(string path)
    {
        try { var fi = new FileInfo(path); return fi.Exists ? string.Format(Localization.Loc.T("S_VM_060"), fi.Length / 1048576.0, fi.LastWriteTime) : Localization.Loc.T("S_VM_061"); }
        catch { return "—"; }
    }
    [ObservableProperty] private bool geoRefreshing;
    [RelayCommand] private async Task RefreshGeo()
    {
        if (SelectedRouting is null) { GeoStatus = Localization.Loc.T("S_VM_062"); return; }
        GeoRefreshing = true;
        try
        {
            await MoonInternet.Services.GeoService.RefreshAsync(SelectedRouting);
            OnPropertyChanged(nameof(GeoipInfo)); OnPropertyChanged(nameof(GeositeInfo));
            OnPropertyChanged(nameof(GeoSources));
            ReconnectIfConnected();
        }
        finally { GeoRefreshing = false; }
    }

    // ===== Обновления =====
    // The badge on Home is driven by UpdateAvailable; the dialog reads the rest.
    [ObservableProperty][NotifyPropertyChangedFor(nameof(UpdateButtonHint))] private bool updateAvailable;
    [ObservableProperty] private bool showUpdateDialog;
    [ObservableProperty] private string latestVersion = "—";
    [ObservableProperty] private string updateNotes = "";
    [ObservableProperty] private string updateStatus = "";
    [ObservableProperty] private bool updateChecking;

    private string? _releasePage, _assetUrl;

    public string UpdateButtonHint => UpdateAvailable ? Localization.Loc.T("S_VM_070") : Localization.Loc.T("S_VM_071");

    [RelayCommand] private void OpenUpdateDialog() { ShowUpdateDialog = true; if (LatestVersion == "—") _ = CheckUpdate(); }
    [RelayCommand] private void CloseUpdateDialog() => ShowUpdateDialog = false;

    /// <summary>Asks GitHub. Runs once at startup too, quietly — a failure just leaves the badge off.</summary>
    [RelayCommand]
    private async Task CheckUpdate()
    {
        if (UpdateChecking) return;
        UpdateChecking = true;
        UpdateStatus = Localization.Loc.T("S_VM_072");
        try
        {
            var rel = await UpdateService.LatestAsync();
            if (rel is null) { UpdateStatus = Localization.Loc.T("S_VM_073"); return; }

            LatestVersion = rel.Version;
            UpdateNotes = rel.Notes;
            _releasePage = rel.PageUrl;
            _assetUrl = rel.AssetUrl;
            UpdateAvailable = UpdateService.IsNewer(rel.Version, AppVersion);
            UpdateStatus = UpdateAvailable
                ? string.Format(Localization.Loc.T("S_VM_074"), rel.Version)
                : Localization.Loc.T("S_VM_075");
            // The badge on Home is easy to miss when the window starts minimised to the tray.
            if (UpdateAvailable) Notifier.AppUpdate("Moon Internet", string.Format(Localization.Loc.T("S_VM_074"), rel.Version));
        }
        finally { UpdateChecking = false; }
    }

    /// <summary>
    /// Opens the download in the browser rather than fetching it ourselves: the installer has to
    /// replace the running app, and handing that to the browser and the user avoids us babysitting
    /// a download, verifying it and relaunching.
    /// </summary>
    [RelayCommand]
    private async Task DownloadUpdate()
    {
        // No installer in the release (or nothing but a page) — the browser is still the answer.
        if (string.IsNullOrEmpty(_assetUrl))
        {
            if (string.IsNullOrEmpty(_releasePage)) return;
            try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(_releasePage!) { UseShellExecute = true }); }
            catch { UpdateStatus = Localization.Loc.T("S_VM_076"); }
            return;
        }

        DownloadPercent = 0;
        UpdateStatus = Localization.Loc.T("S_Upd_Downloading");
        var progress = new Progress<int>(p => DownloadPercent = p);
        string? file = await UpdateDownloader.DownloadAsync(_assetUrl!, progress);
        DownloadPercent = null;

        if (file is null) { UpdateStatus = Localization.Loc.T("S_Upd_Failed"); return; }

        UpdateStatus = Localization.Loc.T("S_Upd_Installing");
        if (!UpdateDownloader.Run(file)) { UpdateStatus = Localization.Loc.T("S_Upd_Failed"); return; }

        // The installer replaces files this process is holding open, so we get out of its way.
        // A beat first, so the status is readable and the installer's window is up.
        await Task.Delay(1200);
        System.Windows.Application.Current?.Shutdown();
    }

    /// <summary>0..100 while downloading, -1 when the size is unknown, null when idle.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsDownloading))]
    private int? downloadPercent;
    public bool IsDownloading => DownloadPercent is not null;

    // ===== About =====
    // The fourth part is the build counter we bump while working (0.9.1.N); it is dropped for a
    // release, which ships as plain 0.9.1. Keeping it here means the title bar, the About page
    // and the update check all read the same number.
    public string AppVersion => GetType().Assembly.GetName().Version is { } v
        ? (v.Revision > 0 ? $"{v.Major}.{v.Minor}.{v.Build}.{v.Revision}" : $"{v.Major}.{v.Minor}.{v.Build}")
        : "—";
    public string DotNetVersion => System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription;
    public string OsPlatform => Environment.OSVersion.Version.Build >= 22000 ? "Windows 11" : "Windows 10";
    public string OsArch => System.Runtime.InteropServices.RuntimeInformation.OSArchitecture.ToString().ToLowerInvariant();
    public string HwidDisplay => string.IsNullOrEmpty(_settings.Hwid) ? "—" : _settings.Hwid!;
    [ObservableProperty] private string xrayVersion = "…";
    [ObservableProperty] private string singBoxVersion = "…";
    [RelayCommand] private void CopyHwid() { try { System.Windows.Clipboard.SetText(_settings.Hwid ?? ""); Status = Localization.Loc.T("S_VM_206"); } catch { } }

    private bool _coreVersionsLoaded;
    private async void LoadCoreVersions()
    {
        if (_coreVersionsLoaded) return;
        var cores = CoreLocator.CoresDir();
        // Off the UI thread: starting two processes and reading their output on the dispatcher
        // is enough to stall the page, and a stall here reads as "it just shows dashes".
        var (xray, sing) = await Task.Run(async () => (
            await RunVersion(System.IO.Path.Combine(cores, "xray", "xray.exe")).ConfigureAwait(false),
            await RunVersion(System.IO.Path.Combine(cores, "singbox", "sing-box.exe")).ConfigureAwait(false)
        )).ConfigureAwait(true);
        XrayVersion = xray ?? "—";
        SingBoxVersion = sing ?? "—";
        // Only latch when something actually answered, so a first look before the cores are
        // unpacked does not leave the row on a dash for the rest of the session.
        _coreVersionsLoaded = xray is not null || sing is not null;
    }
    private static async Task<string?> RunVersion(string exe)
    {
        try
        {
            if (!System.IO.File.Exists(exe)) return null;
            var psi = new System.Diagnostics.ProcessStartInfo(exe, "version")
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                WorkingDirectory = System.IO.Path.GetDirectoryName(exe)!,
            };
            using var p = System.Diagnostics.Process.Start(psi);
            if (p is null) return null;
            // sing-box prints its version to stdout, xray to stdout too — but a core that fails to
            // start says so on stderr, and an undrained pipe would block it mid-write.
            var stdout = p.StandardOutput.ReadToEndAsync();
            var stderr = p.StandardError.ReadToEndAsync();
            await p.WaitForExitAsync().ConfigureAwait(false);
            string outp = await stdout.ConfigureAwait(false) + await stderr.ConfigureAwait(false);
            var m = System.Text.RegularExpressions.Regex.Match(outp, @"\d+\.\d+\.\d+");
            return m.Success ? m.Value : null;
        }
        catch { return null; }
    }
    [RelayCommand] private void OpenLogsFolder()
    {
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("explorer.exe", MoonInternet.Core.AppPaths.DataDir) { UseShellExecute = true }); } catch { }
    }

    // ===== Logs =====
    [ObservableProperty] private bool logsEnabled = true;
    partial void OnLogsEnabledChanged(bool value) { _settings.LogsEnabled = value; _settings.Save(); ApplyTuning(); }
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsLogErr), nameof(IsLogWarn), nameof(IsLogInfo), nameof(IsLogDebug))]
    private string logLevel = "warn";
    public bool IsLogErr => LogLevel == "error";
    public bool IsLogWarn => LogLevel == "warn";
    public bool IsLogInfo => LogLevel == "info";
    public bool IsLogDebug => LogLevel == "debug";
    [RelayCommand] private void SetLogLevel(string v) { LogLevel = v; _settings.LogLevel = v; _settings.Save(); ApplyTuning(); }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsKeep1), nameof(IsKeep3), nameof(IsKeep7), nameof(IsKeep30), nameof(IsKeepForever))]
    private int logKeepDays = 7;
    public bool IsKeep1 => LogKeepDays == 1;
    public bool IsKeep3 => LogKeepDays == 3;
    public bool IsKeep7 => LogKeepDays == 7;
    public bool IsKeep30 => LogKeepDays == 30;
    public bool IsKeepForever => LogKeepDays == 0;
    [RelayCommand] private void SetLogKeep(string d) { LogKeepDays = int.Parse(d); _settings.LogKeepDays = LogKeepDays; _settings.Save(); PruneLogs(); }

    [ObservableProperty] private string logsSizeInfo = "—";

    // ---- просмотр логов --------------------------------------------------
    [ObservableProperty] private bool showLogViewer;
    [ObservableProperty] private string logViewerText = "";
    [ObservableProperty] private string logViewerTitle = "";     // ditto

    /// <summary>How many lines the viewer keeps. A debug log runs to megabytes and the window
    /// would take seconds to lay out; the tail is the part anyone actually reads.</summary>
    private const int LogTailLines = 800;

    [RelayCommand]
    private void OpenLogViewer()
    {
        LoadLogTail();
        ShowLogViewer = true;
    }

    [RelayCommand] private void CloseLogViewer() => ShowLogViewer = false;
    [RelayCommand] private void ReloadLogViewer() => LoadLogTail();
    [RelayCommand] private void CopyLogViewer()
    {
        try { System.Windows.Clipboard.SetText(LogViewerText); Status = Localization.Loc.T("S_VM_207"); } catch { }
    }

    private void LoadLogTail()
    {
        var files = LogFiles().ToList();
        if (files.Count == 0) { LogViewerTitle = Localization.Loc.T("S_VM_080"); LogViewerText = Localization.Loc.T("S_VM_081"); return; }

        // Newest file: with several cores writing their own, that is the one being appended to.
        var newest = files.OrderByDescending(f => { try { return new System.IO.FileInfo(f).LastWriteTimeUtc; } catch { return DateTime.MinValue; } }).First();
        LogViewerTitle = System.IO.Path.GetFileName(newest);
        try
        {
            var lines = System.IO.File.ReadLines(newest).ToList();
            var tail = lines.Count > LogTailLines ? lines.Skip(lines.Count - LogTailLines) : lines;
            LogViewerText = string.Join(Environment.NewLine, tail);
            if (LogViewerText.Length == 0) LogViewerText = Localization.Loc.T("S_VM_082");
        }
        catch (Exception ex) { LogViewerText = Localization.Loc.T("S_VM_083") + ex.Message; }
    }

    [RelayCommand] private void ClearLogs()
    {
        foreach (var f in LogFiles()) { try { System.IO.File.WriteAllText(f, ""); } catch { } }
        RefreshLogsInfo();
        Status = Localization.Loc.T("S_VM_208");
    }
    private static IEnumerable<string> LogFiles()
    {
        try { return System.IO.Directory.EnumerateFiles(MoonInternet.Core.AppPaths.DataDir, "*.log"); }
        catch { return Enumerable.Empty<string>(); }
    }
    public void RefreshLogsInfo()
    {
        long total = 0; int n = 0;
        foreach (var f in LogFiles()) { try { total += new System.IO.FileInfo(f).Length; n++; } catch { } }
        LogsSizeInfo = n == 0 ? Localization.Loc.T("S_VM_084") : string.Format(Localization.Loc.T("S_VM_085"), n, FmtSize(total));
    }
    /// <summary>Delete/truncate logs older than the retention window, and cap the size of the live ones.</summary>
    private void PruneLogs()
    {
        foreach (var f in LogFiles())
        {
            try
            {
                var fi = new System.IO.FileInfo(f);
                if (LogKeepDays > 0 && fi.LastWriteTime < DateTime.Now.AddDays(-LogKeepDays)) { System.IO.File.Delete(f); continue; }
                if (_settings.LogMaxMb > 0 && fi.Length > _settings.LogMaxMb * 1024L * 1024)
                {
                    var lines = System.IO.File.ReadAllLines(f);
                    System.IO.File.WriteAllLines(f, lines.Skip(lines.Length / 2));   // keep the newer half
                }
            }
            catch { }
        }
        RefreshLogsInfo();
    }

    // ===== Ping settings =====
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsPingMoon), nameof(IsPingTcp), nameof(IsPingHttpGet), nameof(IsPingHttpHead))]
    private string pingMethod = "moon";
    public bool IsPingMoon => PingMethod == "moon";
    public bool IsPingTcp => PingMethod == "tcp";
    public bool IsPingHttpGet => PingMethod == "httpget";
    public bool IsPingHttpHead => PingMethod == "httphead";
    public bool IsPingStability => PingMethod == "stability";

    /// <summary>Title for the pinned settings header — one header for every sub-page.</summary>
    // Через Loc.T, а не литералами: заголовок висит в закреплённой шапке и обязан
    // переключаться вместе с остальным текстом.
    public string SettingsPageTitle => Localization.Loc.T(SettingsPage switch
    {
        "appearance" => "S_SettingsView_003",
        "connection" => "S_SettingsView_005",
        "routing" => "S_SettingsView_007",
        "approuting" => "S_SettingsView_065",
        "subs" => "S_SettingsView_009",
        "ping" => "S_SettingsView_011",
        "auto" => "S_SettingsView_013",
        "logs" => "S_SettingsView_015",
        "privacy" => "S_SettingsView_188",
        "about" => "S_SettingsView_017",
        "terms" => "S_SettingsView_187",
        "libs" => "S_SettingsView_189",
        "notify" => "S_Page_Notify",
        _ => "S_SettingsView_002",
    });

    /// <summary>False on the hub, which has its own big title and no back button.</summary>
    public bool IsSettingsSubPage => SettingsPage != "hub";

    /// <summary>How many servers we probe at once. More than this looks like a port scan.</summary>
    public int PingParallel => 6;

    // ---- язык -------------------------------------------------------------
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsRussian), nameof(IsEnglish))]
    private string language = "ru";

    public bool IsRussian => Language == "ru";
    public bool IsEnglish => Language == "en";

    /// <summary>Short label for the switch in the settings header.</summary>
    public string LanguageLabel => Language == "en" ? "EN" : "RU";

    [RelayCommand]
    private void ToggleLanguage()
    {
        Language = Language == "ru" ? "en" : "ru";
        _settings.Language = Language;
        _settings.Save();
        Localization.Loc.Apply(Language);
        OnPropertyChanged(nameof(LanguageLabel));
        OnPropertyChanged(nameof(SettingsPageTitle));
        OnPropertyChanged(nameof(RoutingName));
        OnPropertyChanged(nameof(RoutingSubtitle));
        // computed strings that go through Loc.T have no other reason to re-read
        foreach (var p in new[] { nameof(ConnectButtonText), nameof(TrayToggleText), nameof(StateText),
                                  nameof(SelectedServerLabel), nameof(RuleBucketTitle), nameof(VpnDnsLabel),
                                  nameof(UpdateButtonHint), nameof(SubUpdateHint), nameof(FilterChips),
                                  nameof(CheckPingTrayText), nameof(GeoipInfo), nameof(GeositeInfo), nameof(TotalServersText),
                                  nameof(GeoSources), nameof(LogsSizeInfo) })
            OnPropertyChanged(p);
        RefreshLogsInfo();
    }

    [ObservableProperty] private bool showServerCount = true;
    partial void OnShowServerCountChanged(bool value) { _settings.ShowServerCount = value; _settings.Save(); }

    [ObservableProperty] private bool pingStagger;
    partial void OnPingStaggerChanged(bool value) { _settings.PingStagger = value; _settings.Save(); }

    // ---- автопроверка ----------------------------------------------------
    private IDisposable? _autoPingTimer;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsAutoPingOff), nameof(IsAutoPing1), nameof(IsAutoPing5),
                              nameof(IsAutoPing10), nameof(IsAutoPing15), nameof(IsAutoPing20), nameof(IsAutoPing30))]
    private int pingEveryMinutes;

    public bool IsAutoPingOff => PingEveryMinutes == 0;
    public bool IsAutoPing1 => PingEveryMinutes == 1;
    public bool IsAutoPing5 => PingEveryMinutes == 5;
    public bool IsAutoPing10 => PingEveryMinutes == 10;
    public bool IsAutoPing15 => PingEveryMinutes == 15;
    public bool IsAutoPing20 => PingEveryMinutes == 20;
    public bool IsAutoPing30 => PingEveryMinutes == 30;

    [RelayCommand] private void SetAutoPing(string m) { if (int.TryParse(m, out var v)) PingEveryMinutes = v; }

    partial void OnPingEveryMinutesChanged(int value)
    {
        _settings.PingEveryMinutes = value; _settings.Save();
        RestartAutoPing();
    }

    /// <summary>
    /// Re-measures on the chosen interval so the list is fresh when it is opened. Off by default:
    /// a probe to every server on a timer is traffic the user did not ask for.
    /// </summary>
    private void RestartAutoPing()
    {
        _autoPingTimer?.Dispose();
        _autoPingTimer = null;
        if (PingEveryMinutes <= 0) return;

        var t = new System.Windows.Threading.DispatcherTimer
        {
            Interval = TimeSpan.FromMinutes(PingEveryMinutes),
        };
        // Skip a round rather than queue up: with "стабильность" a pass can outlast the interval,
        // and stacking them would leave several cores running at once.
        t.Tick += async (_, _) => { if (!_autoPingBusy) { _autoPingBusy = true; try { await PingAll(); } finally { _autoPingBusy = false; } } };
        t.Start();
        _autoPingTimer = new TimerHandle(t);
    }

    private bool _autoPingBusy;

    private sealed class TimerHandle(System.Windows.Threading.DispatcherTimer t) : IDisposable
    {
        public void Dispose() => t.Stop();
    }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsStagger50), nameof(IsStagger150), nameof(IsStagger300))]
    private int pingStaggerMs = 150;
    partial void OnPingStaggerMsChanged(int value) { _settings.PingStaggerMs = value; _settings.Save(); }

    public bool IsStagger50 => PingStaggerMs == 50;
    public bool IsStagger150 => PingStaggerMs == 150;
    public bool IsStagger300 => PingStaggerMs == 300;
    [RelayCommand] private void SetPingStagger(string ms) { if (int.TryParse(ms, out var v)) PingStaggerMs = v; }
    [RelayCommand] private void SetPingMethod(string m) { PingMethod = m; _settings.PingMethod = m; _settings.Save(); }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsPingDispNum), nameof(IsPingDispBar), nameof(IsPingDispBoth), nameof(IsPingDispDots))]
    private string pingDisplay = "num";
    public bool IsPingDispNum => PingDisplay == "num";
    public bool IsPingDispBar => PingDisplay == "bar";
    public bool IsPingDispBoth => PingDisplay == "both";
    public bool IsPingDispDots => PingDisplay == "dots";
    [RelayCommand] private void SetPingDisplay(string d) { PingDisplay = d; _settings.PingDisplay = d; _settings.Save(); NotifyServerListChanged(); }

    // How the subscription plate shows traffic and expiry — same idea as the ping display above.
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsSubMeterText), nameof(IsSubMeterBar), nameof(IsSubMeterDots))]
    private string subMeter = "text";
    public bool IsSubMeterText => SubMeter == "text";
    public bool IsSubMeterBar => SubMeter == "bar";
    public bool IsSubMeterDots => SubMeter == "dots";

    [RelayCommand] private void SetSubMeter(string d) { SubMeter = d; _settings.SubMeter = d; _settings.Save(); }

    [ObservableProperty] private string pingTestUrl = "https://www.gstatic.com/generate_204";
    partial void OnPingTestUrlChanged(string value) { _settings.PingTestUrl = value?.Trim() ?? ""; _settings.Save(); }
    [RelayCommand] private void SetPingUrl(string url) => PingTestUrl = url;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(PingTimeoutText))]
    private int pingTimeoutMs = 4000;
    public string PingTimeoutText => $"{PingTimeoutMs / 1000}s";
    [RelayCommand] private void PingTimeoutInc() { PingTimeoutMs = Math.Min(15000, PingTimeoutMs + 1000); _settings.PingTimeoutMs = PingTimeoutMs; _settings.Save(); }
    [RelayCommand] private void PingTimeoutDec() { PingTimeoutMs = Math.Max(1000, PingTimeoutMs - 1000); _settings.PingTimeoutMs = PingTimeoutMs; _settings.Save(); }

    // ===== Subscription settings =====
    [ObservableProperty] private bool autoUpdateSubs = true;
    partial void OnAutoUpdateSubsChanged(bool value) { _settings.AutoUpdateSubs = value; _settings.Save(); RestartAutoUpdateTimer(); }
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsSubIntAuto), nameof(IsSubInt30), nameof(IsSubInt60), nameof(IsSubInt120), nameof(IsSubInt360), nameof(IsSubInt720), nameof(IsSubInt1440), nameof(SubUpdateHint))]
    private int autoUpdateSubsMinutes;   // 0 = "По подписке" (use the panel's own interval)
    public bool IsSubIntAuto => AutoUpdateSubsMinutes == 0;
    public bool IsSubInt30 => AutoUpdateSubsMinutes == 30;
    public bool IsSubInt60 => AutoUpdateSubsMinutes == 60;
    public bool IsSubInt120 => AutoUpdateSubsMinutes == 120;
    public bool IsSubInt360 => AutoUpdateSubsMinutes == 360;
    public bool IsSubInt720 => AutoUpdateSubsMinutes == 720;
    public bool IsSubInt1440 => AutoUpdateSubsMinutes == 1440;
    [RelayCommand] private void SetSubInterval(string m) { AutoUpdateSubsMinutes = int.Parse(m); _settings.AutoUpdateSubsMinutes = AutoUpdateSubsMinutes; _settings.Save(); RestartAutoUpdateTimer(); }

    private int _subUpdateMin;   // interval the subscription itself ships (minutes); 0 if none
    private int EffectiveUpdateMin => AutoUpdateSubsMinutes > 0 ? AutoUpdateSubsMinutes : (_subUpdateMin > 0 ? _subUpdateMin : 60);
    public string SubUpdateHint => AutoUpdateSubs
        ? (IsSubIntAuto ? string.Format(Localization.Loc.T("S_VM_090"), FmtMin(EffectiveUpdateMin)) : string.Format(Localization.Loc.T("S_VM_091"), FmtMin(EffectiveUpdateMin)))
        : Localization.Loc.T("S_VM_092");
    private static string FmtMin(int m) => m < 60 ? string.Format(Localization.Loc.T("S_VM_093"), m) : m % 60 == 0 ? string.Format(Localization.Loc.T("S_VM_094"), m / 60) : string.Format(Localization.Loc.T("S_VM_095"), m / 60, m % 60);

    /// <summary>
    /// First launch. Deliberately one screen and not a tour: the only thing the app cannot do
    /// without is a subscription, so that is what it asks for, and everything else stays out of
    /// the way until it is needed.
    /// </summary>
    [ObservableProperty] private bool showWelcome;
    [RelayCommand] private void CloseWelcome() { ShowWelcome = false; _settings.WelcomeShown = true; _settings.Save(); }
    [RelayCommand] private void WelcomeAddSubscription() { CloseWelcome(); OpenAddDialog(); }

    [ObservableProperty] private bool autoFailover = true;
    partial void OnAutoFailoverChanged(bool v) { _settings.AutoFailover = v; _settings.Save(); }
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsDelay3), nameof(IsDelay5), nameof(IsDelay10), nameof(IsDelay30))]
    private int reconnectDelaySec = 5;
    public bool IsDelay3 => ReconnectDelaySec == 3;
    public bool IsDelay5 => ReconnectDelaySec == 5;
    public bool IsDelay10 => ReconnectDelaySec == 10;
    public bool IsDelay30 => ReconnectDelaySec == 30;
    [RelayCommand] private void SetReconnectDelay(string v)
    { ReconnectDelaySec = int.Parse(v); _settings.ReconnectDelaySec = ReconnectDelaySec; _settings.Save(); }

    [ObservableProperty] private bool notifyOnUpdate;
    partial void OnNotifyOnUpdateChanged(bool value) { _settings.NotifyOnUpdate = value; _settings.Save(); }

    // ===== Notifications =====
    // Every switch pushes into Notifier, which is what the call sites ask — nothing reads settings
    // at notify time, so a stale flag cannot leak a popup the user turned off.
    [ObservableProperty] private bool notificationsEnabled = true;
    partial void OnNotificationsEnabledChanged(bool v) { _settings.NotificationsEnabled = v; _settings.Save(); Notifier.Enabled = v; }
    [ObservableProperty] private bool trayBalloons = true;
    partial void OnTrayBalloonsChanged(bool v) { _settings.TrayBalloons = v; _settings.Save(); Notifier.UseBalloons = v; }
    [ObservableProperty] private bool notifyConnection;
    partial void OnNotifyConnectionChanged(bool v) { _settings.NotifyConnection = v; _settings.Save(); Notifier.OnConnection = v; }
    [ObservableProperty] private bool notifyAppUpdate = true;
    partial void OnNotifyAppUpdateChanged(bool v) { _settings.NotifyAppUpdate = v; _settings.Save(); Notifier.OnAppUpdate = v; }

    [ObservableProperty] private bool updateSubsOnStart;
    partial void OnUpdateSubsOnStartChanged(bool value) { _settings.UpdateSubsOnStart = value; _settings.Save(); }
    [ObservableProperty] private bool pingOnStart = true;
    partial void OnPingOnStartChanged(bool value) { _settings.PingOnStart = value; _settings.Save(); }
    [ObservableProperty] private bool sendHwid = true;
    partial void OnSendHwidChanged(bool value) { _settings.SendHwid = value; _settings.Save(); ApplyHwid(); }
    [ObservableProperty] private bool showSubHeader = true;
    partial void OnShowSubHeaderChanged(bool value) { _settings.ShowSubHeader = value; _settings.Save(); }
    /// <summary>Warned already this run — one notice per subscription per condition, not per refresh.</summary>
    private readonly HashSet<string> _warned = new();

    /// <summary>
    /// The two "your plan is running out" notices. Both were settings with nothing behind them:
    /// the switches saved fine and nobody ever looked at them.
    /// </summary>
    private void WarnAboutSubscription(SubscriptionVM sub, SubscriptionInfo? info)
    {
        if (info is null) return;

        if (NotifyExpiry && info.DaysLeft is { } days && days <= ExpiryNotifyDays && _warned.Add(sub.Url + "|exp"))
            Notifier.Show("Moon Internet", string.Format(Localization.Loc.T("S_VM_130"), sub.Name, days));

        if (NotifyTrafficLow && info.Total > 0)
        {
            double left = 1 - (double)info.Used / info.Total;
            if (left <= 0.10 && _warned.Add(sub.Url + "|traffic"))
                Notifier.Show("Moon Internet", string.Format(Localization.Loc.T("S_VM_131"), sub.Name, (int)Math.Round(left * 100)));
        }
    }

    [ObservableProperty] private bool notifyTrafficLow = true;
    partial void OnNotifyTrafficLowChanged(bool value) { _settings.NotifyTrafficLow = value; _settings.Save(); }
    [ObservableProperty] private bool notifyExpiry = true;
    partial void OnNotifyExpiryChanged(bool value) { _settings.NotifyExpiry = value; _settings.Save(); }
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsExp1), nameof(IsExp3), nameof(IsExp5), nameof(IsExp7))]
    private int expiryNotifyDays = 3;
    public bool IsExp1 => ExpiryNotifyDays == 1;
    public bool IsExp3 => ExpiryNotifyDays == 3;
    public bool IsExp5 => ExpiryNotifyDays == 5;
    public bool IsExp7 => ExpiryNotifyDays == 7;
    [RelayCommand] private void SetExpiryDays(string d) { ExpiryNotifyDays = int.Parse(d); _settings.ExpiryNotifyDays = ExpiryNotifyDays; _settings.Save(); }

    private DispatcherTimer? _autoUpdateTimer;
    private void RestartAutoUpdateTimer()
    {
        _autoUpdateTimer?.Stop();
        if (!AutoUpdateSubs || Subscriptions.Count == 0) return;
        _autoUpdateTimer ??= new DispatcherTimer();
        _autoUpdateTimer.Interval = TimeSpan.FromMinutes(Math.Max(1, EffectiveUpdateMin));
        _autoUpdateTimer.Tick -= AutoUpdateTick; _autoUpdateTimer.Tick += AutoUpdateTick;
        _autoUpdateTimer.Start();
    }
    private async void AutoUpdateTick(object? s, EventArgs e) { await RefreshAll(); if (NotifyOnUpdate) Status = Localization.Loc.T("S_VM_209"); }

    private void ApplyHwid()
    {
        if (SendHwid) { _settings.Hwid ??= Guid.NewGuid().ToString("N"); _settings.Save(); SubscriptionService.Hwid = _settings.Hwid; }
        else SubscriptionService.Hwid = null;
    }

    [RelayCommand]
    private void Navigate(string page)
    {
        CurrentPage = Enum.Parse<AppPage>(page, ignoreCase: true);
        if (CurrentPage == AppPage.Settings) { SettingsPage = "hub"; _settingsBack.Clear(); }   // tapping the Settings tab lands on the hub
    }

    [RelayCommand] private void SelectServer(ServerItem? s) { if (s is not null) SelectedServer = s; }

    [RelayCommand]
    private void SelectRouting(RoutingProfile profile)
    {
        SelectedRouting = profile;
        _settings.RoutingChoice = $"{profile.Source}:{profile.Name}";
        _settings.Save();
        ReconnectIfConnected(); // switching routing while connected → reconnect with it
    }

    [RelayCommand]
    private void ToggleEdit() { EditLayout = !EditLayout; if (!EditLayout) SaveLayout(); }

    [RelayCommand]
    private void ResetLayout()
    {
        ModeBlock.Set(LayoutDefaults["mode"]); StatsBlock.Set(LayoutDefaults["stats"]);
        RingBlock.Set(LayoutDefaults["ring"]); SubBlock.Set(LayoutDefaults["sub"]);
        SaveLayout();
    }

    public void SaveLayout() => LayoutStore.Save(new()
    {
        ["mode"] = ModeBlock.ToArray(), ["stats"] = StatsBlock.ToArray(),
        ["ring"] = RingBlock.ToArray(), ["sub"] = SubBlock.ToArray(),
    });

    // ===== Theme / appearance (live-applied, saved to theme.json) =====
    public string[] Fonts { get; } = { "Segoe UI", "Arial", "Verdana", "Tahoma", "Georgia", "Consolas", "Comic Sans MS", "Trebuchet MS" };
    public IReadOnlyList<Theme> Presets => Theme.Presets;
    public string[] AccentSwatches { get; } = { "#4C82FF", "#A66CFF", "#9D7BFF", "#34D399", "#FF7A59", "#F5C042", "#FF6B8A", "#22D3EE" };
    public string[] BgSwatches { get; } = { "#0D0A18", "#0C0820", "#07120E", "#160A0E", "#0E0F12", "#0B0D11", "#101018", "#141414" };
    public string[] TextSwatches { get; } = { "#ECE9F5", "#FFFFFF", "#E8EAF0", "#D8DCE8", "#C9CEDA", "#B7BECC" };

    [ObservableProperty] private string accentHex = App.ActiveTheme.Accent;
    [ObservableProperty] private string bgHex = App.ActiveTheme.WinBg1;
    [ObservableProperty] private string textHex = App.ActiveTheme.Text;
    [ObservableProperty] private double uiOpacity = App.ActiveTheme.WindowOpacity;
    [ObservableProperty] private string selectedFont = App.ActiveTheme.FontFamily;
    [ObservableProperty] private double bgOpacity = App.ActiveTheme.BackgroundOpacity;
    [ObservableProperty] private string backgroundInfo =
        App.ActiveTheme.BackgroundImage is null ? Localization.Loc.T("S_VM_239") : Path.GetFileName(App.ActiveTheme.BackgroundImage);

    private void ApplyTheme() { ThemeService.Apply(App.ActiveTheme); ThemeStore.Save(App.ActiveTheme); }
    private void RefreshThemeFields()
    {
        AccentHex = App.ActiveTheme.Accent;
        BgHex = App.ActiveTheme.WinBg1;
        TextHex = App.ActiveTheme.Text;
        UiOpacity = App.ActiveTheme.WindowOpacity;
        SelectedFont = App.ActiveTheme.FontFamily;
        BgOpacity = App.ActiveTheme.BackgroundOpacity;
        BackgroundInfo = App.ActiveTheme.BackgroundImage is null ? Localization.Loc.T("S_VM_239") : Path.GetFileName(App.ActiveTheme.BackgroundImage);
    }

    private static bool IsHex(string? s) => System.Text.RegularExpressions.Regex.IsMatch(s ?? "", "^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");
    private static string Darken(string hex, double amt)
    {
        try
        {
            var c = (System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(hex)!;
            byte f(byte b) => (byte)Math.Clamp(b * (1 - amt), 0, 255);
            return $"#{f(c.R):X2}{f(c.G):X2}{f(c.B):X2}";
        }
        catch { return hex; }
    }

    partial void OnSelectedFontChanged(string value) { if (!_themeReady) return; App.ActiveTheme.FontFamily = value; ApplyTheme(); }
    partial void OnBgOpacityChanged(double value) { if (!_themeReady) return; App.ActiveTheme.BackgroundOpacity = value; ApplyTheme(); }
    partial void OnUiOpacityChanged(double value) { if (!_themeReady) return; App.ActiveTheme.WindowOpacity = value; ApplyTheme(); }
    partial void OnAccentHexChanged(string value)
    {
        if (!_themeReady || !IsHex(value)) return;
        if (!string.Equals(value, App.ActiveTheme.Accent, StringComparison.OrdinalIgnoreCase))
        { App.ActiveTheme.Accent = value; ApplyTheme(); }
    }
    partial void OnBgHexChanged(string value)
    {
        if (!_themeReady || !IsHex(value)) return;
        if (!string.Equals(value, App.ActiveTheme.WinBg1, StringComparison.OrdinalIgnoreCase))
        { App.ActiveTheme.WinBg1 = value; App.ActiveTheme.WinBg2 = Darken(value, 0.22); ApplyTheme(); }
    }
    partial void OnTextHexChanged(string value)
    {
        if (!_themeReady || !IsHex(value)) return;
        if (!string.Equals(value, App.ActiveTheme.Text, StringComparison.OrdinalIgnoreCase))
        { App.ActiveTheme.Text = value; ApplyTheme(); }
    }

    [RelayCommand] private void SetAccent(string hex) { AccentHex = hex; }
    [RelayCommand] private void SetBgColor(string hex) { BgHex = hex; }
    [RelayCommand] private void SetTextColor(string hex) { TextHex = hex; }
    [RelayCommand] private void SetFont(string family) { SelectedFont = family; }

    [RelayCommand]
    private void ApplyPreset(Theme preset)
    {
        var img = App.ActiveTheme.BackgroundImage; // keep the user's background + moon art across preset swaps
        var (mon, moff, nav) = (App.ActiveTheme.MoonOnImage, App.ActiveTheme.MoonOffImage, App.ActiveTheme.NavMoonImage);
        App.ActiveTheme.CopyFrom(preset);
        App.ActiveTheme.BackgroundImage = img;
        (App.ActiveTheme.MoonOnImage, App.ActiveTheme.MoonOffImage, App.ActiveTheme.NavMoonImage) = (mon, moff, nav);
        RefreshThemeFields();
        ApplyTheme();
    }

    [RelayCommand]
    private void PickBackground()
    {
        var dlg = new OpenFileDialog { Filter = "Изображения|*.png;*.jpg;*.jpeg;*.bmp;*.webp;*.gif" };
        if (dlg.ShowDialog() == true) { App.ActiveTheme.BackgroundImage = dlg.FileName; BackgroundInfo = Path.GetFileName(dlg.FileName); ApplyTheme(); }
    }

    [RelayCommand]
    private void ClearBackground() { App.ActiveTheme.BackgroundImage = null; BackgroundInfo = Localization.Loc.T("S_VM_239"); ApplyTheme(); }

    // ===== Custom connect-button / nav moon images (overlay the built-in vector when set) =====
    public ImageSource? MoonOffImage => LoadImg(App.ActiveTheme.MoonOffImage) ?? LoadImg("pack://application:,,,/Assets/btn_off.png");
    public ImageSource? MoonOnImage  => LoadImg(App.ActiveTheme.MoonOnImage)  ?? LoadImg("pack://application:,,,/Assets/btn_on.png");
    public ImageSource? NavMoonImage => LoadImg(App.ActiveTheme.NavMoonImage) ?? LoadImg("pack://application:,,,/Assets/nav.png");
    [ObservableProperty] private string moonOnInfo  = ImgName(App.ActiveTheme.MoonOnImage);
    [ObservableProperty] private string moonOffInfo = ImgName(App.ActiveTheme.MoonOffImage);
    [ObservableProperty] private string navMoonInfo = ImgName(App.ActiveTheme.NavMoonImage);

    private static string ImgName(string? p) => string.IsNullOrEmpty(p) ? Localization.Loc.T("S_VM_240") : Path.GetFileName(p);
    private static ImageSource? LoadImg(string? path)
    {
        if (string.IsNullOrEmpty(path)) return null;
        try
        {
            if (!path.StartsWith("pack:") && !File.Exists(path)) return null;
            var b = new BitmapImage();
            b.BeginInit(); b.CacheOption = BitmapCacheOption.OnLoad; b.UriSource = new Uri(path); b.EndInit(); b.Freeze();
            return b;
        }
        catch { return null; }
    }
    private void RefreshMoonImages()
    {
        OnPropertyChanged(nameof(MoonOffImage)); OnPropertyChanged(nameof(MoonOnImage)); OnPropertyChanged(nameof(NavMoonImage));
        MoonOnInfo = ImgName(App.ActiveTheme.MoonOnImage);
        MoonOffInfo = ImgName(App.ActiveTheme.MoonOffImage);
        NavMoonInfo = ImgName(App.ActiveTheme.NavMoonImage);
    }
    private void PickMoon(Action<string> set)
    {
        var dlg = new OpenFileDialog { Filter = "Изображения|*.png;*.jpg;*.jpeg;*.webp" };
        if (dlg.ShowDialog() == true) { set(dlg.FileName); ApplyTheme(); RefreshMoonImages(); }
    }
    [RelayCommand] private void PickMoonOn()  => PickMoon(p => App.ActiveTheme.MoonOnImage = p);
    [RelayCommand] private void PickMoonOff() => PickMoon(p => App.ActiveTheme.MoonOffImage = p);
    [RelayCommand] private void PickNavMoon() => PickMoon(p => App.ActiveTheme.NavMoonImage = p);
    [RelayCommand] private void ResetMoonImages()
    {
        App.ActiveTheme.MoonOnImage = App.ActiveTheme.MoonOffImage = App.ActiveTheme.NavMoonImage = null;
        ApplyTheme(); RefreshMoonImages();
    }

    [RelayCommand]
    private void ExportTheme()
    {
        var dlg = new SaveFileDialog { Filter = "Тема Moon|*.json", FileName = "moon-theme.json" };
        if (dlg.ShowDialog() == true) { try { File.WriteAllText(dlg.FileName, ThemeStore.Export(App.ActiveTheme)); } catch { } }
    }

    [RelayCommand]
    private void ImportTheme()
    {
        var dlg = new OpenFileDialog { Filter = "Тема Moon|*.json" };
        if (dlg.ShowDialog() == true && ThemeStore.Import(SafeRead(dlg.FileName)) is { } t)
        { App.ActiveTheme.CopyFrom(t); RefreshThemeFields(); ApplyTheme(); }
    }

    [RelayCommand]
    private void ResetTheme() { App.ActiveTheme.CopyFrom(new Theme { Version = 2 }); RefreshThemeFields(); ApplyTheme(); }

    private static string SafeRead(string path) { try { return File.ReadAllText(path); } catch { return ""; } }

    // ===== Per-server config (JSON) dialog — opened from a server row's "⋯" =====
    [ObservableProperty] private bool showConfigDialog;
    [ObservableProperty] private string configDialogTitle = "";
    [ObservableProperty] private string configDialogJson = "";
    [RelayCommand] private void ShowServerConfig(ServerItem? s)
    {
        if (s is null) return;
        ConfigDialogTitle = s.Label; ConfigDialogJson = s.ConfigJson; ShowConfigDialog = true;
    }
    [RelayCommand] private void CloseConfigDialog() => ShowConfigDialog = false;
    [RelayCommand] private void CopyConfigJson() { try { System.Windows.Clipboard.SetText(ConfigDialogJson); Status = Localization.Loc.T("S_VM_210"); } catch { } }

    // ===== QR-code dialog (server share link / subscription URL) =====
    [ObservableProperty] private bool showQrDialog;
    [ObservableProperty] private string qrTitle = "";
    [ObservableProperty] private string qrSubtitle = "";
    [ObservableProperty] private ImageSource? qrImage;
    [RelayCommand] private void CloseQrDialog() => ShowQrDialog = false;
    private void ShowQr(string title, string? url)
    {
        if (string.IsNullOrWhiteSpace(url)) { Status = Localization.Loc.T("S_VM_211"); return; }
        if (Qr.Make(url) is not { } img) { Status = Localization.Loc.T("S_VM_212"); return; }
        QrTitle = title; QrSubtitle = url!; QrImage = img; ShowQrDialog = true;
    }

    // ===== per-server "⋯" actions =====
    [RelayCommand] private void CopyServerUrl(ServerItem? s)
    {
        if (s?.ShareUrl is not { } u) { Status = Localization.Loc.T("S_VM_213"); return; }
        try { System.Windows.Clipboard.SetText(u); Status = Localization.Loc.T("S_VM_214"); } catch { }
    }
    [RelayCommand] private void ShowServerQr(ServerItem? s) { if (s is not null) ShowQr(s.Label, s.ShareUrl); }
    [RelayCommand] private void ToggleFavorite(ServerItem? s)
    {
        if (s?.ShareUrl is not { } key) return;
        s.IsFavorite = !s.IsFavorite;
        if (s.IsFavorite) { if (!_settings.FavoriteServers.Contains(key)) _settings.FavoriteServers.Add(key); }
        else _settings.FavoriteServers.Remove(key);
        _settings.Save();
        foreach (var sub in Subscriptions) sub.Refresh();   // re-sort so favourites float up when in "Избранное" order
    }
    [RelayCommand] private async Task PingServerItem(ServerItem? s)
    {
        if (s is null) return;
        int? outIf = _conn.State == ConnectionState.Connected && _conn.ActiveMode == TunnelMode.Tun ? Pinger.PhysicalIfIndex() : null;
        s.Ping = await ProbeAsync(s, outIf);
        OnPropertyChanged(nameof(CheckPingText)); OnPropertyChanged(nameof(HasCheckPing));
    }

    // ===== action sheets: in-window menus (a WPF ContextMenu is its own window and spills outside the app) =====
    [ObservableProperty] private bool showServerSheet;
    [ObservableProperty] private ServerItem? sheetServer;
    [RelayCommand] private void OpenServerSheet(ServerItem? s) { if (s is null) return; SheetServer = s; ShowServerSheet = true; }
    [RelayCommand] private void CloseServerSheet() => ShowServerSheet = false;

    [ObservableProperty] private bool showSubSheet;
    [ObservableProperty] private SubscriptionVM? sheetSub;
    [RelayCommand] private void OpenSubSheet(SubscriptionVM? s) { if (s is null) return; SheetSub = s; ShowSubSheet = true; }
    [RelayCommand] private void CloseSubSheet() => ShowSubSheet = false;

    // sheet actions (close the sheet, then run the real command on the captured item)
    [RelayCommand] private void SheetToggleFavorite() { ShowServerSheet = false; ToggleFavorite(SheetServer); }
    [RelayCommand] private async Task SheetPing() { ShowServerSheet = false; await PingServerItem(SheetServer); }
    [RelayCommand] private void SheetServerConfig() { ShowServerSheet = false; ShowServerConfig(SheetServer); }
    [RelayCommand] private void SheetCopyServerUrl() { ShowServerSheet = false; CopyServerUrl(SheetServer); }
    [RelayCommand] private void SheetServerQr() { ShowServerSheet = false; ShowServerQr(SheetServer); }
    [RelayCommand] private void SheetSubSettings() { ShowSubSheet = false; if (SheetSub is { } s) OpenEditDialog(s); }
    [RelayCommand] private void SheetCopySubUrl() { ShowSubSheet = false; CopySubUrl(SheetSub); }
    [RelayCommand] private void SheetSubQr() { ShowSubSheet = false; ShowSubQr(SheetSub); }
    [RelayCommand] private void SheetDeleteSub() { ShowSubSheet = false; DeleteSubscription(SheetSub); }

    /// <summary>Latency probe for one server. WireGuard/AmneziaWG endpoints are UDP-only, so a TCP connect would
    /// always read as a timeout — use ICMP for those and the configured method for everything else.</summary>
    private async Task<int> ProbeAsync(ServerItem s, int? outIf)
    {
        if (s.Profile.Protocol != ProtocolType.Wireguard)
            return await Pinger.MeasureAsync(PingMethod, s.Profile.Address, s.Profile.Port, PingTimeoutMs, outIf);
        // WireGuard/AmneziaWG: the endpoint is UDP-only and the server usually drops ICMP too, so there's nothing
        // that replies on the VPN port itself. Race several probes against the SAME host and take the first answer:
        // ICMP echo, plus TCP on ports that commonly answer (22/443/80 — an open port OR an RST both give a real RTT).
        // Nothing answered → "—" (unknown), never "✕", which would wrongly read as "server down".
        int timeout = Math.Min(PingTimeoutMs, 3000);
        var probes = new List<Task<int>>
        {
            Pinger.IcmpLatencyAsync(s.Profile.Address, timeout),
            Pinger.RstLatencyAsync(s.Profile.Address, 22, timeout),
            Pinger.RstLatencyAsync(s.Profile.Address, 443, timeout),
            Pinger.RstLatencyAsync(s.Profile.Address, 80, timeout),
        };
        while (probes.Count > 0)
        {
            var done = await Task.WhenAny(probes);
            probes.Remove(done);
            int ms = await done;
            if (ms >= 0) return ms;
        }
        return -2;
    }

    // ===== per-subscription "⋯" actions =====
    [RelayCommand] private void CopySubUrl(SubscriptionVM? sub)
    {
        if (sub is null) return;
        try { System.Windows.Clipboard.SetText(sub.Url); Status = Localization.Loc.T("S_VM_215"); } catch { }
    }
    [RelayCommand] private void ShowSubQr(SubscriptionVM? sub) { if (sub is not null) ShowQr(sub.Name, sub.Url); }

    // ===== Offline cache: keep the last good fetch on disk so subscriptions/servers show with no internet =====
    private void SaveSubCache(SubscriptionVM sub)
    {
        var entry = _settings.CachedSubs.FirstOrDefault(c => c.Url == sub.Url);
        if (entry is null) { entry = new CachedSub { Url = sub.Url }; _settings.CachedSubs.Add(entry); }
        entry.Name = sub.Name;
        entry.Announcement = sub.Announcement;
        entry.TrafficText = sub.TrafficText;
        entry.ExpiryText = sub.ExpiryText;
        entry.Links = sub.Servers.Select(s => s.ShareUrl).Where(u => !string.IsNullOrEmpty(u)).Select(u => u!).ToList();
        entry.FetchedAt = DateTimeOffset.Now;
        _settings.Save();
    }

    /// <summary>Rebuild subscriptions from the on-disk cache — runs before any network call, so the UI is
    /// populated instantly and still works fully offline.</summary>
    private void LoadCachedSubs()
    {
        foreach (var c in _settings.CachedSubs.Where(c => c.Links.Count > 0))
        {
            if (Subscriptions.Any(s => s.Url == c.Url)) continue;
            var sub = new SubscriptionVM(c.Url, string.IsNullOrWhiteSpace(c.Name) ? HostOf(c.Url) : c.Name, FilterServer);
            var profiles = new List<OutboundProfile>();
            foreach (var link in c.Links)
            {
                // WireGuard/AmneziaWG configs need their own parser — ShareLinkParser only knows vless/trojan/…
                if (WireGuardParser.TryParseProfile(link, out var wp) && wp is not null) profiles.Add(wp);
                else if (ShareLinkParser.TryParse(link, out var p, out _) && p is not null) profiles.Add(p);
            }
            if (profiles.Count == 0) continue;
            sub.SetServers(profiles);
            foreach (var srv in sub.Servers) srv.IsFavorite = srv.ShareUrl is { } fu && _settings.FavoriteServers.Contains(fu);
            sub.SetAnnouncement(c.Announcement);
            sub.SetCachedInfo(c.TrafficText, c.ExpiryText);
            sub.ApplySort(ServerSort);
            Subscriptions.Add(sub);
        }
        if (Subscriptions.Count > 0)
        {
            RefreshHomeSummary();
            NotifyServerListChanged();
            SelectedServer ??= AllServers.FirstOrDefault(s => s.Label == _settings.LastServerName) ?? AllServers.FirstOrDefault();
        }
    }

    [RelayCommand]
    private void DeleteSubscription(SubscriptionVM? sub)
    {
        if (sub is null) return;
        Subscriptions.Remove(sub);
        _settings.SubscriptionUrls.Remove(sub.Url);
        _settings.CachedSubs.RemoveAll(c => c.Url == sub.Url);
        _settings.Save();
        if (SelectedServer is { } sel && sub.Servers.Contains(sel)) SelectedServer = AllServers.FirstOrDefault();
        RebuildRouting(); RefreshHomeSummary(); NotifyServerListChanged();
        Status = Localization.Loc.T("S_VM_216");
    }

    // ===== Add / edit subscription mini-dialog (Name + URL) =====
    [ObservableProperty] private bool showAddDialog;
    [ObservableProperty] private string newSubName = "";
    [ObservableProperty] private string dialogTitle = "";        // ditto
    private SubscriptionVM? _editingSub;

    [RelayCommand] private void OpenAddDialog() { _editingSub = null; DialogTitle = Localization.Loc.T("S_VM_100"); NewSubName = ""; SubscriptionUrl = ""; ShowAddDialog = true; }
    [RelayCommand] private void OpenEditDialog(SubscriptionVM sub)
    {
        _editingSub = sub; DialogTitle = Localization.Loc.T("S_VM_101"); NewSubName = sub.Name; SubscriptionUrl = sub.Url; ShowAddDialog = true;
    }
    [RelayCommand] private void CloseAddDialog() { ShowAddDialog = false; _editingSub = null; }

    [RelayCommand]
    private async Task ConfirmAdd()
    {
        var url = SubscriptionUrl.Trim();
        if (string.IsNullOrWhiteSpace(url)) { Status = Localization.Loc.T("S_VM_217"); return; }
        ShowAddDialog = false;
        if (_editingSub is { } es)                       // edit mode
        {
            if (!string.IsNullOrWhiteSpace(NewSubName)) es.Name = NewSubName.Trim();
            _editingSub = null;
            if (!string.Equals(url, es.Url, StringComparison.OrdinalIgnoreCase)) { RemoveSubscription(es); await Import(); }  // URL changed → replace
            else { NewSubName = ""; SubscriptionUrl = ""; RefreshHomeSummary(); NotifyServerListChanged(); }
            return;
        }
        await Import();
    }

    // Per-subscription actions (header buttons): ping just this sub / re-fetch just this sub.
    [RelayCommand] private async Task PingSubscription(SubscriptionVM? sub) { if (sub is not null) await PingSub(sub); }
    [RelayCommand] private async Task RefreshSubscription(SubscriptionVM? sub) { if (sub is not null) { NewSubName = ""; await ImportUrl(sub.Url); } }
    // Home global actions: ping / refresh every subscription.
    [RelayCommand] private async Task RefreshAll() { NewSubName = ""; foreach (var s in Subscriptions.ToList()) await ImportUrl(s.Url); }

    [RelayCommand]
    private async Task Import()
    {
        var url = SubscriptionUrl.Trim();
        if (string.IsNullOrWhiteSpace(url)) { Status = Localization.Loc.T("S_VM_218"); return; }
        await ImportUrl(url);
        if (!_settings.SubscriptionUrls.Contains(url)) { _settings.SubscriptionUrls.Add(url); _settings.Save(); }
        SubscriptionUrl = ""; NewSubName = "";
    }

    private async Task ImportUrl(string url)
    {
        try
        {
            Status = Localization.Loc.T("S_VM_219");
            var (content, info, title, announce, subUpdateMin) = await SubscriptionService.FetchFullAsync(url);
            if (subUpdateMin > 0) { _subUpdateMin = subUpdateMin; OnPropertyChanged(nameof(SubUpdateHint)); }
            // dialog name wins, else the panel's profile-title, else the host
            string name = !string.IsNullOrWhiteSpace(NewSubName) ? NewSubName.Trim()
                        : !string.IsNullOrWhiteSpace(title) ? title! : HostOf(url);

            var sub = Subscriptions.FirstOrDefault(s => s.Url == url);
            if (sub is null) { sub = new SubscriptionVM(url, name, FilterServer); Subscriptions.Add(sub); }
            else if (!string.IsNullOrWhiteSpace(NewSubName)) sub.Name = NewSubName.Trim();  // keep name on plain refresh
            sub.SetServers(content.Servers);
            foreach (var srv in sub.Servers) srv.IsFavorite = srv.ShareUrl is { } fu && _settings.FavoriteServers.Contains(fu);
            sub.SetInfo(info);
            WarnAboutSubscription(sub, info);
            sub.SetAnnouncement(announce);          // panel welcome/notice from the Announce header (overrides fake-node text)
            sub.SetRouting(content.Routing);
            sub.ApplySort(ServerSort);

            SaveSubCache(sub);                      // keep this fetch on disk for offline use
            RebuildRouting();                       // aggregate routing across subs (INCY priority)
            RefreshHomeSummary();
            NotifyServerListChanged();
            RestartAutoUpdateTimer();               // (re)arm periodic auto-refresh now that a subscription exists
            SelectedServer ??= sub.Servers.FirstOrDefault(s => s.Label == _settings.LastServerName) ?? sub.Servers.FirstOrDefault();
            Status = TotalServers > 0
                ? string.Format(Localization.Loc.T("S_VM_121"), TotalServers) + (SelectedRouting is not null ? $" · routing «{SelectedRouting.Name}»" : "")
                : Localization.Loc.T("S_VM_220");
            await MaybeAutoConnect(sub);
        }
        catch (Exception ex) { Status = "Ошибка загрузки: " + ex.Message; }
    }

    private async Task MaybeAutoConnect(SubscriptionVM sub)
    {
        // Auto-connect once, after the first subscription loads (ping first so "lowest ping" has data).
        if (AutoConnectOnStart && !_autoConnectTried && ConnectionState == ConnectionState.Disconnected && sub.Servers.Count > 0)
        {
            _autoConnectTried = true;
            await PingSub(sub);
            if (PickAutoServer() is { } target) { SelectedServer = target; await Connect(); }
            else Status = Localization.Loc.T("S_VM_221");
        }
        else if (PingOnStart) _ = PingSub(sub);
    }

    [RelayCommand]
    private void RemoveSubscription(SubscriptionVM sub)
    {
        Subscriptions.Remove(sub);
        _settings.SubscriptionUrls.Remove(sub.Url);
        _settings.Save();
        RebuildRouting();
        RefreshHomeSummary();
        NotifyServerListChanged();
    }

    // ===== paste / QR import (Home + Servers empty state) =====
    [RelayCommand]
    private async Task PasteImport()
    {
        string text = "";
        try { text = (System.Windows.Clipboard.GetText() ?? "").Trim(); } catch { /* clipboard busy/empty */ }
        if (string.IsNullOrWhiteSpace(text)) { Status = Localization.Loc.T("S_VM_222"); return; }
        await ImportFromText(text);
    }

    private async Task ImportFromText(string text)
    {
        // A subscription URL → fetch it (gets name/traffic/routing headers). Otherwise treat as inline links/base64.
        if (text.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || text.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            SubscriptionUrl = text;
            await Import();
            return;
        }
        // A full AmneziaWG config (vpn://… or a multi-line [Interface]/[Peer]) → one WG server.
        if (WireGuardParser.TryParseProfile(text, out var wgp) && wgp is not null)
        {
            if (wgp.Wireguard is { IsAmnezia: true })
            { Status = Localization.Loc.T("S_VM_223"); return; }
            var wsub = new SubscriptionVM("clipboard:" + Guid.NewGuid().ToString("N"), "AmneziaWG", FilterServer);
            Subscriptions.Add(wsub);
            wsub.SetServers(new[] { wgp });
            wsub.ApplySort(ServerSort);
            SaveSubCache(wsub);                     // persist it — pasted servers must survive a restart too
            RebuildRouting(); RefreshHomeSummary(); NotifyServerListChanged();
            SelectedServer ??= wsub.Servers.FirstOrDefault();
            Status = Localization.Loc.T("S_VM_224");
            _ = PingSub(wsub);
            return;
        }
        var content = SubscriptionParser.ParseFull(text);
        if (content.Servers.Count == 0) { Status = Localization.Loc.T("S_VM_225"); return; }

        var sub = new SubscriptionVM("clipboard:" + Guid.NewGuid().ToString("N"), "Из буфера обмена", FilterServer);
        Subscriptions.Add(sub);
        sub.SetServers(content.Servers);
        sub.SetRouting(content.Routing);
        sub.ApplySort(ServerSort);
        RebuildRouting();
        RefreshHomeSummary();
        NotifyServerListChanged();
        SelectedServer ??= sub.Servers.FirstOrDefault();
        Status = $"Импортировано из буфера: {content.Servers.Count}";
        _ = PingSub(sub);
    }

    [RelayCommand]
    private void ScanQr() =>
        // ponytail: QR-image decoding needs a decoder library that can't be fetched in this offline build.
        Status = Localization.Loc.T("S_VM_226");

    private void RebuildRouting()
    {
        AvailableRoutings.Clear();
        foreach (var r in _installedRoutings) AvailableRoutings.Add(r);              // INCY + HAPP (installed) first
        foreach (var s in Subscriptions) foreach (var r in s.Routing)                // then subscription routings
            if (AvailableRoutings.Count < 10 && !AvailableRoutings.Any(x => x.Source == r.Source && x.Name == r.Name))
                AvailableRoutings.Add(r);
        while (AvailableRoutings.Count > 10) AvailableRoutings.RemoveAt(AvailableRoutings.Count - 1);   // store up to 10
        AvailableRoutings.Add(CustomRouting);                                          // always offer the user's own profile
        // Honour the user's saved choice; otherwise default to INCY.
        SelectedRouting = AvailableRoutings.FirstOrDefault(r => $"{r.Source}:{r.Name}" == _settings.RoutingChoice)
                          ?? AvailableRoutings.FirstOrDefault(r => r.Source == RoutingSource.Incy)
                          ?? AvailableRoutings.FirstOrDefault();
        _syncingRoutingToggle = true;
        RoutingUseHapp = SelectedRouting?.Source == RoutingSource.Happ;   // reflect the selection in the toggle
        _syncingRoutingToggle = false;
        OnPropertyChanged(nameof(HasMultipleRoutings));
        OnPropertyChanged(nameof(HasRoutings));
        OnPropertyChanged(nameof(HasHappRouting));
        OnPropertyChanged(nameof(HasIncyRouting));
        OnPropertyChanged(nameof(ShowRoutingToggle));
    }

    private void RefreshHomeSummary()
    {
        var primary = Subscriptions.FirstOrDefault();
        SubscriptionName = primary?.Name ?? Localization.Loc.T("S_VM_031");
        SubscriptionTraffic = primary?.TrafficText ?? "—";
        SubscriptionExpiry = primary?.ExpiryText ?? "∞";
    }

    private static string HostOf(string url) { try { return new Uri(url).Host; } catch { return url; } }

    // AllowConcurrentExecutions: without it AsyncRelayCommand disables itself for the whole "Connecting"
    // await, so the button greys out and the cancel-click never lands. We WANT the re-entrant click.
    [RelayCommand(AllowConcurrentExecutions = true)]
    private async Task Connect()
    {
        if (ConnectionState == ConnectionState.Connecting) { ConnectingStatus = Localization.Loc.T("S_VM_227"); Elapsed = "00:00"; _conn.CancelConnect(); return; }
        if (ConnectionState == ConnectionState.Connected)
        {
            if (Disconnecting) return;
            Disconnecting = true;
            Status = Localization.Loc.T("S_VM_228");
            await Task.Delay(500);                        // let Localization.Loc.T("S_VM_228") show before/while we tear down
            await Task.Run(() => _conn.Disconnect());     // teardown off the UI thread so the ring stays live
            Disconnecting = false;
            Status = Localization.Loc.T("S_VM_229"); Elapsed = "00:00";
            return;
        }
        if (SelectedServer is null) { Status = Localization.Loc.T("S_VM_230"); CurrentPage = AppPage.Servers; return; }
        if (!_conn.CoreAvailable) { Status = Localization.Loc.T("S_VM_231"); return; }
        try
        {
            var routing = UseRouting ? SelectedRouting : null;
            // Don't make the user wait for the (big, first-time) geo download: connect NOW without routing, then
            // quietly re-apply it once geo finishes downloading in the background.
            bool deferRouting = routing is not null && !GeoService.IsReady();
            Status = deferRouting || routing is null ? $"Подключение к {SelectedServer.Label}…"
                                                     : $"Подключение к {SelectedServer.Label}… (загрузка geo)";
            await _conn.ConnectAsync(SelectedServer.Profile, TunMode ? TunnelMode.Tun : TunnelMode.SystemProxy, deferRouting ? null : routing);
            if (ConnectionState != ConnectionState.Connected) { if (Status == $"Подключение к {SelectedServer.Label}…" || Status.StartsWith("Подключение")) Status = Localization.Loc.T("S_VM_232"); return; } // cancelled/aborted
            _connectedAt = DateTime.Now; ResetTraffic();
            string modeLabel = _conn.ActiveMode == TunnelMode.Tun ? "TUN" : "системный прокси";
            string routingLabel = _conn.RoutingNote is not null ? " · " + _conn.RoutingNote : "";
            Status = $"Подключено ({modeLabel}){routingLabel} · {SelectedServer.Label}";
            if (TunMode && _conn.ActiveMode != TunnelMode.Tun)
                Status = $"TUN-служба не установлена — включён системный прокси · {SelectedServer.Label}";
            _settings.LastServerName = SelectedServer.Label;
            _settings.TunMode = TunMode;
            _settings.Save();
            if (deferRouting) _ = ApplyRoutingWhenReady(routing!);   // finish geo in the background, then re-apply
        }
        catch (Exception ex) { Status = "Не удалось подключиться: " + ex.Message; }
    }

    // Background: finish the geo download, then quietly reconnect WITH routing — but only if still connected to the
    // same routing (user didn't switch/disconnect). Keeps first-connect instant while routing catches up.
    private async Task ApplyRoutingWhenReady(RoutingProfile routing)
    {
        var dir = await GeoService.EnsureAsync(routing);
        if (dir is null) return;                                                   // download failed — no routing
        if (ConnectionState == ConnectionState.Connected && UseRouting && SelectedRouting == routing)
        {
            Status = Localization.Loc.T("S_VM_233");
            ReconnectIfConnected();
        }
    }

    // Re-establish the tunnel with the CURRENT server/mode/routing. Called when the user flips a switch while
    // connected (TUN↔proxy, server, routing) — ConnectAsync tears the old one down first, so this is a clean swap.
    private bool _reconnecting;
    private CancellationTokenSource? _reconnectDebounce;
    // Coalesce a burst of switches (mode + server + routing changing together, or a list rebuild) into ONE
    // reconnect after a short quiet period. Before this, each change fired its own STOP/START and the rapid
    // cycling killed xray (seen in the log: 3 reconnects in 5 s → dead tunnel).
    private void ReconnectIfConnected()
    {
        if (ConnectionState != ConnectionState.Connected) return;
        _reconnectDebounce?.Cancel();
        var cts = new CancellationTokenSource();
        _reconnectDebounce = cts;
        _ = DebouncedReconnect(cts.Token);
    }
    private async Task DebouncedReconnect(CancellationToken ct)
    {
        try { await Task.Delay(600, ct); } catch { return; }   // let the burst settle
        while (_reconnecting && !ct.IsCancellationRequested) { try { await Task.Delay(150, ct); } catch { return; } }
        if (ct.IsCancellationRequested || ConnectionState != ConnectionState.Connected) return;
        await ReconnectAsync();
    }
    private async Task ReconnectAsync()
    {
        if (SelectedServer is null) return;
        _reconnecting = true;
        try
        {
            ReSleeping = true;                     // graceful Localization.Loc.T("S_VM_234") beat so the switch isn't abrupt
            Status = Localization.Loc.T("S_VM_234");
            await Task.Delay(900);
            ReSleeping = false;

            var routing = UseRouting ? SelectedRouting : null;
            Status = $"Переподключение к {SelectedServer.Label}…";
            await _conn.ConnectAsync(SelectedServer.Profile, TunMode ? TunnelMode.Tun : TunnelMode.SystemProxy, routing);
            if (ConnectionState != ConnectionState.Connected) { Status = Localization.Loc.T("S_VM_229"); return; }
            _connectedAt = DateTime.Now; ResetTraffic();
            string modeLabel = _conn.ActiveMode == TunnelMode.Tun ? "TUN" : "системный прокси";
            string routingLabel = _conn.RoutingNote is not null ? " · " + _conn.RoutingNote : "";
            Status = $"Подключено ({modeLabel}){routingLabel} · {SelectedServer.Label}";
            _settings.LastServerName = SelectedServer.Label; _settings.Save();
        }
        catch (Exception ex) { Status = "Не удалось переподключиться: " + ex.Message; }
        finally { _reconnecting = false; ReSleeping = false; }
    }

    [RelayCommand]
    private async Task PingAll()
    {
        foreach (var s in Subscriptions.ToList()) await PingSub(s);
    }

    // "Проверить соединение" (shown only while connected): ping the active server, show its latency next to the button.
    /// <summary>
    /// The pill next to the connect button. Derived from the selected server's own reading rather
    /// than stored separately — two copies of the same number drifted apart the moment a batch
    /// ping refreshed the list and left the pill on its old value.
    /// </summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CheckPingText), nameof(HasCheckPing), nameof(CheckPingTrayText))]
    private bool checkPinging;

    public string CheckPingText => ConnectionState != ConnectionState.Connected ? ""
        : CheckPinging ? "…"
        : SelectedServer is { } s2 && s2.Ping != -2 ? (s2.Ping >= 0 ? $"{s2.Ping} ms" : "✕")
        : "";
    public bool HasCheckPing => !string.IsNullOrEmpty(CheckPingText);
    /// <summary>Tray label for the connection check — carries the measured ping once we have it.</summary>
    public string CheckPingTrayText => string.IsNullOrEmpty(CheckPingText) ? Localization.Loc.T("S_VM_110") : string.Format(Localization.Loc.T("S_VM_111"), CheckPingText);
    [RelayCommand]
    private async Task CheckConnection()
    {
        if (SelectedServer is null || CheckPinging) return;
        var server = SelectedServer;
        CheckPinging = true;
        try
        {
            // Off the dispatcher, all of it. PhysicalIfIndex walks every adapter, which is slow
            // enough on its own to freeze the window — the batch ping was moved off for the same
            // reason and this one was missed.
            int ms = await Task.Run(async () =>
            {
                int? outIf = Pinger.PhysicalIfIndex();
                return await ProbeAsync(server, outIf).ConfigureAwait(false);
            }).ConfigureAwait(true);
            server.Ping = ms;
            _lastCheckPingMs = ms;          // the learner pairs this with the next speed sample
            OnPropertyChanged(nameof(CheckPingText)); OnPropertyChanged(nameof(HasCheckPing));
            OnPropertyChanged(nameof(CheckPingTrayText));
        }
        finally { CheckPinging = false; }
    }

    private async Task PingSub(SubscriptionVM sub)
    {
        sub.Pinging = true;
        try
        {
            if (PingMethod == "stability") { await StabilitySub(sub); return; }

            var servers = sub.Servers.ToList();
            foreach (var s in servers) s.Pinging = true;

            // The whole batch runs off the UI thread. Enumerating the NICs alone takes long enough
            // to drop frames, and thirty probes' worth of continuations landing on the dispatcher
            // is what made the window stop repainting mid-ping.
            await Task.Run(async () =>
            {
                // Always out the physical card, not only while OUR tunnel is up. A TUN stack
                // answers the handshake itself and every server then reads a fake 0-1 ms — and the
                // tunnel doing that is just as likely to be INCY's or HAPP's, running while we idle.
                int? outIf = Pinger.PhysicalIfIndex();

                using var gate = new SemaphoreSlim(PingParallel);
                var tasks = servers.Select(async (s, i) =>
                {
                    // Stagger: thirty handshakes in the same instant look like a port scan to some
                    // providers, and it is also the difference between rows filling in one by one
                    // and the whole list snapping to numbers before the spinners are even seen.
                    if (PingStagger && PingStaggerMs > 0) await Task.Delay(i * PingStaggerMs).ConfigureAwait(false);
                    await gate.WaitAsync().ConfigureAwait(false);
                    try { s.Ping = await ProbeAsync(s, outIf).ConfigureAwait(false); }
                    finally { gate.Release(); s.Pinging = false; }
                });
                await Task.WhenAll(tasks).ConfigureAwait(false);
            }).ConfigureAwait(true);
            sub.Refresh(); RefreshReachability();
            OnPropertyChanged(nameof(CheckPingText)); OnPropertyChanged(nameof(HasCheckPing));
            OnPropertyChanged(nameof(CheckPingTrayText));
        }
        finally { sub.Pinging = false; }
    }

    /// <summary>
    /// The "стабильность" method: one core, a real outbound per server, a real request through
    /// each. Slower than a handshake and the only one that can tell a working protocol from a
    /// port that merely accepts connections.
    /// </summary>
    private async Task StabilitySub(SubscriptionVM sub)
    {
        var servers = sub.Servers.ToList();
        if (servers.Count == 0) return;

        foreach (var s in servers) s.Pinging = true;
        try
        {
            var pinger = new StabilityPinger(CoreLocator.CoresDir());
            if (!pinger.CoreAvailable) { Status = Localization.Loc.T("S_VM_235"); return; }

            var results = await pinger.MeasureAsync(
                servers.Select(s => (s.Profile.Name ?? "", s.Profile)).ToList(),
                probeUrl: PingTestUrl,
                attempts: 3,
                timeoutMs: PingTimeoutMs,
                progress: new Progress<string>(m => Status = m));

            var byKey = results.ToDictionary(r => r.Key, r => r);
            foreach (var s in servers)
            {
                if (!byKey.TryGetValue(s.Profile.Name ?? "", out var r)) continue;
                // -2 keeps its "unknown" meaning: a protocol this core cannot carry was never
                // measured, and marking it down would be a claim we did not make.
                s.Ping = r.NotMeasured ? -2 : r.Ms;
            }
            Status = "";
        }
        finally
        {
            foreach (var s in servers) s.Pinging = false;
            Application.Current?.Dispatcher.Invoke(() => { sub.Refresh(); RefreshReachability(); });
        }
    }

    public void Shutdown() { try { _settings.Save(); } catch { } _conn.Disconnect(); }
}
