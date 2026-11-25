package cc.moon.internet

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.view.Gravity
import android.widget.TextView
import cc.moon.internet.data.Lang

/** Runs in a separate process so it can reopen Moon Internet after the old process exits. */
class RestartActivity : Activity() {
    companion object {
        private const val OLD_PROCESS = "old_process"

        fun request(context: Context) = Intent(context, RestartActivity::class.java).putExtras(
            Bundle().apply { putBinder(OLD_PROCESS, Binder()) },
        )
    }

    private var oldProcess: IBinder? = null
    private var exited = false
    private var resumed = false
    private val onExit = IBinder.DeathRecipient {
        runOnUiThread {
            exited = true
            if (resumed) reopen()
        }
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val process = intent.extras?.getBinder(OLD_PROCESS)
        if (process == null) {
            finish()
            return
        }
        oldProcess = process
        setContentView(TextView(this).apply {
            gravity = Gravity.CENTER
            setText(R.string.settings_restart_progress)
            textSize = 16f
        })
        try { process.linkToDeath(onExit, 0) }
        catch (_: RemoteException) { exited = true }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (exited) reopen()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { oldProcess?.unlinkToDeath(onExit, 0) }
        super.onDestroy()
    }

    private fun reopen() {
        if (isFinishing) return
        startActivity(Intent.makeRestartActivityTask(ComponentName(this, MainActivity::class.java)))
        finish()
    }
}
