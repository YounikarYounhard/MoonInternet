using System.IO;
using System.Text.Json;
using MoonInternet.Core.Models;
using MoonInternet.Core.Parsing;

namespace MoonInternet.Services;

/// <summary>
/// Loads routing profiles that the INCY and HAPP clients already keep on disk, so both an "INCY" and a "HAPP"
/// routing are available even before a subscription loads:
///   INCY → <c>%AppData%\incy\routing\*.json</c> (one profile per file)
///   HAPP → <c>%LocalAppData%\Happ\routing.json</c> (a { "routings": [ … ] } wrapper).
/// </summary>
public static class RoutingStore
{
    private static string IncyDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "incy", "routing");

    private static string HappFile => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Happ", "routing.json");

    public static IReadOnlyList<RoutingProfile> LoadInstalledIncy()
    {
        var list = new List<RoutingProfile>();
        try
        {
            if (!Directory.Exists(IncyDir)) return list;
            foreach (var f in Directory.EnumerateFiles(IncyDir, "*.json"))
                if (IncyRoutingParser.TryParseJson(File.ReadAllText(f), RoutingSource.Incy, out var p) && p is not null
                    && !string.IsNullOrWhiteSpace(p.Name))
                    list.Add(p);
        }
        catch { /* best effort — INCY may not be installed */ }
        return list;
    }

    public static IReadOnlyList<RoutingProfile> LoadInstalledHapp()
    {
        var list = new List<RoutingProfile>();
        try
        {
            if (!File.Exists(HappFile)) return list;
            using var doc = JsonDocument.Parse(File.ReadAllText(HappFile));
            if (doc.RootElement.TryGetProperty("routings", out var arr) && arr.ValueKind == JsonValueKind.Array)
                foreach (var el in arr.EnumerateArray())
                    if (IncyRoutingParser.TryParseJson(el.GetRawText(), RoutingSource.Happ, out var p) && p is not null
                        && !string.IsNullOrWhiteSpace(p.Name))
                        list.Add(p);
        }
        catch { /* best effort — HAPP may not be installed */ }
        return list;
    }

    /// <summary>INCY + HAPP profiles installed on disk (INCY first for the "INCY wins" default).</summary>
    public static IReadOnlyList<RoutingProfile> LoadInstalled()
    {
        var list = new List<RoutingProfile>(LoadInstalledIncy());
        list.AddRange(LoadInstalledHapp());
        return list;
    }
}
