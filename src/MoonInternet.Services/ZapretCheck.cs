namespace MoonInternet.Services;

/// <summary>
/// Does a strategy actually help? A ping cannot answer that.
///
/// Zapret carries nothing, so there is no tunnel to measure and no server to reach — the only
/// honest question is whether a site that was blocked now opens. So we fetch one and time it.
/// A number means the page came back; a failure means this strategy does nothing here, which is
/// exactly the thing twenty-one names cannot tell you on their own.
///
/// The request deliberately ignores the system proxy: with one set, a success would say something
/// about the proxy rather than about the strategy.
/// </summary>
public static class ZapretCheck
{
    /// <summary>What to fetch. Blocked in the places this mode exists for, and cheap to ask for.</summary>
    public const string DefaultUrl = "https://www.youtube.com/generate_204";

    /// <summary>Milliseconds, or -1 when it did not come back.</summary>
    public static async Task<int> MeasureAsync(string url, int timeoutMs = 6000, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(url)) url = DefaultUrl;
        try
        {
            using var handler = new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false };
            using var http = new HttpClient(handler) { Timeout = TimeSpan.FromMilliseconds(timeoutMs) };
            // HEAD, not GET: we want the handshake and the first answer, not the page.
            using var req = new HttpRequestMessage(HttpMethod.Head, url);
            var sw = System.Diagnostics.Stopwatch.StartNew();
            using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
            sw.Stop();
            // Any answer at all is the point. A 4xx still means the handshake got through, which is
            // what the DPI was stopping — only silence and resets count as a failure.
            return (int)sw.ElapsedMilliseconds;
        }
        catch { return -1; }
    }
}
