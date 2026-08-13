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

    fun stop() {
        proc?.let { p -> runCatching { p.destroy(); p.waitFor() } }
        proc = null
    }
}
