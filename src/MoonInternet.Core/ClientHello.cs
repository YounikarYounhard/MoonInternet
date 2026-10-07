using System.Buffers.Binary;
using System.Reflection;
using System.Security.Cryptography;

namespace MoonInternet.Core;

/// <summary>
/// The TLS ClientHello the core sends for a given uTLS fingerprint, rewritten for one server.
///
/// What it is for: the censor's box reads the ClientHello — fingerprint and SNI — and when it has
/// decided against a server it lets the TCP handshake through and swallows the TLS one. A probe
/// that only opens TCP then reports a healthy 35 ms for a server that carries nothing, which is
/// what Moon Ping did. Sending the very bytes xray would send, with this server's SNI, asks the
/// question the box is actually answering.
///
/// The bytes are xray's own, captured by build/capture-hellos.py (Resources/client-hellos.txt);
/// rewritten here are only the SNI and the parts that must never repeat — client random and
/// session id. The key shares are left as captured: the server parses them and answers, which is
/// all a probe needs, and it never gets as far as using them.
/// </summary>
public static class ClientHello
{
    private static readonly Lazy<Dictionary<string, byte[]>> Templates = new(Load);

    private static Dictionary<string, byte[]> Load()
    {
        var map = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("MoonInternet.Core.client-hellos.txt");
        if (s is null) return map;
        using var r = new StreamReader(s);
        while (r.ReadLine() is { } line)
        {
            if (line.StartsWith('#') || line.Length == 0) continue;
            var parts = line.Split(' ', 2);
            if (parts.Length == 2) map[parts[0]] = Convert.FromBase64String(parts[1].Trim());
        }
        return map;
    }

    /// <summary>
    /// The fingerprint the core will actually use. Reality without one is chrome (xray's default
    /// there); TLS without one is Go's own stack; "random"/"randomized" pick a browser per
    /// connection, so a browser one stands in.
    /// </summary>
    public static string Effective(string security, string? fp)
    {
        fp = fp?.Trim().ToLowerInvariant();
        if (string.IsNullOrEmpty(fp)) return security == "reality" ? "chrome" : "go";
        if (fp is "random" or "randomized" or "randomizednoalpn") return "chrome";
        return Templates.Value.ContainsKey(fp) ? fp : "chrome";
    }

    /// <summary>A ready-to-send TLS record, or null when there is nothing to send for this SNI.</summary>
    public static byte[]? For(string fingerprint, string sni)
    {
        if (string.IsNullOrWhiteSpace(sni) || System.Net.IPAddress.TryParse(sni, out _)) return null;
        return Templates.Value.TryGetValue(fingerprint, out var t) ? Rewrite(t, sni) : null;
    }

    /// <summary>Swaps the SNI, refreshes random and session id, and fixes up every length around them.</summary>
    public static byte[] Rewrite(byte[] record, string sni)
    {
        // record(5) | handshake type(1) + length(3) | body: version(2) random(32) sid compsuites ... exts
        var body = record.AsSpan(9).ToArray();
        int p = 34;
        int sidLen = body[p];
        RandomNumberGenerator.Fill(body.AsSpan(2, 32));
        RandomNumberGenerator.Fill(body.AsSpan(p + 1, sidLen));
        p += 1 + sidLen;
        p += 2 + BinaryPrimitives.ReadUInt16BigEndian(body.AsSpan(p));   // cipher suites
        p += 1 + body[p];                                                  // compression methods
        int extLen = BinaryPrimitives.ReadUInt16BigEndian(body.AsSpan(p));
        var exts = body.AsSpan(p + 2, extLen);

        var name = System.Text.Encoding.ASCII.GetBytes(sni.Trim());
        var output = new List<byte>(extLen + name.Length);
        int oldSni = 0, padAt = -1;
        for (int q = 0; q < exts.Length;)
        {
            ushort type = BinaryPrimitives.ReadUInt16BigEndian(exts[q..]);
            int len = BinaryPrimitives.ReadUInt16BigEndian(exts[(q + 2)..]);
            var data = exts.Slice(q + 4, len).ToArray();
            if (type == 0)
            {
                oldSni = len;
                // server_name_list(2) | name_type(1)=host_name | name length(2) | name
                data = new byte[5 + name.Length];
                BinaryPrimitives.WriteUInt16BigEndian(data, (ushort)(3 + name.Length));
                BinaryPrimitives.WriteUInt16BigEndian(data.AsSpan(3), (ushort)name.Length);
                name.CopyTo(data, 5);
            }
            if (type == 21) padAt = output.Count;   // padding: resized below so the total stays put
            AppendExt(output, type, data);
            q += 4 + len;
        }
        if (padAt >= 0)
        {
            // BoringSSL-style padding exists to keep the hello at a fixed size; keep it there.
            int padLen = BinaryPrimitives.ReadUInt16BigEndian(output.ToArray().AsSpan(padAt + 2));
            int newPad = Math.Max(0, padLen - ((5 + name.Length) - oldSni));
            output.RemoveRange(padAt, 4 + padLen);
            var pad = new List<byte>();
            AppendExt(pad, 21, new byte[newPad]);
            output.InsertRange(padAt, pad);
        }

        var newBody = new List<byte>(p + 2 + output.Count);
        newBody.AddRange(body.AsSpan(0, p).ToArray());
        newBody.Add((byte)(output.Count >> 8)); newBody.Add((byte)output.Count);
        newBody.AddRange(output);

        var result = new byte[9 + newBody.Count];
        result[0] = record[0]; result[1] = record[1]; result[2] = record[2];
        BinaryPrimitives.WriteUInt16BigEndian(result.AsSpan(3), (ushort)(4 + newBody.Count));
        result[5] = 1;   // client_hello
        result[6] = (byte)(newBody.Count >> 16); result[7] = (byte)(newBody.Count >> 8); result[8] = (byte)newBody.Count;
        newBody.CopyTo(result, 9);
        return result;
    }

    private static void AppendExt(List<byte> to, ushort type, byte[] data)
    {
        to.Add((byte)(type >> 8)); to.Add((byte)type);
        to.Add((byte)(data.Length >> 8)); to.Add((byte)data.Length);
        to.AddRange(data);
    }

    /// <summary>The SNI a ClientHello carries, or null. For the self-check.</summary>
    public static string? SniOf(byte[] record)
    {
        var body = record.AsSpan(9);
        int p = 34; p += 1 + body[p];
        p += 2 + BinaryPrimitives.ReadUInt16BigEndian(body[p..]);
        p += 1 + body[p];
        int end = p + 2 + BinaryPrimitives.ReadUInt16BigEndian(body[p..]);
        for (int q = p + 2; q < end;)
        {
            ushort type = BinaryPrimitives.ReadUInt16BigEndian(body[q..]);
            int len = BinaryPrimitives.ReadUInt16BigEndian(body[(q + 2)..]);
            if (type == 0) return System.Text.Encoding.ASCII.GetString(body.Slice(q + 9, len - 5));
            q += 4 + len;
        }
        return null;
    }

    public static IReadOnlyCollection<string> Fingerprints => Templates.Value.Keys;
}
