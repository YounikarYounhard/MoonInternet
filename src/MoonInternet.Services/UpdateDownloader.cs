using System.IO;

namespace MoonInternet.Services;

/// <summary>
/// Fetches a release installer and runs it.
///
/// The installer has to replace files this very process is holding open, so we do not try to be
/// clever: it is started with a flag telling it to wait for us, and we close. NSIS uninstalls the
/// old build itself, and the leftover file is cleaned up on the next launch — by which point it
/// has either done its job or been abandoned.
/// </summary>
public static class UpdateDownloader
{
    private static string Dir => Path.Combine(Path.GetTempPath(), "MoonInternetUpdate");

    /// <summary>Downloads to a temp folder. Reports 0..100, or -1 when the size is unknown.</summary>
    public static async Task<string?> DownloadAsync(string url, IProgress<int> progress, CancellationToken ct = default)
    {
        try
        {
            Directory.CreateDirectory(Dir);
            string path = Path.Combine(Dir, "MoonInternetSetup.exe");
            if (File.Exists(path)) File.Delete(path);

            using var http = new HttpClient();
            http.DefaultRequestHeaders.UserAgent.ParseAdd("MoonInternet");
            using var res = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            res.EnsureSuccessStatusCode();

            long? total = res.Content.Headers.ContentLength;
            await using var input = await res.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            await using (var output = File.Create(path))
            {
                var buf = new byte[64 * 1024];
                long got = 0;
                while (true)
                {
                    int n = await input.ReadAsync(buf, ct).ConfigureAwait(false);
                    if (n == 0) break;
                    await output.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
                    got += n;
                    progress.Report(total is > 0 ? (int)(got * 100 / total.Value) : -1);
                }
            }
            return new FileInfo(path).Length > 0 ? path : null;
        }
        catch { return null; }
    }

    /// <summary>Starts the installer and returns — the caller is expected to exit right after.</summary>
    public static bool Run(string path)
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(path)
            {
                UseShellExecute = true,   // lets Windows show the UAC prompt the installer needs
            });
            return true;
        }
        catch { return false; }
    }

    /// <summary>Called at startup: a downloaded installer is dead weight once we are running again.</summary>
    public static void CleanUp()
    {
        try
        {
            if (Directory.Exists(Dir)) Directory.Delete(Dir, recursive: true);
        }
        catch { /* still in use, or already gone */ }
    }
}
