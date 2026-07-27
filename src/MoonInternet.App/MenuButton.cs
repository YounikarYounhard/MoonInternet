using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;

namespace MoonInternet.App;

/// <summary>Makes a Button open its own ContextMenu on a normal left click (below the button), so a "⋯" button
/// acts like a dropdown. Menu items bind through <c>PlacementTarget</c> (Tag = the VM, DataContext = the row item).</summary>
public static class MenuButton
{
    public static readonly DependencyProperty EnabledProperty = DependencyProperty.RegisterAttached(
        "Enabled", typeof(bool), typeof(MenuButton), new PropertyMetadata(false, OnEnabledChanged));
    public static bool GetEnabled(DependencyObject o) => (bool)o.GetValue(EnabledProperty);
    public static void SetEnabled(DependencyObject o, bool v) => o.SetValue(EnabledProperty, v);

    private static void OnEnabledChanged(DependencyObject o, DependencyPropertyChangedEventArgs e)
    {
        if (o is not Button b || e.NewValue is not true) return;
        b.Click += (_, _) =>
        {
            if (b.ContextMenu is not { } cm) return;
            cm.PlacementTarget = b;
            cm.Placement = PlacementMode.Bottom;
            cm.IsOpen = true;
        };
    }
}
