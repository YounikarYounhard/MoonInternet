using Avalonia;

namespace MoonInternet.Desktop;

internal static class Program
{
    // Avalonia needs to be initialised before anything touches its types, so keep this method
    // free of other work — same rule as the generated template.
    [STAThread]
    public static void Main(string[] args) => BuildAvaloniaApp()
        .StartWithClassicDesktopLifetime(args);

    public static AppBuilder BuildAvaloniaApp() => AppBuilder.Configure<App>()
        .UsePlatformDetect()
        .WithInterFont()
        .LogToTrace();
}
