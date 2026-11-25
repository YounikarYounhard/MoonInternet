using System.Text.Json;
using MoonInternet.Core.Models;
using MoonInternet.Core.Parsing;

namespace MoonInternet.Services;

/// <summary>Fetches a subscription URL and parses it into profiles. Sends a Happ UA so servers return all protocols.</summary>
public static class SubscriptionService
{
    // UseProxy=false: fetch the subscription directly, ignoring any http_proxy env var / system proxy
    // (a stale VPN-client proxy like INCY's 127.0.0.1:10808 would otherwise break the fetch when it's down).
    private static readonly HttpClient Http = new(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(30), MaxResponseContentBufferSize = 8 * 1024 * 1024 };

    private static readonly HttpClient Artwork = new(new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false });

    /// <summary>Optional stable device id sent as the <c>X-HWID</c> header (set by the app when "Отправлять HWID" is on).</summary>
    public static string? Hwid { get; set; }

    public static async Task<IReadOnlyList<OutboundProfile>> FetchAsync(string url, CancellationToken ct = default)
        => (await FetchFullAsync(url, ct)).Content.Servers;

    public static async Task<(SubscriptionResult Content, SubscriptionInfo? Info, string? Title, string? Announce, int UpdateMin, byte[]? Icon, byte[]? HeaderImage)> FetchFullAsync(string url, CancellationToken ct = default)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.UserAgent.ParseAdd("Happ/1.0");
        if (!string.IsNullOrEmpty(Hwid)) req.Headers.TryAddWithoutValidation("X-HWID", Hwid);   // device id for panels that count devices
        using var resp = await Http.SendAsync(req, ct);
        resp.EnsureSuccessStatusCode();

        var body = await resp.Content.ReadAsStringAsync(ct);
        string? Meta(string name) => Header(resp, name) ?? InlineHeader(body, name);
        var info = SubscriptionInfo.Parse(Meta("subscription-userinfo"));
        var content = SubscriptionParser.ParseFull(body);

        // Routing usually arrives as a response header (happ://routing/add/… or incy://…), not a body line.
        var routingHeader = Meta("routing");
        if (!string.IsNullOrWhiteSpace(routingHeader) && IncyRoutingParser.TryParse(routingHeader!, out var rp) && rp is not null)
        {
            var merged = content.Routing.ToList();
            if (!merged.Any(x => string.Equals(x.Name, rp.Name, StringComparison.OrdinalIgnoreCase))) merged.Add(rp);
            content = content with { Routing = merged };
        }

        // Panels ship an auto-update cadence in "profile-update-interval" (hours). 0 = not specified.
        int updateMin = int.TryParse(Meta("profile-update-interval"), out var h) && h > 0 ? h * 60 : 0;
        // Announce = panel's welcome/notice text (multi-line), same "base64:<b64>" encoding as the title.
        var (jsonIcon, jsonHeader) = JsonArtwork(body);
        var baseUri = resp.RequestMessage!.RequestUri!;
        var icon = ReadImageAsync(baseUri, Meta("profile-icon") ?? Meta("profile-logo") ?? jsonIcon, ct);
        var header = ReadImageAsync(baseUri, Meta("profile-header-image") ?? Meta("subscription-banner") ?? jsonHeader, ct);
        return (content, info, DecodeB64Header(Meta("profile-title")), DecodeB64Header(Meta("announce")), updateMin, await icon, await header);
    }

    private static string? InlineHeader(string body, string name)
    {
        using var reader = new StringReader(body.TrimStart('\uFEFF'));
        while (reader.ReadLine() is { } raw)
        {
            var line = raw.Trim();
            if (line.Length == 0) continue;
            if (!line.StartsWith('#')) break;
            if (line.StartsWith("#" + name + ":", StringComparison.OrdinalIgnoreCase))
                return line[(name.Length + 2)..].Trim();
        }
        return null;
    }

    private static (string? Icon, string? Header) JsonArtwork(string body)
    {
        if (!body.AsSpan().TrimStart().StartsWith("{", StringComparison.Ordinal)) return default;
        try
        {
            using var document = JsonDocument.Parse(body);
            var root = document.RootElement;
            string? Value(string name) => root.TryGetProperty(name, out var node) && node.ValueKind == JsonValueKind.String
                ? node.GetString() : null;
            return (Value("icon"), Value("headerImage"));
        }
        catch (JsonException) { return default; }
    }

    private static async Task<byte[]?> ReadImageAsync(Uri baseUri, string? value, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(value) || !Uri.TryCreate(baseUri, value, out var uri)
            || uri.Scheme is not ("https" or "http") || uri.UserInfo.Length > 0
            || uri.Scheme != baseUri.Scheme || uri.Host != baseUri.Host || uri.Port != baseUri.Port) return null;
        const int limit = 512 * 1024;
        try
        {
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
            deadline.CancelAfter(TimeSpan.FromSeconds(5));
            using var response = await Artwork.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, deadline.Token);
            if (!response.IsSuccessStatusCode || response.Content.Headers.ContentLength is > limit
                || response.Content.Headers.ContentType?.MediaType is not ("image/png" or "image/jpeg" or "image/gif" or "image/x-icon" or "image/vnd.microsoft.icon"))
                return null;
            using var stream = await response.Content.ReadAsStreamAsync(deadline.Token);
            var buffer = new byte[limit + 1];
            int length = 0;
            while (length < buffer.Length)
            {
                int read = await stream.ReadAsync(buffer.AsMemory(length), deadline.Token);
                if (read == 0) break;
                length += read;
            }
            return length > limit ? null : buffer[..length];
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or OperationCanceledException)
        { return null; }
    }

    private static string? Header(HttpResponseMessage r, string name) =>
        (r.Headers.TryGetValues(name, out var v) || r.Content.Headers.TryGetValues(name, out v)) ? v.FirstOrDefault() : null;

    // Panels send text headers (title, announce) as "base64:<b64>" (or occasionally plain text).
    private static string? DecodeB64Header(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        raw = raw.Trim();
        const string p = "base64:";
        if (raw.StartsWith(p, StringComparison.OrdinalIgnoreCase) && Base64Ext.TryDecodeUtf8(raw[p.Length..].Trim(), out var dec) && dec.Length > 0)
            return dec.Trim();
        return raw;
    }
}
