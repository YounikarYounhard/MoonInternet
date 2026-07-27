using System.Text;

namespace MoonInternet.Core.Parsing;

/// <summary>Tolerant base64 decode: accepts url-safe alphabet and missing padding (as VPN share links do).</summary>
public static class Base64Ext
{
    public static byte[] DecodeBytes(string s)
    {
        s = s.Trim().Replace('-', '+').Replace('_', '/');
        switch (s.Length % 4)
        {
            case 2: s += "=="; break;
            case 3: s += "="; break;
        }
        return Convert.FromBase64String(s);
    }

    public static string DecodeUtf8(string s) => Encoding.UTF8.GetString(DecodeBytes(s));

    public static bool TryDecodeUtf8(string s, out string result)
    {
        try { result = DecodeUtf8(s); return true; }
        catch { result = ""; return false; }
    }

    public static string EncodeUtf8(string s) => Convert.ToBase64String(Encoding.UTF8.GetBytes(s));
}
