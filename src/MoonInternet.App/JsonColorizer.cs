using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;

namespace MoonInternet.App;

/// <summary>Attached property that renders a JSON string into a TextBlock with syntax colors (keys/strings/numbers/…).
/// Usage: <c>&lt;TextBlock app:JsonColorizer.Json="{Binding SomeJson}"/&gt;</c>. Read-only, for the per-server config view.</summary>
public static class JsonColorizer
{
    public static readonly DependencyProperty JsonProperty = DependencyProperty.RegisterAttached(
        "Json", typeof(string), typeof(JsonColorizer), new PropertyMetadata("", OnJsonChanged));
    public static string GetJson(DependencyObject o) => (string)o.GetValue(JsonProperty);
    public static void SetJson(DependencyObject o, string v) => o.SetValue(JsonProperty, v);

    private static readonly Brush Key = Frozen("#C4B4FF"), Str = Frozen("#7EE0A8"), Num = Frozen("#F5C042"),
                                  Lit = Frozen("#FF8A5B"), Punc = Frozen("#7A7498");
    private static Brush Frozen(string hex) { var b = (Brush)new BrushConverter().ConvertFromString(hex)!; b.Freeze(); return b; }

    // one token = a string, a number, a literal, a single punctuation char, or a run of whitespace
    private static readonly Regex Tok = new(
        @"""(?:\\.|[^""\\])*""|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null|[{}\[\],:]|\s+|.", RegexOptions.Compiled);

    private static void OnJsonChanged(DependencyObject o, DependencyPropertyChangedEventArgs e)
    {
        if (o is not TextBlock tb) return;
        tb.Inlines.Clear();
        string s = e.NewValue as string ?? "";
        foreach (Match m in Tok.Matches(s))
        {
            string t = m.Value;
            char c = t[0];
            Brush? br;
            if (c == '"')
            {
                int j = m.Index + m.Length;                     // string → key if the next non-space char is ':'
                while (j < s.Length && char.IsWhiteSpace(s[j])) j++;
                br = j < s.Length && s[j] == ':' ? Key : Str;
            }
            else if (t is "true" or "false" or "null") br = Lit;
            else if (char.IsDigit(c) || c == '-') br = Num;
            else if (t.Length == 1 && "{}[],:".IndexOf(c) >= 0) br = Punc;
            else br = null;                                      // whitespace → inherit
            tb.Inlines.Add(br is null ? new Run(t) : new Run(t) { Foreground = br });
        }
    }
}
