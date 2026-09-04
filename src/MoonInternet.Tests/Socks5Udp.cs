using System.Net;
using System.Net.Sockets;

namespace MoonInternet.Tests;

/// <summary>
/// A DNS question asked over SOCKS5's UDP ASSOCIATE.
///
/// It exists because the AmneziaWG chain carried TCP perfectly while every datagram was being
/// refused at the proxy, and no check that only speaks TCP would ever have noticed: the tunnel came
/// up, the ping was good, and QUIC and name lookups quietly went somewhere else.
/// </summary>
internal static class Socks5Udp
{
    /// <summary>The address <paramref name="name"/> resolves to through the proxy, or null.</summary>
    public static async Task<string?> ResolveAsync(int proxyPort, string resolver, string name, int timeoutMs = 8000)
    {
        var deadline = TimeSpan.FromMilliseconds(timeoutMs);
        using var tcp = new TcpClient();
        await tcp.ConnectAsync(IPAddress.Loopback, proxyPort).WaitAsync(deadline);
        var s = tcp.GetStream();

        await s.WriteAsync(new byte[] { 5, 1, 0 });
        var greeting = await ReadAsync(s, 2, deadline);
        if (greeting[0] != 5 || greeting[1] != 0) return null;

        // The address on an associate is where the client promises to send from, and 0.0.0.0:0 is
        // how everyone says "from wherever". The reply carries the address to send the datagrams to.
        await s.WriteAsync(new byte[] { 5, 3, 0, 1, 0, 0, 0, 0, 0, 0 });
        var head = await ReadAsync(s, 4, deadline);
        if (head[1] != 0) return null;
        var bound = await ReadAsync(s, head[3] == 4 ? 16 : 4, deadline);
        var boundPort = await ReadAsync(s, 2, deadline);
        var address = new IPAddress(bound);
        // "The address you already dialled" is what 0.0.0.0 means here.
        if (address.Equals(IPAddress.Any)) address = IPAddress.Loopback;
        var relay = new IPEndPoint(address, boundPort[0] << 8 | boundPort[1]);

        using var udp = new UdpClient(0, AddressFamily.InterNetwork);
        var query = BuildQuery(name, out ushort id);
        var packet = new byte[10 + query.Length];
        packet[3] = 1;                                                   // IPv4 destination
        IPAddress.Parse(resolver).GetAddressBytes().CopyTo(packet, 4);
        packet[9] = 53;
        query.CopyTo(packet, 10);
        await udp.SendAsync(packet, packet.Length, relay);

        var reply = await udp.ReceiveAsync().WaitAsync(deadline);
        // Same header on the way back, and the destination in it decides how far to skip.
        int skip = reply.Buffer.Length > 3 && reply.Buffer[3] == 4 ? 22 : 10;
        if (reply.Buffer.Length <= skip) return null;
        return FirstAddress(reply.Buffer.AsSpan(skip), id);
    }

    private static async Task<byte[]> ReadAsync(NetworkStream s, int count, TimeSpan deadline)
    {
        var buf = new byte[count];
        await s.ReadExactlyAsync(buf).AsTask().WaitAsync(deadline);
        return buf;
    }

    private static byte[] BuildQuery(string name, out ushort id)
    {
        id = (ushort)Random.Shared.Next(1, ushort.MaxValue);
        var body = new List<byte> { (byte)(id >> 8), (byte)id, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0 };
        foreach (var label in name.Split('.'))
        {
            body.Add((byte)label.Length);
            body.AddRange(System.Text.Encoding.ASCII.GetBytes(label));
        }
        body.AddRange(new byte[] { 0, 0, 1, 0, 1 });                     // root, type A, class IN
        return body.ToArray();
    }

    /// <summary>The first A record in a response, or null if it is not one, or carries none.</summary>
    private static string? FirstAddress(ReadOnlySpan<byte> dns, ushort id)
    {
        if (dns.Length < 12 || dns[0] != (byte)(id >> 8) || dns[1] != (byte)id) return null;
        int answers = dns[6] << 8 | dns[7];
        if (answers == 0) return null;

        int i = 12;
        for (int q = dns[4] << 8 | dns[5]; q > 0; q--)
        {
            while (i < dns.Length && dns[i] != 0) i += dns[i] + 1;        // no pointers in a question
            i += 5;                                                       // the root label, type, class
        }
        for (; answers > 0 && i + 12 <= dns.Length; answers--)
        {
            if ((dns[i] & 0xC0) == 0xC0) i += 2;                          // compressed name
            else { while (i < dns.Length && dns[i] != 0) i += dns[i] + 1; i++; }
            if (i + 10 > dns.Length) return null;
            int type = dns[i] << 8 | dns[i + 1];
            int length = dns[i + 8] << 8 | dns[i + 9];
            i += 10;
            if (i + length > dns.Length) return null;
            if (type == 1 && length == 4) return new IPAddress(dns.Slice(i, 4).ToArray()).ToString();
            i += length;
        }
        return null;
    }
}
