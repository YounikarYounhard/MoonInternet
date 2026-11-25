using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MoonInternet.App.Localization;

namespace MoonInternet.App.Views;

public partial class SettingsView : UserControl
{
    public SettingsView()
    {
        InitializeComponent();
        IsVisibleChanged += (_, e) => { if (e.NewValue is true) ResetScroll(); };
    }

    private void OpenSupport_Click(object sender, RoutedEventArgs e)
    {
        if (Window.GetWindow(this) is Window owner) new SupportWindow(owner).ShowDialog();
    }

    private void Restart_Click(object sender, RoutedEventArgs e)
    {
        if (Window.GetWindow(this) is not MainWindow window) return;
        var choice = MessageBox.Show(window, Loc.T("S_Settings_RestartWarning"), Loc.T("S_Settings_Restart"),
            MessageBoxButton.OKCancel, MessageBoxImage.Warning, MessageBoxResult.Cancel);
        if (choice == MessageBoxResult.OK) window.RestartApp();
    }

    /// <summary>
    /// Every page starts at the top, not where it was left. Coming back to Оформление and landing
    /// halfway down where you were ten minutes ago reads as a glitch, not as a convenience.
    /// </summary>
    private void ResetScroll()
    {
        SettingsScroll.ScrollToTop();
        foreach (var sv in FindScrollViewers(this)) sv.ScrollToTop();
    }

    /// <summary>
    /// The wheel, done by hand.
    ///
    /// The cards on these pages sit inside their own scrollable pieces, and WPF hands the wheel to
    /// the innermost one that will take it — which here swallowed almost all of it: sixty clicks
    /// moved the page about seventy pixels and the lower sections were effectively unreachable.
    /// Handling it on the way down and scrolling the page ourselves puts that back.
    /// </summary>
    private void PageScroll_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        if (sender is not ScrollViewer sv) return;
        sv.ScrollToVerticalOffset(sv.VerticalOffset - e.Delta);
        e.Handled = true;
    }

    private static IEnumerable<ScrollViewer> FindScrollViewers(DependencyObject root)
    {
        int n = System.Windows.Media.VisualTreeHelper.GetChildrenCount(root);
        for (int i = 0; i < n; i++)
        {
            var child = System.Windows.Media.VisualTreeHelper.GetChild(root, i);
            if (child is ScrollViewer sv) yield return sv;
            foreach (var deeper in FindScrollViewers(child)) yield return deeper;
        }
    }
}
