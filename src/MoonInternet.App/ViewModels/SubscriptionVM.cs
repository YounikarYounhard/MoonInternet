using System.Collections;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows;
using System.Windows.Data;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using MoonInternet.Core.Models;

namespace MoonInternet.App.ViewModels;

/// <summary>One subscription = a collapsible group of servers on the main list, with its own traffic/expiry.</summary>
public partial class SubscriptionVM : ObservableObject
{
    public string Url { get; }
    public ObservableCollection<ServerItem> Servers { get; } = new();
    public ICollectionView ServersView { get; }
    public IReadOnlyList<RoutingProfile> Routing { get; private set; } = Array.Empty<RoutingProfile>();

    [ObservableProperty] private string name;
    [ObservableProperty] private string trafficText = "—";
    [ObservableProperty] private string expiryText = "∞";

    // ---- meters -----------------------------------------------------------
    // The panel gives us bytes and a timestamp; the text form throws that away. Keeping the two
    // fractions lets the plate draw a bar or a row of dots instead of a number nobody reads.
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(TrafficFill), nameof(TrafficRest), nameof(HasTrafficMeter),
        nameof(MeterBrush), nameof(TrafficDots))]
    private double trafficFraction = -1;          // <0 = unlimited or unknown
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ExpiryFill), nameof(ExpiryRest), nameof(HasExpiryMeter), nameof(ExpiryDots))]
    private double expiryFraction = -1;

    // An unlimited plan still gets a bar — a full one in a muted accent, meaning "no ceiling".
    // Hiding it instead was read as "the bars do not work".
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(MeterBrush), nameof(ExpiryBrush))]
    private bool trafficUnlimited = true;
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ExpiryBrush))]
    private bool expiryUnlimited = true;

    public bool HasTrafficMeter => true;
    public bool HasExpiryMeter => true;
    /// <summary>Bar width as a pair of star columns — filled and remaining.</summary>
    public GridLength TrafficFill => Star(TrafficUnlimited ? 1 : TrafficFraction);
    public GridLength TrafficRest => Star(TrafficUnlimited ? 0 : 1 - Math.Clamp(TrafficFraction, 0, 1));
    public GridLength ExpiryFill => Star(ExpiryUnlimited ? 1 : ExpiryFraction);
    public GridLength ExpiryRest => Star(ExpiryUnlimited ? 0 : 1 - Math.Clamp(ExpiryFraction, 0, 1));
    private static GridLength Star(double f) => new(Math.Clamp(f, 0, 1), GridUnitType.Star);

    /// <summary>Green while there is room, amber past 75%, red past 90% — the usual traffic-light read.</summary>
    public System.Windows.Media.Brush MeterBrush =>
        TrafficUnlimited ? MeterIdle
        : TrafficFraction >= 0.9 ? MeterRed
        : TrafficFraction >= 0.75 ? MeterAmber : MeterGreen;
    public System.Windows.Media.Brush ExpiryBrush => ExpiryUnlimited ? MeterIdle : MeterAccent;

    private static readonly System.Windows.Media.Brush MeterGreen = ServerItem.Frozen(0x34, 0xD3, 0x99);
    private static readonly System.Windows.Media.Brush MeterAmber = ServerItem.Frozen(0xE8, 0xB3, 0x39);
    private static readonly System.Windows.Media.Brush MeterRed = ServerItem.Frozen(0xFF, 0x6B, 0x8A);
    private static readonly System.Windows.Media.Brush MeterAccent = ServerItem.Frozen(0x9D, 0x7B, 0xFF);
    /// <summary>No ceiling to fill towards — a calm bar rather than a scary full red one.</summary>
    private static readonly System.Windows.Media.Brush MeterIdle = ServerItem.Frozen(0x4A, 0x3E, 0x78);

    /// <summary>Ten dots, filled left to right. Same information as the bar, smaller and calmer.</summary>
    public IReadOnlyList<bool> TrafficDots => Dots(TrafficFraction);
    public IReadOnlyList<bool> ExpiryDots => Dots(ExpiryFraction);
    private static bool[] Dots(double f)
    {
        int on = f < 0 ? 10 : (int)Math.Round(Math.Clamp(1 - f, 0, 1) * 10);   // dots show what is LEFT
        return Enumerable.Range(0, 10).Select(i => i < on).ToArray();
    }

    partial void OnTrafficFractionChanged(double value) => TrafficUnlimited = value < 0;
    partial void OnExpiryFractionChanged(double value) => ExpiryUnlimited = value < 0;

    /// <summary>True while this subscription is being pinged — the button shows a spinner.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(NotPinging))]
    private bool pinging;
    public bool NotPinging => !Pinging;

    /// <summary>True while this subscription is being re-fetched.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(NotRefreshing))]
    private bool refreshing;
    public bool NotRefreshing => !Refreshing;
    // Panels ship a welcome/announcement as fake nodes (dummy address, name = the text). We pull them out of the
    // connectable list and show them as a banner (like INCY). Empty = no announcement.
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasAnnouncement))]
    private string announcement = "";
    public bool HasAnnouncement => !string.IsNullOrWhiteSpace(Announcement);   // banner always shown (collapsed too)
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsExpanded))]
    private bool isCollapsed;                      // Home's collapse state
    public bool IsExpanded => !IsCollapsed;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsExpandedServers))]
    private bool isCollapsedServers;              // Servers page keeps its own collapse state (independent of Home)
    public bool IsExpandedServers => !IsCollapsedServers;

    public SubscriptionVM(string url, string name, Predicate<object> filter)
    {
        Url = url;
        this.name = name;
        ServersView = CollectionViewSource.GetDefaultView(Servers);
        ServersView.Filter = filter;
        Servers.CollectionChanged += (_, _) => OnPropertyChanged(nameof(Count));
    }

    public int Count => Servers.Count;

    public void SetServers(IEnumerable<OutboundProfile> profiles)
    {
        Servers.Clear();
        var ann = new List<string>();
        int order = 0;
        foreach (var p in profiles)
        {
            if (IsAnnouncement(p)) { if (!string.IsNullOrWhiteSpace(p.Name)) ann.Add(p.Name.Trim()); continue; }
            Servers.Add(new ServerItem(p) { SubscriptionName = Name, Order = order++ });
        }
        Announcement = string.Join("\n", ann);
    }

    /// <summary>Panel welcome/notice from the <c>Announce</c> HTTP header. Overrides the fake-node text when non-empty.</summary>
    public void SetAnnouncement(string? text) { if (!string.IsNullOrWhiteSpace(text)) Announcement = text!.Trim(); }

    // A "server" whose address is a placeholder is not connectable — it's an announcement line the panel injected.
    private static bool IsAnnouncement(OutboundProfile p) =>
        (p.Address ?? "").Trim().ToLowerInvariant() is "" or "127.0.0.1" or "localhost" or "0.0.0.0" or "::1" or "example.com" or "example.org";

    public void SetInfo(SubscriptionInfo? info)
    {
        if (info is null) return;
        TrafficText = info.TrafficText;
        ExpiryText = info.ExpiryText + (info.DaysLeft is { } d ? $" · {d}д" : "");
        TrafficFraction = info.Total > 0 ? Math.Clamp((double)info.Used / info.Total, 0, 1) : -1;
        // A month is the plan length nearly every panel sells, so it is the scale a bar is read
        // against; anything longer just shows full until it is inside the last thirty days.
        ExpiryFraction = info.DaysLeft is { } days ? Math.Clamp(1 - days / 30.0, 0, 1) : -1;
    }

    /// <summary>Restore traffic/expiry text from the offline cache (no SubscriptionInfo available).</summary>
    public void SetCachedInfo(string traffic, string expiry) { TrafficText = traffic; ExpiryText = expiry; }

    public void SetRouting(IReadOnlyList<RoutingProfile> r) => Routing = r;
    public void Refresh() => ServersView.Refresh();

    /// <summary>default = subscription order, ping = fastest first (unknown/timeout last), name = A→Z, favorite = starred first.</summary>
    public void ApplySort(string mode)
    {
        if (ServersView is ListCollectionView lcv)
            lcv.CustomSort = mode switch
            {
                "ping" => ServerComparers.ByPing,
                "name" => ServerComparers.ByName,
                "favorite" => ServerComparers.ByFavorite,
                _ => ServerComparers.ByDefault
            };
    }

    [RelayCommand] private void ToggleCollapse() => IsCollapsed = !IsCollapsed;
    [RelayCommand] private void ToggleCollapseServers() => IsCollapsedServers = !IsCollapsedServers;
}

