using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace MoonInternet.App;

/// <summary>
/// Slide a row to its new place instead of letting it appear there.
///
/// WPF has no equivalent of a list item animating its own position: re-sorting an ItemsControl
/// lays everything out again, so the row you just clicked is somewhere else on the next frame.
/// That jump is the one you are looking straight at, which is what makes it feel harsh.
///
/// Three things had to be right, and the first attempt got all three wrong — hence the comments.
/// </summary>
public static class FluidMove
{
    /// <summary>Long enough to follow by eye, short enough not to be in the way.</summary>
    private static readonly Duration Length = new(TimeSpan.FromMilliseconds(450));

    /// <summary>
    /// Until when a move is not worth animating.
    ///
    /// Folding a subscription up moves every row below it, and each one honouring that with its own
    /// slide is a screenful of things in motion at once — which reads as the list shuddering rather
    /// than settling. The animation is for a row changing PLACE in its list; a card changing shape
    /// is a different event, and the rows should simply be where they now are.
    /// </summary>
    private static DateTime _quietUntil;
    public static void Hush() => _quietUntil = DateTime.UtcNow.AddMilliseconds(700);
    private static bool Quiet => DateTime.UtcNow < _quietUntil;

    public static bool GetAnimate(DependencyObject o) => (bool)o.GetValue(AnimateProperty);
    public static void SetAnimate(DependencyObject o, bool value) => o.SetValue(AnimateProperty, value);

    public static readonly DependencyProperty AnimateProperty = DependencyProperty.RegisterAttached(
        "Animate", typeof(bool), typeof(FluidMove), new PropertyMetadata(false, OnAnimateChanged));

    /// <summary>
    /// Where each ITEM was last laid out — keyed by the row's data, not by the control showing it.
    ///
    /// (1) Re-sorting rebuilds the containers, so the element arriving in the new position is not
    /// the one that left the old one and remembers nothing. The data object outlives that, so it
    /// carries the memory. Weak keys: a server that goes away takes its entry with it.
    /// </summary>
    private static readonly ConditionalWeakTable<object, StrongBox<double>> LastTop = new();

    private static void OnAnimateChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not FrameworkElement element || !Equals(e.NewValue, true)) return;
        element.LayoutUpdated += (_, _) => Track(element);
    }

    private static void Track(FrameworkElement element)
    {
        if (!element.IsVisible || PresentationSource.FromVisual(element) is null) return;
        if (element.DataContext is not { } item) return;
        // (2) Measured against the list, not against whatever is immediately above. A row is the
        // only child of its own container, so its offset THERE is always zero however far it has
        // moved. Against the list, scrolling also does not register: the panel moves inside the
        // scroller, the rows do not move inside the panel.
        if (ListAbove(element) is not { } list) return;

        var slide = element.RenderTransform as TranslateTransform;
        double rendered;
        try { rendered = element.TransformToAncestor(list).Transform(default).Y; }
        catch { return; }   // mid-teardown: no longer under that list

        // (3) ...minus our own animation. That transform is part of what TransformToAncestor
        // reports, so measuring it raw fed the offset back into itself: every layout pass added
        // another slide onto the last one, and since LayoutUpdated fires on things as ordinary as
        // the mouse moving, the rows visibly flew off the page. What is wanted here is where the
        // row was PUT, which is the rendered position with the slide taken back out.
        double top = rendered - (slide?.Y ?? 0);

        var slot = LastTop.GetValue(item, _ => new StrongBox<double>(double.NaN));
        double previous = slot.Value;
        slot.Value = top;
        if (double.IsNaN(previous)) return;   // first sight of this row

        if (Quiet) return;   // the list is changing shape, not re-ordering

        double moved = previous - top;
        // Sub-pixel drift is not a move, and a jump of a screenful is a page change rather than a
        // re-sort — animating either would be a list that never sits still.
        if (Math.Abs(moved) < 0.5 || Math.Abs(moved) > 2000) return;

        // Leave a transform we did not make alone: a template may be using it for something else,
        // and fighting over it would break that instead.
        if (slide is null)
        {
            if (element.RenderTransform is not null && !element.RenderTransform.Value.IsIdentity) return;
            slide = new TranslateTransform();
            element.RenderTransform = slide;
        }

        slide.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(moved, 0, Length)
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut },
        });
    }

    /// <summary>The list this row belongs to — the thing it moves within.</summary>
    private static ItemsControl? ListAbove(DependencyObject from)
    {
        for (var at = VisualTreeHelper.GetParent(from); at is not null; at = VisualTreeHelper.GetParent(at))
            if (at is ItemsControl list) return list;
        return null;
    }
}
