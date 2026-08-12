using System.Collections.Generic;

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
    private static Action<string, string>? _balloon;
    private static readonly List<(string Title, string Text)> _pending = new();

    /// <summary>
    /// Set by MainWindow once the tray icon exists — which is *after* the view model has run its
    /// startup update check. That check is the one time a new version gets announced, and its
    /// notification used to be dropped on the floor because there was nothing to show it with yet.
    /// Anything raised before the tray is ready waits here and goes out the moment it is.
    /// </summary>
    public static Action<string, string>? Balloon
    {
        get => _balloon;
        set
        {
            _balloon = value;
            if (value is null) return;
            foreach (var (title, text) in _pending) value(title, text);
            _pending.Clear();
        }
    }

    public static bool Enabled { get; set; } = true;
    public static bool UseBalloons { get; set; } = true;
    /// <summary>Connected / disconnected. Off by default — it fires on every toggle.</summary>
    public static bool OnConnection { get; set; }
    public static bool OnAppUpdate { get; set; } = true;

    public static void Show(string title, string text)
    {
        if (!Enabled || !UseBalloons) return;
        if (_balloon is { } show) show(title, text);
        else _pending.Add((title, text));   // the tray is not up yet; see Balloon
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
