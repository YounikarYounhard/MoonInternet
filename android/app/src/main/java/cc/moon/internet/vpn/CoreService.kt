package cc.moon.internet.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import cc.moon.internet.core.CoreLib

/**
 * Holds a downloaded engine, in a process of its own.
 *
 * Not a detail of tidiness — it is the whole reason this class exists. The engines are written in
 * Go and so is xray, and **two Go runtimes cannot share one process**: their garbage collectors
 * walk each other's memory and the second one to touch it dies. What that looked like on a phone
 * was a tunnel that came up, carried traffic for a few seconds, and then took the app down with:
 *
 *     fatal error: addspecial on invalid pointer
 *     AndroidLibXrayLite.(*CoreController).doShutdown
 *
 * — xray's collector, tripping over the engine's heap. It never happened while the engines were
 * separate executables, because then each had a process to itself; moving them in-process to make
 * them downloadable is what introduced it.
 *
 * So the engine gets its own process again. It still talks to xray the way it always has, over a
 * loopback port, and the app still decides it is up by knocking on that port — nothing else about
 * the arrangement changes.
 */
class CoreService : Service() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_SO = "so"
        private const val EXTRA_ARGS = "args"
        private const val ACTION_STOP = "cc.moon.internet.CORE_STOP"

        fun start(ctx: Context, id: String, soPath: String, args: List<String>) {
            ctx.startService(Intent(ctx, CoreService::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_SO, soPath)
                .putExtra(EXTRA_ARGS, args.toTypedArray()))
        }

        fun stop(ctx: Context, id: String) {
            runCatching {
                ctx.startService(Intent(ctx, CoreService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_ID, id))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
        if (intent.action == ACTION_STOP) {
            CoreLib.stop(id)
            // Nothing left to hold this process up. Ending it is also the only way to unload the
            // engine: a Go runtime cannot be started twice in one process, so a fresh start after
            // an engine is replaced has to be a fresh process.
            stopSelf()
            return START_NOT_STICKY
        }
        val so = intent.getStringExtra(EXTRA_SO) ?: return START_NOT_STICKY
        val args = intent.getStringArrayExtra(EXTRA_ARGS)?.toList() ?: emptyList()
        CoreLib.start(id, so, args)
        // Not sticky: if the system does kill this, the tunnel is gone anyway and the app will say
        // so. Bringing the engine back on its own, under a tunnel that no longer exists, would be
        // a port open onto nothing.
        return START_NOT_STICKY
    }
}
