package cc.moon.internet.vpn

import android.content.Context
import cc.moon.internet.core.Protocol
import cc.moon.internet.core.ServerProfile
import cc.moon.internet.core.XrayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * «Стабильность»: every server measured through the engine that really carries it — the desktop's
 * StabilityPinger, the same design.
 *
 * The old one handed each server to xray's own one-shot measurement. Two things came of that.
 * Hysteria2 and AmneziaWG are not carried by xray at all here — their outbound is a SOCKS dial to
 * a helper that only runs while connected — so they read ✕ for servers that were fine. And the
 * one request it timed was a cold one: TCP, TLS, the protocol's handshake and the test site's TLS
 * all in one figure, 400 ms one time and 1200 the next for the same server.
 *
 * Now: xray holds every server it can carry (a SOCKS inbound each), sing-box is brought up per
 * Hysteria2 server, awgproxy per AmneziaWG server in a process of its own; the app fetches the
 * test URL through each — one request to open the path (retried once), two more over the same
 * connection, the best of those. Different machines are measured side by side, one machine's
 * inbounds strictly one after another: a dozen handshakes at one address within a second is the
 * burst the censor's box answers by cutting that server's TLS for minutes.
 */
class StabilityProbe(private val ctx: Context) {

    private companion object {
        /** Machines at once. */
        const val LANES = 3
        /** One run at a time, app-wide. */
        val gate = Mutex()
        /** awgproxy keeps one engine per process, and the probe process is one. */
        val awgGate = Mutex()
        /**
         * The first request through an engine started a moment ago for this one server. AmneziaWG
         * carries nothing for about three seconds after its port opens — the handshake — and a
         * request sent into that gap recovered only on the next retry, past the ordinary limit:
         * every AmneziaWG server read ✕ while it worked (measured on the desktop). Fifteen,
         * because a missed handshake is retried only five seconds later.
         */
        const val FRESH_ENGINE_MS = 15_000
        /** The first request through an engine that is already up. */
        const val COLD_MS = 8_000
    }

    private enum class Engine { XRAY, SINGBOX, AWG }

    private fun engineOf(s: ServerProfile): Engine? = when {
        s.protocol == Protocol.HYSTERIA2 -> Engine.SINGBOX
        s.protocol == Protocol.WIREGUARD && s.wg?.isAmnezia != false -> Engine.AWG
        XrayConfig.supports(s.protocol) -> Engine.XRAY
        else -> null
    }

    /**
     * [onResult] hears each server as it lands: ms, -1 for no answer, -2 for "could not measure"
     * (its engine is not on this phone).
     */
    suspend fun measure(servers: List<ServerProfile>, url: String, timeoutMs: Int, onResult: (ServerProfile, Int) -> Unit) =
        withContext(Dispatchers.IO) {
            if (servers.isEmpty()) return@withContext
            val timeout = maxOf(timeoutMs, 5000)
            gate.withLock {
                val viaXray = servers.filter { engineOf(it) == Engine.XRAY }
                val ports = HashMap<String, Int>()
                var core: libv2ray.CoreController? = null
                try {
                    if (viaXray.isNotEmpty()) {
                        val ins = JSONArray(); val outs = JSONArray(); val rules = JSONArray()
                        viaXray.forEachIndexed { i, s ->
                            val out = runCatching { XrayConfig.buildOutbound(s, "out$i") }.getOrNull() ?: return@forEachIndexed
                            val port = java.net.ServerSocket(0).use { it.localPort }
                            ports[s.key] = port
                            ins.put(JSONObject().put("tag", "in$i").put("listen", "127.0.0.1").put("port", port)
                                .put("protocol", "socks").put("settings", JSONObject().put("auth", "noauth").put("udp", false)))
                            outs.put(out)
                            rules.put(JSONObject().put("type", "field")
                                .put("inboundTag", JSONArray().put("in$i")).put("outboundTag", "out$i"))
                        }
                        val cfg = JSONObject()
                            .put("log", JSONObject().put("loglevel", "none"))
                            .put("inbounds", ins).put("outbounds", outs)
                            .put("routing", JSONObject().put("rules", rules))
                        core = XrayRunner.startProbe(ctx, cfg.toString())
                        if (core == null || ports.isEmpty() || !portUp(ports.values.last(), 5000)) ports.clear()
                    }

                    val lanes = Semaphore(LANES)
                    coroutineScope {
                        servers.groupBy { it.address.lowercase() }.values.map { machine ->
                            async {
                                lanes.withPermit {
                                    for (s in machine) onResult(s, one(s, ports[s.key], url, timeout))
                                }
                            }
                        }.awaitAll()
                    }
                } finally {
                    core?.let { runCatching { it.stopLoop() } }
                }
            }
        }

