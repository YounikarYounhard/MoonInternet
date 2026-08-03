using System.Collections;
using System.Collections.ObjectModel;
using System.ComponentModel;
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

    public static readonly IComparer ByDefault = Comparer<object>.Create((a, b) =>
    {
        var (x, y) = ((ServerItem)a, (ServerItem)b);
        int c = Fav(x, y);
        return c != 0 ? c : x.Order.CompareTo(y.Order);               // subscription order
    });

    public static readonly IComparer ByName = Comparer<object>.Create((a, b) =>
    {
        var (x, y) = ((ServerItem)a, (ServerItem)b);
        int c = Fav(x, y);
        return c != 0 ? c : string.Compare(x.Label, y.Label, StringComparison.OrdinalIgnoreCase);
    });

    public static readonly IComparer ByFavorite = ByName;             // favourites first, then A→Z (favourite mode also filters)

    public static readonly IComparer ByPing = Comparer<object>.Create((a, b) =>
    {
        var (x, y) = ((ServerItem)a, (ServerItem)b);
        int c = Fav(x, y);
        if (c != 0) return c;
        c = PingRank(x.Ping).CompareTo(PingRank(y.Ping));
        return c != 0 ? c : string.Compare(x.Label, y.Label, StringComparison.OrdinalIgnoreCase);
    });
}
