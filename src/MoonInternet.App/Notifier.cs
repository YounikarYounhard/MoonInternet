namespace MoonInternet.App;

/// <summary>
/// Tray balloons, one entry point. The window owns the tray icon, so it plugs the real
/// implementation in here at startup and the view model never has to reach for a Window.
///
/// The switches live here rather than being read from settings at every call site: the view model
/// pushes them in when they change, and callers just say what happened.
/// </summary>
public static class Notifier
{
    /// <summary>Set by MainWindow once the tray icon exists.</summary>
    public static Action<string, string>? Balloon;

    public static bool Enabled { get; set; } = true;
    public static bool UseBalloons { get; set; } = true;
    /// <summary>Connected / disconnected. Off by default — it fires on every toggle.</summary>
    public static bool OnConnection { get; set; }
    public static bool OnAppUpdate { get; set; } = true;

    public static void Show(string title, string text)
    {
        if (Enabled && UseBalloons) Balloon?.Invoke(title, text);
    }

    public static void Connection(string title, string text)
    {
        if (OnConnection) Show(title, text);
    }

    public static void AppUpdate(string title, string text)
    {
        if (OnAppUpdate) Show(title, text);
    }
}
