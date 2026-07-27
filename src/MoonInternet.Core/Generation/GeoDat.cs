using System.IO;
using System.Text;

namespace MoonInternet.Core.Generation;

/// <summary>Minimal reader for v2ray geoip.dat / geosite.dat (protobuf). Extracts every entry's category/country code
/// so the UI can browse and pick tags (like HAPP). Format: GeoSiteList { repeated GeoSite entry=1 }, GeoSite { string code=1 }.</summary>
public static class GeoDat
{
    /// <summary>All tags as "prefix:CODE" (e.g. "geosite:CATEGORY-RU", "geoip:RU"), sorted, deduped.</summary>
    public static List<string> Tags(string datPath, string prefix)
    {
        var set = new SortedSet<string>(StringComparer.OrdinalIgnoreCase);
        byte[] d;
        try { d = File.ReadAllBytes(datPath); } catch { return new List<string>(); }
        int i = 0;
        while (i < d.Length)
        {
            if (!ReadKey(d, ref i, out int tag, out int wt)) break;
            if (tag == 1 && wt == 2)                       // top-level repeated entry (length-delimited)
            {
                if (!ReadVarint(d, ref i, out long len)) break;
                int end = i + (int)len;
                if (end > d.Length || end < i) break;
                int j = i;                                  // scan the entry for its field-1 string (the code)
                while (j < end)
                {
                    if (!ReadKey(d, ref j, out int t2, out int w2)) break;
                    if (t2 == 1 && w2 == 2)
                    {
                        if (!ReadVarint(d, ref j, out long l2)) break;
                        if (j + (int)l2 <= end) set.Add(prefix + ":" + Encoding.UTF8.GetString(d, j, (int)l2).ToUpperInvariant());
                        break;
                    }
                    if (!SkipField(d, ref j, w2)) break;
                }
                i = end;
            }
            else if (!SkipField(d, ref i, wt)) break;
        }
        return set.ToList();
    }

    /// <summary>All IPv4 CIDRs of one country from geoip.dat (e.g. "RU" → 25k entries like "5.8.0.0/21").
    /// Used to route a whole country around a WireGuard tunnel, which has no domain router of its own.
    /// Format: GeoIPList{ repeated GeoIP=1 }, GeoIP{ code=1, repeated CIDR=2 }, CIDR{ bytes ip=1, uint32 prefix=2 }.</summary>
    public static List<string> Cidrs(string geoipDat, string countryCode)
    {
        var result = new List<string>();
        byte[] d;
        try { d = File.ReadAllBytes(geoipDat); } catch { return result; }
        int i = 0;
        while (i < d.Length)
        {
            if (!ReadKey(d, ref i, out int tag, out int wt)) break;
            if (tag == 1 && wt == 2)
            {
                if (!ReadVarint(d, ref i, out long len)) break;
                int end = i + (int)len;
                if (end > d.Length || end < i) break;
                int j = i;
                string? code = null;
                var pending = new List<(byte[] ip, int prefix)>();
                while (j < end)
                {
                    if (!ReadKey(d, ref j, out int t2, out int w2)) break;
                    if (t2 == 1 && w2 == 2)                       // country_code
                    {
                        if (!ReadVarint(d, ref j, out long l2)) break;
                        code = Encoding.UTF8.GetString(d, j, (int)l2).ToUpperInvariant();
                        j += (int)l2;
                    }
                    else if (t2 == 2 && w2 == 2)                  // cidr { ip, prefix }
                    {
                        if (!ReadVarint(d, ref j, out long l2)) break;
                        int cend = j + (int)l2;
                        byte[]? ip = null; int prefix = 0;
                        while (j < cend)
                        {
                            if (!ReadKey(d, ref j, out int t3, out int w3)) break;
                            if (t3 == 1 && w3 == 2)
                            {
                                if (!ReadVarint(d, ref j, out long l3)) break;
                                ip = d[j..(j + (int)l3)]; j += (int)l3;
                            }
                            else if (t3 == 2 && w3 == 0) { ReadVarint(d, ref j, out long pv); prefix = (int)pv; }
                            else if (!SkipField(d, ref j, w3)) break;
                        }
                        if (ip is { Length: 4 }) pending.Add((ip, prefix));
                        j = cend;
                    }
                    else if (!SkipField(d, ref j, w2)) break;
                }
                if (string.Equals(code, countryCode, StringComparison.OrdinalIgnoreCase))
                    foreach (var (ip, prefix) in pending)
                        result.Add($"{ip[0]}.{ip[1]}.{ip[2]}.{ip[3]}/{prefix}");
                i = end;
            }
            else if (!SkipField(d, ref i, wt)) break;
        }
        return result;
    }

    private static bool ReadKey(byte[] d, ref int i, out int tag, out int wt)
    {
        tag = 0; wt = 0;
        if (!ReadVarint(d, ref i, out long key)) return false;
        tag = (int)(key >> 3); wt = (int)(key & 7); return true;
    }

    private static bool ReadVarint(byte[] d, ref int i, out long value)
    {
        value = 0; int shift = 0;
        while (i < d.Length)
        {
            byte b = d[i++];
            value |= (long)(b & 0x7F) << shift;
            if ((b & 0x80) == 0) return true;
            shift += 7;
            if (shift > 63) return false;
        }
        return false;
    }

    private static bool SkipField(byte[] d, ref int i, int wt)
    {
        switch (wt)
        {
            case 0: return ReadVarint(d, ref i, out _);
            case 1: i += 8; return i <= d.Length;
            case 2: if (!ReadVarint(d, ref i, out long len)) return false; i += (int)len; return i <= d.Length;
            case 5: i += 4; return i <= d.Length;
            default: return false;
        }
    }
}
