using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace MoonInternet.App;

/// <summary>
/// The "⋯" sheet: it comes up from the bottom edge, goes back down the same way, and can be
/// dragged down by its handle to dismiss — the three things the phone's does and this one did not.
///
/// Binding Visibility straight to the flag is what stopped the closing half from being possible at
/// all: the moment the flag goes false the sheet is gone, and there is nothing left to animate. So
/// the flag comes here instead, and the visibility is ours to set once the sheet has finished
/// leaving.
/// </summary>
public static class Sheet
{
    private static readonly Duration In = new(TimeSpan.FromMilliseconds(300));
    private static readonly Duration Out = new(TimeSpan.FromMilliseconds(220));

    /// <summary>How far down the card starts, and how far it is dragged before letting go closes it.</summary>
    private const double Travel = 52;
    private const double DismissAfter = 70;

    // ---- open / close ------------------------------------------------------

    public static bool GetIsOpen(DependencyObject o) => (bool)o.GetValue(IsOpenProperty);
    public static void SetIsOpen(DependencyObject o, bool value) => o.SetValue(IsOpenProperty, value);

    public static readonly DependencyProperty IsOpenProperty = DependencyProperty.RegisterAttached(
        "IsOpen", typeof(bool), typeof(Sheet),
        new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.BindsTwoWayByDefault, OnIsOpenChanged));

    private static void OnIsOpenChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not Border scrim || Card(scrim) is not { } card) return;
        bool open = Equals(e.NewValue, true);

        if (open)
        {
            scrim.Visibility = Visibility.Visible;
            Slide(card, from: Travel, to: 0, In, new CubicEase { EasingMode = EasingMode.EaseOut });
            Fade(scrim, from: 0, to: 1, In, whenDone: null);
            return;
        }

        // Down and out, and only then out of the way. Where it starts from is wherever it is now,
        // so a sheet let go halfway through a drag carries on from there rather than jumping back.
        double at = (card.RenderTransform as TranslateTransform)?.Y ?? 0;
        Slide(card, from: at, to: Travel, Out, new CubicEase { EasingMode = EasingMode.EaseIn });
        Fade(scrim, from: scrim.Opacity, to: 0, Out, whenDone: () =>
        {
            scrim.Visibility = Visibility.Collapsed;
            scrim.Opacity = 1;   // ready for the next time it is asked for
        });
    }

    private static FrameworkElement? Card(Border scrim) => scrim.Child as FrameworkElement;

    private static void Slide(FrameworkElement card, double from, double to, Duration length, IEasingFunction ease)
    {
        if (card.RenderTransform is not TranslateTransform move)
        {
            move = new TranslateTransform();
            card.RenderTransform = move;
        }
        move.BeginAnimation(TranslateTransform.YProperty,
            new DoubleAnimation(from, to, length) { EasingFunction = ease });
    }

    private static void Fade(UIElement element, double from, double to, Duration length, Action? whenDone)
    {
        var fade = new DoubleAnimation(from, to, length);
        if (whenDone is not null) fade.Completed += (_, _) => whenDone();
        element.BeginAnimation(UIElement.OpacityProperty, fade);
    }

    // ---- drag the handle ---------------------------------------------------

    public static bool GetDragHandle(DependencyObject o) => (bool)o.GetValue(DragHandleProperty);
    public static void SetDragHandle(DependencyObject o, bool value) => o.SetValue(DragHandleProperty, value);

    public static readonly DependencyProperty DragHandleProperty = DependencyProperty.RegisterAttached(
        "DragHandle", typeof(bool), typeof(Sheet), new PropertyMetadata(false, OnDragHandleChanged));

    private static void OnDragHandleChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not FrameworkElement handle || !Equals(e.NewValue, true)) return;
        handle.Cursor = Cursors.Hand;   // a thing to take hold of, not a thing to resize

        double startY = 0;
        FrameworkElement? card = null;
        TranslateTransform? move = null;

        handle.MouseLeftButtonDown += (_, args) =>
        {
            card = CardAbove(handle);
            if (card is null) return;
            // The backdrop closes the sheet on a click, and a drag starts with one. Without this
            // the sheet was gone before the mouse had moved a pixel, so it could only ever be
            // clicked and never pulled.
            args.Handled = true;
            move = card.RenderTransform as TranslateTransform;
            if (move is null) { move = new TranslateTransform(); card.RenderTransform = move; }
            // Stop the opening animation, or it would keep writing over the drag.
            move.BeginAnimation(TranslateTransform.YProperty, null);
            startY = args.GetPosition(card).Y - move.Y;
            handle.CaptureMouse();
        };

        handle.MouseMove += (_, args) =>
        {
            if (!handle.IsMouseCaptured || card is null || move is null) return;
            // Downwards only: a sheet that can be pulled up off the edge looks broken.
            move.Y = Math.Max(0, args.GetPosition(card).Y - startY);
        };

        handle.MouseLeftButtonUp += (_, args) =>
        {
            if (!handle.IsMouseCaptured || card is null || move is null) return;
            args.Handled = true;          // ...and the release must not read as a click either
            handle.ReleaseMouseCapture();

            if (move.Y >= DismissAfter)
            {
                // Far enough is a decision. Setting the flag lets the view model close it, and the
                // closing animation above carries on from where the drag left off.
                if (ScrimAbove(handle) is { } scrim) SetIsOpen(scrim, false);
                return;
            }
            Slide(card, move.Y, 0, In, new CubicEase { EasingMode = EasingMode.EaseOut });
        };
    }

    /// <summary>The sheet card the handle sits on: the child of the dimmed backdrop.</summary>
    private static FrameworkElement? CardAbove(DependencyObject from) =>
        ScrimAbove(from) is { } scrim ? Card(scrim) : null;

    private static Border? ScrimAbove(DependencyObject from)
    {
        Border? outermost = null;
        for (var at = VisualTreeHelper.GetParent(from); at is not null; at = VisualTreeHelper.GetParent(at))
            if (at is Border border && GetIsOpenIsSet(border)) outermost = border;
        return outermost;
    }

    private static bool GetIsOpenIsSet(DependencyObject o) =>
        o.ReadLocalValue(IsOpenProperty) != DependencyProperty.UnsetValue;
}
