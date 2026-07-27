using System.Windows.Controls;
using System.Windows.Input;

namespace MoonInternet.App.Views;

public partial class HomeView : UserControl
{
    public HomeView()
    {
        InitializeComponent();
        IsVisibleChanged += (_, e) => { if (e.NewValue is true) ListScroll.ScrollToTop(); };  // back to top each time the page opens
    }

    // The inner item rows would otherwise swallow the wheel; scroll the outer viewer ourselves so the wheel works.
    private void ListScroll_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        var sv = (ScrollViewer)sender;
        sv.ScrollToVerticalOffset(sv.VerticalOffset - e.Delta);
        e.Handled = true;
    }
}
