using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MoonInternet.App.Models;

namespace MoonInternet.App;

/// <summary>Applies a <see cref="Theme"/> live by mutating the shared resource brushes + window background/font.</summary>
public static class ThemeService
{
    private static Color C(string hex)
    {
        try { return (Color)ColorConverter.ConvertFromString(hex)!; }
        catch { return Colors.Gray; }
    }

    private static Color Lighten(Color c, double amt) => Color.FromRgb(
        (byte)(c.R + (255 - c.R) * amt), (byte)(c.G + (255 - c.G) * amt), (byte)(c.B + (255 - c.B) * amt));

    private static void SetBrush(string key, Color color)
    {
        if (Application.Current.Resources[key] is SolidColorBrush b && !b.IsFrozen) b.Color = color;
        else Application.Current.Resources[key] = new SolidColorBrush(color);
    }

    /// <summary>Recolour shared resources — safe to call any time after App resources are loaded.</summary>
    public static void ApplyColors(Theme t)
    {
        var accent = C(t.Accent);
        SetBrush("Accent", accent);
        SetBrush("WinBg", C(t.WinBg1));
        SetBrush("SidebarBg", C(t.Sidebar));
        SetBrush("Card", C(t.Card));
        SetBrush("CardHover", Lighten(C(t.Card), 0.06));
        SetBrush("TextPrimary", C(t.Text));
        SetBrush("TextSecondary", C(t.TextSecondary));

        if (Application.Current.Resources["AccentBrush"] is LinearGradientBrush lg && !lg.IsFrozen && lg.GradientStops.Count >= 2)
        {
            lg.GradientStops[0].Color = accent;
            lg.GradientStops[1].Color = Lighten(accent, 0.2);
        }
    }

    /// <summary>Window-level bits (background gradient/image + base font).</summary>
    public static void ApplyToWindow(Window w, Theme t)
    {
        w.Opacity = Math.Clamp(t.WindowOpacity <= 0 ? 1.0 : t.WindowOpacity, 0.6, 1.0);   // whole-window transparency
        if (!string.IsNullOrWhiteSpace(t.BackgroundImage) && File.Exists(t.BackgroundImage))
        {
            try
            {
                var img = new BitmapImage();
                img.BeginInit();
                img.CacheOption = BitmapCacheOption.OnLoad;
                img.UriSource = new Uri(t.BackgroundImage);
                img.EndInit();
                w.Background = new ImageBrush(img) { Stretch = Stretch.UniformToFill, Opacity = t.BackgroundOpacity };
                w.Resources["_bgBase"] = new SolidColorBrush(C(t.WinBg1)); // (kept for reference)
                return;
            }
            catch { /* fall through to gradient */ }
        }

        w.Background = new LinearGradientBrush
        {
            StartPoint = new Point(0, 0),
            EndPoint = new Point(0.6, 1),
            GradientStops = { new GradientStop(C(t.WinBg1), 0), new GradientStop(C(t.WinBg2), 1) }
        };
        if (!string.IsNullOrWhiteSpace(t.FontFamily))
        {
            try { w.FontFamily = new FontFamily(t.FontFamily); } catch { }
        }
    }

    public static void Apply(Theme t)
    {
        ApplyColors(t);
        if (Application.Current.MainWindow is { } w) ApplyToWindow(w, t);
    }
}
