using System.Collections.ObjectModel;
using System.Runtime.InteropServices;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using MoonInternet.Core.Models;
using MoonInternet.Services;
using MoonInternet.Services.Platform;

namespace MoonInternet.Desktop.ViewModels;

/// <summary>
/// The cross-platform view model. Everything it talks to — parsing, subscriptions, connection,
/// ping, routing — comes from Core/Services, the same assemblies the Windows build uses.
/// </summary>
public partial class MainViewModel : ObservableObject
{
    private readonly ConnectionManager _conn = new(CoresDir);

    /// <summary>Where the xray/sing-box binaries live: next to the app, or in the XDG data dir.</summary>
    private static string CoresDir
    {
        get
        {
            var beside = Path.Combine(AppContext.BaseDirectory, "cores");
            if (Directory.Exists(beside)) return beside;
            return Path.Combine(PlatformIntegration.Current.DataDirectory, "cores");
        }
    }

    /// <summary>
    /// Ping concurrency cap. Firing every server at once hammers the provider's panel and
    /// balloons memory — the Windows build has the same limit for the same reason.
    /// </summary>
    private readonly SemaphoreSlim _pingGate = new(6);

    public MainViewModel()
    {
        SettingsHub = new ObservableCollection<HubEntry>(HubEntry.Default);
        RuleBuckets = new ObservableCollection<RuleBucket>(RuleBucket.Empty);
        _ = LoadAsync();
    }

    // ---- navigation ------------------------------------------------------
    [ObservableProperty] private string page = "Home";

    public bool IsHome => Page == "Home";
    public bool IsServers => Page == "Servers";
    public bool IsRouting => Page == "Routing";
    public bool IsSettings => Page == "Settings";

    partial void OnPageChanged(string value)
    {
        OnPropertyChanged(nameof(IsHome));
        OnPropertyChanged(nameof(IsServers));
        OnPropertyChanged(nameof(IsRouting));
        OnPropertyChanged(nameof(IsSettings));
        OnPropertyChanged(nameof(ServersTint));
        OnPropertyChanged(nameof(SettingsTint));
    }

    [RelayCommand] private void Navigate(string? target) => Page = target ?? "Home";

    public string ServersTint => IsServers ? "#ECE9F5" : "#9A93B5";
    public string SettingsTint => IsSettings || IsRouting ? "#ECE9F5" : "#9A93B5";

    // ---- settings pages --------------------------------------------------
    public ObservableCollection<HubEntry> SettingsHub { get; }

    [ObservableProperty] private string settingsPage = "hub";

    public bool IsSettingsHub => SettingsPage == "hub";
    public bool IsSettingsSubPage => SettingsPage != "hub";
    public bool IsPageConnection => SettingsPage == "connection";
    public bool IsPageAuto => SettingsPage == "auto";
    public bool IsPageSubs => SettingsPage == "subs";
    public bool IsPageAbout => SettingsPage == "about";
    public bool IsPagePlaceholder =>
        IsSettingsSubPage && !IsPageConnection && !IsPageAuto && !IsPageSubs && !IsPageAbout;

    public string SettingsPageTitle =>
        SettingsHub.FirstOrDefault(h => h.Id == SettingsPage)?.Title ?? "Настройки";

    partial void OnSettingsPageChanged(string value)
    {
        foreach (var p in new[]
                 {
                     nameof(IsSettingsHub), nameof(IsSettingsSubPage), nameof(IsPageConnection),
                     nameof(IsPageAuto), nameof(IsPageSubs), nameof(IsPageAbout),
                     nameof(IsPagePlaceholder), nameof(SettingsPageTitle),
                 })
        {
            OnPropertyChanged(p);
        }
    }

    [RelayCommand] private void OpenSettingsPage(string? id) => SettingsPage = id ?? "hub";
    [RelayCommand] private void SettingsBack() => SettingsPage = "hub";
    [RelayCommand] private void BackToRoutingSettings() { Page = "Settings"; SettingsPage = "routing"; }

    // ---- connection ------------------------------------------------------
    [ObservableProperty] private bool tunMode = true;
    [ObservableProperty] private string stateText = "Луна спит";
    [ObservableProperty] private string uploadSpeed = "—";
    [ObservableProperty] private string downloadSpeed = "—";
    [ObservableProperty] private string sessionTraffic = "—";
    [ObservableProperty] private string elapsedDisplay = "—";
    [ObservableProperty] private string checkPingText = "";
    [ObservableProperty] private bool isConnected;

    public bool ProxyMode
    {
        get => !TunMode;
        set => TunMode = !value;
    }

    partial void OnTunModeChanged(bool value) => OnPropertyChanged(nameof(ProxyMode));

    public bool HasCheckPing => !string.IsNullOrEmpty(CheckPingText);
    partial void OnCheckPingTextChanged(string value) => OnPropertyChanged(nameof(HasCheckPing));

