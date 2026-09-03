package cc.moon.internet.vpn

import android.content.Context
import cc.moon.internet.core.WireGuardConfig
import cc.moon.internet.core.WireGuardUapi
import java.io.File

/**
 * Runs awgproxy — AmneziaWG behind a local SOCKS5 listener.
 *
 * Same shape as [ByedpiRunner], and for the same reasons: the binary lives in `jniLibs` as
 * `libawgproxy.so` because Android only unpacks and marks executable what it finds there, and the
 * app waits for the PORT rather than for "the process is alive" — a process that started and died
 * a moment later looks identical otherwise.
 *
 * It exposes SOCKS so that xray can dial it as an ordinary outbound, which is what gives AmneziaWG
 * the same routing as every other protocol without writing any of it twice.
 */
class AwgRunner(private val ctx: Context) {

    companion object {
        /** Fixed, and free by the time we bind: the service tears the old core down first. */
        const val PORT = 1091
    }

    private var proc: Process? = null

    /** Null on success, or a line for the user to read. */
    fun start(wg: WireGuardConfig): String? {
        stop()
        val exe = File(ctx.applicationInfo.nativeLibraryDir, "libawgproxy.so")
        if (!exe.exists()) return "Ядро AmneziaWG не найдено в сборке"

        // The document holds the private key, so it goes to the app's own files — unreadable by
        // other apps — and is deleted the moment the core has read it. It is read only at startup.
        val uapi = File(ctx.filesDir, "awg.uapi")
        return try {
            uapi.writeText(WireGuardUapi.build(wg))
            val cmd = mutableListOf(
                exe.absolutePath,
                "-uapi", uapi.absolutePath,
                "-listen", "127.0.0.1:$PORT",
                "-addr", wg.address.joinToString(","),
            )
            if (wg.dns.isNotEmpty()) { cmd += "-dns"; cmd += wg.dns.joinToString(",") }
            if (wg.mtu > 0) { cmd += "-mtu"; cmd += wg.mtu.toString() }

            proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val why = awaitPort()
            uapi.delete()
            why
        } catch (e: Throwable) {
            runCatching { uapi.delete() }
            "AmneziaWG не запустился: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Knock on the port rather than trust the process — 15 × 100 ms, as byedpi taught us. */
    private fun awaitPort(): String? {
        repeat(15) {
            if (proc?.isAlive == false) {
                val tail = runCatching { proc?.inputStream?.bufferedReader()?.readText()?.take(200) }.getOrNull()
                return "AmneziaWG не поднялся" + (tail?.takeIf { t -> t.isNotBlank() }?.let { t -> ": $t" } ?: "")
            }
            runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", PORT), 300) }
                return null
            }
            Thread.sleep(100)
        }
        return "AmneziaWG не открыл порт"
    }

    fun stop() {
        runCatching { proc?.destroy() }
        proc = null
        runCatching { File(ctx.filesDir, "awg.uapi").delete() }
    }
}
