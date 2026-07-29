using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using Microsoft.Win32;

namespace MoonInternet.Services.Platform;

/// <summary>
/// The original Windows behaviour, moved behind the interface unchanged: HKCU Run key for
/// autostart and the per-user WinINet proxy (neither needs admin).
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsPlatform : IPlatformIntegration
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ProxyKey = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
    private const string Name = "MoonInternet";

    private const int INTERNET_OPTION_SETTINGS_CHANGED = 39;
    private const int INTERNET_OPTION_REFRESH = 37;

    [DllImport("wininet.dll", SetLastError = true)]
    private static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);

    public string DataDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "MoonInternet");

    public string ExecutableName(string baseName) => baseName + ".exe";

    public void SetAutostart(bool enabled, string exePath)
    {
        using var k = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
        if (k is null) return;
        if (enabled) k.SetValue(Name, $"\"{exePath}\"");
        else if (k.GetValue(Name) is not null) k.DeleteValue(Name, throwOnMissingValue: false);
    }

    public void EnableSystemProxy(string host, int httpPort)
    {
        using var k = Registry.CurrentUser.OpenSubKey(ProxyKey, writable: true)
                      ?? throw new InvalidOperationException("Internet Settings key missing");
        k.SetValue("ProxyServer", $"{host}:{httpPort}");
        k.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
        k.SetValue("ProxyOverride",
            "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;192.168.*;<local>");
        Refresh();
    }

    public void DisableSystemProxy()
    {
        using var k = Registry.CurrentUser.OpenSubKey(ProxyKey, writable: true);
        k?.SetValue("ProxyEnable", 0, RegistryValueKind.DWord);
        Refresh();
    }

    private static void Refresh()
    {
        InternetSetOption(IntPtr.Zero, INTERNET_OPTION_SETTINGS_CHANGED, IntPtr.Zero, 0);
        InternetSetOption(IntPtr.Zero, INTERNET_OPTION_REFRESH, IntPtr.Zero, 0);
    }
}
