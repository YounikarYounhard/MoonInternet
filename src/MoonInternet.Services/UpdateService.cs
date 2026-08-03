using System.Net.Http;
using System.Text.Json;

namespace MoonInternet.Services;

/// <summary>What the latest GitHub release says, once we have asked it.</summary>
public sealed record ReleaseInfo(string Version, string Notes, string PageUrl, string? AssetUrl, string? AssetName);

/// <summary>
/// Checks the GitHub releases page for a newer build.
///
/// Read-only and anonymous: the API allows 60 unauthenticated calls an hour per IP, and we make
/// one per launch, so no token is needed and none is shipped.
/// </summary>
public static class UpdateService
{
    // The list, not /releases/latest: that endpoint skips pre-releases, and everything we ship is
    // tagged beta, so it answered 404. The list is newest-first, drafts excluded for anonymous
    // callers, so the first entry is what we want.
    private const string Api = "https://api.github.com/repos/YounikarYounhard/MoonInternet/releases?per_page=5";

    // UseProxy=false for the same reason the subscription client does it: the check has to work
    // while our own tunnel is down or half-configured.
    private static readonly HttpClient Http = new(new HttpClientHandler { UseProxy = false })
    {
        Timeout = TimeSpan.FromSeconds(12),
    };

    /// <summary>Extension of the file this platform installs, used to pick an asset off the release.</summary>
    public static string AssetExtension { get; set; } = ".exe";

    /// <summary>The newest release, or null if GitHub could not be reached or has none.</summary>
    public static async Task<ReleaseInfo?> LatestAsync(CancellationToken ct = default)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, Api);
            req.Headers.UserAgent.ParseAdd("MoonInternet");          // GitHub rejects a missing UA
            req.Headers.Accept.ParseAdd("application/vnd.github+json");

            using var res = await Http.SendAsync(req, ct);
            if (!res.IsSuccessStatusCode) return null;

            using var doc = JsonDocument.Parse(await res.Content.ReadAsStringAsync(ct));
            if (doc.RootElement.ValueKind != JsonValueKind.Array) return null;

            var root = doc.RootElement.EnumerateArray()
                .FirstOrDefault(r => !r.TryGetProperty("draft", out var d) || !d.GetBoolean());
            if (root.ValueKind != JsonValueKind.Object) return null;

            var tag = root.GetProperty("tag_name").GetString() ?? "";
            var notes = root.TryGetProperty("body", out var b) ? b.GetString() ?? "" : "";
            var page = root.TryGetProperty("html_url", out var h) ? h.GetString() ?? "" : "";

            string? assetUrl = null, assetName = null;
            if (root.TryGetProperty("assets", out var assets))
            {
                foreach (var a in assets.EnumerateArray())
                {
                    var name = a.GetProperty("name").GetString() ?? "";
                    if (!name.EndsWith(AssetExtension, StringComparison.OrdinalIgnoreCase)) continue;
                    assetName = name;
                    assetUrl = a.GetProperty("browser_download_url").GetString();
                    break;
                }
            }
            return new ReleaseInfo(Normalize(tag), notes.Trim(), page, assetUrl, assetName);
        }
        catch { return null; }   // offline, rate-limited, GitHub down — all the same to the caller
    }

    /// <summary>
    /// True when <paramref name="latest"/> is newer than <paramref name="current"/>.
    ///
    /// Compares the numeric parts only. Our own builds carry a fourth number that grows with every
    /// build (0.9.1.7), while a release is three (0.9.1) — so a local build of the version being
    /// prepared must not be reported as "an update is available".
    /// </summary>
    public static bool IsNewer(string latest, string current)
    {
        var l = Numbers(latest);
        var c = Numbers(current);
        for (int i = 0; i < Math.Max(l.Length, c.Length); i++)
        {
            int a = i < l.Length ? l[i] : 0;
            int b = i < c.Length ? c[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    /// <summary>Strips the tag down to digits and dots: "v0.9.1-beta" → "0.9.1".</summary>
    public static string Normalize(string tag)
    {
        var s = tag.Trim().TrimStart('v', 'V');
        int end = 0;
        while (end < s.Length && (char.IsDigit(s[end]) || s[end] == '.')) end++;
        return s[..end].Trim('.');
    }

    private static int[] Numbers(string v) =>
        Normalize(v).Split('.', StringSplitOptions.RemoveEmptyEntries)
            .Select(p => int.TryParse(p, out var n) ? n : 0)
            .ToArray();
}
