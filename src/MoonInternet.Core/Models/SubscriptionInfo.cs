namespace MoonInternet.Core.Models;

/// <summary>Traffic/expiry info from the <c>subscription-userinfo</c> HTTP header (upload/download/total/expire).</summary>
public sealed record SubscriptionInfo(long Used, long Total, DateTimeOffset? Expire)
{
    public string TrafficText => $"{Fmt(Used)} / {(Total > 0 ? Fmt(Total) : "∞")}";
    public string ExpiryText => Expire is { } e ? e.LocalDateTime.ToString("dd.MM.yyyy") : "∞";
    public int? DaysLeft => Expire is { } e ? Math.Max(0, (int)Math.Ceiling((e - DateTimeOffset.Now).TotalDays)) : null;

    /// <summary>Parse a header like <c>upload=1; download=2; total=3; expire=1700000000</c>.</summary>
    public static SubscriptionInfo? Parse(string? header)
    {
        if (string.IsNullOrWhiteSpace(header)) return null;
        long up = 0, down = 0, total = 0; long? exp = null;
        foreach (var part in header.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            int eq = part.IndexOf('=');
            if (eq < 0) continue;
            string k = part[..eq].Trim().ToLowerInvariant();
            if (!long.TryParse(part[(eq + 1)..].Trim(), out var v)) continue;
            switch (k) { case "upload": up = v; break; case "download": down = v; break; case "total": total = v; break; case "expire": exp = v; break; }
        }
        var expire = exp is > 0 ? DateTimeOffset.FromUnixTimeSeconds(exp.Value) : (DateTimeOffset?)null;
        return new SubscriptionInfo(up + down, total, expire);
    }

    private static string Fmt(long bytes)
    {
        string[] u = { "B", "KB", "MB", "GB", "TB" };
        double v = bytes; int i = 0;
        while (v >= 1024 && i < u.Length - 1) { v /= 1024; i++; }
        return $"{v:0.##} {u[i]}";
    }
}
