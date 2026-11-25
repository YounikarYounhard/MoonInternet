using System.Runtime.InteropServices;
using System.Windows;

namespace MoonInternet.App;

internal static class WindowPlacement
{
    private const uint MonitorDefaultToNearest = 2;
    private const uint KeepZOrderAndActivation = 0x0004 | 0x0010;

    internal static void Fit(Window window, IntPtr handle, double preferredWidth, double preferredHeight)
    {
        if (handle == IntPtr.Zero || window.WindowState != WindowState.Normal) return;
        var info = new MonitorInfo { Size = Marshal.SizeOf<MonitorInfo>() };
        if (!GetMonitorInfo(MonitorFromWindow(handle, MonitorDefaultToNearest), ref info)
            || !GetWindowRect(handle, out var position)) return;

        var dpi = GetDpiForWindow(handle);
        if (dpi == 0 || info.Work.Right <= info.Work.Left || info.Work.Bottom <= info.Work.Top) return;
        var scale = dpi / 96d;
        var fitted = FitBounds(info.Work.ToRect(), position.ToRect(), new Size(preferredWidth, preferredHeight), scale);

        var width = fitted.Width / scale;
        var height = fitted.Height / scale;
        if (Math.Abs(window.Width - width) > 0.01) window.Width = width;
        if (Math.Abs(window.Height - height) > 0.01) window.Height = height;

        if (fitted != position.ToRect())
            SetWindowPos(handle, IntPtr.Zero, (int)fitted.X, (int)fitted.Y,
                (int)fitted.Width, (int)fitted.Height, KeepZOrderAndActivation);
    }

    internal static Rect FitBounds(Rect workArea, Rect current, Size preferred, double scale)
    {
        // All coordinates here are physical pixels, including monitor origins left of the primary display.
        var gap = Math.Floor(Math.Min(12 * scale, Math.Min(workArea.Width, workArea.Height) / 16));
        var width = Math.Min(Math.Round(preferred.Width * scale), workArea.Width - 2 * gap);
        var height = Math.Min(Math.Round(preferred.Height * scale), workArea.Height - 2 * gap);
        var left = Math.Clamp(current.Left, workArea.Left + gap, workArea.Right - gap - width);
        var top = Math.Clamp(current.Top, workArea.Top + gap, workArea.Bottom - gap - height);
        return new Rect(left, top, width, height);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeRect
    {
        public int Left, Top, Right, Bottom;
        public readonly Rect ToRect() => new(Left, Top, Right - Left, Bottom - Top);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MonitorInfo
    {
        public int Size;
        public NativeRect Monitor;
        public NativeRect Work;
        public uint Flags;
    }

    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern IntPtr MonitorFromWindow(IntPtr window, uint flags);

    [DllImport("user32.dll", EntryPoint = "GetMonitorInfoW", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetMonitorInfo(IntPtr monitor, ref MonitorInfo info);

    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(IntPtr window, out NativeRect rect);

    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern uint GetDpiForWindow(IntPtr window);

    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowPos(IntPtr window, IntPtr insertAfter, int x, int y, int width, int height, uint flags);
}
