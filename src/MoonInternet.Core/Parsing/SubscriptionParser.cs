using MoonInternet.Core.Models;

namespace MoonInternet.Core.Parsing;

/// <summary>Servers + any routing profiles found in a subscription.</summary>
public sealed record SubscriptionResult(IReadOnlyList<OutboundProfile> Servers, IReadOnlyList<RoutingProfile> Routing);

/// <summary>
/// Parses subscription content into servers (and INCY routing profiles). Accepts base64-of-links
/// (the common format), plain newline-separated links, or a mix. Malformed lines are skipped, not fatal.
/// </summary>
public static class SubscriptionParser
{
    public static IReadOnlyList<OutboundProfile> Parse(string content) => ParseFull(content).Servers;

    public static SubscriptionResult ParseFull(string content)
    {
        string text = content.Trim();

        // Whole body may be base64-wrapped (no scheme visible until decoded).
        if (!text.Contains("://") && Base64Ext.TryDecodeUtf8(text, out var decoded) && decoded.Contains("://"))
            text = decoded;

        var servers = new List<OutboundProfile>();
        var routing = new List<RoutingProfile>();
        foreach (var raw in text.Split('\n'))
        {
            string line = raw.Trim();
            if (line.Length == 0 || !line.Contains("://")) continue;

            if (IncyRoutingParser.IsRoutingLink(line))
            {
                if (IncyRoutingParser.TryParse(line, out var r)) routing.Add(r!);
            }
            else if (WireGuardParser.IsWireGuardLink(line))
            {
                if (WireGuardParser.TryParseProfile(line, out var wp)) servers.Add(wp!);
            }
            else if (ShareLinkParser.TryParse(line, out var p, out _))
            {
                servers.Add(p!);
            }
        }
        return new SubscriptionResult(servers, routing);
    }
}
