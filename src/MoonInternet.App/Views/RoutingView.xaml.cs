using System.Windows;
using System.Windows.Controls;

namespace MoonInternet.App.Views;

public partial class RoutingView : UserControl
{
    public RoutingView() => InitializeComponent();

    /// <summary>
    /// The phone opens a profile's menu from a "⋮" button, not from a right-click nobody would
    /// guess at, so the button drops its own context menu on a plain left click.
    /// </summary>
    private void RoutingMenu_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button b || b.ContextMenu is null) return;
        b.ContextMenu.PlacementTarget = b;
        b.ContextMenu.IsOpen = true;
        e.Handled = true;   // otherwise the card behind it takes the click and switches profile
    }
}
