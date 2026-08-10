using System.Text.Json;
using System.Text.Json.Serialization;
using MoonInternet.Core.Models;

namespace MoonInternet.Core.Parsing;

/// <summary>Parses routing deep-links: <c>incy://routing/add/&lt;b64&gt;</c> or <c>happ://routing/add/&lt;b64&gt;</c>.</summary>
public static class IncyRoutingParser
{
    private const string IncyPrefix = "incy://routing/add/";
    private const string HappPrefix = "happ://routing/add/";

    // HAPP serialises booleans as strings ("true"/"false") and uses camelCase keys (geoIpUrl, remoteDnsIp…);
    // case-insensitive + the bool converter let one RoutingProfile shape read both INCY and HAPP payloads.
    private static readonly JsonSerializerOptions Opts = new() { PropertyNameCaseInsensitive = true, Converters = { new BoolLikeConverter() } };

    public static bool IsRoutingLink(string link) =>
        link.StartsWith("incy://routing/", StringComparison.OrdinalIgnoreCase) ||
        link.StartsWith("happ://routing/", StringComparison.OrdinalIgnoreCase);

    public static bool TryParse(string link, out RoutingProfile? profile)
    {
        profile = null;
        try
        {
            link = link.Trim();
            RoutingSource src;
            string b64;
            if (link.StartsWith(IncyPrefix, StringComparison.OrdinalIgnoreCase)) { src = RoutingSource.Incy; b64 = link[IncyPrefix.Length..]; }
            else if (link.StartsWith(HappPrefix, StringComparison.OrdinalIgnoreCase)) { src = RoutingSource.Happ; b64 = link[HappPrefix.Length..]; }
            else return false;

            return TryParseJson(Base64Ext.DecodeUtf8(b64), src, out profile);
        }
        catch
        {
            profile = null;
            return false;
        }
    }

    /// <summary>Parse a raw routing JSON payload (e.g. INCY's on-disk <c>%AppData%\incy\routing\*.json</c>).</summary>
    public static bool TryParseJson(string json, RoutingSource source, out RoutingProfile? profile)
    {
        profile = null;
        try
        {
            profile = JsonSerializer.Deserialize<RoutingProfile>(json, Opts);
            if (profile is null) return false;
            profile.Source = source;
            return true;
        }
        catch
        {
            profile = null;
            return false;
        }
    }

    /// <summary>The shareable form: what "Экспорт в буфер" puts on the clipboard.</summary>
    public static string ToLink(RoutingProfile p)
    {
        // Our own bookkeeping is not part of the payload — the other app has its own ids and
        // knows nothing about which subscription this came from.
        var wire = JsonSerializer.Deserialize<RoutingProfile>(JsonSerializer.Serialize(p, Opts), Opts)!;
        wire.Id = ""; wire.SubUrl = ""; wire.Builtin = false;
        var b64 = Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(JsonSerializer.Serialize(wire, Opts)))
            .TrimEnd('=').Replace('+', '-').Replace('/', '_');
        return "incy://routing/add/" + b64;
    }
}

/// <summary>Reads a bool from a JSON bool OR a string like "true"/"false"/"1" (HAPP uses the string form).</summary>
internal sealed class BoolLikeConverter : JsonConverter<bool>
{
    public override bool Read(ref Utf8JsonReader reader, Type t, JsonSerializerOptions o) => reader.TokenType switch
    {
        JsonTokenType.True => true,
        JsonTokenType.False => false,
        JsonTokenType.String => reader.GetString() is "true" or "True" or "1",
        JsonTokenType.Number => reader.TryGetInt64(out var n) && n != 0,
        _ => false,
    };

    public override void Write(Utf8JsonWriter w, bool value, JsonSerializerOptions o) => w.WriteBooleanValue(value);
}
