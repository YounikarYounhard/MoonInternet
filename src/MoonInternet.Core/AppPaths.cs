using System.IO;

namespace MoonInternet.Core;

/// <summary>
/// App data (settings, routing, subscriptions, geo, logs) lives in a <c>save</c> folder at the install ROOT:
///   default install  →  &lt;root&gt;\app\MoonInternet.exe  +  &lt;root&gt;\save   (exe sits in an "app" subfolder)
///   custom (flat)     →  &lt;root&gt;\MoonInternet.exe      +  &lt;root&gt;\save
/// So when the exe is inside an "app" folder we use the SIBLING <c>save</c>; otherwise <c>save</c> next to the exe.
/// The installer grants that folder write access. If it still isn't writable, fall back to %APPDATA%\MoonInternet.
/// </summary>
public static class AppPaths
{
    /// <summary>The folder to read/write all app data. Guaranteed to exist and be writable.</summary>
    public static string DataDir { get; } = Resolve();

    /// <summary><see cref="DataDir"/> + <paramref name="name"/> (a file or subfolder name).</summary>
    public static string In(string name) => Path.Combine(DataDir, name);

    private static string Resolve()
    {
        string exeDir = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        string parent = Path.GetDirectoryName(exeDir) ?? exeDir;

        // default layout: exe in "<root>\app" → data is the sibling "<root>\save"; otherwise "<exeDir>\save".
        string save = string.Equals(Path.GetFileName(exeDir), "app", StringComparison.OrdinalIgnoreCase)
            ? Path.Combine(parent, "save")
            : Path.Combine(exeDir, "save");
        if (TryWritable(save)) return save;

        string roaming = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "MoonInternet");
        try { Directory.CreateDirectory(roaming); } catch { }
        return roaming;
    }

    private static bool TryWritable(string dir)
    {
        try
        {
            Directory.CreateDirectory(dir);
            string probe = Path.Combine(dir, ".writetest");
            File.WriteAllText(probe, "");
            File.Delete(probe);
            return true;
        }
        catch { return false; }
    }
}
