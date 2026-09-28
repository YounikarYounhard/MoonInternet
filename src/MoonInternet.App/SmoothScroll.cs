using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Animation;

namespace MoonInternet.App;

/// <summary>
/// Takes a <see cref="ScrollViewer"/> back to the top as a movement instead of a jump.
///
/// ScrollToVerticalOffset(0) is instant: press the tab you are already on and the page is simply
/// somewhere else, with nothing to tell the eye that it went up rather than changed. The phone
/// glides, and this is the same ride on the desktop.
///
/// VerticalOffset is read-only, so there is nothing to animate directly — hence the attached
/// property: the animation drives it, and every step forwards the value to the scroll viewer.
/// </summary>
public static class SmoothScroll
{
    public static readonly DependencyProperty OffsetProperty =
        DependencyProperty.RegisterAttached(
            "Offset", typeof(double), typeof(SmoothScroll),
            new PropertyMetadata(0.0, (d, e) =>
            {
                if (d is ScrollViewer sv) sv.ScrollToVerticalOffset((double)e.NewValue);
            }));

    public static void SetOffset(DependencyObject d, double v) => d.SetValue(OffsetProperty, v);
    public static double GetOffset(DependencyObject d) => (double)d.GetValue(OffsetProperty);

    public static void ToTop(ScrollViewer sv)
    {
        double from = sv.VerticalOffset;
        if (from <= 0.5) return;

        // Long enough to read as a movement, short enough not to be a wait. A fixed duration makes
        // a short page feel sluggish and a long one feel like a rocket, so it grows with the
        // distance and then stops growing.
        double ms = Math.Clamp(from * 0.6, 360, 780);
        var slide = new DoubleAnimation(from, 0, TimeSpan.FromMilliseconds(ms))
        {
            // Eased at BOTH ends. An ease-out alone leaves the start abrupt — full speed from a
            // standstill in one frame, which is the jolt the eye actually objects to; the soft
            // landing was never the problem. This pulls away gently and arrives gently.
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut },
            // Released at the end, so the wheel and the scrollbar own the offset again. The value
            // it falls back to is zero, which is where the animation was going anyway.
            FillBehavior = FillBehavior.Stop,
        };
        slide.Completed += (_, _) => sv.ScrollToVerticalOffset(0);
        sv.BeginAnimation(OffsetProperty, slide);
    }
}
