using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace MoonInternet.App;

/// <summary>Visible when the bound enum/string equals the ConverterParameter.</summary>
public sealed class EnumToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? p, CultureInfo c) =>
        string.Equals(value?.ToString(), p?.ToString(), StringComparison.OrdinalIgnoreCase)
            ? Visibility.Visible : Visibility.Collapsed;
    public object ConvertBack(object? v, Type t, object? p, CultureInfo c) => Binding.DoNothing;
}

/// <summary>True when the bound enum/string equals the ConverterParameter (nav highlight).</summary>
public sealed class EnumToBoolConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? p, CultureInfo c) =>
        string.Equals(value?.ToString(), p?.ToString(), StringComparison.OrdinalIgnoreCase);
    public object ConvertBack(object? v, Type t, object? p, CultureInfo c) =>
        (v is true) ? (p ?? Binding.DoNothing) : Binding.DoNothing;
}

/// <summary>Inverts a bool (e.g. enable a button only when NOT busy).</summary>
public sealed class InverseBoolConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? p, CultureInfo c) => value is not true;
    public object ConvertBack(object? value, Type t, object? p, CultureInfo c) => value is not true;
}

/// <summary>True when the value is -1 — the download progress we report for an unknown size.</summary>
public sealed class NegativeOneConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? p, CultureInfo c) => value is int and < 0;
    public object ConvertBack(object? value, Type t, object? p, CultureInfo c) => Binding.DoNothing;
}

/// <summary>Visible only when ALL bound bools are true (e.g. banner: has-announcement AND show-header enabled).</summary>
public sealed class AllTrueToVisibilityConverter : IMultiValueConverter
{
    public object Convert(object[] values, Type t, object? p, CultureInfo c) =>
        values.All(v => v is true) ? Visibility.Visible : Visibility.Collapsed;
    public object[] ConvertBack(object v, Type[] t, object? p, CultureInfo c) => throw new NotSupportedException();
}

/// <summary>True when the two bound values are equal (e.g. current filter == this chip's text).</summary>
public sealed class EqualsMultiConverter : IMultiValueConverter
{
    public object Convert(object[] v, Type t, object? p, CultureInfo c) =>
        v.Length >= 2 && string.Equals(v[0]?.ToString(), v[1]?.ToString(), StringComparison.OrdinalIgnoreCase);
    public object[] ConvertBack(object v, Type[] t, object? p, CultureInfo c) => throw new NotSupportedException();
}

/// <summary>Signal level (0..4) vs a dot index (ConverterParameter "1".."4") → 1.0 opacity if lit, else dim.</summary>
public sealed class SignalOpacityConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? p, CultureInfo c)
    {
        int sig = value is int i ? i : 0;
        int idx = int.TryParse(p?.ToString(), out var x) ? x : 0;
        return sig >= idx ? 1.0 : 0.18;
    }
    public object ConvertBack(object? v, Type t, object? p, CultureInfo c) => Binding.DoNothing;
}

/// <summary>#RRGGBB hex string → SolidColorBrush (for theme swatches/previews).</summary>
public sealed class HexToBrushConverter : IValueConverter
{
    public object Convert(object? value, Type t, object? p, CultureInfo c)
    {
        try { return new System.Windows.Media.SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(value?.ToString() ?? "#888")!); }
        catch { return System.Windows.Media.Brushes.Gray; }
    }
    public object ConvertBack(object? value, Type t, object? p, CultureInfo c) => Binding.DoNothing;
}
