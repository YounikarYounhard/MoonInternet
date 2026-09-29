using System.IO;

namespace MoonInternet.App.Models;

/// <summary>
/// The engines the app can run, and which of them are actually here. The Android build's
/// <c>Cores.kt</c> is the same list in the same order — the Ядра page has to read identically on
/// both, so the order lives in one place on each side and nowhere else.
///
/// Built-in means the app cannot work without it: Xray carries every protocol a subscription is
/// likely to hand out, and sing-box carries Hysteria2, which is in very nearly all of them. The
/// other two are a choice — Запрет is a feature of its own rather than a way to reach a server, and
/// AmneziaWG is one protocol that xray cannot speak.
///
/// A downloadable engine is not in the installer. It arrives afterwards and lands in the writable
/// data folder — not beside the exe, which is under Program Files and read-only to an app that
/// deliberately runs without administrator rights. Landing there has the same happy consequence it
/// has on the phone: the installer never touches that folder, so an engine installed once survives
/// every app update and reinstall.
/// </summary>
public sealed record MoonCore(
    string Id, string Title, string Folder, bool BuiltIn, string DescKey, bool Downloadable = false)
{
    // Declaration order is the order of the page, built-in first: the page shows them as two
    // groups, what the app carries and what can be added to it.
    public static readonly MoonCore[] All =
    {
        new("xray",   "Xray",      "xray",    true,  "S_Cores_Xray"),
        new("singbox","sing-box",  "singbox", true,  "S_Cores_SingBox"),
        new("zapret", "Zapret",    "zapret",  false, "S_Cores_Zapret"),
        new("awg",    "AmneziaWG", "awg",     false, "S_Cores_Awg", Downloadable: true),
    };

    public static MoonCore? ById(string id) => Array.Find(All, c => c.Id == id);

    /// <summary>Where this engine's files are, whichever of the two places it came from.</summary>
    public string DirIn(string bundledCoresDir)
    {
        string downloaded = Path.Combine(MoonInternet.Core.AppPaths.In("cores"), Folder);
        return Downloadable && Directory.Exists(downloaded) ? downloaded
                                                            : Path.Combine(bundledCoresDir, Folder);
    }

    /// <summary>True when the engine's files are on disk and it can actually be started.</summary>
    public bool Present(string bundledCoresDir) => Directory.Exists(DirIn(bundledCoresDir));
}
