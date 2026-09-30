package cc.moon.internet.vpn

import android.content.Context
import cc.moon.internet.core.CoreLib
import cc.moon.internet.core.CoreStore
import cc.moon.internet.core.MoonCore
import cc.moon.internet.core.WireGuardConfig
import cc.moon.internet.core.WireGuardUapi
import java.io.File

/**
 * Runs awgproxy — AmneziaWG behind a local SOCKS5 listener.
 *
 * The engine is downloaded rather than shipped, so it lives in the app's data directory and is
 * loaded as a library instead of being run as a program: since Android 10 an app may not exec a
 * file it wrote there, but it may load one. See [CoreLib] and `android/moonload/moonload.c`.
 *
 * What has not changed is how the app decides it worked: it waits for the PORT. A thread that
 * started and died a moment later looks exactly like one that is serving, and always did.
 *
 * It exposes SOCKS so that xray can dial it as an ordinary outbound, which is what gives AmneziaWG
 * the same routing as every other protocol without writing any of it twice.
 */
class AwgRunner(private val ctx: Context) {

    companion object {
        /** Fixed, and free by the time we bind: the service tears the old core down first. */
        const val PORT = 1091
    }

    /** Null on success, or a line for the user to read. */
    fun start(wg: WireGuardConfig): String? {
        stop()
        val so = CoreStore.fileOf(ctx, MoonCore.Amnezia)
        if (so == null || !so.exists()) return "Ядро AmneziaWG не установлено"

        // The document holds the private key, so it goes to the app's own files — unreadable by
        // other apps — and is deleted the moment the core has read it. It is read only at startup.
        val uapi = File(ctx.filesDir, "awg.uapi")
        return try {
            uapi.writeText(WireGuardUapi.build(wg))
            val args = mutableListOf(
                "-uapi", uapi.absolutePath,
                "-listen", "127.0.0.1:$PORT",
                "-addr", wg.address.joinToString(","),
            )
            if (wg.dns.isNotEmpty()) { args += "-dns"; args += wg.dns.joinToString(",") }
            if (wg.mtu > 0) { args += "-mtu"; args += wg.mtu.toString() }

            // Into its own process: xray is Go and so is this, and two Go runtimes in one process
            // kill each other's garbage collector. See CoreService.
            CoreService.start(ctx, MoonCore.Amnezia.id, so.absolutePath, args)
            awaitPort()
        } catch (e: Throwable) {
            runCatching { uapi.delete() }
            "AmneziaWG не запустился: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            // Only after the core has had its chance to read it — awaitPort returning either way
            // means startup is over.
            runCatching { uapi.delete() }
        }
    }

    /**
     * Knock on the port — 25 × 100 ms.
     *
     * The only signal there is now: the engine runs in another process, so there is no thread here
     * to ask about. A little longer than before because starting that process costs a moment.
     */
    private fun awaitPort(): String? {
        repeat(25) {
            runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", PORT), 300) }
                return null
            }
            Thread.sleep(100)
        }
        return "AmneziaWG не открыл порт"
    }

    fun stop() {
        CoreService.stop(ctx, MoonCore.Amnezia.id)
        runCatching { File(ctx.filesDir, "awg.uapi").delete() }
    }
}
