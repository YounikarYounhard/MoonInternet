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
    private static readonly Duration Length = new(TimeSpan.FromMilliseconds(260));

    public static bool GetOpen(DependencyObject o) => (bool)o.GetValue(OpenProperty);
    public static void SetOpen(DependencyObject o, bool value) => o.SetValue(OpenProperty, value);

    public static readonly DependencyProperty OpenProperty = DependencyProperty.RegisterAttached(
        "Open", typeof(bool), typeof(Fold), new PropertyMetadata(true, OnOpenChanged));

    private static void OnOpenChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not FrameworkElement element) return;
        bool open = Equals(e.NewValue, true);

        // Not on screen yet — a card that starts life folded should simply be folded, and there is
        // no height to animate from anyway.
        if (!element.IsLoaded)
        {
            element.BeginAnimation(FrameworkElement.MaxHeightProperty, null);
            element.ClearValue(FrameworkElement.MaxHeightProperty);
            element.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
            return;
        }

        double from, to;
        if (open)
        {
            element.Visibility = Visibility.Visible;
            element.Measure(new Size(element.ActualWidth > 0 ? element.ActualWidth : double.PositiveInfinity,
                                     double.PositiveInfinity));
            (from, to) = (0, element.DesiredSize.Height);
        }
        else
        {
            (from, to) = (element.ActualHeight, 0);
        }

        var run = new DoubleAnimation(from, to, Length)
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut },
        };
        run.Completed += (_, _) =>
        {
            // Hand the height back to the layout: held at its measured value, a card would stop
            // growing when a server was added to it.
            element.BeginAnimation(FrameworkElement.MaxHeightProperty, null);
            element.ClearValue(FrameworkElement.MaxHeightProperty);
            element.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
        };
        element.BeginAnimation(FrameworkElement.MaxHeightProperty, run);
    }
}
