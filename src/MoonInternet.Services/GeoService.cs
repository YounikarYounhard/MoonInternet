using System.IO.Compression;
using MoonInternet.Core.Models;

namespace MoonInternet.Services;

/// <summary>
/// Downloads a routing profile's geoip.dat/geosite.dat (e.g. runetfreedom) ONCE to a user-writable geo dir.
/// Once both files are present they are reused forever — the runetfreedom lists update continuously, so the
/// hashes pinned in a subscription/INCY profile go stale within hours; pinning them would re-download 69 MB
/// on every connect (and, when the fresh file didn't match the stale pin, silently disable routing).
/// Returns the dir to pass to xray as XRAY_LOCATION_ASSET, or null. Delete the folder to force a refresh.
/// </summary>
public static class GeoService
{
    private static readonly HttpClient Http = new(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromMinutes(3) };

    /// <summary>Progress text for the UI ("Загрузка geosite.dat…", "Гео-файлы готовы").</summary>
    public static event Action<string>? Status;

    public static string GeoDir => MoonInternet.Core.AppPaths.In("geo");   // portable: next to the exe

    private static string Geoip => Path.Combine(GeoDir, "geoip.dat");
    private static string Geosite => Path.Combine(GeoDir, "geosite.dat");

    /// <summary>sing-box rule-sets (.srs) for Hysteria2 — the sing-box equivalent of geoip/geosite.dat.</summary>
    public static string SrsDir => Path.Combine(GeoDir, "srs");

    public static string GeoipFile => Geoip;
    public static string GeositeFile => Geosite;
    public static bool IsReady() => File.Exists(Geoip) && File.Exists(Geosite);
    public static bool RulesReady() => Directory.Exists(SrsDir) && Directory.EnumerateFiles(SrsDir, "*.srs").Any();

    /// <summary>Force a re-download of geoip/geosite (delete + fetch). Returns the geo dir or null.</summary>
    public static async Task<string?> RefreshAsync(RoutingProfile r, CancellationToken ct = default)
    {
        try { File.Delete(Geoip); } catch { }
        try { File.Delete(Geosite); } catch { }
        return await EnsureAsync(r, ct);
    }

    // runetfreedom ships sing-box rule-sets as sing-box.zip per release (same lists as its geoip/geosite.dat, just
    // sing-box's binary .srs format). Hysteria2 runs on sing-box, which can't read xray's .dat — so download these
    // ONCE to give it the same РФ-direct routing. Stable "latest" URL, like the .dat files.
    private const string SingBoxRulesUrl = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/sing-box.zip";

    /// <summary>Downloads+extracts the sing-box .srs rule-sets once. Returns <see cref="SrsDir"/> or null (→ no routing).</summary>
    public static async Task<string?> EnsureSingBoxRulesAsync(CancellationToken ct = default)
    {
        if (RulesReady()) { Status?.Invoke("Правила маршрутизации готовы"); return SrsDir; }
        Directory.CreateDirectory(SrsDir);
        try
        {
            Status?.Invoke("Загрузка правил маршрутизации…");
            var bytes = await Http.GetByteArrayAsync(SingBoxRulesUrl, ct);
            using var zip = new ZipArchive(new MemoryStream(bytes));
            foreach (var e in zip.Entries)
            {
                if (e.Length == 0 || !e.FullName.EndsWith(".srs", StringComparison.OrdinalIgnoreCase)) continue;
                e.ExtractToFile(Path.Combine(SrsDir, Path.GetFileName(e.FullName)), overwrite: true);   // flatten: geoip-/geosite- prefixes don't collide
            }
        }
        catch { /* leave empty → SingBoxHy2TunConfig falls back to all-proxy (tunnel everything) */ }
        return RulesReady() ? SrsDir : null;
    }

    public static async Task<string?> EnsureAsync(RoutingProfile r, CancellationToken ct = default)
    {
        Directory.CreateDirectory(GeoDir);
        if (IsReady()) { Status?.Invoke("Гео-файлы готовы"); return GeoDir; }

        await Download("geoip.dat", Geoip, r.Geoipurl, ct);
        await Download("geosite.dat", Geosite, r.Geositeurl, ct);

        if (IsReady()) { Status?.Invoke("Гео-файлы загружены"); return GeoDir; }
        Status?.Invoke("Не удалось загрузить гео-файлы");
        return null;
    }

    private static async Task Download(string name, string path, string url, CancellationToken ct)
    {
        if (File.Exists(path) || string.IsNullOrWhiteSpace(url)) return; // have it, or nowhere to get it
        try
        {
            Status?.Invoke($"Загрузка {name}…");
            var bytes = await Http.GetByteArrayAsync(url, ct);
            if (bytes.Length > 0) await File.WriteAllBytesAsync(path, bytes, ct);
        }
        catch { /* leave missing → EnsureAsync reports failure and routing is skipped for this connect */ }
    }
}
