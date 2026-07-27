using Microsoft.Win32;

namespace MoonInternet.Services;

/// <summary>Per-user autostart via the HKCU Run key (no admin needed).</summary>
public static class Autostart
{
    private const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string Name = "MoonInternet";

    public static void Apply(bool enabled, string exePath)
    {
        using var k = Registry.CurrentUser.OpenSubKey(Key, writable: true);
        if (k is null) return;
        if (enabled) k.SetValue(Name, $"\"{exePath}\"");
        else if (k.GetValue(Name) is not null) k.DeleteValue(Name, throwOnMissingValue: false);
    }
}
