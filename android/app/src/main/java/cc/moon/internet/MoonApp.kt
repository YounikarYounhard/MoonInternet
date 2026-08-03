package cc.moon.internet

import android.app.Application
import cc.moon.internet.data.LogStore

class MoonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything opens the file: a debug level left on overnight would otherwise grow
        // without limit, and the retention the Логи page offers has to be honoured by someone.
        LogStore.prune(this, keepDays = 7)
    }
}
