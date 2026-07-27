namespace MoonInternet.App.ViewModels;

/// <summary>One routing rule shown as a chip: which bucket it belongs to (direct/proxy/block) and its value
/// (a domain, IP/CIDR, or a geosite:/geoip: tag).</summary>
public sealed record RuleChip(string Bucket, string Value);
