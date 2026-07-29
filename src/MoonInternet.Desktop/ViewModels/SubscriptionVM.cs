using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using MoonInternet.Core.Models;

namespace MoonInternet.Desktop.ViewModels;

/// <summary>A subscription card: name, quota line and its servers.</summary>
public partial class SubscriptionVM : ObservableObject
{
    public SubscriptionVM(string url, string name, IReadOnlyList<OutboundProfile> servers, SubscriptionInfo? info)
    {
        Url = url;
        Name = name;
        Servers = new ObservableCollection<ServerItem>(servers.Select(p => new ServerItem(p)));
        TrafficText = FormatQuota(info);
        ExpiryText = FormatExpiry(info);
    }

    public string Url { get; }
    public string Name { get; }
    public ObservableCollection<ServerItem> Servers { get; }
    public int Count => Servers.Count;

    [ObservableProperty] private string trafficText = "—";
    [ObservableProperty] private string expiryText = "∞";

    private static string FormatQuota(SubscriptionInfo? info) => info?.TrafficText ?? "—";

    private static string FormatExpiry(SubscriptionInfo? info) => info?.ExpiryText ?? "∞";
}
