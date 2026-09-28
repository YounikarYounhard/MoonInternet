package cc.moon.internet.vpn

import android.content.Context
import cc.moon.internet.core.ServerProfile
import cc.moon.internet.core.SingBoxHy2Config
import java.io.File

/**
 * Runs sing-box for one job only: Hysteria2 behind a local SOCKS listener.
 *
 * Same shape as [AwgRunner], and for the same reasons — the binary lives in `jniLibs` as
 * `libsingbox.so` because Android only unpacks and marks executable what it finds there, and the
 * app waits for the PORT rather than for "the process is alive", since a process that started and
 * died a moment later looks identical otherwise.
 *
 * Why at all: xray carries every other protocol here and is the only core that speaks XHTTP, but
 * its Hysteria2 does not survive Russian filtering (XTLS/Xray-core#6717, closed as "not planned")
 * while sing-box does. The desktop has always run Hysteria2 on sing-box; this is the phone catching
 * up, without moving anything else off xray.
 *
 * The child process is outside the tunnel for free: MoonVpnService excludes our own package from
 * the VPN in every mode, and a child shares our uid — so it dials the server directly instead of
 * looping back through us.
 */
class SingBoxRunner(private val ctx: Context) {

    companion object {
        /** Fixed, and free by the time we bind: the service tears the old core down first. */
        const val PORT = 1092
    }

    private var proc: Process? = null

    /** Null on success, or a line for the user to read. */
    fun start(s: ServerProfile): String? {
        stop()
        val exe = File(ctx.applicationInfo.nativeLibraryDir, "libsingbox.so")
        if (!exe.exists()) return "Ядро Hysteria не найдено в сборке"

        // The config carries the server password, so it goes to the app's own files — unreadable
        // by other apps — and is deleted as soon as the core has read it.
        val cfg = File(ctx.filesDir, "hy2.json")
        return try {
            cfg.writeText(SingBoxHy2Config.build(s, PORT))
            proc = ProcessBuilder(exe.absolutePath, "run", "-c", cfg.absolutePath)
                .redirectErrorStream(true)
                .apply {
                    // The binary is built GOOS=linux — an android build would drag in cgo and the
                    // NDK for nothing else — and Go then looks for root certificates where Linux
                    // keeps them. Android keeps them somewhere else entirely, so every server came
                    // back "certificate signed by unknown authority". Point it at the real store;
                    // the apex path is where Android 10 and later moved it.
                    environment()["SSL_CERT_DIR"] =
                        "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
                }
                .start()
            drainToLog()
            val why = awaitPort()
            cfg.delete()
            why
        } catch (e: Throwable) {
            runCatching { cfg.delete() }
            "Hysteria не запустилась: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Keep what the engine says. A process whose output nobody reads blocks once the pipe fills,
     * so this has to happen anyway — and a tunnel that comes up and then carries nothing is
     * unanswerable without it. External dir, for the same reason the journal is there: a
     * release-signed build refuses `run-as`, so nothing private can be read off a cable.
     */
    private fun drainToLog() {
        val p = proc ?: return
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val log = File(dir, "singbox.log")
        Thread {
            runCatching {
                log.writeText("")
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (log.length() < 256 * 1024) log.appendText(line + "\n")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Knock on the port rather than trust the process — 15 × 100 ms, as byedpi taught us. */
    private fun awaitPort(): String? {
        repeat(15) {
            if (proc?.isAlive == false) {
                // From the file, not the pipe: the draining thread owns that stream now.
                val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                val tail = runCatching { File(dir, "singbox.log").readLines().lastOrNull() }.getOrNull()
                return "Hysteria не поднялась" + (tail?.takeIf { t -> t.isNotBlank() }?.let { t -> ": $t" } ?: "")
            }
            runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", PORT), 300) }
                return null
            }
            Thread.sleep(100)
        }
        return "Hysteria не открыла порт"
    }

    fun stop() {
        runCatching { proc?.destroy() }
        proc = null
        runCatching { File(ctx.filesDir, "hy2.json").delete() }
    }
}
