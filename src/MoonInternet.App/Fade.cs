using System.Windows;
using System.Windows.Media.Animation;

namespace MoonInternet.App;

/// <summary>
/// Show or hide by fading, for two things that take turns in the same place.
///
/// <see cref="Fold"/> changes a size, which is right when something joins or leaves a list. This is
/// for the other case: one arrangement replacing another — the Home row stacks its buttons beside
/// the readouts while connected and stands them side by side when there is nothing to read. Sliding
/// or shrinking between those two says the wrong thing; they simply swap.
/// </summary>
public static class Fade
{
    private static readonly Duration In = new(TimeSpan.FromMilliseconds(280));
    private static readonly Duration Out = new(TimeSpan.FromMilliseconds(200));

    public static bool GetVisible(DependencyObject o) => (bool)o.GetValue(VisibleProperty);
    public static void SetVisible(DependencyObject o, bool value) => o.SetValue(VisibleProperty, value);

    public static readonly DependencyProperty VisibleProperty = DependencyProperty.RegisterAttached(
        "Visible", typeof(bool), typeof(Fade), new PropertyMetadata(true, OnVisibleChanged));

    private static void OnVisibleChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not UIElement element) return;
        bool show = Equals(e.NewValue, true);

        // Nothing on screen to fade from — a page that opens in this state opens in it.
        if (element is FrameworkElement { IsLoaded: false })
        {
            element.BeginAnimation(UIElement.OpacityProperty, null);
            element.Opacity = show ? 1 : 0;
            element.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
            return;
        }

        if (show) element.Visibility = Visibility.Visible;

        var run = new DoubleAnimation(show ? 1 : 0, show ? In : Out)
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
        };
        // The one going out stops taking room the moment it is gone, so the row it shares can
        // settle at the height of whichever arrangement stayed.
        run.Completed += (_, _) => { if (!show) element.Visibility = Visibility.Collapsed; };
        element.BeginAnimation(UIElement.OpacityProperty, run);
    }
}
