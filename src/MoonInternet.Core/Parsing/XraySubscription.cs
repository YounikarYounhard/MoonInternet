using System.Text.Json;
using System.Text.Json.Nodes;
using MoonInternet.Core.Models;

namespace MoonInternet.Core.Parsing;

internal static class XraySubscription
{
    internal static IEnumerable<OutboundProfile> Parse(string text)
    {
        JsonNode? root;
        try { root = JsonNode.Parse(text); }
        catch (JsonException) { yield break; }

        var entries = root as JsonArray ?? (root as JsonObject)?["servers"] as JsonArray;
        if (entries is null)
        {
            if (ReadProfile(root) is { } single) yield return single;
            yield break;
        }

        foreach (var entry in entries)
        {
            if (entry is JsonValue value && value.TryGetValue<string>(out var link))
            {
                if (WireGuardParser.TryParseProfile(link, out var wg) && wg is not null) yield return wg;
                else if (ShareLinkParser.TryParse(link, out var server, out _) && server is not null) yield return server;
            }
            else if (ReadProfile(entry) is { } profile) yield return profile;
        }
    }

    private static OutboundProfile? ReadProfile(JsonNode? entry)
    {
        if (entry is not JsonObject config || MainOutbound(config) is not { } outbound) return null;
        var protocol = Protocol(outbound);
        if (protocol is null) return null;
        var endpoint = Endpoint(outbound);
        var address = Text(endpoint, "address");
        if (address.Length == 0 || !int.TryParse(endpoint?["port"]?.ToString(), out int port) || port is < 1 or > 65535)
            return null;

        var user = First(endpoint?["users"]);
        var stream = outbound["streamSettings"] as JsonObject;
        var tls = stream?["realitySettings"] as JsonObject ?? stream?["tlsSettings"] as JsonObject;
        return new OutboundProfile
        {
            Protocol = protocol.Value,
            Name = Text(config, "remarks").Trim() is { Length: > 0 } name ? name : address,
            Address = address,
            Port = port,
            Id = Text(user, "id"),
            Password = Text(user, "pass") is { Length: > 0 } pass ? pass : Text(endpoint, "password"),
            Method = Text(endpoint, "method"),
            Network = Text(stream, "network") is { Length: > 0 } network ? network : "tcp",
            Security = Text(stream, "security") is { Length: > 0 } security ? security : "none",
            Sni = Text(tls, "serverName"),
            Fingerprint = Text(tls, "fingerprint"),
            Raw = config.ToJsonString(),
        };
    }

    internal static bool IsConfig(string? raw) => raw?.TrimStart().StartsWith('{') == true;

    private static ProtocolType? Protocol(JsonObject outbound) => Text(outbound, "protocol").ToLowerInvariant() switch
    {
        "vless" => ProtocolType.Vless,
        "vmess" => ProtocolType.Vmess,
        "trojan" => ProtocolType.Trojan,
        "shadowsocks" => ProtocolType.Shadowsocks,
        "socks" => ProtocolType.Socks,
        _ => null,
    };

    private static string Text(JsonObject? node, string field) =>
        node?[field] is JsonValue value && value.TryGetValue<string>(out var text) ? text : "";

    private static JsonObject? First(JsonNode? node) =>
        node is JsonArray { Count: > 0 } array ? array[0] as JsonObject : null;

    private static JsonObject? Endpoint(JsonObject outbound) =>
        outbound["settings"] is JsonObject settings
            ? First(settings["vnext"]) ?? First(settings["servers"]) ?? settings
            : null;

    private static JsonObject? MainOutbound(JsonObject config)
    {
        if (config["outbounds"] is not JsonArray outbounds) return null;
        var supported = outbounds.OfType<JsonObject>().Where(o => Protocol(o) is not null);
        return supported.FirstOrDefault(o => Text(o, "tag") == "proxy") ?? supported.FirstOrDefault();
    }

    internal static JsonObject? CopyOutbound(OutboundProfile profile, string tag)
    {
        if (!IsConfig(profile.Raw) || JsonNode.Parse(profile.Raw!) is not JsonObject source
            || MainOutbound(source) is not { } outbound) return null;
        var result = (JsonObject)outbound.DeepClone();
        result["tag"] = tag;
        if (profile.OriginalHost is not null && Endpoint(result) is { } endpoint)
        {
            endpoint["address"] = profile.Address;
            if (result["streamSettings"] is JsonObject stream)
            {
                var tls = stream["realitySettings"] as JsonObject ?? stream["tlsSettings"] as JsonObject;
                if (tls is not null && Text(tls, "serverName").Length == 0)
                    tls["serverName"] = profile.OriginalHost;
            }
        }
        return result;
    }

    internal static IEnumerable<string> Hosts(OutboundProfile profile)
    {
        if (!IsConfig(profile.Raw) || JsonNode.Parse(profile.Raw!) is not JsonObject source
            || source["outbounds"] is not JsonArray outbounds) yield break;
        foreach (var outbound in outbounds.OfType<JsonObject>())
            if (Text(Endpoint(outbound), "address") is { Length: > 0 } address) yield return address;
    }

    internal static string Apply(string appJson, OutboundProfile profile, bool useAppDns)
    {
        if (!IsConfig(profile.Raw) || JsonNode.Parse(profile.Raw!) is not JsonObject source
            || source["outbounds"] is not JsonArray providerOutbounds) return appJson;
        var app = JsonNode.Parse(appJson)!.AsObject();
        var outbounds = app["outbounds"]!.AsArray();
        foreach (var outbound in providerOutbounds.OfType<JsonObject>())
        {
            var tag = Text(outbound, "tag");
            if (tag.Length == 0 || tag is "proxy" or "api" or "dns-out") continue;
            var existing = outbounds.OfType<JsonObject>().FirstOrDefault(o => Text(o, "tag") == tag);
            if (existing is not null) outbounds.Remove(existing);
            outbounds.Add(outbound.DeepClone());
        }

        if (source["routing"] is JsonObject providerRouting && app["routing"] is JsonObject routing)
        {
            if (providerRouting["balancers"] is JsonArray balancers) routing["balancers"] = balancers.DeepClone();
            if (providerRouting["rules"] is JsonArray rules && routing["rules"] is JsonArray appRules)
            {
                var tags = new Dictionary<string, string>(StringComparer.Ordinal);
                if (source["inbounds"] is JsonArray inbounds)
                {
                    foreach (var inbound in inbounds.OfType<JsonObject>())
                    {
                        var tag = Text(inbound, "tag");
                        var protocol = Text(inbound, "protocol");
                        if (tag.Length > 0 && protocol is "socks" or "http") tags[tag] = protocol + "-in";
                    }
                }

                foreach (var rule in rules.OfType<JsonObject>())
                {
                    var copy = (JsonObject)rule.DeepClone();
                    if (copy["inboundTag"] is JsonArray inTags)
                    {
                        for (int i = 0; i < inTags.Count; i++)
                            if (inTags[i] is JsonValue v && v.TryGetValue<string>(out var tag) && tags.TryGetValue(tag, out var mapped))
                                inTags[i] = mapped;
                    }
                    appRules.Add(copy);
                }
            }
        }

        if (!useAppDns && source["dns"] is JsonObject dns) app["dns"] = dns.DeepClone();
        foreach (var name in new[] { "observatory", "burstObservatory" })
            if (source[name] is JsonObject monitor) app[name] = monitor.DeepClone();
        return app.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
    }
}
