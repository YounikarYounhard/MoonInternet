using System.IO;

namespace MoonInternet.App;

/// <summary>Locates the bundled <c>cores/</c> folder (next to the exe when installed, up the tree in dev).</summary>
public static class CoreLocator
{
    public static string CoresDir()
    {
        string? dir = AppContext.BaseDirectory;
        for (int i = 0; i < 6 && dir is not null; i++)
        {
            string candidate = Path.Combine(dir, "cores");
            if (Directory.Exists(candidate)) return candidate;
            dir = Directory.GetParent(dir)?.FullName;
        }
        return Path.Combine(AppContext.BaseDirectory, "cores");
    }
}
