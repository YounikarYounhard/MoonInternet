using Avalonia.Controls;
using Avalonia.Markup.Xaml;

namespace MoonInternet.Desktop.Views;

public partial class RoutingView : UserControl
{
    public RoutingView() => InitializeComponent();

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);
}
