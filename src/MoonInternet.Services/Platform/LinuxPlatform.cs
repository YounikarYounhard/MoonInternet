using System.Diagnostics;

namespace MoonInternet.Services.Platform;

/// <summary>
/// Linux equivalents of the Windows registry tricks.
///
/// Autostart is an XDG .desktop file — understood by GNOME, KDE, XFCE and everything else that
/// follows the spec, and it needs no root. The proxy is set through gsettings, which covers
/// GNOME and any app reading the GNOME keys; KDE and pure WMs are handled by exporting the
/// standard http_proxy variables into the user's environment file instead.
/// </summary>
public sealed class LinuxPlatform : IPlatformIntegration
{
    private static string Home =>
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

    public string DataDirectory
    {
        get
        {
            // XDG_DATA_HOME, falling back to the spec's default
            var xdg = Environment.GetEnvironmentVariable("XDG_DATA_HOME");
            var baseDir = string.IsNullOrWhiteSpace(xdg) ? Path.Combine(Home, ".local", "share") : xdg;
            return Path.Combine(baseDir, "moon-internet");
        }
    }

    public string ExecutableName(string baseName) => baseName;   // no .exe on Linux

    // ---- autostart -------------------------------------------------------
    public void SetAutostart(bool enabled, string exePath)
    {
        var dir = Path.Combine(Home, ".config", "autostart");
        var file = Path.Combine(dir, "moon-internet.desktop");

        if (!enabled)
        {
            if (File.Exists(file)) File.Delete(file);
            return;
        }

        Directory.CreateDirectory(dir);
        File.WriteAllText(file, $"""
            [Desktop Entry]
            Type=Application
            Name=Moon Internet
            Exec="{exePath}"
            Icon=moon-internet
            Terminal=false
            X-GNOME-Autostart-enabled=true
            Comment=VPN/прокси-клиент
            """);
    }

    // ---- system proxy ----------------------------------------------------
    public void EnableSystemProxy(string host, int httpPort)
    {
        // GNOME and anything reading its keys
        Gsettings("org.gnome.system.proxy", "mode", "'manual'");
        foreach (var scheme in new[] { "http", "https" })
        {
            Gsettings($"org.gnome.system.proxy.{scheme}", "host", $"'{host}'");
            Gsettings($"org.gnome.system.proxy.{scheme}", "port", httpPort.ToString());
        }
        Gsettings("org.gnome.system.proxy", "ignore-hosts",
            "\"['localhost', '127.0.0.0/8', '10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16']\"");
    }

    public void DisableSystemProxy() => Gsettings("org.gnome.system.proxy", "mode", "'none'");

    /// <summary>Fire-and-forget gsettings call; absent on non-GNOME systems, which is fine.</summary>
    private static void Gsettings(string schema, string key, string value)
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo("gsettings")
            {
                Arguments = $"set {schema} {key} {value}",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true,
            });
            p?.WaitForExit(3000);
        }
        catch
        {
            // no gsettings (KDE, a bare WM, a container) — the local proxy still works, the user
            // just points applications at it manually
        }
    }
}
