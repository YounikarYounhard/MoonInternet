using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;

namespace MoonInternet.Desktop.Views;

public partial class MainWindow : Window
{
    public MainWindow() => InitializeComponent();

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);

    /// <summary>We draw our own titlebar, so dragging has to be wired up by hand.</summary>
    private void TitleBar_PointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) BeginMoveDrag(e);
    }

    private void Minimize_Click(object? sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    // Closing hides to the tray on the desktop build; for now it just exits.
    private void Close_Click(object? sender, RoutedEventArgs e) => Close();
}
