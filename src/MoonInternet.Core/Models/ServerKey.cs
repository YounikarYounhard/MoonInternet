namespace MoonInternet.Core.Models;

/// <summary>
/// What identifies a server across a subscription refresh.
///
/// The share link without its <c>#remark</c>. The remark is only the display name and panels
/// rewrite it — x-ui writes the traffic left and the expiry date into it — so a key made of the
/// whole link turned one server into a different one every time the subscription updated. Stars,
/// the current selection, the pinned row and every measured ping were all keyed off it, which is
/// why they kept clearing themselves for no reason anybody could point at.
///
/// AmneziaWG needs the same thing for a different reason: its whole link is one base64 blob with
/// the config inside it, name and all, so there is no <c>#</c> to cut at and the least cosmetic
/// edit on the panel's side produces a brand-new key.
///
/// ponytail: a vmess:// link keeps its name inside the base64 payload too, so renaming one still
/// moves its key. Decode it here if that ever turns up in practice.
/// </summary>
public static class ServerKey
{
    public static string? Of(string? raw) =>
        raw is null ? null : raw.IndexOf('#') is var i and >= 0 ? raw[..i] : raw;

    /// <summary>Key for a parsed profile, falling back to its endpoint when it carries no link
    /// (some formats do not ship one, and those servers must still be identifiable).</summary>
    public static string Of(OutboundProfile p)
    {
        // The peer's public key and its endpoint are what identify an AmneziaWG server. Neither
        // moves when a label does, which the encoded config underneath it very much does.
        if (p.Wireguard is { PeerPublicKey.Length: > 0 } wg)
            return $"awg:{wg.PeerPublicKey}@{wg.Endpoint}";
        return Of(p.Raw) is { Length: > 0 } k ? k : $"{p.Address}:{p.Port}";
    }
}
