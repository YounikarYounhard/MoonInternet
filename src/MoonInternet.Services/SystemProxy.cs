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

    /// <summary>The private ranges, kept out of the proxy unless the user asks otherwise.</summary>
    private static readonly string[] LanRanges =
    {
        "10.*", "172.16.*", "172.17.*", "172.18.*", "172.19.*", "172.20.*", "192.168.*", "<local>",
    };

    /// <param name="lanThroughProxy">
    /// Send local-network traffic through the proxy too. Both this and <paramref name="extraBypass"/>
    /// were settings the user could change while this list stayed hard-coded, so neither did anything.
    /// </param>
    /// <param name="extraBypass">Hosts the user wants to reach directly; ';', ',' or newline separated.</param>
    public static void Enable(string host, int httpPort, bool lanThroughProxy = false, string? extraBypass = null)
    {
        using var k = Registry.CurrentUser.OpenSubKey(Key, writable: true)
                      ?? throw new InvalidOperationException("Internet Settings key missing");
        k.SetValue("ProxyServer", $"{host}:{httpPort}");
        k.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);

        k.SetValue("ProxyOverride", BypassList(lanThroughProxy, extraBypass));
        Refresh();
    }

    /// <summary>The ProxyOverride value, split out so it can be checked without touching the registry.</summary>
    public static string BypassList(bool lanThroughProxy, string? extraBypass)
    {
        // localhost stays out of the proxy whatever happens — the proxy is what lives there.
        var bypass = new List<string> { "localhost", "127.*" };
        if (!lanThroughProxy) bypass.AddRange(LanRanges);
        if (!string.IsNullOrWhiteSpace(extraBypass))
            bypass.AddRange(extraBypass.Split(new[] { ';', ',', '\n', '\r', ' ', '\t' },
                                              StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
        return string.Join(";", bypass.Distinct(StringComparer.OrdinalIgnoreCase));
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
