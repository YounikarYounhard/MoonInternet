using System.IO;
using System.Text.Json;
using CommunityToolkit.Mvvm.ComponentModel;

namespace MoonInternet.App.Models;

/// <summary>Position + size of one movable UI block (bindable so drag/resize updates live).</summary>
public partial class BlockLayout : ObservableObject
{
    [ObservableProperty] private double x;
    [ObservableProperty] private double y;
    [ObservableProperty] private double w;
    [ObservableProperty] private double h;

    public BlockLayout() { }
    public BlockLayout(double x, double y, double w, double h) { X = x; Y = y; W = w; H = h; }

    public double[] ToArray() => new[] { X, Y, W, H };
    public void Set(double[] a) { if (a.Length == 4) { X = a[0]; Y = a[1]; W = a[2]; H = a[3]; } }
}

/// <summary>Persists all block positions to %AppData%\MoonInternet\layout.json.</summary>
public static class LayoutStore
{
    private static string Dir => MoonInternet.Core.AppPaths.DataDir;   // portable: next to the exe
    private static string FilePath => Path.Combine(Dir, "layout.json");

    public static Dictionary<string, double[]> Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Dictionary<string, double[]>>(File.ReadAllText(FilePath)) ?? new();
        }
        catch { }
        return new();
    }

    public static void Save(Dictionary<string, double[]> blocks)
    {
        try
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(blocks, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }
    }
}