    /// <summary>Moon art: full while connected, crescent while not — the same two files as on Windows.</summary>
    public Bitmap MoonImage => LoadAsset(IsConnected ? "moon_on.png" : "moon_off.png");

    partial void OnIsConnectedChanged(bool value)
    {
        StateText = value ? "Луна укрыла" : "Луна спит";
        OnPropertyChanged(nameof(MoonImage));
    }

    [RelayCommand]
    private async Task Connect()
    {
        if (IsConnected) { _conn.Disconnect(); IsConnected = false; return; }
        if (SelectedServer is null) { Status = "Сначала выберите сервер"; return; }

        StateText = "Луна просыпается…";
        try
        {
            await _conn.ConnectAsync(SelectedServer.Profile,
                                     TunMode ? TunnelMode.Tun : TunnelMode.SystemProxy,
                                     ActiveRouting);
            IsConnected = _conn.State == ConnectionState.Connected;
        }
        catch (Exception ex)
        {
            Status = $"Не удалось подключиться: {ex.Message}";
            StateText = "Луна спит";
        }
    }

    [RelayCommand]
    private async Task CheckConnection()
    {
        CheckPingText = "…";
        var ms = await ProbeUrlAsync("https://www.gstatic.com/generate_204");
        CheckPingText = ms < 0 ? "нет ответа" : $"{ms} ms";
    }

    // ---- servers & subscriptions ----------------------------------------
    public ObservableCollection<SubscriptionVM> Subscriptions { get; } = new();

    [ObservableProperty] private ServerItem? selectedServer;
    [ObservableProperty] private string status = "Готово";
    [ObservableProperty] private string search = "";

    public bool HasSelectedServer => SelectedServer is not null;
    public bool HasNoServers => Subscriptions.Count == 0;
    public string SelectedServerLabel => SelectedServer?.Label ?? "";
    public Bitmap? SelectedServerFlag => SelectedServer?.FlagImage;
    public string TotalServersText => $"{Subscriptions.Sum(s => s.Servers.Count)} серверов";

    partial void OnSelectedServerChanged(ServerItem? value)
    {
        OnPropertyChanged(nameof(HasSelectedServer));
        OnPropertyChanged(nameof(SelectedServerLabel));
        OnPropertyChanged(nameof(SelectedServerFlag));
    }

    public ObservableCollection<string> FilterChips { get; } = new() { "Все" };

    private async Task LoadAsync()
    {
        var url = LoadSavedUrl();
        if (!string.IsNullOrWhiteSpace(url)) await ImportUrl(url);
    }

    [RelayCommand] private async Task RefreshAll()
    {
        foreach (var s in Subscriptions.ToList()) await ImportUrl(s.Url);
    }

    private async Task ImportUrl(string url)
    {
        try
        {
            var (content, info, title, _, _) = await SubscriptionService.FetchFullAsync(url);
            var vm = new SubscriptionVM(url, title ?? new Uri(url).Host, content.Servers, info);

            var existing = Subscriptions.FirstOrDefault(s => s.Url == url);
            if (existing is not null) Subscriptions.Remove(existing);
            Subscriptions.Add(vm);

            SelectedServer ??= vm.Servers.FirstOrDefault();
            RebuildFilters();
            OnPropertyChanged(nameof(HasNoServers));
            OnPropertyChanged(nameof(TotalServersText));
            SaveUrl(url);
            await PingAll();
        }
        catch (Exception ex)
        {
            Status = $"Ошибка загрузки: {ex.Message}";
        }
    }

    private void RebuildFilters()
    {
        FilterChips.Clear();
        FilterChips.Add("Все");
        foreach (var p in Subscriptions.SelectMany(s => s.Servers)
                                       .Select(s => s.Protocol).Distinct().OrderBy(x => x))
        {
            FilterChips.Add(p);
        }
    }

    [RelayCommand]
    private async Task PingAll()
    {
        var tasks = Subscriptions.SelectMany(s => s.Servers).Select(async item =>
        {
            await _pingGate.WaitAsync();
            try { item.Ping = await ProbeAsync(item.Profile.Address, item.Profile.Port); }
            finally { _pingGate.Release(); }
        });
        await Task.WhenAll(tasks);
    }

    [RelayCommand] private void SetSort(string? mode) { /* ported with the servers page */ }
    [RelayCommand] private void OpenAddDialog() { /* dialog port pending */ }
    [RelayCommand] private void PasteImport() { /* clipboard port pending */ }

    // ---- routing ---------------------------------------------------------
    public ObservableCollection<RuleBucket> RuleBuckets { get; }

    [ObservableProperty] private string ruleSearch = "";
    [ObservableProperty] private string geoStatus = "";

    public RoutingProfile? ActiveRouting { get; private set; }

    public string GeoipInfo => FileInfoText(GeoService.GeoipFile);
    public string GeositeInfo => FileInfoText(GeoService.GeositeFile);

