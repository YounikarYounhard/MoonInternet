using Avalonia.Media.Imaging;
using Avalonia.Platform;
using CommunityToolkit.Mvvm.ComponentModel;
using MoonInternet.Core.Models;

namespace MoonInternet.Desktop.ViewModels;

/// <summary>One server row. Wraps the shared <see cref="OutboundProfile"/> with display bits.</summary>
public partial class ServerItem(OutboundProfile profile) : ObservableObject
{
    public OutboundProfile Profile { get; } = profile;

    [ObservableProperty] private int ping = -2;      // -2 unknown, -1 failed, >=0 ms
    [ObservableProperty] private bool isFavorite;

    public string Label => Profile.Name;
    public string Protocol => Profile.Protocol.ToString().ToUpperInvariant();

    /// <summary>Transport badge. Always shown, TCP included — same as the desktop row.</summary>
    public string Network => (Profile.Network ?? "tcp").ToUpperInvariant();

    public string Security => (Profile.Security ?? "").ToUpperInvariant();
    public bool HasSecurity => !string.IsNullOrEmpty(Profile.Security) && Profile.Security != "none";

    public string PingText => Ping switch
    {
        -2 => "",
        -1 => "✕",
        _ => $"{Ping} ms",
    };

    partial void OnPingChanged(int value) => OnPropertyChanged(nameof(PingText));

    /// <summary>Country flag guessed from the leading emoji, falling back to a globe.</summary>
    public Bitmap? FlagImage
    {
        get
        {
            var code = CountryCode(Profile.Name);
            var asset = code is null ? "flags/xx.png" : $"flags/{code}.png";
            try { return new Bitmap(AssetLoader.Open(new Uri($"avares://MoonInternet/Assets/{asset}"))); }
            catch { return null; }
        }
    }

    private static string? CountryCode(string name)
    {
        if (name.Length < 4) return null;
        var a = char.ConvertToUtf32(name, 0);
        var rest = char.ConvertFromUtf32(a).Length;
        if (name.Length <= rest) return null;
        var b = char.ConvertToUtf32(name, rest);
        if (a is < 0x1F1E6 or > 0x1F1FF || b is < 0x1F1E6 or > 0x1F1FF) return null;
        return $"{(char)('a' + a - 0x1F1E6)}{(char)('a' + b - 0x1F1E6)}";
    }
}
