using System.Globalization;
using System.Windows;

namespace MoonInternet.App.Localization;

/// <summary>
/// Language switching.
///
/// The strings live in two ResourceDictionaries and the app swaps which one is merged. That is
/// why every localised string in XAML is a <c>DynamicResource</c> and not a Static one: a dynamic
/// lookup re-resolves when the dictionary changes, so the language switches on the spot instead
/// of after a restart.
/// </summary>
public static class Loc
{
    private const string RuUri = "pack://application:,,,/Localization/Strings.ru.xaml";
    private const string EnUri = "pack://application:,,,/Localization/Strings.en.xaml";

    private static ResourceDictionary? _current;

    /// <summary>"ru" or "en".</summary>
    public static string Language { get; private set; } = "ru";

    /// <summary>
    /// The language to start in: whatever was saved, or the system's if it was never chosen.
    /// Anything that is not Russian gets English — those are the only two we ship.
    /// </summary>
    public static string Resolve(string? saved) =>
        saved is "ru" or "en" ? saved : SystemLanguage();

    public static string SystemLanguage() =>
        CultureInfo.CurrentUICulture.TwoLetterISOLanguageName.Equals("ru", StringComparison.OrdinalIgnoreCase)
            ? "ru" : "en";

    /// <summary>Swaps the merged dictionary. Safe to call before any window exists.</summary>
    public static void Apply(string language)
    {
        var app = Application.Current;
        if (app is null) return;

        Language = language == "en" ? "en" : "ru";
        var next = new ResourceDictionary { Source = new Uri(Language == "en" ? EnUri : RuUri) };

        // Replace rather than append: two string dictionaries merged at once would leave the
        // lookup order deciding the language, which is not something to leave to chance.
        if (_current is not null) app.Resources.MergedDictionaries.Remove(_current);
        app.Resources.MergedDictionaries.Add(next);
        _current = next;
    }

    /// <summary>A string by key, for the few places that build text in code.</summary>
    public static string T(string key) =>
        Application.Current?.TryFindResource(key) as string ?? key;
}
