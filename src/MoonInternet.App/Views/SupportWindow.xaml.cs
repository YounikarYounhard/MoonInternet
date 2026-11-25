using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using MoonInternet.App.Localization;
using MoonInternet.Core;

namespace MoonInternet.App.Views;

public partial class SupportWindow : Window
{
    private const string IssueUrl = "https://github.com/YounikarYounhard/MoonInternet/issues/new";
    private bool _preparing;

    public SupportWindow(Window owner)
    {
        InitializeComponent();
        Owner = owner;
        MaxWidth = owner.ActualWidth;
        MaxHeight = owner.ActualHeight;
    }

    private void DescriptionChanged(object sender, TextChangedEventArgs e) => UpdateActions();

    private void UpdateActions()
    {
        if (OpenReport is null) return;
        ChooseScreenshot.IsEnabled = RemoveScreenshot.IsEnabled = !_preparing;
        OpenReport.IsEnabled = !_preparing && (!string.IsNullOrWhiteSpace(Description.Text) || Preview.Source is not null);
    }

    private void ChooseScreenshot_Click(object sender, RoutedEventArgs e)
    {
        var picker = new OpenFileDialog { Filter = "PNG, JPEG|*.png;*.jpg;*.jpeg" };
        if (picker.ShowDialog(this) != true) return;
        try
        {
            using var stream = File.OpenRead(picker.FileName);
            if (stream.Length > 10 * 1024 * 1024)
            {
                ShowNotice("S_Support_InvalidScreenshot");
                return;
            }
            var image = new BitmapImage();
            image.BeginInit();
            image.StreamSource = stream;
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.DecodePixelWidth = 360;
            image.EndInit();
            Preview.Source = image;
            Preview.Visibility = RemoveScreenshot.Visibility = Visibility.Visible;
            Notice.Visibility = Visibility.Collapsed;
            UpdateActions();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or NotSupportedException or FormatException)
        {
            ShowNotice("S_Support_InvalidScreenshot");
        }
    }

    private void RemoveScreenshot_Click(object sender, RoutedEventArgs e)
    {
        Preview.Source = null;
        Preview.Visibility = RemoveScreenshot.Visibility = Visibility.Collapsed;
        Notice.Visibility = Visibility.Collapsed;
        UpdateActions();
    }

    private async void OpenReport_Click(object sender, RoutedEventArgs e)
    {
        var description = Description.Text.Trim();
        var hasScreenshot = Preview.Source is not null;
        var includeDiagnostics = IncludeDiagnostics.IsChecked == true;
        _preparing = true;
        Preparing.Visibility = Visibility.Visible;
        Notice.Visibility = Visibility.Collapsed;
        UpdateActions();
        try
        {
            var events = includeDiagnostics
                ? await Task.Run(() => SupportDiagnostics.Summarize(DevLog.Text().Split('\n')))
                : "";
            var summary = typeof(App).Assembly.GetName().Version?.ToString();
            var body = string.IsNullOrEmpty(description) ? Loc.T("S_Support_ScreenshotOnly") : description;
            body += $"\n\nMoon Internet {summary} · {Environment.OSVersion.VersionString}";
            if (events.Length > 0)
                body += $"\n\n### {Loc.T("S_Support_Diagnostics")}\n\n~~~\n{events}\n~~~";
            if (!IsVisible) return;
            Clipboard.SetText(body);
            Process.Start(new ProcessStartInfo(IssueUrl) { UseShellExecute = true })?.Dispose();
            ShowNotice(hasScreenshot ? "S_Support_PasteWithScreenshot" : "S_Support_Paste");
        }
        catch (Exception ex) when (ex is System.Runtime.InteropServices.ExternalException or InvalidOperationException)
        {
            ShowNotice("S_Settings_SupportFailed");
        }
        finally
        {
            _preparing = false;
            Preparing.Visibility = Visibility.Collapsed;
            UpdateActions();
        }
    }

    private void ShowNotice(string key)
    {
        Notice.Text = Loc.T(key);
        Notice.Visibility = Visibility.Visible;
    }
}
