using MoonInternet.Services.Platform;

namespace MoonInternet.Services;

/// <summary>
/// Per-user autostart. The OS-specific part moved to <see cref="IPlatformIntegration"/> —
/// HKCU Run key on Windows, an XDG .desktop file on Linux. Neither needs admin.
/// </summary>
public static class Autostart
{
    public static void Apply(bool enabled, string exePath) =>
        PlatformIntegration.Current.SetAutostart(enabled, exePath);
}
