using System.Windows;
using System.Windows.Media.Animation;

namespace MoonInternet.App;

/// <summary>
/// Give up the height over a quarter of a second instead of between two frames.
///
/// A subscription card taller than the window drags the page back up with it when it folds: the
/// list is scrolled past an end that has just moved, so the scroller pulls it back. Nothing is
/// wrong with the pull — there is simply nothing left below to hold the page where it was — but
/// arriving there in one frame reads as a kick. Shrinking over time makes the same pull happen a
/// few pixels at a time, which is all it needed.
///
/// Bound where a Visibility binding used to be, and it owns Visibility for that reason: the two
/// would otherwise take turns hiding the same element.
/// </summary>
public static class Fold
{
    private static readonly Duration Length = new(TimeSpan.FromMilliseconds(420));

    public static bool GetOpen(DependencyObject o) => (bool)o.GetValue(OpenProperty);
    public static void SetOpen(DependencyObject o, bool value) => o.SetValue(OpenProperty, value);

    public static readonly DependencyProperty OpenProperty = DependencyProperty.RegisterAttached(
        "Open", typeof(bool), typeof(Fold), new PropertyMetadata(true, OnOpenChanged));

    /// <summary>
    /// Fold sideways instead of down — for something that shares a row rather than a column.
    /// </summary>
    public static bool GetWide(DependencyObject o) => (bool)o.GetValue(WideProperty);
    public static void SetWide(DependencyObject o, bool value) => o.SetValue(WideProperty, value);

    public static readonly DependencyProperty WideProperty = DependencyProperty.RegisterAttached(
        "Wide", typeof(bool), typeof(Fold), new PropertyMetadata(false));

    private static void OnOpenChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not FrameworkElement element) return;
        bool open = Equals(e.NewValue, true);
        bool wide = GetWide(element);
        var side = wide ? FrameworkElement.MaxWidthProperty : FrameworkElement.MaxHeightProperty;

        void HandBack()
        {
            // Give the size back to the layout: held at its measured value, a card would stop
            // growing when a server was added to it.
            element.BeginAnimation(side, null);
            element.ClearValue(side);
            element.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
        }

        // Not on screen yet — something that starts life folded should simply be folded, and there
        // is no size to animate from anyway.
        if (!element.IsLoaded) { HandBack(); return; }

        double from, to;
        if (open)
        {
            element.Visibility = Visibility.Visible;
            element.Measure(wide
                ? new Size(double.PositiveInfinity, element.ActualHeight > 0 ? element.ActualHeight : double.PositiveInfinity)
                : new Size(element.ActualWidth > 0 ? element.ActualWidth : double.PositiveInfinity, double.PositiveInfinity));
            (from, to) = (0, wide ? element.DesiredSize.Width : element.DesiredSize.Height);
        }
        else
        {
            (from, to) = (wide ? element.ActualWidth : element.ActualHeight, 0);
        }

        var run = new DoubleAnimation(from, to, Length)
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut },
        };
        run.Completed += (_, _) => HandBack();
        element.BeginAnimation(side, run);
    }
}
