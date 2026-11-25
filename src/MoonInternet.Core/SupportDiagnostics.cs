using System.Globalization;

namespace MoonInternet.Core;

public static class SupportDiagnostics
{
    public static string Summarize(IEnumerable<string> newestFirst) =>
        string.Join("\n", newestFirst.Select(Event).OfType<string>().Take(20).Reverse());

    private static string? Event(string line)
    {
        int separator = line.IndexOf(": ", StringComparison.Ordinal);
        if (separator < 10 || line[8..10] != "  " ||
            !TimeOnly.TryParseExact(line[..8], "HH:mm:ss", CultureInfo.InvariantCulture, DateTimeStyles.None, out _))
            return null;

        string topic = line[10..separator];
        string detail = line[(separator + 2)..].TrimEnd();
        // Publish only these fixed event names. Journal messages may contain connection data.
        string? code = topic switch
        {
            "подключение" when detail == "отключено" => "connection.stopped",
            "подключение" => "connection.requested",
            "переподключение" => "connection.retry",
            "туннель" when detail == "поднят" || detail == "AmneziaWG поднят напрямую" => "tunnel.started",
            "туннель" when detail.StartsWith("не поднялся", StringComparison.Ordinal) => "tunnel.proxy_fallback",
            "туннель" when detail.StartsWith("служба TUN не отвечает", StringComparison.Ordinal) => "tunnel.helper_unavailable",
            "туннель" when detail.StartsWith("AmneziaWG напрямую не поднялся", StringComparison.Ordinal) => "tunnel.awg_fallback",
            "туннель" => "tunnel.event",
            "подписка" when detail.StartsWith("обновлена ", StringComparison.Ordinal) => "subscription.updated",
            "подписка" when detail.Contains("не обновилась:", StringComparison.Ordinal) => "subscription.failed",
            "подписка" => "subscription.event",
            "ядро" => "core.event",
            _ => null,
        };
        return code is null ? null : $"{line[..8]} {code}";
    }
}
