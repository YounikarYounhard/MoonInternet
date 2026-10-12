namespace MoonInternet.Core.Parsing;

/// <summary>What a line of the release notes is, which is all we need to lay it out.</summary>
public enum NoteKind
{
    Paragraph,
    /// <summary>A <c>##</c> section — "Что нового", "Что починено".</summary>
    Heading,
    /// <summary>A bold line on its own, which is how the notes label a group inside a section.</summary>
    Group,
    Bullet,
}

public sealed record NoteBlock(NoteKind Kind, string Text);

/// <summary>Which app is reading the notes.</summary>
public enum NotePlatform { Pc, Phone }

/// <summary>
/// Turns a GitHub release body into something worth looking at.
///
/// The notes are written as Markdown, and both apps were showing it raw: literal <c>##</c> and
/// <c>**</c> down the middle of the window, which reads far worse than the same text does on the
/// release page. This is not a Markdown implementation and does not want to be — it is the four
/// shapes our notes are actually written in.
/// </summary>
public static class ReleaseNotes
{
    public static IReadOnlyList<NoteBlock> Parse(string? markdown)
    {
        var blocks = new List<NoteBlock>();
        if (string.IsNullOrWhiteSpace(markdown)) return blocks;

        foreach (var raw in markdown.Replace("\r\n", "\n").Split('\n'))
        {
            string line = raw.Trim();
            if (line.Length == 0) continue;
            // A horizontal rule separates the summary from the sections. The spacing already says
            // that, and a line drawn across a narrow window says it twice.
            if (line.All(c => c is '-' or '*' or '_') && line.Length >= 3) continue;

            if (line.StartsWith("#", StringComparison.Ordinal))
            {
                blocks.Add(new NoteBlock(NoteKind.Heading, Clean(line.TrimStart('#').Trim())));
                continue;
            }
            if (line.Length > 4 && line.StartsWith("**", StringComparison.Ordinal)
                && line.EndsWith("**", StringComparison.Ordinal)
                && line.IndexOf("**", 2, StringComparison.Ordinal) == line.Length - 2)
            {
                blocks.Add(new NoteBlock(NoteKind.Group, Clean(line)));
                continue;
            }
            // The notes use an en dash; GitHub's own bullets use the other two.
            if (line.StartsWith("– ", StringComparison.Ordinal)
                || line.StartsWith("- ", StringComparison.Ordinal)
                || line.StartsWith("* ", StringComparison.Ordinal))
            {
                blocks.Add(new NoteBlock(NoteKind.Bullet, Clean(line[2..].Trim())));
                continue;
            }
            blocks.Add(new NoteBlock(NoteKind.Paragraph, Clean(line)));
        }
        return blocks;
    }

    /// <summary>
    /// The part of the notes meant for this app. One release ships both apps, and the notes are
    /// written as a section per platform — <c>## ПК</c> and <c>## Телефон</c> — so the desktop shows
    /// what changed on the desktop and the phone what changed on the phone. Kept: the introduction
    /// (everything before the first heading) and this platform's own section, without its heading;
    /// the dialog already says which app it is. Notes written without platform sections — every
    /// release before 0.9.6 — come back whole.
    /// </summary>
    public static IReadOnlyList<NoteBlock> ForPlatform(string? markdown, NotePlatform platform)
    {
        var all = Parse(markdown);
        if (!all.Any(b => b.Kind == NoteKind.Heading && PlatformOf(b.Text) is not null)) return all;
        var mine = new List<NoteBlock>();
        bool intro = true, ours = false;
        foreach (var b in all)
        {
            if (b.Kind == NoteKind.Heading) { intro = false; ours = PlatformOf(b.Text) == platform; continue; }
            if (intro || ours) mine.Add(b);
        }
        return mine;
    }

    private static readonly string[] PcWords = { "пк", "компьютер", "компьютера", "windows", "pc", "desktop" };
    private static readonly string[] PhoneWords = { "телефон", "телефона", "смартфон", "android", "phone" };

    /// <summary>The platform a heading names, by whole word — "ПК", "💻 Компьютер", "Android".</summary>
    private static NotePlatform? PlatformOf(string heading)
    {
        var words = System.Text.RegularExpressions.Regex.Split(heading.ToLowerInvariant(), @"[^\p{L}]+").Where(w => w.Length > 0).ToList();
        if (words.Any(w => PcWords.Contains(w))) return NotePlatform.Pc;
        if (words.Any(w => PhoneWords.Contains(w))) return NotePlatform.Phone;
        return null;
    }

    /// <summary>Drops the emphasis markers. We carry the emphasis in the block's kind instead, so
    /// leaving the asterisks in would only put them on screen.</summary>
    private static string Clean(string s) =>
        s.Replace("**", "").Replace("`", "").Trim();
}
