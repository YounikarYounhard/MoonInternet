using System.IO;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace MoonInternet.App.ViewModels;

/// <summary>One app in the split-tunnel list: its exe path, display name and extracted icon.
/// Routing matches by <see cref="Name"/> (process image name); the icon needs the full <see cref="Path"/>.</summary>
public sealed class AppRouteItem
{
    public string Path { get; }
    public string Name => System.IO.Path.GetFileName(Path);
    public ImageSource? Icon { get; }

    public AppRouteItem(string path) { Path = path; Icon = LoadIcon(path); }

    private static ImageSource? LoadIcon(string path)
    {
        try
        {
            if (!File.Exists(path)) return null;
            using var ico = System.Drawing.Icon.ExtractAssociatedIcon(path);
            if (ico is null) return null;
            var src = Imaging.CreateBitmapSourceFromHIcon(ico.Handle, Int32Rect.Empty, BitmapSizeOptions.FromEmptyOptions());
            src.Freeze();
            return src;
        }
        catch { return null; }
    }
}
