using Avalonia.Controls;
using Avalonia.Markup.Xaml;

namespace MoonInternet.Desktop.Views;

public partial class SettingsView : UserControl
{
    public SettingsView() => InitializeComponent();

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);
}