// Every mode keeps favourites pinned on top (starred first), then breaks ties by the mode's own key.
internal static class ServerComparers
{
    private static int PingRank(int p) => p < 0 ? int.MaxValue : p; // -2 unknown / -1 timeout sort last
    private static int Fav(ServerItem x, ServerItem y) => y.IsFavorite.CompareTo(x.IsFavorite); // true first

    /// <summary>
    /// The server the tunnel is currently running on, or null. Set by the view model; every mode
    /// puts it first, because that is the row you go looking for while it is live.
    /// </summary>
    public static string? ActiveRaw;
    private static int Active(ServerItem x, ServerItem y)
    {
        if (ActiveRaw is null) return 0;
        return (y.ShareUrl == ActiveRaw).CompareTo(x.ShareUrl == ActiveRaw);
    }

    public static readonly IComparer ByDefault = Comparer<object>.Create((a, b) =>
    {
        var (x, y) = ((ServerItem)a, (ServerItem)b);
        int c = Active(x, y);
        if (c != 0) return c;
        c = Fav(x, y);
        return c != 0 ? c : x.Order.CompareTo(y.Order);               // subscription order
    });

    public static readonly IComparer ByName = Comparer<object>.Create((a, b) =>
    {
        var (x, y) = ((ServerItem)a, (ServerItem)b);
        int c = Active(x, y);
        if (c != 0) return c;
        c = Fav(x, y);
        return c != 0 ? c : string.Compare(x.Label, y.Label, StringComparison.OrdinalIgnoreCase);
    });

    public static readonly IComparer ByFavorite = ByName;             // favourites first, then A→Z (favourite mode also filters)

    public static readonly IComparer ByPing = Comparer<object>.Create((a, b) =>
    {
        var (x, y) = ((ServerItem)a, (ServerItem)b);
        int c = Active(x, y);
        if (c != 0) return c;
        c = Fav(x, y);
        if (c != 0) return c;
        c = PingRank(x.Ping).CompareTo(PingRank(y.Ping));
        return c != 0 ? c : string.Compare(x.Label, y.Label, StringComparison.OrdinalIgnoreCase);
    });
}
