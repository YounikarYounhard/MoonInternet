using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;

namespace MoonInternet.App.Controls;

/// <summary>
/// A Canvas-positioned content block the user can drag (move) and resize when <see cref="IsEditing"/> is on.
/// X/Y/W/H are two-way bindable so the owning VM can persist them. Raises <see cref="Changed"/> on drag end.
/// </summary>
public class MovableBlock : ContentControl
{
    public event EventHandler? Changed;

    public static readonly DependencyProperty IsEditingProperty =
        DependencyProperty.Register(nameof(IsEditing), typeof(bool), typeof(MovableBlock), new(false));
    public bool IsEditing { get => (bool)GetValue(IsEditingProperty); set => SetValue(IsEditingProperty, value); }

    public static readonly DependencyProperty XProperty =
        DependencyProperty.Register(nameof(X), typeof(double), typeof(MovableBlock),
            new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.BindsTwoWayByDefault, (d, e) => Canvas.SetLeft((UIElement)d, (double)e.NewValue)));
    public double X { get => (double)GetValue(XProperty); set => SetValue(XProperty, value); }

    public static readonly DependencyProperty YProperty =
        DependencyProperty.Register(nameof(Y), typeof(double), typeof(MovableBlock),
            new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.BindsTwoWayByDefault, (d, e) => Canvas.SetTop((UIElement)d, (double)e.NewValue)));
    public double Y { get => (double)GetValue(YProperty); set => SetValue(YProperty, value); }

    public static readonly DependencyProperty WProperty =
        DependencyProperty.Register(nameof(W), typeof(double), typeof(MovableBlock),
            new FrameworkPropertyMetadata(120.0, FrameworkPropertyMetadataOptions.BindsTwoWayByDefault, (d, e) => ((MovableBlock)d).Width = (double)e.NewValue));
    public double W { get => (double)GetValue(WProperty); set => SetValue(WProperty, value); }

    public static readonly DependencyProperty HProperty =
        DependencyProperty.Register(nameof(H), typeof(double), typeof(MovableBlock),
            new FrameworkPropertyMetadata(80.0, FrameworkPropertyMetadataOptions.BindsTwoWayByDefault, (d, e) => ((MovableBlock)d).Height = (double)e.NewValue));
    public double H { get => (double)GetValue(HProperty); set => SetValue(HProperty, value); }

    public override void OnApplyTemplate()
    {
        base.OnApplyTemplate();
        Width = W; Height = H; Canvas.SetLeft(this, X); Canvas.SetTop(this, Y);

        if (GetTemplateChild("PART_Move") is Thumb move)
        {
            move.DragDelta += (_, e) => { X = Math.Max(0, X + e.HorizontalChange); Y = Math.Max(0, Y + e.VerticalChange); };
            move.DragCompleted += (_, _) => Changed?.Invoke(this, EventArgs.Empty);
        }
        if (GetTemplateChild("PART_Resize") is Thumb resize)
        {
            resize.DragDelta += (_, e) => { W = Math.Max(90, W + e.HorizontalChange); H = Math.Max(50, H + e.VerticalChange); };
            resize.DragCompleted += (_, _) => Changed?.Invoke(this, EventArgs.Empty);
        }
    }
}
