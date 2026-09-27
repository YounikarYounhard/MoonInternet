namespace MoonInternet.Core;

/// <summary>
/// What the app decided, and why. The engines keep their own logs (xray.log, tunservice.log) but
/// those say nothing about our choices — a tunnel that quietly became a system proxy left no trace
/// anywhere, and a laptop sat in that state for three days without a line to show for it.
///
/// Mirrors the phone's DevLog: a short ring buffer for the window plus a file, so the run can be
/// read afterwards without the app being open.
/// </summary>
public static class DevLog
{
    private const int Cap = 300;
    private const long FileCap = 512 * 1024;

    private static readonly object Gate = new();
    private static readonly Queue<string> Lines_ = new();

    /// <summary>Raised after every line, on the caller's thread — marshal before touching the UI.</summary>
    public static event Action? Changed;

    public static void Log(string topic, string message)
    {
        string line = $"{DateTime.Now:HH:mm:ss}  {topic}: {message}";
        lock (Gate)
        {
            Lines_.Enqueue(line);
            while (Lines_.Count > Cap) Lines_.Dequeue();
            try
            {
                string path = AppPaths.In("dev.log");
                // Start over rather than trim: this is a diagnostic tail, and rewriting half a
                // megabyte on a line that arrives every few seconds is not worth the tidiness.
                if (File.Exists(path) && new FileInfo(path).Length > FileCap) File.Delete(path);
                File.AppendAllText(path, line + Environment.NewLine);
            }
            catch { /* a log that throws is worse than a log that misses a line */ }
        }
        Changed?.Invoke();
    }

    public static string Text()
    {
        lock (Gate) return string.Join(Environment.NewLine, Lines_.Reverse());
    }

    public static void Clear()
    {
        lock (Gate)
        {
            Lines_.Clear();
            try { File.Delete(AppPaths.In("dev.log")); } catch { }
        }
        Changed?.Invoke();
    }
}
