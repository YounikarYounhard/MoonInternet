using System.IO;
using System.Text.Json;

namespace MoonInternet.App.Models;

/// <summary>User-editable appearance. All colors are #RRGGBB / #AARRGGBB hex strings. Persisted to theme.json.</summary>
public sealed class Theme
{
    public int? Version { get; set; }        // null = legacy file (no field) → migrated to the violet palette on load
    public string Name { get; set; } = "Луна";
    public string Accent { get; set; } = "#9D7BFF";
    public string WinBg1 { get; set; } = "#0D0A18";
    public string WinBg2 { get; set; } = "#170A38";
    public string Sidebar { get; set; } = "#0A0814";
    public string Card { get; set; } = "#181229";
    public string Text { get; set; } = "#ECE9F5";
    public string TextSecondary { get; set; } = "#9A93B5";
    public string FontFamily { get; set; } = "Comic Sans MS";
    public double UiScale { get; set; } = 1.0;
    public double WindowOpacity { get; set; } = 1.0;   // whole-window transparency (0.75–1.0)
    public string? BackgroundImage { get; set; }
    public double BackgroundOpacity { get; set; } = 0.35;

    // Custom connect-button art (paths). Null → built-in vector moon. Square transparent PNG, ≥350px recommended.
    public string? MoonOnImage { get; set; }     // connected  (full)
    public string? MoonOffImage { get; set; }    // disconnected (crescent)
    public string? NavMoonImage { get; set; }    // bottom-nav centre moon

    public Theme Clone() => (Theme)MemberwiseClone();

    /// <summary>Copy all values from another theme into this instance (keeps the object identity for bindings).</summary>
    public void CopyFrom(Theme o)
    {
        Version = o.Version;
        Name = o.Name; Accent = o.Accent; WinBg1 = o.WinBg1; WinBg2 = o.WinBg2; Sidebar = o.Sidebar;
        Card = o.Card; Text = o.Text; TextSecondary = o.TextSecondary; FontFamily = o.FontFamily;
        UiScale = o.UiScale; WindowOpacity = o.WindowOpacity; BackgroundImage = o.BackgroundImage; BackgroundOpacity = o.BackgroundOpacity;
        MoonOnImage = o.MoonOnImage; MoonOffImage = o.MoonOffImage; NavMoonImage = o.NavMoonImage;
    }

    public static readonly Theme[] Presets =
    {
        new() { Version = 3, Name = "Луна",     Accent = "#9D7BFF", WinBg1 = "#0D0A18", WinBg2 = "#170A38", Sidebar = "#0A0814", Card = "#181229" },
        new() { Version = 3, Name = "Аметист",  Accent = "#A66CFF", WinBg1 = "#0C0820", WinBg2 = "#170D33", Sidebar = "#0E0A1C", Card = "#181229" },
        new() { Version = 3, Name = "Изумруд",  Accent = "#34D399", WinBg1 = "#07120E", WinBg2 = "#0A1E17", Sidebar = "#081310", Card = "#0F1B17" },
        new() { Version = 3, Name = "Закат",    Accent = "#FF7A59", WinBg1 = "#160A0E", WinBg2 = "#26121A", Sidebar = "#170B10", Card = "#201319" },
        new() { Version = 3, Name = "Графит",   Accent = "#9AA0C7", WinBg1 = "#0E0F12", WinBg2 = "#15171C", Sidebar = "#101114", Card = "#181A20" },
    };
}

/// <summary>Load/save the active theme in %AppData%\MoonInternet\theme.json.</summary>
public static class ThemeStore
{
    private static string Path_ => MoonInternet.Core.AppPaths.In("theme.json");

    public static Theme Load()
    {
        try
        {
            if (File.Exists(Path_))
            {
                var t = JsonSerializer.Deserialize<Theme>(File.ReadAllText(Path_));
                if (t is null) return new Theme { Version = 2 };
                if (t.Version is null or < 2)   // legacy blue palette → adopt violet defaults + new main font, keep the user's extras
                {
                    var def = new Theme
                    {
                        Version = 3,
                        UiScale = t.UiScale,        // FontFamily left to default (Comic Sans MS)
                        BackgroundImage = t.BackgroundImage, BackgroundOpacity = t.BackgroundOpacity,
                        MoonOnImage = t.MoonOnImage, MoonOffImage = t.MoonOffImage, NavMoonImage = t.NavMoonImage,
                    };
                    Save(def);
                    return def;
                }
                if (t.Version < 3)   // v2 violet users → switch main font to Comic Sans, keep everything else
                {
                    t.FontFamily = "Comic Sans MS"; t.Version = 3;
                    Save(t);
                }
                return t;
            }
        }
        catch { }
        return new Theme { Version = 2 };
    }

    public static void Save(Theme t)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(Path_)!);
            File.WriteAllText(Path_, JsonSerializer.Serialize(t, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }
    }

    public static string Export(Theme t) => JsonSerializer.Serialize(t, new JsonSerializerOptions { WriteIndented = true });
    public static Theme? Import(string json) { try { return JsonSerializer.Deserialize<Theme>(json); } catch { return null; } }
}
