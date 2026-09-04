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

    /// <summary>Drops the emphasis markers. We carry the emphasis in the block's kind instead, so
    /// leaving the asterisks in would only put them on screen.</summary>
    private static string Clean(string s) =>
        s.Replace("**", "").Replace("`", "").Trim();
}
