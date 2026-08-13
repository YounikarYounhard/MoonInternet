namespace MoonInternet.Core.Parsing;

/// <summary>
/// Self-check for <see cref="ZapretStrategyParser"/>, in the same spirit as
/// <c>NetworkQualitySelfTest</c>: the strategies are somebody else's files that change with every
/// zapret release, so the parser has to be checked against the real ones rather than a sample we
/// wrote ourselves. A missed %VARIABLE% is not a crash — winws simply refuses to start, and the
/// mode looks broken for no visible reason.
///
/// Run: <c>MoonInternet.Core.Parsing.ZapretStrategyParserSelfTest.Run(coresDir)</c>
/// </summary>
public static class ZapretStrategyParserSelfTest
{
    public static void Run(string zapretDir)
    {
        var all = ZapretStrategyParser.Load(zapretDir);
        Check(all.Count > 0, $"no strategies found in {zapretDir}");
        Check(all[0].Id == "general", $"«general» should come first, got «{all[0].Id}»");

        foreach (var s in all)
        {
            Check(!s.Arguments.Contains('%'), $"{s.Id}: a %VARIABLE% was left unexpanded");
            Check(!s.Arguments.Contains("winws.exe"), $"{s.Id}: the exe is still in the arguments");
            Check(s.Arguments.StartsWith("--"), $"{s.Id}: arguments start with «{Head(s.Arguments)}»");
            // Every payload the strategy names has to be on disk — winws treats a missing one as fatal.
            foreach (System.Text.RegularExpressions.Match m in
                     System.Text.RegularExpressions.Regex.Matches(s.Arguments, @"""([^""]+\.bin)"""))
                Check(File.Exists(m.Groups[1].Value), $"{s.Id}: missing payload {m.Groups[1].Value}");
        }

        // Switched off the game filter is port 12, never blank: blank leaves «--wf-tcp=…,8443,»
        // with a dangling comma and winws will not start.
        var off = ZapretStrategyParser.Load(zapretDir, ZapretGameFilter.Off).First(s => s.Id == "general");
        Check(!off.Arguments.Contains(",,") && !off.Arguments.Contains(", "), "off: dangling comma in the port list");
        Check(off.Arguments.Contains(",12"), "off: the game filter should fall back to port 12");

        var all2 = ZapretStrategyParser.Load(zapretDir, ZapretGameFilter.All).First(s => s.Id == "general");
        Check(all2.Arguments.Contains("1024-65535"), "all: the game filter did not open the port range");

        var tcp = ZapretStrategyParser.Load(zapretDir, ZapretGameFilter.Tcp).First(s => s.Id == "general");
        Check(tcp.Arguments.Contains("--wf-udp=443,19294-19344,50000-50100,12"), "tcp: UDP should stay closed");

        Console.WriteLine($"ZapretStrategyParser: {all.Count} strategies, all clean.");
    }

    private static string Head(string s) => s.Length <= 30 ? s : s[..30] + "…";

    private static void Check(bool ok, string what)
    {
        if (!ok) throw new Exception("ZapretStrategyParser self-check failed: " + what);
    }
}
