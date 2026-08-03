using System.Text.Json;

namespace MoonInternet.Services;

/// <summary>
/// Learns how much the link actually carries, by watching it instead of asking the user.
///
/// The idea is the one behind every anti-bufferbloat scheme: a link is not "full" when the
/// speed graph flattens, it is full when the *queue* starts growing, and a growing queue shows
/// up as latency climbing while throughput stops climbing. So:
///
///   * the lowest round-trip we have seen recently is the baseline — the link with an empty queue;
///   * while latency stays near that baseline, whatever speed we are getting is genuinely
///     available, so the highest such sample is the capacity estimate;
///   * once latency runs away from the baseline, the queue is filling and further speed is
///     borrowed from it, not real — those samples are ignored.
///
/// The estimate is kept across runs: it is a property of the user's connection, not of a session,
/// and having it ready at connect time is the whole point — see <see cref="TargetBytesPerSecond"/>.
/// </summary>
public sealed class NetworkQuality
{
    /// <summary>Latency this many times the baseline means the queue is filling, not the link.</summary>
    private const double BloatFactor = 2.0;

    /// <summary>How much of the learned capacity we aim to use. The rest is the headroom that keeps the queue empty.</summary>
    private const double TargetShare = 0.90;

    /// <summary>Samples below this are noise — a few packets, not a transfer worth learning from.</summary>
    private const long MinInterestingBytesPerSecond = 64 * 1024;

    private readonly string _path;

    private long _capacityBytesPerSecond;
    private int _baselineRttMs = int.MaxValue;

    /// <summary>Recent minimum, so the baseline can rise again when the user moves to a worse network.</summary>
    private readonly Queue<int> _recentRtt = new();

    public NetworkQuality(string? statePath = null)
    {
        _path = statePath ?? MoonInternet.Core.AppPaths.In("netquality.json");
        Load();
    }

    /// <summary>Best speed seen while the link was not queueing, in bytes per second. 0 = not learned yet.</summary>
    public long CapacityBytesPerSecond => _capacityBytesPerSecond;

    /// <summary>Round-trip with an empty queue, in ms. -1 = not learned yet.</summary>
    public int BaselineRttMs => _baselineRttMs == int.MaxValue ? -1 : _baselineRttMs;

    /// <summary>What to cap at: 90% of what we learned, or 0 while we have not learned anything.</summary>
    public long TargetBytesPerSecond =>
        _capacityBytesPerSecond == 0 ? 0 : (long)(_capacityBytesPerSecond * TargetShare);

    /// <summary>True when the last sample looked like a queue building up rather than a fast link.</summary>
    public bool Congested { get; private set; }

    /// <summary>
    /// One second of reality: how many bytes moved and what the round-trip was.
    /// <paramref name="rttMs"/> below zero means "no measurement this tick".
    /// </summary>
    public void Observe(long bytesPerSecond, int rttMs)
    {
        if (rttMs > 0)
        {
            _recentRtt.Enqueue(rttMs);
            while (_recentRtt.Count > 60) _recentRtt.Dequeue();       // a minute of history
            _baselineRttMs = _recentRtt.Min();
        }

        if (bytesPerSecond < MinInterestingBytesPerSecond) { Congested = false; return; }

        // No latency reading yet: we cannot tell a fast link from a filling queue, so learn nothing.
        if (BaselineRttMs < 0 || rttMs <= 0) return;

        Congested = rttMs > BaselineRttMs * BloatFactor;

        // Only samples taken with a healthy queue describe the link. A sample taken while
        // congested is inflated by data sitting in a buffer somewhere and would teach us a
        // capacity the link cannot actually sustain.
        if (!Congested && bytesPerSecond > _capacityBytesPerSecond)
        {
            _capacityBytesPerSecond = bytesPerSecond;
            Save();
        }
    }

    /// <summary>Forgets what was learned — for when the user changes ISP or moves house.</summary>
    public void Reset()
    {
        _capacityBytesPerSecond = 0;
        _baselineRttMs = int.MaxValue;
        _recentRtt.Clear();
        Congested = false;
        Save();
    }

    // ---- persistence ------------------------------------------------------
    private sealed record State(long Capacity, int Baseline);

    private void Load()
    {
        try
        {
            if (!File.Exists(_path)) return;
            var s = JsonSerializer.Deserialize<State>(File.ReadAllText(_path));
            if (s is null) return;
            _capacityBytesPerSecond = s.Capacity;
            if (s.Baseline > 0) { _baselineRttMs = s.Baseline; _recentRtt.Enqueue(s.Baseline); }
        }
        catch { /* a corrupt file just means we learn again */ }
    }

    private void Save()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            File.WriteAllText(_path, JsonSerializer.Serialize(new State(_capacityBytesPerSecond, BaselineRttMs)));
        }
        catch { }
    }

    /// <summary>"12,5 Мбит/с" — capacity reads better in the units people buy internet in.</summary>
    public static string FormatMbit(long bytesPerSecond) =>
        bytesPerSecond <= 0 ? "—" : $"{bytesPerSecond * 8 / 1_000_000.0:0.#} Мбит/с";
}
