using MoonInternet.Core.Models;
using MoonInternet.Core.Parsing;

namespace MoonInternet.Services;

/// <summary>Fetches a subscription URL and parses it into profiles. Sends a Happ UA so servers return all protocols.</summary>
public static class SubscriptionService
{
    // UseProxy=false: fetch the subscription directly, ignoring any http_proxy env var / system proxy
    // (a stale VPN-client proxy like INCY's 127.0.0.1:10808 would otherwise break the fetch when it's down).
    private static readonly HttpClient Http = new(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(30) };

    /// <summary>Optional stable device id sent as the <c>X-HWID</c> header (set by the app when "Отправлять HWID" is on).</summary>
    public static string? Hwid { get; set; }

    public static async Task<IReadOnlyList<OutboundProfile>> FetchAsync(string url, CancellationToken ct = default)
        => (await FetchFullAsync(url, ct)).Content.Servers;

    public static async Task<(SubscriptionResult Content, SubscriptionInfo? Info, string? Title, string? Announce, int UpdateMin)> FetchFullAsync(string url, CancellationToken ct = default)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.UserAgent.ParseAdd("Happ/1.0");
        if (!string.IsNullOrEmpty(Hwid)) req.Headers.TryAddWithoutValidation("X-HWID", Hwid);   // device id for panels that count devices
        using var resp = await Http.SendAsync(req, ct);
        resp.EnsureSuccessStatusCode();

        var info = SubscriptionInfo.Parse(Header(resp, "subscription-userinfo"));
        var content = SubscriptionParser.ParseFull(await resp.Content.ReadAsStringAsync(ct));

        // Routing usually arrives as a response header (happ://routing/add/… or incy://…), not a body line.
        var routingHeader = Header(resp, "routing");
        if (!string.IsNullOrWhiteSpace(routingHeader) && IncyRoutingParser.TryParse(routingHeader!, out var rp) && rp is not null)
        {
            var merged = content.Routing.ToList();
            if (!merged.Any(x => string.Equals(x.Name, rp.Name, StringComparison.OrdinalIgnoreCase))) merged.Add(rp);
            content = content with { Routing = merged };
        }

        // Panels ship an auto-update cadence in "profile-update-interval" (hours). 0 = not specified.
        int updateMin = int.TryParse(Header(resp, "profile-update-interval"), out var h) && h > 0 ? h * 60 : 0;
        // Announce = panel's welcome/notice text (multi-line), same "base64:<b64>" encoding as the title.
        return (content, info, DecodeB64Header(Header(resp, "profile-title")), DecodeB64Header(Header(resp, "announce")), updateMin);
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
