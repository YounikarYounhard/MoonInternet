package cc.moon.internet.vpn

import android.content.Context
import cc.moon.internet.core.ZapretStrategies
import java.io.File

/**
 * Runs byedpi — the запрет mode's engine on the phone.
 *
 * It is a separate process, not a linked library, because that is what it is: a whole program with
 * its own main(). Android will only execute a file that came out of jniLibs, which is why it is
 * called libbyedpi.so and not ciadpi.
 *
 * It listens on 127.0.0.1 and speaks SOCKS5; the tunnel dials it. Nothing outside this app can
 * reach it — the port is bound to loopback, and on Android loopback is per-app.
 */
class ByedpiRunner(private val ctx: Context) {

    private var proc: Process? = null

    val isRunning: Boolean get() = proc?.isAlive == true

    private fun exe(): File = File(ctx.applicationInfo.nativeLibraryDir, "libbyedpi.so")

    /**
     * Starts the strategy. Returns null on success, otherwise what went wrong in the user's words.
     *
     * A bad flag makes byedpi print usage and quit immediately, so we wait a moment and check that
     * it is still alive rather than reporting success for a process that has already gone.
     */
    fun start(strategyId: String?): String? {
        stop()
        val bin = exe()
        if (!bin.exists()) return "byedpi не найден в сборке"

        val cmd = ZapretStrategies.commandLine(bin.absolutePath, strategyId)
        return try {
            android.util.Log.i("byedpi", "start: " + cmd.joinToString(" "))
            proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            // Drain the pipe: a process whose output nobody reads blocks once the buffer fills.
            Thread {
                runCatching {
                    proc?.inputStream?.bufferedReader()?.forEachLine {
                        android.util.Log.i("byedpi", it)
                    }
                }
            }.apply { isDaemon = true }.start()

            // Alive is not the same as ready, and it is not the same as useful either: byedpi
            // exits on a flag it dislikes, and a port it never bound accepts nothing. So we knock
            // on the port itself. Without this the tunnel came up, pointed at nobody, and the
            // phone just sat there with no page loading and no error to show for it.
            var lastWhy: String? = null
            repeat(15) {
                Thread.sleep(100)
                if (proc?.isAlive != true) {
                    val code = runCatching { proc?.exitValue() }.getOrNull()
                    proc = null
                    return "byedpi завершился сразу (код $code) — стратегия ему не подошла"
                }
                val knock = runCatching {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress("127.0.0.1", ZapretStrategies.PORT), 300)
                    }
                }
                if (knock.isSuccess) return null
                lastWhy = knock.exceptionOrNull()?.message
            }
            stop()
            "byedpi не отвечает на 127.0.0.1:${ZapretStrategies.PORT}" + (lastWhy?.let { " ($it)" } ?: "")
        } catch (e: Exception) {
            proc = null
            e.message ?: "byedpi не запустился"
        }
    }

    /**
     * Does the strategy actually help?
     *
     * A ping cannot say: Zapret carries traffic nowhere, so there is nothing to measure to. We
     * fetch a page that is blocked and time it — an answer means this strategy works here.
     *
     * Deliberately through byedpi's own SOCKS rather than straight out. Our package is excluded
     * from the tunnel (otherwise byedpi's own dials would loop back into it), so a plain request
     * from this app would sail past byedpi and report a healthy number for a strategy doing
     * nothing at all.
     */
    fun check(url: String, timeoutMs: Int = 6000): Int {
        val target = runCatching { java.net.URL(url) }.getOrNull() ?: return -1
        val host = target.host
        val port = if (target.port > 0) target.port else if (target.protocol == "https") 443 else 80
        return try {
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", ZapretStrategies.PORT))
            val started = System.currentTimeMillis()
            java.net.Socket(proxy).use { s ->
                s.connect(java.net.InetSocketAddress.createUnresolved(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                // The TLS handshake is the part DPI interferes with, so it is the part worth
                // timing: a bare TCP connect completes even when the strategy does nothing at
                // all. Plain HTTP is not handled — there is nothing for a strategy to hide in
                // an unencrypted request, so checking over it would prove nothing either way.
                val f = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                (f.createSocket(s, host, port, true) as javax.net.ssl.SSLSocket).use { it.startHandshake() }
            }
            (System.currentTimeMillis() - started).toInt()
        } catch (e: Exception) {
            android.util.Log.i("byedpi", "check failed: " + e.message)
            -1
        }
    }

    fun stop() {
        proc?.let { p -> runCatching { p.destroy(); p.waitFor() } }
        proc = null
    }
}
