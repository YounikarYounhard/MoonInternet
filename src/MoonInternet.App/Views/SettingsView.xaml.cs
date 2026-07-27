using System.Windows.Controls;

namespace MoonInternet.App.Views;

public partial class SettingsView : UserControl
{
    public SettingsView()
    {
        InitializeComponent();
        IsVisibleChanged += (_, e) => { if (e.NewValue is true) SettingsScroll.ScrollToTop(); };  // back to top each time the page opens
    }
}
