namespace MoonInternet.Core.Parsing;

/// <summary>Which ports the game filter covers. Off is not "nothing" — see <see cref="ZapretStrategyParser"/>.</summary>
public enum ZapretGameFilter { Off, All, Tcp, Udp }

/// <param name="Id">File name without the extension — <c>general (ALT2)</c>. Stable across updates.</param>
/// <param name="Name">What to show. The author's own names: every guide out there refers to them.</param>
/// <param name="Arguments">Ready for winws.exe, every %VARIABLE% already expanded.</param>
public sealed record ZapretStrategy(string Id, string Name, string Arguments);

/// <summary>
/// Reads the strategies out of zapret's own .bat files.
///
/// We do not run them: each one first calls service.bat, which checks for updates, edits lists and
/// can install a Windows service — none of which we want happening behind the user's back. What we
/// need is the one line they all end with, the winws.exe command, so that is what we take.
/// </summary>
public static class ZapretStrategyParser
{
    /// <summary>
    /// Reads every strategy in <paramref name="zapretDir"/>, sorted the way the folder lists them.
    /// Returns empty when the folder is missing — zapret is downloaded separately and may not be here yet.
    /// </summary>
    public static IReadOnlyList<ZapretStrategy> Load(string zapretDir, ZapretGameFilter filter = ZapretGameFilter.Off)
    {
        if (!Directory.Exists(zapretDir)) return Array.Empty<ZapretStrategy>();

        var list = new List<ZapretStrategy>();
        foreach (var path in Directory.EnumerateFiles(zapretDir, "*.bat"))
        {
            // service.bat is the menu, not a strategy.
            if (Path.GetFileName(path).Equals("service.bat", StringComparison.OrdinalIgnoreCase)) continue;
            if (Parse(path, zapretDir, filter) is { } s) list.Add(s);
        }
        // "general" first, then the variants in the author's own order.
        return list.OrderBy(s => s.Id.Contains('(') ? 1 : 0).ThenBy(s => s.Id, StringComparer.OrdinalIgnoreCase).ToList();
    }

    /// <summary>The one strategy, or null when the file holds no winws.exe command.</summary>
    public static ZapretStrategy? Parse(string batPath, string zapretDir, ZapretGameFilter filter)
    {
        string[] lines;
        try { lines = File.ReadAllLines(batPath); } catch { return null; }

        var start = Array.FindIndex(lines, l => l.TrimStart().StartsWith("start ", StringComparison.OrdinalIgnoreCase));
        if (start < 0) return null;

        // The command is one logical line broken up with a trailing ^ — glue it back together.
        var sb = new System.Text.StringBuilder();
        for (var i = start; i < lines.Length; i++)
        {
            var line = lines[i].TrimEnd();
            var more = line.EndsWith('^');
            sb.Append(more ? line[..^1] : line).Append(' ');
            if (!more) break;
        }

        var cmd = sb.ToString();
        // Drop everything up to and including the winws.exe token: the `start "title" /min` part is
        // cmd's way of getting a detached window, and we start the process ourselves.
        var exe = cmd.IndexOf("winws.exe", StringComparison.OrdinalIgnoreCase);
        if (exe < 0) return null;
        var args = cmd[(exe + "winws.exe".Length)..].TrimStart('"', ' ');

        var id = Path.GetFileNameWithoutExtension(batPath);
        return new ZapretStrategy(id, id, Expand(args, zapretDir, filter).Trim());
    }

    /// <summary>
    /// Substitutes the four variables the strategies use. The game filter is the odd one: switched
    /// off it is not blank but port 12, a port nothing uses — leaving it empty would produce
    /// <c>--wf-tcp=80,443,</c> with a dangling comma, which winws rejects.
    /// </summary>
    private static string Expand(string args, string zapretDir, ZapretGameFilter filter)
    {
        var (tcp, udp) = filter switch
        {
            ZapretGameFilter.All => ("1024-65535", "1024-65535"),
            ZapretGameFilter.Tcp => ("1024-65535", "12"),
            ZapretGameFilter.Udp => ("12", "1024-65535"),
            _ => ("12", "12"),
        };
        return args
            .Replace("%BIN%", Path.Combine(zapretDir, "bin") + Path.DirectorySeparatorChar)
            .Replace("%LISTS%", Path.Combine(zapretDir, "lists") + Path.DirectorySeparatorChar)
            .Replace("%GameFilterTCP%", tcp)
            .Replace("%GameFilterUDP%", udp);
    }
}
