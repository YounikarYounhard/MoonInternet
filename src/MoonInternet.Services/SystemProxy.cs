using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace MoonInternet.Services;

/// <summary>Toggles the per-user WinINet system proxy (no admin required). Used by "system proxy" mode.</summary>
public static class SystemProxy
{
    private const string Key = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
    private const int INTERNET_OPTION_SETTINGS_CHANGED = 39;
    private const int INTERNET_OPTION_REFRESH = 37;

    [DllImport("wininet.dll", SetLastError = true)]
    private static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);

    public static void Enable(string host, int httpPort)
    {
        using var k = Registry.CurrentUser.OpenSubKey(Key, writable: true)
                      ?? throw new InvalidOperationException("Internet Settings key missing");
        k.SetValue("ProxyServer", $"{host}:{httpPort}");
        k.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
        k.SetValue("ProxyOverride", "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;192.168.*;<local>");
        Refresh();
    }

    public static void Disable()
    {
        using var k = Registry.CurrentUser.OpenSubKey(Key, writable: true);
        k?.SetValue("ProxyEnable", 0, RegistryValueKind.DWord);
        Refresh();
    }

    private static void Refresh()
    {
        InternetSetOption(IntPtr.Zero, INTERNET_OPTION_SETTINGS_CHANGED, IntPtr.Zero, 0);
        InternetSetOption(IntPtr.Zero, INTERNET_OPTION_REFRESH, IntPtr.Zero, 0);
    }
}
