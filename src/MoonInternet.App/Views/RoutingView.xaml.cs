using System.Windows;
using System.Windows.Controls;
using MoonInternet.App.ViewModels;
using MoonInternet.Core.Models;

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

    /// <summary>
    /// A ContextMenu is not in this control's visual tree, so its items cannot bind to the view
    /// model the way the rest of the page does — that is why they all came up greyed. The profile
    /// arrives on the item's Tag and the view model is read from here.
    /// </summary>
    private (MainViewModel vm, RoutingProfile p)? Target(object sender)
    {
        if (DataContext is not MainViewModel vm) return null;
        if ((sender as MenuItem)?.Tag is not RoutingProfile p) return null;
        return (vm, p);
    }

    private void MenuEdit_Click(object sender, RoutedEventArgs e)
    {
        if (Target(sender) is { } t) t.vm.EditRoutingProfileCommand.Execute(t.p);
    }

    private void MenuDuplicate_Click(object sender, RoutedEventArgs e)
    {
        if (Target(sender) is { } t) t.vm.DuplicateRoutingCommand.Execute(t.p);
    }

    private void MenuExport_Click(object sender, RoutedEventArgs e)
    {
        if (Target(sender) is { } t) t.vm.ExportRoutingCommand.Execute(t.p);
    }

    private void MenuDelete_Click(object sender, RoutedEventArgs e)
    {
        if (Target(sender) is { } t) t.vm.DeleteRoutingCommand.Execute(t.p);
    }
}
