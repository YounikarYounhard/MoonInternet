using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Animation;

namespace MoonInternet.App;

/// <summary>
/// The Home action row, as one panel with a dial on it.
///
/// At 0 the readouts have no width showing and the two buttons share the row. At 1 the readouts
/// have the left half and the buttons are stacked in the right. Every position between the two is
/// measured and arranged, so turning the dial does not swap one arrangement for another — the same
/// two buttons walk from where they were to where they are going, widening and narrowing as they go.
///
/// A crossfade was the obvious way and the wrong one: two copies of a button, one fading out at the
/// old place while another faded in at the new, which reads as a redraw rather than a move.
///
/// Children, in order: the readouts, the first button, the second. The buttons are each a container
/// so the mode can change what is inside one without changing what this panel is arranging.
/// </summary>
public class MorphActions : Panel
{
    private const double Gap = 8;      // between the readouts and the buttons, and between buttons
    private const double Stack = 6;    // between the two buttons once they are one above the other
    private const double Share = 0.55; // of the width the readouts take when they are up

    /// <summary>Which arrangement it is heading for. The panel animates its way there.</summary>
    public bool Open
    {
        get => (bool)GetValue(OpenProperty);
        set => SetValue(OpenProperty, value);
    }

    public static readonly DependencyProperty OpenProperty = DependencyProperty.Register(
        nameof(Open), typeof(bool), typeof(MorphActions),
        new PropertyMetadata(false, (d, e) => ((MorphActions)d).GoTo(Equals(e.NewValue, true))));

    /// <summary>Where it is between the two arrangements. Animated, not set.</summary>
    public double Progress
    {
        get => (double)GetValue(ProgressProperty);
        set => SetValue(ProgressProperty, value);
    }

    public static readonly DependencyProperty ProgressProperty = DependencyProperty.Register(
        nameof(Progress), typeof(double), typeof(MorphActions),
        new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.AffectsMeasure));

    public MorphActions() => ClipToBounds = true;

    private void GoTo(bool open)
    {
        // Before it is on screen there is nothing to travel from.
        if (!IsLoaded)
        {
            BeginAnimation(ProgressProperty, null);
            Progress = open ? 1 : 0;
            return;
        }
        BeginAnimation(ProgressProperty, new DoubleAnimation(open ? 1 : 0, new Duration(TimeSpan.FromMilliseconds(420)))
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut },
        });
    }

    private static double Lerp(double a, double b, double t) => a + (b - a) * t;

    protected override Size MeasureOverride(Size available)
    {
        if (InternalChildren.Count < 3) return default;
        double width = double.IsInfinity(available.Width) ? 0 : available.Width;
        double p = Math.Clamp(Progress, 0, 1);

        UIElement stats = InternalChildren[0], add = InternalChildren[1], paste = InternalChildren[2];

        // The readouts are always measured at the width they have when they are up, however much of
        // it is showing. Measured at the width of the moment, they re-wrap as they narrow, grow a
        // line, and push the page down before snapping back.
        double statsWidth = width * Share;
        double right = statsWidth + Gap;
        double buttonWidth = Lerp((width - Gap) / 2, Math.Max(1, width - right), p);

        stats.Measure(new Size(statsWidth, double.PositiveInfinity));
        add.Measure(new Size(buttonWidth, double.PositiveInfinity));
        paste.Measure(new Size(buttonWidth, double.PositiveInfinity));
        stats.Opacity = p;

        // ...and the height goes straight from one shape's to the other's, rather than being
        // whatever happens to be tallest at this instant.
        double height = Lerp(
            Math.Max(add.DesiredSize.Height, paste.DesiredSize.Height),
            Math.Max(stats.DesiredSize.Height, add.DesiredSize.Height + Stack + paste.DesiredSize.Height),
            p);
        return new Size(width, height);
    }

    protected override Size ArrangeOverride(Size final)
    {
        if (InternalChildren.Count < 3) return final;
        double p = Math.Clamp(Progress, 0, 1);
        UIElement stats = InternalChildren[0], add = InternalChildren[1], paste = InternalChildren[2];

        double statsWidth = final.Width * Share;
        double right = statsWidth + Gap;
        double buttonWidth = Lerp((final.Width - Gap) / 2, Math.Max(1, final.Width - right), p);

        stats.Arrange(new Rect(0, (final.Height - stats.DesiredSize.Height) / 2, statsWidth, stats.DesiredSize.Height));
        // Side by side at 0, one under the other at 1.
        add.Arrange(new Rect(Lerp(0, right, p), 0, buttonWidth, add.DesiredSize.Height));
        paste.Arrange(new Rect(
            Lerp(buttonWidth + Gap, right, p),
            Lerp(0, add.DesiredSize.Height + Stack, p),
            buttonWidth, paste.DesiredSize.Height));
        return final;
    }
}
