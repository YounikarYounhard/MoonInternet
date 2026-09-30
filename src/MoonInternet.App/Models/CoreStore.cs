using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using MoonInternet.Core;

namespace MoonInternet.App.Models;

/// <summary>One engine as the catalogue describes it.</summary>
public sealed record CoreRelease(string Id, string Version, string File, long Size, string Sha256, string Url);

/// <summary>
/// The engines that are not in the installer: where they land, how they arrive, what version is here.
///
/// The same shape as the Android side, for the same reasons and with the same catalogue format —
/// a different file only because the files themselves differ. They land in the writable data
/// folder, which the installer never touches, so an engine installed once outlives every update.
///
/// Downloaded code is checked against the digest in the catalogue before anything is unpacked.
/// That is not optional: without it, anything that can answer for the URL hands the app something
/// to run.
/// </summary>
public static class CoreStore
{
    // A branch of its own, not master: the engines are megabytes and they change on their own
    // schedule, so a core can be republished without touching the app — and the app's history does
    // not carry a copy of every engine it ever shipped.
    private const string Catalog =
        "https://raw.githubusercontent.com/YounikarYounhard/MoonInternet/cores/windows-x64.json";

    private static string Dir => AppPaths.In("cores");
    private static string VersionsFile => Path.Combine(Dir, "versions.json");

    public static async Task<IReadOnlyList<CoreRelease>> CatalogueAsync(CancellationToken ct = default)
    {
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
            http.DefaultRequestHeaders.Add("User-Agent", "MoonInternet");
            string body = await http.GetStringAsync(Catalog, ct);
            var list = new List<CoreRelease>();
            foreach (var e in JsonDocument.Parse(body).RootElement.GetProperty("cores").EnumerateArray())
            {
                list.Add(new CoreRelease(
                    e.GetProperty("id").GetString() ?? "",
                    e.GetProperty("version").GetString() ?? "",
                    e.GetProperty("file").GetString() ?? "",
                    e.TryGetProperty("size", out var s) ? s.GetInt64() : 0,
                    (e.GetProperty("sha256").GetString() ?? "").ToLowerInvariant(),
                    e.GetProperty("url").GetString() ?? ""));
            }
            return list;
        }
        catch (Exception ex)
        {
            // Said out loud. A catalogue that cannot be read means nothing installs and nothing
            // updates, and swallowing that leaves an engine quietly missing with no trace of why.
            DevLog.Log("ядро", "список ядер не читается: " + ex.Message);
            return Array.Empty<CoreRelease>();
        }
    }

    public static string? VersionOf(string id) =>
        Versions().TryGetValue(id, out var v) ? v : null;

    /// <summary>Fetches an engine and unpacks it into place. Null when it worked.</summary>
    public static async Task<string?> InstallAsync(
        MoonCore core, CoreRelease rel, IProgress<int>? progress = null, CancellationToken ct = default)
    {
        Directory.CreateDirectory(Dir);
        string part = Path.Combine(Dir, rel.File + ".part");
        try
        {
            using (var http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) })
            {
                http.DefaultRequestHeaders.Add("User-Agent", "MoonInternet");
                using var response = await http.GetAsync(rel.Url, HttpCompletionOption.ResponseHeadersRead, ct);
                response.EnsureSuccessStatusCode();
                long total = rel.Size > 0 ? rel.Size : (response.Content.Headers.ContentLength ?? 0);
                using var source = await response.Content.ReadAsStreamAsync(ct);
                using var file = System.IO.File.Create(part);
                var buffer = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = await source.ReadAsync(buffer, ct)) > 0)
                {
                    await file.WriteAsync(buffer.AsMemory(0, n), ct);
                    done += n;
                    if (total > 0) progress?.Report((int)Math.Clamp(done * 100 / total, 0, 100));
                }
            }

            string got;
            using (var stream = System.IO.File.OpenRead(part))
                got = Convert.ToHexString(await SHA256.HashDataAsync(stream, ct)).ToLowerInvariant();
            if (got != rel.Sha256) return "Ядро скачалось повреждённым";

            // Unpacked beside the target and swapped in, so an unpack that fails partway leaves the
            // previous engine working rather than half of a new one.
            string staging = Path.Combine(Dir, core.Folder + ".new");
            if (Directory.Exists(staging)) Directory.Delete(staging, true);
            ZipFile.ExtractToDirectory(part, staging);

            string target = Path.Combine(Dir, core.Folder);
            if (Directory.Exists(target)) Directory.Delete(target, true);
            Directory.Move(staging, target);

            var versions = Versions();
            versions[core.Id] = rel.Version;
            System.IO.File.WriteAllText(VersionsFile, JsonSerializer.Serialize(versions));
            return null;
        }
        catch (Exception ex)
        {
            return "Ядро не скачалось: " + ex.Message;
        }
        finally
        {
            try { System.IO.File.Delete(part); } catch { }
        }
    }

    public static void Remove(MoonCore core)
    {
        try { Directory.Delete(Path.Combine(Dir, core.Folder), true); } catch { }
        var versions = Versions();
        if (versions.Remove(core.Id))
            try { System.IO.File.WriteAllText(VersionsFile, JsonSerializer.Serialize(versions)); } catch { }
    }

    private static Dictionary<string, string> Versions()
    {
        try
        {
            if (System.IO.File.Exists(VersionsFile))
                return JsonSerializer.Deserialize<Dictionary<string, string>>(
                    System.IO.File.ReadAllText(VersionsFile)) ?? new();
        }
        catch { }
        return new();
    }
}
