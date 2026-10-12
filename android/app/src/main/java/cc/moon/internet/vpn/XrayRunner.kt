package cc.moon.internet.vpn

import android.content.Context
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

/**
 * Wrapper around xray-core (2dust/AndroidLibXrayLite) — the same engine the desktop build runs,
 * and the same one v2RayTun / HAPP / INCY ship.
 *
 * xray-core has its own `tun` inbound: it picks the VpnService file descriptor up from an env
 * var that [CoreController.startLoop] sets, so there is no separate tun2socks here.
 *
 * Nothing calls VpnService.protect(): the whole app package is excluded from the tunnel when the
 * interface is built, which keeps the core's own dials outside it. That is what v2rayNG does too.
 */
class XrayRunner(private val ctx: Context, private val onStopped: () -> Unit) {

    companion object {
        /** (uplink, downlink) of the "proxy" outbound in one stats line; anything unreadable is 0. */
        fun proxyDelta(line: String): Pair<Long, Long> {
            var up = 0L
            var down = 0L
            for (entry in line.split(';')) {
                val f = entry.split(',')
                if (f.size != 3 || f[0] != "proxy") continue
                val v = f[2].toLongOrNull() ?: continue
                when (f[1]) { "uplink" -> up += v; "downlink" -> down += v }
            }
            return up to down
        }

        /** Core version string, for the About page. */
        fun version(): String = runCatching { Libv2ray.checkVersionX() }.getOrDefault("—")

        /**
         * startLoop hands the TUN descriptor to xray through a process-wide environment variable,
         * so two cores starting at once — the tunnel and a stability probe — could swap them.
         */
        private val startLock = Any()

        /**
         * A second, short-lived core for [StabilityProbe]: SOCKS inbounds only, no TUN. Null if it
         * would not start. The caller stops it.
         */
        fun startProbe(ctx: Context, configJson: String): CoreController? {
            Libv2ray.initCoreEnv(File(ctx.filesDir, "assets").apply { mkdirs() }.absolutePath, "")
            val c = Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(code: Long, message: String?): Long = 0
            })
            return runCatching { synchronized(startLock) { c.startLoop(configJson, 0) }; c }
                .getOrElse { runCatching { c.stopLoop() }; null }
        }
    }

    private var core: CoreController? = null

    /** Traffic counters are cumulative here; xray's QueryStats resets on read, so we accumulate. */
    private var upTotal = 0L
    private var downTotal = 0L

    fun start(configJson: String, tunFd: Int) {
        // geo files live here; xray tolerates their absence and simply matches no geo rule
        val assets = File(ctx.filesDir, "assets").apply { mkdirs() }
        Libv2ray.initCoreEnv(assets.absolutePath, "")

        val c = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long { onStopped(); return 0 }
            override fun onEmitStatus(code: Long, message: String?): Long {
                android.util.Log.i("xray", "[$code] ${message.orEmpty()}")
                return 0
            }
        })
        // The handle is stored *before* startLoop, and a failed start is torn down here.
        // startLoop binds the inbounds one by one and throws on the first that fails — the
        // ones already listening stay listening. Assigning core afterwards meant stop() had
        // nothing to call, so a failed connect leaked the local proxy port and the next
        // attempt died on "address already in use" against ourselves.
        core = c
        runCatching { synchronized(startLock) { c.startLoop(configJson, tunFd) } }.onFailure {
            runCatching { c.stopLoop() }
            core = null
            throw it
        }
    }

    fun stop() {
        runCatching { core?.stopLoop() }
        core = null
        upTotal = 0; downTotal = 0
    }

    /**
     * Cumulative (up, down) in bytes. The core zeroes the counters it returns, so each read is a
     * delta that has to be summed — reporting it raw would make the speed read as the total.
     *
     * Read as one line, `tag,direction,value;…` for every outbound: the per-counter QueryStats is
     * gone from the library since 26.9. Only "proxy" counts — direct traffic never touched a server.
     */
    fun trafficTotals(): Pair<Long, Long>? {
        val c = core ?: return null
        val line = runCatching { c.queryAllOutboundTrafficStats() }.getOrDefault("")
        val (up, down) = proxyDelta(line)
        upTotal += up; downTotal += down
        return upTotal to downTotal
    }

    /** Real latency through the proxy, measured by the core itself. */
    fun measureDelay(url: String = "https://www.gstatic.com/generate_204"): Long? =
        core?.let { runCatching { it.measureDelay(url) }.getOrNull() }
}
