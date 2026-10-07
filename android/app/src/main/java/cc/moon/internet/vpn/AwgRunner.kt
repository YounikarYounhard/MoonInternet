package cc.moon.internet.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import cc.moon.internet.core.CoreLib
import cc.moon.internet.core.CoreStore
import cc.moon.internet.core.MoonCore
import cc.moon.internet.core.WireGuardConfig
import cc.moon.internet.core.WireGuardUapi
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs awgproxy — the AmneziaWG engine — in its own process ([CoreService]).
 *
 * Two shapes. With a [tun] descriptor the engine carries the VpnService's packets itself, the way
 * amneziawg-android does: no xray, no SOCKS, no second TCP stack, and the speed and the UDP that
 * come with that. Without one (proxy mode, where there is no interface to hand over) it is a local
 * SOCKS5 listener that xray dials as an ordinary outbound.
 *
 * The engine is downloaded rather than shipped, so it lives in the app's data directory and is
 * loaded as a library instead of being run as a program: since Android 10 an app may not exec a
 * file it wrote there, but it may load one. See [CoreLib] and `android/moonload/moonload.c`.
 *
 * Either way the app decides it worked by the PORT. A thread that started and died a moment later
 * looks exactly like one that is serving, and always did. In TUN mode the port also answers with
 * the counters — see [counters].
 */
class AwgRunner(
    private val ctx: Context,
    /** The stability probe runs a second engine: its own port, its own process, its own key file. */
    private val port: Int = PORT,
    private val service: Class<out android.app.Service> = CoreService::class.java,
    private val tag: String = "awg",
    private val onDropped: () -> Unit = {},
) {

    companion object {
        /** Fixed, and free by the time we bind: the service tears the old core down first. */
        const val PORT = 1091
        /** The stability probe's engine — see [StabilityProbe]. */
        const val PROBE_PORT = 1093
    }

    @Volatile private var connection: ServiceConnection? = null
    private var gone: CountDownLatch? = null

    /** Null on success, or a line for the user to read. */
    fun start(wg: WireGuardConfig, tun: ParcelFileDescriptor? = null): String? {
        stop()
        val so = CoreStore.fileOf(ctx, MoonCore.Amnezia)
        if (so == null || !so.exists()) return "Ядро AmneziaWG не установлено"

        // The document holds the private key, so it goes to the app's own files — unreadable by
        // other apps — and is deleted the moment the core has read it. It is read only at startup.
        val uapi = File(ctx.filesDir, "$tag.uapi")
        return try {
            uapi.writeText(WireGuardUapi.build(wg))
            val args = arrayListOf("-v", "-uapi", uapi.absolutePath, "-listen", "127.0.0.1:$port")
            if (tun == null) {
                args += "-addr"; args += wg.address.joinToString(",")
                if (wg.dns.isNotEmpty()) { args += "-dns"; args += wg.dns.joinToString(",") }
                if (wg.mtu > 0) { args += "-mtu"; args += wg.mtu.toString() }
            }

            val core = bind() ?: return "AmneziaWG: процесс ядра не запустился"
            core.send(Message.obtain(null, CoreService.MSG_START).apply {
                data = Bundle().apply {
                    putString(CoreService.KEY_ID, MoonCore.Amnezia.id)
                    putString(CoreService.KEY_SO, so.absolutePath)
                    putStringArrayList(CoreService.KEY_ARGS, args)
                    // Its own log beside the app's, readable off a cable like singbox.log.
                    putString(CoreService.KEY_LOG,
                        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "$tag.log").absolutePath)
                    if (tun != null) putParcelable(CoreService.KEY_TUN, tun)
                }
            })
            awaitPort()
        } catch (e: Throwable) {
            "AmneziaWG не запустился: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            // Only after the core has had its chance to read it — awaitPort returning either way
            // means startup is over.
            runCatching { uapi.delete() }
        }
    }

    /** Binds [CoreService] in a fresh process and returns its messenger, or null after 5 s. */
    private fun bind(): Messenger? {
        val ready = LinkedBlockingQueue<IBinder>(1)
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) { ready.offer(binder) }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        if (!ctx.bindService(Intent(ctx, service), c, flags)) return null
        connection = c
        val binder = ready.poll(5, TimeUnit.SECONDS) ?: return null
        val latch = CountDownLatch(1)
        // The process dying while we still hold it is the engine crashing, not us stopping it.
        runCatching { binder.linkToDeath({ latch.countDown(); if (connection === c) onDropped() }, 0) }.onFailure { latch.countDown() }
        gone = latch
        return Messenger(binder)
    }

    /**
     * Knock on the port — 30 × 100 ms.
     *
     * The only signal there is: the engine runs in another process, so there is no thread here to
     * ask about. Generous because that process is started fresh for every connect.
     */
    private fun awaitPort(): String? {
        repeat(30) {
            runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 300) }
                return null
            }
            Thread.sleep(100)
        }
        return "AmneziaWG не открыл порт"
    }

    /**
     * TUN mode's counters: bytes received, bytes sent, and the unix second of the last handshake
     * (0 before the first). Null when the engine does not answer.
     */
    fun counters(): Triple<Long, Long, Long>? = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 300)
            s.soTimeout = 500
            val (rx, tx, hs) = s.getInputStream().bufferedReader().readLine().trim().split(' ').map { it.toLong() }
            Triple(rx, tx, hs)
        }
    }.getOrNull()

    /**
     * Lets go of the engine. Its process ends on unbind; waiting for that (up to 3 s) is what
     * keeps the next start from being handed the dying one.
     */
    fun stop() {
        val c = connection ?: return
        connection = null
        runCatching { ctx.unbindService(c) }
        gone?.await(3, TimeUnit.SECONDS)
        gone = null
    }
}