    private suspend fun one(s: ServerProfile, xrayPort: Int?, url: String, timeout: Int): Int {
        if (xrayPort != null) return through(xrayPort, url, timeout, name = s.label)
        return when (engineOf(s)) {
            Engine.SINGBOX -> {
                val port = java.net.ServerSocket(0).use { it.localPort }
                val sb = SingBoxRunner(ctx, port, "hy2probe$port")
                try {
                    sb.start(s)?.let { why -> cc.moon.internet.data.DevLog.log("стабильность", "${s.label}: $why"); -1 }
                        ?: through(port, url, timeout, FRESH_ENGINE_MS, s.label)
                } finally { sb.stop() }
            }
            // Never the server we are connected through — the caller measures that one through the
            // live tunnel. A second session on the same keys moves the peer on the server and
            // knocks the live one off.
            Engine.AWG -> awgGate.withLock {
                val wg = s.wg ?: return@withLock -2
                if (cc.moon.internet.core.CoreStore.fileOf(ctx, cc.moon.internet.core.MoonCore.Amnezia)?.exists() != true)
                    return@withLock -2
                val runner = AwgRunner(ctx, port = AwgRunner.PROBE_PORT,
                    service = ProbeCoreService::class.java, tag = "awg-probe")
                try {
                    runner.start(wg)?.let { why -> cc.moon.internet.data.DevLog.log("стабильность", "${s.label}: $why"); -1 }
                        ?: through(AwgRunner.PROBE_PORT, url, timeout, FRESH_ENGINE_MS, s.label)
                } finally { runner.stop() }
            }
            else -> -2
        }
    }

    /**
     * Through a local SOCKS port: open the path (twice if the first try is refused quickly), then
     * the best of two requests over the connection it left open. -1 if it never opened.
     */
    private suspend fun through(port: Int, url: String, timeout: Int, coldMs: Int = 0, name: String = ""): Int {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        var why = ""
        fun once(limit: Int): Int = try {
            val t0 = System.nanoTime()
            val c = URL(url).openConnection(proxy) as HttpURLConnection
            c.connectTimeout = limit; c.readTimeout = limit
            c.instanceFollowRedirects = false
            val code = c.responseCode
            // Read to the end and do NOT disconnect: that is what puts the connection back in the
            // pool for the next request to reuse.
            runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } }
            why = "HTTP $code"
            if (code < 500) ((System.nanoTime() - t0) / 1_000_000).toInt() else -1
        } catch (e: Exception) { why = e.message ?: e.javaClass.simpleName; -1 }

        // The first request opens the whole path — the server's handshake, and for a cascade the
        // next hop's too — then the test site's TLS. Five seconds turned slow-but-working Russian
        // entry points (the exit is another machine) into ✕ several times a pass.
        val coldLimit = maxOf(timeout, COLD_MS, coldMs)
        val t0 = System.currentTimeMillis()
        var cold = once(coldLimit)
        if (coldMs > 0) {
            // An engine started a moment ago: knock until its budget runs out. Until its handshake
            // is through it refuses or stalls — a fixed count of tries was not enough here.
            while (cold < 0 && System.currentTimeMillis() - t0 < coldLimit - 1000) {
                delay(1000)
                cold = once(maxOf(1000, (coldLimit - (System.currentTimeMillis() - t0)).toInt()))
            }
        } else if (cold < 0 && System.currentTimeMillis() - t0 < coldLimit / 2) {
            // Again only after a quick refusal; a full timeout means nothing came back at all, and
            // waiting it out twice made every dead server cost eleven seconds.
            delay(1000); cold = once(coldLimit)
        }
        if (cold < 0) {
            // Why, in the journal: «✕» alone could not tell a dead server from a probe that broke.
            cc.moon.internet.data.DevLog.log("стабильность", "$name: $why")
            return -1
        }
        val warm = listOf(once(timeout), once(timeout)).filter { it >= 0 }
        return warm.minOrNull() ?: cold
    }

    private suspend fun portUp(port: Int, waitMs: Long): Boolean {
        val until = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < until) {
            if (runCatching { java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess) return true
            delay(100)
        }
        return false
    }
}
