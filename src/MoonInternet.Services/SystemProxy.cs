using MoonInternet.Services.Platform;

namespace MoonInternet.Services;

/// <summary>
/// Points the desktop's proxy settings at our local listener. Used by "system proxy" mode.
/// The OS-specific part moved to <see cref="IPlatformIntegration"/>: WinINet on Windows,
/// gsettings on Linux.
/// </summary>
public static class SystemProxy
{
    public static void Enable(string host, int httpPort) =>
        PlatformIntegration.Current.EnableSystemProxy(host, httpPort);

    public static void Disable() => PlatformIntegration.Current.DisableSystemProxy();
}