    [RelayCommand] private void SelectRoutingSource(string? src) { /* routing port pending */ }

    [RelayCommand]
    private async Task RefreshGeo()
    {
        GeoStatus = "Скачиваю…";
        try
        {
            await GeoService.EnsureAsync(ActiveRouting);
            GeoStatus = "Гео-файлы обновлены";
        }
        catch (Exception ex) { GeoStatus = $"Не удалось: {ex.Message}"; }

        OnPropertyChanged(nameof(GeoipInfo));
        OnPropertyChanged(nameof(GeositeInfo));
    }

    // ---- settings --------------------------------------------------------
    [ObservableProperty] private bool autoReconnect = true;
    [ObservableProperty] private bool blockUdp;
    [ObservableProperty] private bool autostart;
    [ObservableProperty] private bool autoConnectOnStart;
    [ObservableProperty] private bool updateSubsOnStart = true;
    [ObservableProperty] private bool pingOnStart = true;

    public string LocalProxyInfo => $"SOCKS5 127.0.0.1:{_conn.SocksPort} · HTTP 127.0.0.1:{_conn.HttpPort}";
    public string XrayVersion => "xray-core";
    public string OsDescription => RuntimeInformation.OSDescription.Trim();

    partial void OnAutostartChanged(bool value) =>
        PlatformIntegration.Current.SetAutostart(value, Environment.ProcessPath ?? "");

    // ---- helpers ---------------------------------------------------------
    /// <summary>Plain TCP handshake timing — no platform APIs, so it works on Linux as is.</summary>
    private static async Task<int> ProbeAsync(string host, int port, int timeoutMs = 4000)
    {
        try
        {
            var sw = System.Diagnostics.Stopwatch.StartNew();
            using var client = new System.Net.Sockets.TcpClient();
            var connect = client.ConnectAsync(host, port);
            if (await Task.WhenAny(connect, Task.Delay(timeoutMs)) != connect) return -1;
            await connect;
            return (int)sw.ElapsedMilliseconds;
        }
        catch { return -1; }
    }

    private static async Task<int> ProbeUrlAsync(string url, int timeoutMs = 5000)
    {
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromMilliseconds(timeoutMs) };
            var sw = System.Diagnostics.Stopwatch.StartNew();
            using var r = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead);
            return (int)sw.ElapsedMilliseconds;
        }
        catch { return -1; }
    }

    private static string FileInfoText(string path) =>
        File.Exists(path) ? $"{new FileInfo(path).Length / 1048576.0:0.0} МБ" : "не загружен";

    private static Bitmap LoadAsset(string name) =>
        new(AssetLoader.Open(new Uri($"avares://MoonInternet/Assets/{name}")));

    private static string SettingsFile =>
        Path.Combine(PlatformIntegration.Current.DataDirectory, "desktop.txt");

    private static string LoadSavedUrl() =>
        File.Exists(SettingsFile) ? File.ReadAllText(SettingsFile).Trim() : "";

    private static void SaveUrl(string url)
    {
        Directory.CreateDirectory(PlatformIntegration.Current.DataDirectory);
        File.WriteAllText(SettingsFile, url);
    }
}

/// <summary>One card on the settings hub — the same nine the desktop shows, in the same order.</summary>
public sealed record HubEntry(string Id, string Title, string Subtitle)
{
    public static readonly HubEntry[] Default =
    [
        new("appearance", "Оформление", "Тема, шрифт, цвета, иконки"),
        new("connection", "Соединение", "Локальный прокси, LAN, UDP"),
        new("routing", "Маршрутизация", "Профили, правила, приложения, DNS"),
        new("subs", "Настройки подписок", "Обновление, срок, авто-обновление"),
        new("ping", "Настройки пинга", "Как и когда измерять задержку"),
        new("auto", "Авто", "Автозапуск, автоподключение"),
        new("logs", "Логи", "Диагностика и журналы"),
        new("privacy", "Политика конфиденциальности", "Как приложение обращается с данными"),
        new("about", "О приложении", "Версии, ссылки, система"),
    ];
}

/// <summary>DIRECT / PROXY / BLOCK on the routing page.</summary>
public sealed class RuleBucket(string id, string title, string subtitle, string tint)
{
    public string Id { get; } = id;
    public string Title { get; } = title;
    public string Subtitle { get; } = subtitle;
    public string Tint { get; } = tint;
    public ObservableCollection<string> Rules { get; } = new();
    public int Count => Rules.Count;

    public static RuleBucket[] Empty =>
    [
        new("direct", "DIRECT", "Идёт напрямую, мимо VPN", "#34D399"),
        new("proxy", "PROXY", "Всегда через VPN", "#B9A7FF"),
        new("block", "BLOCK", "Блокируется полностью", "#FF6B8A"),
    ];
}
