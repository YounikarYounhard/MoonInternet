using System.IO;
using System.Threading;
using System.Diagnostics;
using System.Windows;
using System.Windows.Threading;
using MoonInternet.App.Models;

namespace MoonInternet.App;

public partial class App : Application
{
    private static readonly string LogPath = MoonInternet.Core.AppPaths.In("error.log");
    private const string InstanceKey = "MoonInternet.SingleInstance.v1";
    private const string RestartArgument = "--restart-after";
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
        // Opening the mutex before the old process exits would only wake the old window.
        if (e.Args is [RestartArgument, var pid] && int.TryParse(pid, out var previousPid)
            && !WaitForPreviousInstance(previousPid))
        {
            Localization.Loc.Apply(Localization.Loc.Resolve(AppSettings.Load().Language));
            MessageBox.Show(Localization.Loc.T("S_Settings_RestartFailed"),
                Localization.Loc.T("S_Settings_Restart"), MessageBoxButton.OK, MessageBoxImage.Warning);
            Shutdown();
            return;
        }

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
        // Whatever the updater downloaded has done its job by the time we run again.
        MoonInternet.Services.UpdateDownloader.CleanUp();

        ActiveTheme = ThemeStore.Load();
        ThemeService.ApplyColors(ActiveTheme); // colours before the window renders (window bg/font applied on Loaded)
        _window = new MainWindow();
        MainWindow = _window;
        _window.Show();
    }

    internal static void LaunchRestart()
    {
        var executable = Environment.ProcessPath ?? throw new InvalidOperationException("No application path.");
        var start = new ProcessStartInfo(executable);
        if (Path.GetFileNameWithoutExtension(executable).Equals("dotnet", StringComparison.OrdinalIgnoreCase))
            start.ArgumentList.Add(typeof(App).Assembly.Location);
        start.ArgumentList.Add(RestartArgument);
        start.ArgumentList.Add(Environment.ProcessId.ToString(System.Globalization.CultureInfo.InvariantCulture));
        using var process = Process.Start(start) ?? throw new InvalidOperationException("Could not start the application.");
    }

    private static bool WaitForPreviousInstance(int pid)
    {
        try
        {
            using var previous = Process.GetProcessById(pid);
            return previous.WaitForExit(30_000);
        }
        catch (ArgumentException) { return true; } // Already exited.
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
