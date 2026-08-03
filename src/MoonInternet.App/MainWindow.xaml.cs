using System.ComponentModel;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;
using H.NotifyIcon;
using MoonInternet.App.ViewModels;

namespace MoonInternet.App;

public partial class MainWindow : Window
{
    private readonly TaskbarIcon _tray;
    private bool _exiting;

    public MainWindow()
    {
        InitializeComponent();
        _tray = BuildTray();
        Loaded += (_, _) => ThemeService.ApplyToWindow(this, App.ActiveTheme);
        if (((MainViewModel)DataContext).StartMinimized)
        {
            // Render the window once (invisibly) BEFORE hiding, else WPF composites nothing and it comes back
            // solid BLACK when restored from the tray. Opacity 0 = no black, no visible flash.
            Opacity = 0;
            ShowInTaskbar = false;
            ContentRendered += HideAfterFirstRender;
        }
    }

    private void HideAfterFirstRender(object? sender, EventArgs e)
    {
        ContentRendered -= HideAfterFirstRender;
        Hide();
        Opacity = 1;
        ShowInTaskbar = true;
    }

    // full moon = connected, crescent = disconnected, red crescent = nothing reachable
    private System.Drawing.Icon? _iconOn, _iconOff, _iconDead;

    private TaskbarIcon BuildTray()
    {
        var menu = (System.Windows.Controls.ContextMenu)FindResource("TrayMenu");
        menu.DataContext = DataContext;   // bind the menu's header/toggle to the VM

        _iconOn = LoadTrayIcon("pack://application:,,,/Assets/btn_on.png");
        _iconOff = LoadTrayIcon("pack://application:,,,/Assets/btn_off.png");
        _iconDead = LoadTrayIcon("pack://application:,,,/Assets/btn_off.png", tint: System.Drawing.Color.FromArgb(0xFF, 0x6B, 0x8A));
        var vm = (MainViewModel)DataContext;

        var tray = new TaskbarIcon
        {
            ToolTipText = "Moon Internet",
            Icon = TrayIconFor(vm),
            ContextMenu = menu,
            DataContext = DataContext,     // H.NotifyIcon pushes THIS onto the menu when it opens → bindings resolve
        };
        // The icon carries the state: connected, idle, or "every server was measured and none
        // answered" — the last one looks like idle otherwise, and the user waits for a tunnel
        // that is never going to come up.
        vm.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName is nameof(MainViewModel.IsConnected) or nameof(MainViewModel.AllServersDown))
                Dispatcher.Invoke(() =>
                {
                    tray.Icon = TrayIconFor(vm);
                    tray.ToolTipText = vm.IsConnected ? "Moon Internet — подключено"
                        : vm.AllServersDown ? "Moon Internet — ни один сервер не отвечает"
                        : "Moon Internet — отключено";
                });
        };
        // re-assert every open (the host resets the menu's DataContext to the icon's on show)
        menu.Opened += (_, _) => menu.DataContext = DataContext;
        tray.TrayLeftMouseUp += (_, _) => ShowFromTray();
        tray.ForceCreate();
        return tray;
    }

    /// <summary>Which of the three tray icons the current state calls for.</summary>
    private System.Drawing.Icon? TrayIconFor(MainViewModel vm) =>
        vm.IsConnected ? _iconOn : vm.AllServersDown ? _iconDead : _iconOff;

    /// <summary>Loads a bundled PNG and renders it to a crisp 32-px tray icon (high-quality downscale — no pixelation).
    /// The HICON is kept alive for the whole session (these icons live as long as the tray does).</summary>
    /// <param name="tint">Recolours the artwork, keeping its shape and alpha — used for the red "nothing reachable" moon.</param>
    private static System.Drawing.Icon LoadTrayIcon(string packUri, System.Drawing.Color? tint = null)
    {
        const int size = 32;
        var res = System.Windows.Application.GetResourceStream(new Uri(packUri))!;
        using var src = new System.Drawing.Bitmap(res.Stream);
        using var canvas = new System.Drawing.Bitmap(size, size);
        using (var g = System.Drawing.Graphics.FromImage(canvas))
        {
            g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            g.PixelOffsetMode = System.Drawing.Drawing2D.PixelOffsetMode.HighQuality;
            g.CompositingQuality = System.Drawing.Drawing2D.CompositingQuality.HighQuality;

            if (tint is { } c)
            {
                // Zero the colour channels and add the tint back, so only the alpha of the
                // original survives: same moon, painted red.
                var m = new System.Drawing.Imaging.ColorMatrix(new[]
                {
                    new[] { 0f, 0f, 0f, 0f, 0f },
                    new[] { 0f, 0f, 0f, 0f, 0f },
                    new[] { 0f, 0f, 0f, 0f, 0f },
                    new[] { 0f, 0f, 0f, 1f, 0f },
                    new[] { c.R / 255f, c.G / 255f, c.B / 255f, 0f, 1f },
                });
                using var attrs = new System.Drawing.Imaging.ImageAttributes();
                attrs.SetColorMatrix(m);
                g.DrawImage(src, new System.Drawing.Rectangle(0, 0, size, size),
                            0, 0, src.Width, src.Height, System.Drawing.GraphicsUnit.Pixel, attrs);
            }
            else
            {
                g.DrawImage(src, new System.Drawing.Rectangle(0, 0, size, size));
            }
        }
        // FromHandle wraps a standalone HICON (GetHicon copies the bitmap bits), so disposing `canvas` is safe.
        // Don't Clone()+DestroyIcon: Clone shares the same handle, so destroying it invalidates the returned icon.
        return System.Drawing.Icon.FromHandle(canvas.GetHicon());
    }

    private void TrayShow_Click(object sender, RoutedEventArgs e) => ShowFromTray();
    private void TrayExit_Click(object sender, RoutedEventArgs e) => ExitApp();

    private void TrayServer_Click(object sender, RoutedEventArgs e)
    {
        if (sender is System.Windows.Controls.MenuItem { DataContext: ServerItem s })
            ((MainViewModel)DataContext).SelectServerCommand.Execute(s);   // select → reconnect if connected
    }
    private void TrayTun_Click(object sender, RoutedEventArgs e) => ((MainViewModel)DataContext).TunMode = true;
    private void TrayProxy_Click(object sender, RoutedEventArgs e) => ((MainViewModel)DataContext).TunMode = false;
    private void TrayRouting_Click(object sender, RoutedEventArgs e)
    {
        ((MainViewModel)DataContext).NavigateCommand.Execute("Routing");
        ShowFromTray(resetToHome: false);   // this menu item asked for a specific page — keep it
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        if (PresentationSource.FromVisual(this) is HwndSource src) src.AddHook(WndProc);
    }

    private static IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        const int WM_NCLBUTTONDBLCLK = 0x00A3; // caption double-click → maximize; swallow it
        if (msg == WM_NCLBUTTONDBLCLK) handled = true;
        return IntPtr.Zero;
    }

    private void Minimize_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    private void Maximize_Click(object sender, RoutedEventArgs e) =>
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void Close_Click(object sender, RoutedEventArgs e) => HideToTray();

    private void HideToTray() => Hide();

    public void ShowFromTray(bool resetToHome = true)
    {
        // Coming back from the tray should feel like opening the app fresh: land on Home (the connect button),
        // not on whatever settings sub-page happened to be open when it was hidden.
        if (resetToHome && DataContext is MainViewModel vm) vm.ResetToHome();
        Show();
        WindowState = WindowState.Normal;
        ShowInTaskbar = true;
        Activate();
        Topmost = true; Topmost = false;   // pull to front even if another window had focus
    }

    private void ExitApp()
    {
        _exiting = true;
        ((MainViewModel)DataContext).Shutdown();
        _tray.Dispose();
        _iconOn?.Dispose(); _iconOff?.Dispose();
        Application.Current.Shutdown();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!_exiting) { e.Cancel = true; HideToTray(); return; }
        base.OnClosing(e);
    }
}
