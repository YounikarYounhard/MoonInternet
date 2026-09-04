using System.Windows;
using System.Windows.Media;
using MoonInternet.Core.Parsing;

namespace MoonInternet.App.ViewModels;

/// <summary>
/// One line of the release notes, already dressed. The window binds a single template to these
/// rather than choosing one per kind: the difference between a heading and a bullet here is a
/// size, a weight, a colour and a margin, and there is no sense in four templates to say that.
/// </summary>
public sealed class NoteLine
{
    public string Text { get; init; } = "";
    public double FontSize { get; init; } = 12;
    public FontWeight FontWeight { get; init; } = FontWeights.Normal;
    public Brush Foreground { get; init; } = Muted;
    public Thickness Margin { get; init; }

    private static readonly Brush Muted = ServerItem.Frozen(0xCF, 0xC7, 0xEC);
    private static readonly Brush Bright = ServerItem.Frozen(0xEC, 0xE9, 0xF5);
    private static readonly Brush Accent = ServerItem.Frozen(0xB9, 0xA7, 0xFF);

    public static NoteLine From(NoteBlock b) => b.Kind switch
    {
        NoteKind.Heading => new NoteLine
        {
            Text = b.Text, FontSize = 14, FontWeight = FontWeights.Bold,
            Foreground = Bright, Margin = new Thickness(0, 14, 0, 6),
        },
        NoteKind.Group => new NoteLine
        {
            Text = b.Text, FontSize = 12.5, FontWeight = FontWeights.SemiBold,
            Foreground = Accent, Margin = new Thickness(0, 9, 0, 3),
        },
        // The dash is put back rather than kept, so every bullet lines up under the same one
        // whichever of the three the notes happened to use.
        NoteKind.Bullet => new NoteLine
        {
            Text = "–  " + b.Text, Margin = new Thickness(6, 0, 0, 4),
        },
        _ => new NoteLine { Text = b.Text, Margin = new Thickness(0, 0, 0, 8) },
    };
}
