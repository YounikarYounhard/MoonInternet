namespace MoonInternet.Services.Platform;

/// <summary>
/// The handful of things that genuinely differ between Windows and Linux. Everything else in
/// Services — subscriptions, connection handling, ping, routing — is plain .NET and shared.
/// </summary>
public interface IPlatformIntegration
{
    /// <summary>Start the app when the user logs in.</summary>
    void SetAutostart(bool enabled, string exePath);

    /// <summary>Point the desktop's HTTP proxy settings at our local listener.</summary>
    void EnableSystemProxy(string host, int httpPort);

    void DisableSystemProxy();

    /// <summary>Where cores, geo files and settings live for this OS.</summary>
    string DataDirectory { get; }

    /// <summary>File name of a core binary, e.g. "xray" vs "xray.exe".</summary>
    string ExecutableName(string baseName);
}

/// <summary>Picks the implementation for the OS we are actually running on.</summary>
public static class PlatformIntegration
{
    public static IPlatformIntegration Current { get; } =
        OperatingSystem.IsWindows() ? new WindowsPlatform() : new LinuxPlatform();
}
