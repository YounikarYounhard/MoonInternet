using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MoonInternet.App.ViewModels;

namespace MoonInternet.App.Views;

public partial class ServersView : UserControl
{
    public ServersView()
    {
        InitializeComponent();
        // Return to the top of the list each time this page is opened (don't keep the last scroll position).
        IsVisibleChanged += (_, e) => { if (e.NewValue is true) ListScroll.ScrollToTop(); };
    }

    private void Chip_Checked(object sender, RoutedEventArgs e)
    {
        if (DataContext is MainViewModel vm && sender is RadioButton { Content: string s })
            vm.Filter = s;
    }

    // Rows swallow the wheel otherwise; scroll the outer viewer ourselves so the wheel works with no visible bar.
    private void ListScroll_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        var sv = (ScrollViewer)sender;
        sv.ScrollToVerticalOffset(sv.VerticalOffset - e.Delta);
        e.Handled = true;
    }
}
