package cc.moon.internet.vpn

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.ParcelFileDescriptor
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
 * Bound rather than started, for two things a started service could not do:
 *
 *  - Carry the VpnService's TUN descriptor. An Intent refuses file descriptors outright; a
 *    message over a binding takes one.
 *  - Die with the connection. The process ends the moment the app lets go, so every connect gets a
 *    fresh one. A Go runtime cannot be started twice in a process and a loaded library is never
 *    unloaded, so a process that outlived its engine kept running the OLD engine after an update
 *    — or kept a dead one. A binding also lends this process the app's foreground priority, which
 *    a started service in the background does not get.
 */
open class CoreService : Service() {

    companion object {
        const val MSG_START = 1
        const val KEY_ID = "id"
        const val KEY_SO = "so"
        const val KEY_ARGS = "args"
        /** The TUN descriptor, when the engine is to carry the phone's packets itself. */
        const val KEY_TUN = "tun"
    }

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == MSG_START) {
            val data = msg.data
            val args = data.getStringArrayList(KEY_ARGS).orEmpty().toMutableList()
            @Suppress("DEPRECATION")
            data.getParcelable<ParcelFileDescriptor>(KEY_TUN)?.let {
                // The engine owns it from here and closes it when it stops.
                args += "-tunfd"; args += it.detachFd().toString()
            }
            CoreLib.start(data.getString(KEY_ID).orEmpty(), data.getString(KEY_SO).orEmpty(), args)
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        // The app let go: the tunnel is over. Ending the process is what stops the engine and
        // closes its sockets and its copy of the TUN — and the only way to unload it.
        android.os.Process.killProcess(android.os.Process.myPid())
        return false
    }
}
