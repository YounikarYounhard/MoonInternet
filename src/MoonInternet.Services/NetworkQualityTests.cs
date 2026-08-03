namespace MoonInternet.Services;

/// <summary>
/// Self-check for <see cref="NetworkQuality"/>. The learner is the part that decides how hard we
/// drive the link, and getting it wrong is invisible until someone's game lags — so it gets a
/// runnable check rather than a hope.
///
/// Run: <c>MoonInternet.Services.NetworkQualitySelfTest.Run()</c>
/// </summary>
public static class NetworkQualitySelfTest
{
    public static void Run()
    {
        var tmp = Path.Combine(Path.GetTempPath(), $"nq-{Guid.NewGuid():N}.json");
        try
        {
            var q = new NetworkQuality(tmp);

            // Idle link, low latency: nothing to learn from a trickle.
            q.Observe(1_000, 40);
            Assert(q.CapacityBytesPerSecond == 0, "трафика почти нет — учиться нечему");

            // A real transfer with latency at baseline: this is genuine capacity.
            q.Observe(2_000_000, 42);
            Assert(q.CapacityBytesPerSecond == 2_000_000, "чистая передача задаёт ёмкость");
            Assert(!q.Congested, "пинг у базового уровня — перегрузки нет");

            // Faster, but latency has run away: the extra speed is queue, not link.
            q.Observe(5_000_000, 400);
            Assert(q.Congested, "пинг в 10 раз выше базового — это перегрузка");
            Assert(q.CapacityBytesPerSecond == 2_000_000, "перегруженная выборка не должна поднимать ёмкость");

            // Back to a healthy queue and genuinely faster: capacity moves up.
            q.Observe(3_000_000, 45);
            Assert(q.CapacityBytesPerSecond == 3_000_000, "чистая и более быстрая выборка поднимает ёмкость");

            // The cap is the headroom rule, not the raw number.
            Assert(q.TargetBytesPerSecond == 2_700_000, "потолок — 90% от выученного");

            // What was learned has to survive a restart: that is the point of learning it.
            var again = new NetworkQuality(tmp);
            Assert(again.CapacityBytesPerSecond == 3_000_000, "ёмкость переживает перезапуск");
            Assert(again.BaselineRttMs == 40, "базовый пинг переживает перезапуск");

            again.Reset();
            Assert(again.CapacityBytesPerSecond == 0, "сброс забывает выученное");

            Console.WriteLine("NetworkQuality: все проверки пройдены");
        }
        finally { try { File.Delete(tmp); } catch { } }
    }

    private static void Assert(bool ok, string what)
    {
        if (!ok) throw new Exception("провалено: " + what);
    }
}
