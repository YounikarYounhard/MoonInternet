using System.IO;
using System.Threading;
using System.Windows;
using System.Windows.Threading;
using MoonInternet.App.Models;

namespace MoonInternet.App;

public partial class App : Application
{
    private static readonly string LogPath = MoonInternet.Core.AppPaths.In("error.log");
    private const string InstanceKey = "MoonInternet.SingleInstance.v1";
    private Mutex? _instanceMutex;
    private EventWaitHandle? _showSignal;
    private MainWindow? _window;

    /// <summary>The active theme, loaded at startup and edited from Settings.</summary>
    public static Theme ActiveTheme { get; private set; } = new();

    public App()
    {
        DispatcherUnhandledException += OnDispatcherException;
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log(e.ExceptionObject as Exception);
    }

    protected override void OnStartup(StartupEventArgs e)
    {
        // Single instance: if a Moon Internet is already running (even hidden in the tray), wake ITS window and
        // quit this launch — no duplicate app, no second tunnel/connection.
        _instanceMutex = new Mutex(true, InstanceKey, out bool isFirst);
        if (!isFirst)
        {
            try { EventWaitHandle.OpenExisting(InstanceKey + ".show").Set(); } catch { /* first is mid-shutdown */ }
            Shutdown();
            return;
        }
        _showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, InstanceKey + ".show");
        new Thread(() => { while (_showSignal.WaitOne()) Dispatcher.BeginInvoke(() => _window?.ShowFromTray()); })
            { IsBackground = true, Name = "MoonShowSignal" }.Start();

        base.OnStartup(e);

        // Before the window: every localised string is a DynamicResource, and with no dictionary
        // merged they resolve to nothing and the whole UI comes up blank.
        Localization.Loc.Apply(Localization.Loc.Resolve(AppSettings.Load().Language));

        ActiveTheme = ThemeStore.Load();
        ThemeService.ApplyColors(ActiveTheme); // colours before the window renders (window bg/font applied on Loaded)
        _window = new MainWindow();
        MainWindow = _window;
        _window.Show();
    }

    private void OnDispatcherException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        Log(e.Exception);
        MessageBox.Show(e.Exception.Message, "Moon Internet — ошибка", MessageBoxButton.OK, MessageBoxImage.Error);
        e.Handled = true;
    }

    internal static void Log(Exception? ex)
    {
        if (ex is null) return;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(LogPath)!);
            File.AppendAllText(LogPath, $"[{DateTime.Now:o}] {ex}\n\n");
        }
        catch { /* logging must never throw */ }
    }
}
