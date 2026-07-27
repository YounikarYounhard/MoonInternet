using System.IO;
using System.Text.RegularExpressions;

namespace MoonInternet.App;

/// <summary>Turns whatever the user picked (.exe / .lnk / .url) into a real .exe path, or null.
/// .lnk → its TargetPath; .url → file:// path or a Steam game (steam://rungameid/APPID → the game's main exe).</summary>
public static class ShortcutResolver
{
    public static string? ToExe(string path)
    {
        try
        {
            if (path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) return File.Exists(path) ? path : null;
            if (path.EndsWith(".lnk", StringComparison.OrdinalIgnoreCase)) return FromLnk(path);
            if (path.EndsWith(".url", StringComparison.OrdinalIgnoreCase)) return FromUrl(path);
        }
        catch { }
        return null;
    }

    private static string? FromLnk(string lnk)
    {
        try
        {
            var t = Type.GetTypeFromProgID("WScript.Shell");
            if (t is null) return null;
            dynamic shell = Activator.CreateInstance(t)!;
            string target = shell.CreateShortcut(lnk).TargetPath;
            return Valid(target);
        }
        catch { return null; }
    }

    private static string? FromUrl(string urlFile)
    {
        string? url = null;
        foreach (var line in File.ReadAllLines(urlFile))
            if (line.StartsWith("URL=", StringComparison.OrdinalIgnoreCase)) { url = line[4..].Trim(); break; }
        if (string.IsNullOrEmpty(url)) return null;

        if (url.StartsWith("file:///", StringComparison.OrdinalIgnoreCase))
            return Valid(Uri.UnescapeDataString(url[8..]).Replace('/', '\\'));

        var m = Regex.Match(url, @"steam://(?:rungameid|run)/(\d+)");
        return m.Success ? SteamGameExe(m.Groups[1].Value) : null;
    }

    private static string? Valid(string? exe) =>
        !string.IsNullOrWhiteSpace(exe) && exe!.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) && File.Exists(exe) ? exe : null;

    // ---- Steam: appid → installed game's main exe --------------------------------
    private static string? SteamGameExe(string appId)
    {
        var steam = SteamPath();
        if (steam is null) return null;
        foreach (var lib in SteamLibraries(steam))
        {
            var acf = Path.Combine(lib, "steamapps", $"appmanifest_{appId}.acf");
            if (!File.Exists(acf)) continue;
            var installdir = Vdf(File.ReadAllText(acf), "installdir");
            if (installdir is null) continue;
            return PickMainExe(Path.Combine(lib, "steamapps", "common", installdir));
        }
        return null;
    }

    private static string? SteamPath()
    {
        try { using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Valve\Steam"); return k?.GetValue("SteamPath") as string; }
        catch { return null; }
    }

    private static IEnumerable<string> SteamLibraries(string steam)
    {
        yield return steam;
        var vdf = Path.Combine(steam, "steamapps", "libraryfolders.vdf");
        if (!File.Exists(vdf)) yield break;
        foreach (Match m in Regex.Matches(File.ReadAllText(vdf), @"""path""\s*""([^""]+)"""))
            yield return m.Groups[1].Value.Replace(@"\\", @"\");
    }

    private static string? Vdf(string text, string key)
    {
        var m = Regex.Match(text, $@"""{Regex.Escape(key)}""\s*""([^""]+)""");
        return m.Success ? m.Groups[1].Value : null;
    }

    // Biggest top-level .exe that isn't an obvious helper (crash handler, uninstaller, redist…).
    private static string? PickMainExe(string dir)
    {
        if (!Directory.Exists(dir)) return null;
        static bool Helper(string n) => n.Contains("crashhandler") || n.Contains("unins") || n.Contains("vcredist")
            || n.Contains("setup") || n.Contains("dotnet") || n.Contains("redist") || n.Contains("launcher_helper");
        return Directory.EnumerateFiles(dir, "*.exe", SearchOption.TopDirectoryOnly)
            .Where(f => !Helper(Path.GetFileName(f).ToLowerInvariant()))
            .OrderByDescending(f => new FileInfo(f).Length)
            .FirstOrDefault();
    }
}
