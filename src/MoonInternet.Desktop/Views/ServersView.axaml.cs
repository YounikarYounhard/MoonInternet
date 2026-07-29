using Avalonia.Controls;
using Avalonia.Markup.Xaml;

namespace MoonInternet.Desktop.Views;

public partial class ServersView : UserControl
{
    public ServersView() => InitializeComponent();

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);
}
